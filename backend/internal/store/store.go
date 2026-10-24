// Package store defines the storage interfaces the HTTP handlers use and
// their domain types, so package httpapi does not depend on Postgres.
// Package db implements them.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
)

// ErrNotFound means the row does not exist, is not the caller's, or is a
// deletion tombstone.
var ErrNotFound = errors.New("store: not found")

// Vehicle is a live vehicle (never a tombstone).
type Vehicle struct {
	ID              uuid.UUID
	ClientVehicleID *uuid.UUID
	VehicleFields
	// Version is bumped by every change; the ETag is derived from it.
	Version   int64
	CreatedAt time.Time
	UpdatedAt time.Time
}

// VehicleFields are the writable fields (VehicleWritable in the spec).
// A nil pointer is NULL / absent.
type VehicleFields struct {
	Nickname             *string
	VariantID            *string
	Make                 string
	Model                string
	FuelType             string
	EngineDisplacementCc *int
	ModelYear            *int
	PlateNumber          *string
	Vin                  *string
	OdometerKm           *int64
	IdleStopGo           *bool
	ObdElmVersion        *string
	ObdProtocol          *int
	Archived             bool
}

// CreateOutcome says what CreateVehicle did.
type CreateOutcome int

const (
	// Created means a new vehicle was stored.
	Created CreateOutcome = iota + 1
	// Existing means the member already has a live vehicle with this
	// clientVehicleId; it is returned unchanged.
	Existing
	// Deleted means the member deleted a vehicle with this clientVehicleId
	// and its tombstone is still kept; nothing was stored.
	Deleted
)

// DeleteResult reports what a vehicle delete removed.
type DeleteResult struct {
	// TripsDeleted is the number of trips tombstoned by a cascade delete.
	TripsDeleted int
	// ObjectKeys are object-storage keys of deleted telemetry that the
	// caller must remove from object storage.
	ObjectKeys []string
}

// Vehicles stores the members' vehicles. Every method is scoped to member,
// and only live rows (deleted_at IS NULL) are ever read or changed, except
// that CreateVehicle and ClientVehicleDeleted look at tombstones.
type Vehicles interface {
	// CreateVehicle stores a vehicle, de-duplicated by clientID (if not nil)
	// per member. For Existing the live vehicle is returned; for Deleted
	// the Vehicle is zero.
	CreateVehicle(ctx context.Context, member uuid.UUID, clientID *uuid.UUID, f VehicleFields) (Vehicle, CreateOutcome, error)
	// GetVehicle returns a live vehicle or ErrNotFound.
	GetVehicle(ctx context.Context, member, id uuid.UUID) (Vehicle, error)
	// ListVehicles returns up to limit live vehicles in id order after
	// after (nil for the first page).
	ListVehicles(ctx context.Context, member uuid.UUID, includeArchived bool, after *uuid.UUID, limit int) ([]Vehicle, error)
	// UpdateVehicle reads the live vehicle, calls check (an error, e.g. a
	// stale If-Match, is returned as is), applies patch and stores the
	// result with a new version, unless patch changed nothing. A
	// concurrent change between the read and the write makes it start
	// over, so check always sees the version that is written over.
	UpdateVehicle(ctx context.Context, member, id uuid.UUID, check func(Vehicle) error, patch func(VehicleFields) VehicleFields) (Vehicle, error)
	// DeleteVehicle locks the live vehicle, calls check, then deletes it:
	// a vehicle with a clientVehicleId leaves a tombstone, one without is
	// removed. cascade also tombstones its trips and deletes their
	// telemetry and the vehicle's DTC reports, in the same transaction.
	DeleteVehicle(ctx context.Context, member, id uuid.UUID, check func(Vehicle) error, cascade bool) (DeleteResult, error)
	// ClientVehicleDeleted reports whether the member has a tombstone for
	// clientID.
	ClientVehicleDeleted(ctx context.Context, member, clientID uuid.UUID) (bool, error)
}

// IdempotencyScope identifies an Idempotency-Key: per member, HTTP method
// and route template.
type IdempotencyScope struct {
	MemberID uuid.UUID
	Method   string
	Route    string
	Key      uuid.UUID
}

// IdempotencyRecord is a stored key.
type IdempotencyRecord struct {
	Fingerprint []byte
	Completed   bool
	// Status, Headers and Body are the response to replay (Completed only).
	Status  int
	Headers map[string]string
	Body    []byte
}

// Idempotency stores Idempotency-Keys. A claim holds the key under a
// claim token; only the holder of the current token can complete or
// release it.
type Idempotency interface {
	// Claim claims an absent (or expired) key; ok is false when a live key
	// exists.
	Claim(ctx context.Context, s IdempotencyScope, fingerprint []byte, token uuid.UUID, lockedUntil, expiresAt time.Time) (ok bool, err error)
	// Get returns a stored key or ErrNotFound.
	Get(ctx context.Context, s IdempotencyScope) (IdempotencyRecord, error)
	// Reclaim takes over an in-progress key whose lease ran out, if the
	// fingerprint matches; ok is false otherwise.
	Reclaim(ctx context.Context, s IdempotencyScope, fingerprint []byte, token uuid.UUID, lockedUntil time.Time) (ok bool, err error)
	// Complete stores the response; ok is false if token lost the claim.
	Complete(ctx context.Context, s IdempotencyScope, token uuid.UUID, status int, headers map[string]string, body []byte) (ok bool, err error)
	// Release drops the claim so the key can be retried; ok is false if
	// token lost the claim.
	Release(ctx context.Context, s IdempotencyScope, token uuid.UUID) (ok bool, err error)
}
