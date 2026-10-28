package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// TelemetryEvent is a driving event as stored: T is UTC, truncated to
// milliseconds; an absent active is true and an absent or null dtc nil.
type TelemetryEvent struct {
	ClientEventID *uuid.UUID
	T             time.Time
	Type          string
	Active        bool
	Lat, Lon      *float64
	Dtc           *string
	Value         *float64
}

// StoredEvent is a TelemetryEvent read back, with its position.
type StoredEvent struct {
	ID int64
	TelemetryEvent
}

// EventPos is a position in event order (t, id); a page continues
// strictly after it.
type EventPos struct {
	T  time.Time
	ID int64
}

// BatchCounts are the counts a TelemetryBatchAck reports.
type BatchCounts struct {
	AcceptedSamples, AcceptedEvents int
	DroppedSamples, DroppedEvents   int
}

// TelemetryBatch is one upload to store: its metadata, the object key of
// its samples (nil when it has none) and its events (already
// de-duplicated within the batch).
type TelemetryBatch struct {
	// ID is the server id of the batch (UUIDv7); it is part of ObjectKey.
	ID            uuid.UUID
	ClientBatchID uuid.UUID
	ClientTripID  uuid.UUID
	VehicleID     uuid.UUID
	ObjectKey     *string
	Counts        BatchCounts
	// FirstSampleT and LastSampleT bound the stored samples (nil without).
	FirstSampleT, LastSampleT *time.Time
	// BodyBytes is the decompressed request body size.
	BodyBytes int
	Events    []TelemetryEvent
	// Fingerprint is the SHA-256 of the batch's normalised content (nil:
	// unknown). A clientBatchId stored with another fingerprint is
	// BatchConflict.
	Fingerprint []byte
}

// BatchOutcome says what happened to an uploaded batch.
type BatchOutcome int

const (
	// BatchAccepted means the batch was stored (StoreBatch), or would be
	// (CheckBatch).
	BatchAccepted BatchOutcome = iota + 1
	// BatchDuplicate means the member already stored this clientBatchId;
	// the counts are the original ones.
	BatchDuplicate
	// BatchTripDeleted means clientTripId has a tombstone; nothing is
	// stored.
	BatchTripDeleted
	// BatchVehicleNotFound means vehicleId is not one of the member's
	// live vehicles; nothing is stored.
	BatchVehicleNotFound
	// BatchVehicleMismatch means the trip exists with another vehicle;
	// nothing is stored.
	BatchVehicleMismatch
	// BatchConflict means the member already stored this clientBatchId
	// with other content; nothing is stored.
	BatchConflict
)

// SampleBatch is a stored batch that holds samples, for read-back.
type SampleBatch struct {
	ID                        uuid.UUID
	ObjectKey                 string
	FirstSampleT, LastSampleT time.Time
}

// Telemetry stores telemetry batches (metadata and events; the samples
// themselves are in object storage). Every method is scoped to member.
// Telemetry is keyed by (member, clientTripId), not by a trip row: it may
// arrive before the trip summary.
type Telemetry interface {
	// CheckBatch reports, without locking or writing, what StoreBatch
	// would most likely do (a cheap pre-check before writing to object
	// storage): BatchTripDeleted, BatchVehicleNotFound, BatchDuplicate
	// (with the stored counts) or BatchConflict (stored with a fingerprint
	// other than fingerprint; nil matches any), BatchVehicleMismatch or
	// BatchAccepted, in that order.
	CheckBatch(ctx context.Context, member, clientBatch, clientTrip, vehicle uuid.UUID, fingerprint []byte) (BatchOutcome, BatchCounts, error)
	// StoreBatch stores b in one transaction, deciding the outcome as
	// CheckBatch does but under locks, so it can't race a trip or vehicle
	// delete. Only for BatchAccepted is anything stored.
	StoreBatch(ctx context.Context, member uuid.UUID, b TelemetryBatch) (BatchOutcome, BatchCounts, error)
	// ListSampleBatches returns up to limit batches of a trip that have
	// samples later than after (all when after is nil), in
	// (first sample, id) order, starting after the batch afterBatch (nil
	// for the first).
	ListSampleBatches(ctx context.Context, member, clientTrip uuid.UUID, after *time.Time, afterBatch *SampleBatch, limit int) ([]SampleBatch, error)
	// ListEvents returns up to limit events of a trip in (t, id) order
	// after after (nil for the first page).
	ListEvents(ctx context.Context, member, clientTrip uuid.UUID, after *EventPos, limit int) ([]StoredEvent, error)
}
