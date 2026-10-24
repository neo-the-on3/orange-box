package db

import (
	"context"
	"errors"
	"fmt"
	"math"
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
	// deploymentID is the configured identity, used only when the role
	// can't read pg_control_system().
	deploymentID string
	systemID     bool
	// hook, if set (tests), runs at named points inside a walk.
	hook func(stage string)
}

// NewTrips returns a trip store on pool. The deployment identity sync
// tokens carry is the cluster's system_identifier when the role may read
// pg_control_system(), else deploymentID (SYNC_DEPLOYMENT_ID); with
// neither, it fails.
func NewTrips(ctx context.Context, pool *pgxpool.Pool, deploymentID string) (*Trips, error) {
	s := &Trips{pool: pool, q: dbgen.New(pool), deploymentID: deploymentID}
	if _, err := s.q.SystemIdentifier(ctx); err == nil {
		s.systemID = true
	} else if deploymentID == "" {
		return nil, fmt.Errorf("trips: pg_control_system() is not readable (%w); set SYNC_DEPLOYMENT_ID", err)
	}
	return s, nil
}

func (s *Trips) at(stage string) {
	if s.hook != nil {
		s.hook(stage)
	}
}

// identity returns the deployment identity, read in q's transaction.
func (s *Trips) identity(ctx context.Context, q *dbgen.Queries) (string, error) {
	if !s.systemID {
		return "id:" + s.deploymentID, nil
	}
	id, err := q.SystemIdentifier(ctx)
	if err != nil {
		return "", fmt.Errorf("read system identifier: %w", err)
	}
	return "pg:" + strconv.FormatInt(id, 10), nil
}

// walk runs read in a read-only REPEATABLE READ transaction. With snapshot
// set it first takes the new SyncPoint, as the transaction's first
// statement (#176). since, if not nil, must belong to this deployment and
// be above the member's purge boundary (#177), else ErrSyncWindowExpired.
func (s *Trips) walk(ctx context.Context, member uuid.UUID, since *store.SyncPoint, snapshot bool, read func(q *dbgen.Queries) error) (*store.SyncPoint, error) {
	var out *store.SyncPoint
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
		if snapshot || since != nil {
			identity, err := s.identity(ctx, q)
			if err != nil {
				return err
			}
			if since != nil && since.Identity != identity {
				return store.ErrSyncWindowExpired
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
		}
		s.at("checked")
		return read(q)
	})
	if err != nil {
		return nil, err
	}
	return out, nil
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
	snap, err := s.walk(ctx, member, &tq.Since, tq.Snapshot, func(q *dbgen.Queries) error {
		var err error
		rows, err = q.ListTripChanges(ctx, dbgen.ListTripChangesParams{
			MemberID: member, Watermark: tq.Since.Watermark, CursorXid: cx, CursorID: cid, MaxRows: limit,
		})
		return err
	})
	if err != nil {
		return store.TripPage{}, err
	}
	return store.TripPage{Trips: toTrips(rows), Snapshot: snap}, nil
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
	snap, err := s.walk(ctx, member, &dq.Since, dq.Snapshot, func(q *dbgen.Queries) error {
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
	return store.TripDeletionPage{Deletions: out, Snapshot: snap}, nil
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
