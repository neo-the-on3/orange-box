package db

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"math"
	"slices"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Trips is the Postgres trip store.
//
// Sync walks (GET /v1/trips with a token, GET /v1/trips/deletions, and the
// unfiltered history walk that hands out the first token) read each page in
// one read-only REPEATABLE READ transaction. On a walk's first page, the
// first statement of that transaction takes the new token's watermark
// (SyncSnapshot), so the watermark and the page come from the same
// snapshot (#176).
type Trips struct {
	pool *pgxpool.Pool
	q    *dbgen.Queries
	// deploymentID is SYNC_DEPLOYMENT_ID, part of the deployment identity.
	deploymentID string
	// systemID is whether the role may read pg_control_system(); without
	// it the identity binds deploymentID only.
	systemID bool
	// hook, if set (tests), runs at named points inside a walk.
	hook func(stage string)
}

// NewTrips returns a trip store on pool. The deployment identity sync
// tokens and cursors carry combines the cluster's system_identifier (read
// in every walk's transaction) with deploymentID (SYNC_DEPLOYMENT_ID,
// required): a change of either expires them all (ErrDeploymentChanged).
// system_identifier catches a new cluster (dump/restore, migration);
// deploymentID, which operators change after any restore, PITR or branch
// reset, catches restores that keep the cluster's identifier. If the role
// can't read pg_control_system(), the identity binds deploymentID only
// (and NewTrips logs nothing: the caller should warn).
func NewTrips(ctx context.Context, pool *pgxpool.Pool, deploymentID string) (*Trips, error) {
	if deploymentID == "" {
		return nil, errors.New("trips: a deployment id (SYNC_DEPLOYMENT_ID) is required")
	}
	s := &Trips{pool: pool, q: dbgen.New(pool), deploymentID: deploymentID}
	if _, err := s.q.SystemIdentifier(ctx); err == nil {
		s.systemID = true
	}
	return s, nil
}

// ReadsSystemIdentifier reports whether the identity includes the
// cluster's system_identifier.
func (s *Trips) ReadsSystemIdentifier() bool { return s.systemID }

// DeploymentIdentity is the identity sync tokens carry for
// system_identifier sysID (empty when unreadable) and deploymentID: a
// 25-character digest of both, so tokens and cursors stay short.
func DeploymentIdentity(sysID, deploymentID string) string {
	h := sha256.New()
	h.Write([]byte("orange-box deployment identity\x00"))
	h.Write([]byte(sysID))
	h.Write([]byte{0})
	h.Write([]byte(deploymentID))
	return "d1:" + base64.RawURLEncoding.EncodeToString(h.Sum(nil)[:16])
}

func (s *Trips) at(stage string) {
	if s.hook != nil {
		s.hook(stage)
	}
}

// identity returns the deployment identity, read in q's transaction.
func (s *Trips) identity(ctx context.Context, q *dbgen.Queries) (string, error) {
	if !s.systemID {
		return DeploymentIdentity("", s.deploymentID), nil
	}
	id, err := q.SystemIdentifier(ctx)
	if err != nil {
		return "", fmt.Errorf("read system identifier: %w", err)
	}
	return DeploymentIdentity(strconv.FormatInt(id, 10), s.deploymentID), nil
}

// walk runs read in a read-only REPEATABLE READ transaction. With snapshot
// set it first takes the new SyncPoint, as the transaction's first
// statement (#176). since, if not nil, and cursor (a cursor's identity, if
// not empty) must belong to this deployment, else ErrDeploymentChanged,
// and since must be above the member's purge boundary (#177), else
// ErrSyncWindowExpired. It returns the new SyncPoint (if asked) and the
// identity the page was read under.
func (s *Trips) walk(ctx context.Context, member uuid.UUID, since *store.SyncPoint, cursor string, snapshot bool, read func(q *dbgen.Queries) error) (*store.SyncPoint, string, error) {
	var out *store.SyncPoint
	var identity string
	err := pgx.BeginTxFunc(ctx, s.pool, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		var snap dbgen.SyncSnapshotRow
		if snapshot {
			var err error
			if snap, err = q.SyncSnapshot(ctx); err != nil {
				return fmt.Errorf("sync snapshot: %w", err)
			}
		}
		s.at("snapshot")
		var err error
		if identity, err = s.identity(ctx, q); err != nil {
			return err
		}
		if since != nil && since.Identity != identity || cursor != "" && cursor != identity {
			return store.ErrDeploymentChanged
		}
		if since != nil {
			// In the same snapshot as the page (#177): a purge that
			// commits after this check is invisible to the page read
			// too, so the page still has every tombstone the token
			// needs, and the next page or walk sees the new boundary.
			if err := checkPurgeBoundary(ctx, q, member, *since); err != nil {
				return err
			}
		}
		if snapshot {
			out = &store.SyncPoint{Identity: identity, IssuedAt: snap.IssuedAt.UTC(), Watermark: snap.Watermark}
		}
		s.at("checked")
		return read(q)
	})
	if err != nil {
		return nil, "", err
	}
	return out, identity, nil
}

// checkPurgeBoundary rejects a token that a tombstone purge has expired:
// one is accepted only if its issue time is after the latest purged
// deletedAt and its watermark is above the highest purged writer id, i.e.
// no tombstone it might not have seen yet is gone.
func checkPurgeBoundary(ctx context.Context, q *dbgen.Queries, member uuid.UUID, since store.SyncPoint) error {
	b, err := q.GetTripPurgeBoundary(ctx, member)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read purge boundary: %w", err)
	}
	if !since.IssuedAt.After(b.MaxDeletedAt) || since.Watermark <= b.MaxWriterXid {
		return store.ErrSyncWindowExpired
	}
	return nil
}

func pageLimit(limit int) (int32, error) {
	if limit < 1 || limit > math.MaxInt32 {
		return 0, fmt.Errorf("trips: limit %d out of range", limit)
	}
	return int32(limit), nil
}

func after(p *store.ChangePos) (uint64, uuid.UUID) {
	if p == nil {
		return 0, uuid.UUID{}
	}
	return p.Xid, p.ID
}

// ListTripChanges returns a page of the member's live trips written at or
// after q.Since's watermark, in change order.
func (s *Trips) ListTripChanges(ctx context.Context, member uuid.UUID, tq store.TripChangesQuery) (store.TripPage, error) {
	limit, err := pageLimit(tq.Limit)
	if err != nil {
		return store.TripPage{}, err
	}
	var rows []dbgen.Trip
	cx, cid := after(tq.After)
	snap, identity, err := s.walk(ctx, member, &tq.Since, tq.Identity, tq.Snapshot, func(q *dbgen.Queries) error {
		var err error
		rows, err = q.ListTripChanges(ctx, dbgen.ListTripChangesParams{
			MemberID: member, Watermark: tq.Since.Watermark, CursorXid: cx, CursorID: cid, MaxRows: limit,
		})
		return err
	})
	if err != nil {
		return store.TripPage{}, err
	}
	return store.TripPage{Trips: toTrips(rows), Snapshot: snap, Identity: identity}, nil
}

// ListTripDeletions returns a page of the member's trip tombstones written
// at or after q.Since's watermark, in change order.
func (s *Trips) ListTripDeletions(ctx context.Context, member uuid.UUID, dq store.TripDeletionsQuery) (store.TripDeletionPage, error) {
	limit, err := pageLimit(dq.Limit)
	if err != nil {
		return store.TripDeletionPage{}, err
	}
	var rows []dbgen.ListTripDeletionsRow
	cx, cid := after(dq.After)
	snap, identity, err := s.walk(ctx, member, &dq.Since, dq.Identity, dq.Snapshot, func(q *dbgen.Queries) error {
		var err error
		rows, err = q.ListTripDeletions(ctx, dbgen.ListTripDeletionsParams{
			MemberID: member, Watermark: dq.Since.Watermark, CursorXid: cx, CursorID: cid, MaxRows: limit,
		})
		return err
	})
	if err != nil {
		return store.TripDeletionPage{}, err
	}
	out := make([]store.TripDeletion, len(rows))
	for i, r := range rows {
		out[i] = store.TripDeletion{
			ID: r.ID, ClientTripID: r.ClientTripID, VehicleID: r.VehicleID, DeletedAt: r.DeletedAt.UTC(),
			Change: store.ChangePos{Xid: r.WriterXid, ID: r.ID},
		}
	}
	return store.TripDeletionPage{Deletions: out, Snapshot: snap, Identity: identity}, nil
}

func toTrips(rows []dbgen.Trip) []store.Trip {
	out := make([]store.Trip, 0, len(rows))
	for _, r := range rows {
		out = append(out, toTrip(r))
	}
	return out
}

// toTrip maps a live row. The CHECK constraint trips_live_fields
// guarantees the required columns of a live row are set.
func toTrip(r dbgen.Trip) store.Trip {
	t := store.Trip{
		ID:           r.ID,
		ClientTripID: r.ClientTripID,
		TripFields: store.TripFields{
			State:             deref(r.State),
			EndedAt:           utcPtr(r.EndedAt),
			DurationS:         r.DurationS,
			DistanceKm:        r.DistanceKm,
			EngineDistanceKm:  r.EngineDistanceKm,
			MaxSpeedKmh:       r.MaxSpeedKmh,
			AvgSpeedKmh:       r.AvgSpeedKmh,
			MaxRpm:            r.MaxRpm,
			HarshAccelCount:   r.HarshAccelCount,
			HarshBrakeCount:   r.HarshBrakeCount,
			HarshStartCount:   r.HarshStartCount,
			HarshStopCount:    r.HarshStopCount,
			FuelUsedL:         r.FuelUsedL,
			FuelEconomyKmPerL: r.FuelEconomyKmPerL,
			FuelCutTimeS:      r.FuelCutTimeS,
			IdleTimeS:         r.IdleTimeS,
			WarmUpTimeS:       r.WarmUpTimeS,
			AccelTimeS:        r.AccelTimeS,
			EcoSpeedTimeS:     r.EcoSpeedTimeS,
			OverSpeedTimeS:    r.OverSpeedTimeS,
			SpeedBands:        r.SpeedBands,
			EcoScore:          r.EcoScore,
			SafetyScore:       r.SafetyScore,
			BatteryMaxV:       r.BatteryMaxV,
			BatteryAvgV:       r.BatteryAvgV,
			CoolantMaxC:       r.CoolantMaxC,
			CoolantAvgC:       r.CoolantAvgC,
			Dtc:               r.Dtc,
			App:               r.App,
		},
		PointsAwarded: r.PointsAwarded,
		CreatedAt:     r.CreatedAt.UTC(),
		UpdatedAt:     r.UpdatedAt.UTC(),
		Change:        store.ChangePos{Xid: r.WriterXid, ID: r.ID},
	}
	if r.VehicleID != nil {
		t.VehicleID = *r.VehicleID
	}
	if r.StartedAt != nil {
		t.StartedAt = r.StartedAt.UTC()
	}
	if r.SnapshotAt != nil {
		t.SnapshotAt = r.SnapshotAt.UTC()
	}
	if r.Simulated != nil {
		t.Simulated = *r.Simulated
	}
	return t
}

func utcPtr(t *time.Time) *time.Time {
	if t == nil {
		return nil
	}
	u := t.UTC()
	return &u
}

var _ store.Trips = (*Trips)(nil)

// UpsertTrips implements store.Trips. The batch runs in one transaction:
// it first share-locks the live vehicles it names (in id order), so it
// can't race a vehicle delete, then upserts the trips in clientTripId
// order, so two batches touching the same trips can't deadlock. A
// snapshot racing a trip delete is settled by the unique key on
// (member_id, client_trip_id): the upsert re-checks the latest row
// version and never overwrites a tombstone.
func (s *Trips) UpsertTrips(ctx context.Context, member uuid.UUID, items []store.TripUpload) ([]store.UpsertResult, error) {
	results := make([]store.UpsertResult, len(items))
	order := make([]int, len(items))
	vehicleSet := map[uuid.UUID]bool{}
	for i, it := range items {
		order[i] = i
		vehicleSet[it.VehicleID] = true
	}
	slices.SortStableFunc(order, func(a, b int) int {
		return bytes.Compare(items[a].ClientTripID[:], items[b].ClientTripID[:])
	})
	vehicles := make([]uuid.UUID, 0, len(vehicleSet))
	for id := range vehicleSet {
		vehicles = append(vehicles, id)
	}
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		live, err := q.LockLiveVehiclesForShare(ctx, dbgen.LockLiveVehiclesForShareParams{MemberID: member, Ids: vehicles})
		if err != nil {
			return fmt.Errorf("lock vehicles: %w", err)
		}
		liveSet := make(map[uuid.UUID]bool, len(live))
		for _, id := range live {
			liveSet[id] = true
		}
		for _, i := range order {
			res, err := upsertOne(ctx, q, member, items[i], liveSet[items[i].VehicleID])
			if err != nil {
				return err
			}
			results[i] = res
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return results, nil
}

func upsertOne(ctx context.Context, q *dbgen.Queries, member uuid.UUID, it store.TripUpload, vehicleLive bool) (store.UpsertResult, error) {
	if !vehicleLive {
		// A tombstone wins (the app drops the trip); otherwise the
		// vehicle is unknown.
		cur, err := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: it.ClientTripID})
		switch {
		case err == nil && cur.DeletedAt != nil:
			return store.UpsertResult{Outcome: store.TripDeleted}, nil
		case err != nil && !errors.Is(err, pgx.ErrNoRows):
			return store.UpsertResult{}, err
		}
		return store.UpsertResult{Outcome: store.TripVehicleNotFound}, nil
	}
	p := upsertParams(member, it)
	// Retried only when the row that blocked the upsert vanished before we
	// read it (its tombstone was purged in between).
	for range maxAttempts {
		var err error
		if p.ID, err = uuid.NewV7(); err != nil {
			return store.UpsertResult{}, err
		}
		row, err := q.UpsertTrip(ctx, p)
		if err == nil {
			if row.Inserted {
				return store.UpsertResult{Outcome: store.TripCreated, ID: row.ID}, nil
			}
			return store.UpsertResult{Outcome: store.TripUpdated, ID: row.ID}, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return store.UpsertResult{}, fmt.Errorf("upsert trip: %w", err)
		}
		cur, err := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: it.ClientTripID})
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			continue
		case err != nil:
			return store.UpsertResult{}, err
		case cur.DeletedAt != nil:
			return store.UpsertResult{Outcome: store.TripDeleted}, nil
		default:
			return store.UpsertResult{Outcome: store.TripUnchanged, ID: cur.ID}, nil
		}
	}
	return store.UpsertResult{}, errors.New("trips: upsert kept racing a purge; retry")
}

func upsertParams(member uuid.UUID, it store.TripUpload) dbgen.UpsertTripParams {
	f := it.TripFields
	return dbgen.UpsertTripParams{
		MemberID: member, ClientTripID: it.ClientTripID, VehicleID: f.VehicleID,
		State: f.State, StartedAt: f.StartedAt, EndedAt: f.EndedAt, SnapshotAt: f.SnapshotAt,
		DurationS: f.DurationS, DistanceKm: f.DistanceKm, EngineDistanceKm: f.EngineDistanceKm,
		MaxSpeedKmh: f.MaxSpeedKmh, AvgSpeedKmh: f.AvgSpeedKmh, MaxRpm: f.MaxRpm,
		HarshAccelCount: f.HarshAccelCount, HarshBrakeCount: f.HarshBrakeCount,
		HarshStartCount: f.HarshStartCount, HarshStopCount: f.HarshStopCount,
		FuelUsedL: f.FuelUsedL, FuelEconomyKmPerL: f.FuelEconomyKmPerL, FuelCutTimeS: f.FuelCutTimeS,
		IdleTimeS: f.IdleTimeS, WarmUpTimeS: f.WarmUpTimeS, AccelTimeS: f.AccelTimeS,
		EcoSpeedTimeS: f.EcoSpeedTimeS, OverSpeedTimeS: f.OverSpeedTimeS, SpeedBands: f.SpeedBands,
		EcoScore: f.EcoScore, SafetyScore: f.SafetyScore, BatteryMaxV: f.BatteryMaxV,
		BatteryAvgV: f.BatteryAvgV, CoolantMaxC: f.CoolantMaxC, CoolantAvgC: f.CoolantAvgC,
		Dtc: f.Dtc, Simulated: f.Simulated, App: f.App,
	}
}

// liveTrip reads a live trip by ref, or ErrNotFound.
func liveTrip(ctx context.Context, q *dbgen.Queries, member uuid.UUID, ref store.TripRef) (dbgen.Trip, error) {
	var row dbgen.Trip
	var err error
	switch {
	case ref.ID != nil:
		row, err = q.GetTrip(ctx, dbgen.GetTripParams{MemberID: member, ID: *ref.ID})
	case ref.ClientID != nil:
		row, err = q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: *ref.ClientID})
		if err == nil && row.DeletedAt != nil {
			err = pgx.ErrNoRows
		}
	default:
		return dbgen.Trip{}, errors.New("trips: empty trip reference")
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return dbgen.Trip{}, store.ErrNotFound
	}
	return row, err
}

// GetTrip implements store.Trips.
func (s *Trips) GetTrip(ctx context.Context, member uuid.UUID, ref store.TripRef) (store.Trip, error) {
	row, err := liveTrip(ctx, s.q, member, ref)
	if err != nil {
		return store.Trip{}, err
	}
	return toTrip(row), nil
}

// DeleteTrip implements store.Trips. The tombstone UPDATE locks the row,
// so a concurrent snapshot upsert or telemetry upload (which share-locks
// the trip) either commits first and is erased with it, or runs after and
// finds the tombstone.
func (s *Trips) DeleteTrip(ctx context.Context, member uuid.UUID, ref store.TripRef) ([]string, error) {
	var keys []string
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		row, err := liveTrip(ctx, q, member, ref)
		if err != nil {
			return err
		}
		gone, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: row.ID})
		if errors.Is(err, pgx.ErrNoRows) {
			return store.ErrNotFound // deleted concurrently
		}
		if err != nil {
			return fmt.Errorf("tombstone trip: %w", err)
		}
		keys, err = q.DeleteTelemetry(ctx, dbgen.DeleteTelemetryParams{MemberID: member, ClientTripIds: []uuid.UUID{gone.ClientTripID}})
		if err != nil {
			return fmt.Errorf("delete telemetry: %w", err)
		}
		return nil
	})
	return keys, err
}

// ListTripHistory implements store.Trips.
func (s *Trips) ListTripHistory(ctx context.Context, member uuid.UUID, hq store.TripHistoryQuery) (store.TripPage, error) {
	limit, err := pageLimit(hq.Limit)
	if err != nil {
		return store.TripPage{}, err
	}
	var curAt *time.Time
	var curID uuid.UUID
	if hq.After != nil {
		at := hq.After.StartedAt
		curAt, curID = &at, hq.After.ID
	}
	var rows []dbgen.Trip
	snap, identity, err := s.walk(ctx, member, nil, hq.Identity, hq.Snapshot, func(q *dbgen.Queries) error {
		var err error
		if hq.VehicleID != nil {
			rows, err = q.ListTripsByVehicle(ctx, dbgen.ListTripsByVehicleParams{
				MemberID: member, VehicleID: *hq.VehicleID, StartedFrom: hq.From, StartedBefore: hq.To,
				CursorStartedAt: curAt, CursorID: curID, MaxRows: limit,
			})
		} else {
			rows, err = q.ListTrips(ctx, dbgen.ListTripsParams{
				MemberID: member, StartedFrom: hq.From, StartedBefore: hq.To,
				CursorStartedAt: curAt, CursorID: curID, MaxRows: limit,
			})
		}
		return err
	})
	if err != nil {
		return store.TripPage{}, err
	}
	return store.TripPage{Trips: toTrips(rows), Snapshot: snap, Identity: identity}, nil
}

// DeletedClientTrips implements store.Trips.
func (s *Trips) DeletedClientTrips(ctx context.Context, member uuid.UUID, clientIDs []uuid.UUID) (map[uuid.UUID]bool, error) {
	ids, err := s.q.ListDeletedClientTrips(ctx, dbgen.ListDeletedClientTripsParams{MemberID: member, ClientTripIds: clientIDs})
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]bool, len(ids))
	for _, id := range ids {
		out[id] = true
	}
	return out, nil
}

// DrivingDays implements store.Trips.
func (s *Trips) DrivingDays(ctx context.Context, member uuid.UUID, bounds []time.Time, vehicle *uuid.UUID) ([]int, error) {
	if len(bounds) < 2 {
		return nil, errors.New("driving days: need at least two bounds")
	}
	for i := 1; i < len(bounds); i++ {
		if bounds[i].Before(bounds[i-1]) {
			return nil, fmt.Errorf("driving days: bounds decreasing at %d", i)
		}
	}
	if !bounds[len(bounds)-1].After(bounds[0]) {
		return make([]int, len(bounds)-1), nil // every range empty (e.g. a skipped day)
	}
	rows, err := s.q.ListDrivingDays(ctx, dbgen.ListDrivingDaysParams{Bounds: bounds, MemberID: member, VehicleID: vehicle})
	if err != nil {
		return nil, err
	}
	counts := make([]int, len(bounds)-1)
	for _, r := range rows {
		if i := int(r.Bucket) - 1; i >= 0 && i < len(counts) {
			counts[i] = int(r.TripCount)
		}
	}
	return counts, nil
}
