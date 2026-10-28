package db

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Telemetry is the Postgres store.Telemetry: batch metadata and events.
// The samples themselves live in object storage under the batch's
// object_key.
type Telemetry struct {
	pool *pgxpool.Pool
	q    *dbgen.Queries
}

var _ store.Telemetry = (*Telemetry)(nil)

// NewTelemetry returns a telemetry store on pool.
func NewTelemetry(pool *pgxpool.Pool) *Telemetry {
	return &Telemetry{pool: pool, q: dbgen.New(pool)}
}

func batchCounts(b dbgen.TelemetryBatch) store.BatchCounts {
	return store.BatchCounts{
		AcceptedSamples: int(b.AcceptedSamples), AcceptedEvents: int(b.AcceptedEvents),
		DroppedSamples: int(b.DroppedSamples), DroppedEvents: int(b.DroppedEvents),
	}
}

// sameContent reports whether a stored batch's fingerprint matches; an
// unknown one (nil, stored before fingerprints) matches anything.
func sameContent(stored, fingerprint []byte) bool {
	return stored == nil || fingerprint == nil || bytes.Equal(stored, fingerprint)
}

// existingBatch is the outcome for a clientBatchId already stored.
func existingBatch(b dbgen.TelemetryBatch, fingerprint []byte) (store.BatchOutcome, store.BatchCounts) {
	if !sameContent(b.ContentSha256, fingerprint) {
		return store.BatchConflict, store.BatchCounts{}
	}
	return store.BatchDuplicate, batchCounts(b)
}

// otherVehicle reports whether a live trip belongs to a vehicle other
// than vehicle.
func otherVehicle(tripVehicle *uuid.UUID, vehicle uuid.UUID) bool {
	return tripVehicle != nil && *tripVehicle != vehicle
}

// CheckBatch implements store.Telemetry.
func (s *Telemetry) CheckBatch(ctx context.Context, member, clientBatch, clientTrip, vehicle uuid.UUID, fingerprint []byte) (store.BatchOutcome, store.BatchCounts, error) {
	trip, err := s.q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: clientTrip})
	tripVehicle := (*uuid.UUID)(nil)
	switch {
	case err == nil && trip.DeletedAt != nil:
		return store.BatchTripDeleted, store.BatchCounts{}, nil
	case err == nil:
		tripVehicle = trip.VehicleID
	case !errors.Is(err, pgx.ErrNoRows):
		return 0, store.BatchCounts{}, fmt.Errorf("check trip: %w", err)
	}
	live, err := s.q.VehicleExists(ctx, dbgen.VehicleExistsParams{MemberID: member, ID: vehicle})
	if err != nil {
		return 0, store.BatchCounts{}, fmt.Errorf("check vehicle: %w", err)
	}
	if !live {
		return store.BatchVehicleNotFound, store.BatchCounts{}, nil
	}
	b, err := s.q.GetTelemetryBatchByClientID(ctx, dbgen.GetTelemetryBatchByClientIDParams{MemberID: member, ClientBatchID: clientBatch})
	switch {
	case err == nil:
		outcome, counts := existingBatch(b, fingerprint)
		return outcome, counts, nil
	case errors.Is(err, pgx.ErrNoRows) && otherVehicle(tripVehicle, vehicle):
		return store.BatchVehicleMismatch, store.BatchCounts{}, nil
	case errors.Is(err, pgx.ErrNoRows):
		return store.BatchAccepted, store.BatchCounts{}, nil
	default:
		return 0, store.BatchCounts{}, fmt.Errorf("check batch: %w", err)
	}
}

// eventRow is one element of InsertTripEvents' JSON array.
type eventRow struct {
	ClientEventID *uuid.UUID `json:"client_event_id"`
	T             string     `json:"t"`
	Type          string     `json:"type"`
	Active        bool       `json:"active"`
	Lat           *float64   `json:"lat"`
	Lon           *float64   `json:"lon"`
	Dtc           *string    `json:"dtc"`
	Value         *float64   `json:"value"`
}

func int32Of(n int, name string) (int32, error) {
	if n < 0 || n > math.MaxInt32 {
		return 0, fmt.Errorf("telemetry: %s %d out of range", name, n)
	}
	return int32(n), nil
}

// StoreBatch implements store.Telemetry. Lock order: the vehicle row
// (shared, as trip batches take it, so a vehicle delete waits for the
// upload or the upload finds the vehicle gone), the per-trip advisory lock
// (shared; trip and cascade deletes take it exclusively), then the trip
// row (shared; a tombstone means trip_deleted).
func (s *Telemetry) StoreBatch(ctx context.Context, member uuid.UUID, b store.TelemetryBatch) (store.BatchOutcome, store.BatchCounts, error) {
	var params dbgen.InsertTelemetryBatchParams
	var err error
	params.ID, params.MemberID, params.ClientBatchID, params.ClientTripID, params.VehicleID =
		b.ID, member, b.ClientBatchID, b.ClientTripID, b.VehicleID
	params.ObjectKey, params.FirstSampleT, params.LastSampleT = b.ObjectKey, utcPtr(b.FirstSampleT), utcPtr(b.LastSampleT)
	params.ContentSha256 = b.Fingerprint
	for _, f := range []struct {
		dst  *int32
		n    int
		name string
	}{
		{&params.AcceptedSamples, b.Counts.AcceptedSamples, "accepted samples"},
		{&params.AcceptedEvents, b.Counts.AcceptedEvents, "accepted events"},
		{&params.DroppedSamples, b.Counts.DroppedSamples, "dropped samples"},
		{&params.DroppedEvents, b.Counts.DroppedEvents, "dropped events"},
		{&params.BodyBytes, b.BodyBytes, "body bytes"},
	} {
		if *f.dst, err = int32Of(f.n, f.name); err != nil {
			return 0, store.BatchCounts{}, err
		}
	}
	events := make([]eventRow, len(b.Events))
	for i, e := range b.Events {
		events[i] = eventRow{
			ClientEventID: e.ClientEventID, T: e.T.UTC().Truncate(time.Millisecond).Format(time.RFC3339Nano),
			Type: e.Type, Active: e.Active, Lat: e.Lat, Lon: e.Lon, Dtc: e.Dtc, Value: e.Value,
		}
	}
	eventsJSON, err := json.Marshal(events)
	if err != nil {
		return 0, store.BatchCounts{}, err
	}

	var outcome store.BatchOutcome
	var counts store.BatchCounts
	err = pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		live, err := q.LockLiveVehiclesForShare(ctx, dbgen.LockLiveVehiclesForShareParams{MemberID: member, Ids: []uuid.UUID{b.VehicleID}})
		if err != nil {
			return fmt.Errorf("lock vehicle: %w", err)
		}
		if err := q.LockClientTripTelemetryShared(ctx, dbgen.LockClientTripTelemetrySharedParams{MemberID: member, ClientTripID: b.ClientTripID}); err != nil {
			return fmt.Errorf("lock trip telemetry: %w", err)
		}
		trip, err := q.LockTripForTelemetry(ctx, dbgen.LockTripForTelemetryParams{MemberID: member, ClientTripID: b.ClientTripID})
		switch {
		case err == nil && trip.DeletedAt != nil:
			outcome = store.BatchTripDeleted
			return nil
		case err != nil && !errors.Is(err, pgx.ErrNoRows):
			return fmt.Errorf("lock trip: %w", err)
		}
		if len(live) == 0 {
			outcome = store.BatchVehicleNotFound
			return nil
		}
		// A stored clientBatchId wins over the vehicle check, as in
		// CheckBatch: its trip may have arrived since, with another vehicle.
		orig, err := q.GetTelemetryBatchByClientID(ctx, dbgen.GetTelemetryBatchByClientIDParams{MemberID: member, ClientBatchID: b.ClientBatchID})
		switch {
		case err == nil:
			outcome, counts = existingBatch(orig, b.Fingerprint)
			return nil
		case !errors.Is(err, pgx.ErrNoRows):
			return fmt.Errorf("read batch: %w", err)
		}
		if trip.DeletedAt == nil && otherVehicle(trip.VehicleID, b.VehicleID) {
			outcome = store.BatchVehicleMismatch
			return nil
		}
		if _, err := q.InsertTelemetryBatch(ctx, params); errors.Is(err, pgx.ErrNoRows) {
			// Stored concurrently (its upload committed after our read).
			orig, err := q.GetTelemetryBatchByClientID(ctx, dbgen.GetTelemetryBatchByClientIDParams{MemberID: member, ClientBatchID: b.ClientBatchID})
			if err != nil {
				return fmt.Errorf("read duplicate batch: %w", err)
			}
			outcome, counts = existingBatch(orig, b.Fingerprint)
			return nil
		} else if err != nil {
			return fmt.Errorf("insert batch: %w", err)
		}
		if len(events) > 0 {
			id := b.ID
			if _, err := q.InsertTripEvents(ctx, dbgen.InsertTripEventsParams{
				MemberID: member, ClientTripID: b.ClientTripID, BatchID: &id, Events: eventsJSON,
			}); err != nil {
				return fmt.Errorf("insert events: %w", err)
			}
		}
		outcome, counts = store.BatchAccepted, b.Counts
		return nil
	})
	if err != nil {
		return 0, store.BatchCounts{}, err
	}
	return outcome, counts, nil
}

// ListSampleBatches implements store.Telemetry.
func (s *Telemetry) ListSampleBatches(ctx context.Context, member, clientTrip uuid.UUID, after *time.Time, afterBatch *store.SampleBatch, limit int) ([]store.SampleBatch, error) {
	n, err := pageLimit(limit)
	if err != nil {
		return nil, err
	}
	p := dbgen.ListSampleBatchesParams{MemberID: member, ClientTripID: clientTrip, AfterT: utcPtr(after), MaxRows: n}
	if afterBatch != nil {
		f := afterBatch.FirstSampleT.UTC()
		p.AfterFirst, p.AfterID = &f, afterBatch.ID
	}
	rows, err := s.q.ListSampleBatches(ctx, p)
	if err != nil {
		return nil, err
	}
	out := make([]store.SampleBatch, len(rows))
	for i, r := range rows {
		out[i] = store.SampleBatch{ID: r.ID, ObjectKey: r.ObjectKey, FirstSampleT: r.FirstSampleT.UTC(), LastSampleT: r.LastSampleT.UTC()}
	}
	return out, nil
}

// ListEvents implements store.Telemetry.
func (s *Telemetry) ListEvents(ctx context.Context, member, clientTrip uuid.UUID, after *store.EventPos, limit int) ([]store.StoredEvent, error) {
	n, err := pageLimit(limit)
	if err != nil {
		return nil, err
	}
	p := dbgen.ListTripEventsParams{MemberID: member, ClientTripID: clientTrip, MaxRows: n}
	if after != nil {
		t := after.T.UTC()
		p.CursorT, p.CursorID = &t, after.ID
	}
	rows, err := s.q.ListTripEvents(ctx, p)
	if err != nil {
		return nil, err
	}
	out := make([]store.StoredEvent, len(rows))
	for i, r := range rows {
		out[i] = store.StoredEvent{ID: r.ID, TelemetryEvent: store.TelemetryEvent{
			ClientEventID: r.ClientEventID, T: r.T.UTC(), Type: r.Type, Active: r.Active,
			Lat: r.Lat, Lon: r.Lon, Dtc: r.Dtc, Value: r.Value,
		}}
	}
	return out, nil
}
