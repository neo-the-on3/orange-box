package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
)

// ErrSyncWindowExpired means a sync token can no longer be used: it was
// issued by another deployment (a restore or a new cluster), or a tombstone
// it might still need has been purged. The client does a full resync.
var ErrSyncWindowExpired = errors.New("store: sync window expired")

// SyncPoint is what a sync token carries (openapi-v2.yaml, Conventions →
// Incremental sync).
type SyncPoint struct {
	// Identity names the database cluster the watermark belongs to.
	Identity string
	// IssuedAt is the database time the watermark was taken.
	IssuedAt time.Time
	// Watermark is pg_snapshot_xmin of the snapshot the walk's first page
	// was read with: every write that snapshot didn't see has a writer
	// transaction id at or above it.
	Watermark uint64
}

// ChangePos is a position in change order (writer transaction id, id); a
// page continues strictly after it.
type ChangePos struct {
	Xid uint64
	ID  uuid.UUID
}

// TripChangesQuery asks for one page of a sync walk over live trips
// (GET /v1/trips?since=).
type TripChangesQuery struct {
	// Since is the token the walk started from.
	Since SyncPoint
	// Snapshot asks for a new SyncPoint (the walk's first page), taken
	// with the snapshot the page is read with.
	Snapshot bool
	// After is the previous page's last item (nil on the first page).
	After *ChangePos
	Limit int
}

// TripDeletionsQuery asks for one page of a GET /v1/trips/deletions walk.
type TripDeletionsQuery struct {
	Since    SyncPoint
	Snapshot bool
	After    *ChangePos
	Limit    int
}

// TripFields are the stored fields of a live trip (TripUpload in the spec).
// JSON fields hold the nested API objects as sent.
type TripFields struct {
	VehicleID         uuid.UUID
	State             string
	StartedAt         time.Time
	EndedAt           *time.Time
	SnapshotAt        time.Time
	DurationS         *int64
	DistanceKm        *float64
	EngineDistanceKm  *float64
	MaxSpeedKmh       *float64
	AvgSpeedKmh       *float64
	MaxRpm            *int64
	HarshAccelCount   *int64
	HarshBrakeCount   *int64
	HarshStartCount   *int64
	HarshStopCount    *int64
	FuelUsedL         *float64
	FuelEconomyKmPerL *float64
	FuelCutTimeS      *float64
	IdleTimeS         *float64
	WarmUpTimeS       *float64
	AccelTimeS        *float64
	EcoSpeedTimeS     *float64
	OverSpeedTimeS    *float64
	SpeedBands        json.RawMessage
	EcoScore          *float64
	SafetyScore       *float64
	BatteryMaxV       *float64
	BatteryAvgV       *float64
	CoolantMaxC       *float64
	CoolantAvgC       *float64
	Dtc               json.RawMessage
	Simulated         bool
	App               json.RawMessage
}

// Trip is a live trip (never a tombstone).
type Trip struct {
	ID           uuid.UUID
	ClientTripID uuid.UUID
	TripFields
	PointsAwarded *int64
	CreatedAt     time.Time
	UpdatedAt     time.Time
	// Change is the trip's position in change order.
	Change ChangePos
}

// TripDeletion is a trip tombstone.
type TripDeletion struct {
	ID           uuid.UUID
	ClientTripID uuid.UUID
	VehicleID    *uuid.UUID
	DeletedAt    time.Time
	Change       ChangePos
}

// TripPage is one page of trips, with the walk's new SyncPoint when the
// query asked for one.
type TripPage struct {
	Trips    []Trip
	Snapshot *SyncPoint
}

// TripDeletionPage is one page of trip tombstones.
type TripDeletionPage struct {
	Deletions []TripDeletion
	Snapshot  *SyncPoint
}

// HistoryPos is a position in history order (startedAt desc, id desc); a
// page continues strictly after it.
type HistoryPos struct {
	StartedAt time.Time
	ID        uuid.UUID
}

// TripHistoryQuery asks for one page of GET /v1/trips without since.
type TripHistoryQuery struct {
	VehicleID *uuid.UUID
	// From (inclusive) and To (exclusive) bound startedAt.
	From, To *time.Time
	// Snapshot asks for a SyncPoint taken with the page's snapshot (the
	// first page of an unfiltered walk, which ends with nextSince).
	Snapshot bool
	After    *HistoryPos
	Limit    int
}

// TripRef names a trip by server id or by client trip id (exactly one).
type TripRef struct {
	ID       *uuid.UUID
	ClientID *uuid.UUID
}

// TripUpload is one item of a trip batch.
type TripUpload struct {
	ClientTripID uuid.UUID
	TripFields
}

// UpsertOutcome says what happened to one uploaded trip.
type UpsertOutcome int

const (
	// TripCreated means a new trip was stored.
	TripCreated UpsertOutcome = iota + 1
	// TripUpdated means a newer in_progress snapshot replaced the stored one.
	TripUpdated
	// TripUnchanged means the snapshot was not newer, or the trip is
	// completed (immutable).
	TripUnchanged
	// TripDeleted means the member deleted this clientTripId and its
	// tombstone is still kept; nothing was stored.
	TripDeleted
	// TripVehicleNotFound means vehicleId is not one of the member's live
	// vehicles; nothing was stored.
	TripVehicleNotFound
)

// UpsertResult is the outcome for one uploaded trip; ID is the server id
// for created, updated and unchanged.
type UpsertResult struct {
	Outcome UpsertOutcome
	ID      uuid.UUID
}

// DrivingDay is a local calendar day with trips.
type DrivingDay struct {
	// Date is midnight UTC of the day.
	Date  time.Time
	Count int
}

// Trips stores the members' trips. Every method is scoped to member, and
// only live rows (deleted_at IS NULL) are read or changed, except that
// UpsertTrips and the deletion walk look at tombstones.
type Trips interface {
	// UpsertTrips stores a batch in one transaction and returns one
	// result per item, in order. A trip is keyed by (member,
	// clientTripId); an existing one is replaced only while it is
	// in_progress and by a newer snapshotAt.
	UpsertTrips(ctx context.Context, member uuid.UUID, items []TripUpload) ([]UpsertResult, error)
	// GetTrip returns a live trip or ErrNotFound.
	GetTrip(ctx context.Context, member uuid.UUID, ref TripRef) (Trip, error)
	// DeleteTrip tombstones a live trip and deletes its telemetry, or
	// returns ErrNotFound. It returns the object-storage keys of the
	// deleted telemetry, which the caller must remove.
	DeleteTrip(ctx context.Context, member uuid.UUID, ref TripRef) ([]string, error)
	// ListTripHistory returns a page in startedAt desc, id desc order.
	ListTripHistory(ctx context.Context, member uuid.UUID, q TripHistoryQuery) (TripPage, error)
	// ListTripChanges returns a page of a sync walk, or
	// ErrSyncWindowExpired.
	ListTripChanges(ctx context.Context, member uuid.UUID, q TripChangesQuery) (TripPage, error)
	// ListTripDeletions returns a page of tombstones, or
	// ErrSyncWindowExpired.
	ListTripDeletions(ctx context.Context, member uuid.UUID, q TripDeletionsQuery) (TripDeletionPage, error)
	// DrivingDays counts trips per local day (in loc) for trips starting
	// in [from, before).
	DrivingDays(ctx context.Context, member uuid.UUID, from, before time.Time, tz string, vehicle *uuid.UUID) ([]DrivingDay, error)
}
