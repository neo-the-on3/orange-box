package db_test

import (
	"context"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func newTrips(t *testing.T, pool *pgxpool.Pool) *db.Trips {
	t.Helper()
	s, err := db.NewTrips(t.Context(), pool, "test-deployment")
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// origin is a sync point from before anything was written: every row of
// the member is at or above watermark 1.
func origin(t *testing.T, q *dbgen.Queries) store.SyncPoint {
	t.Helper()
	id, err := q.SystemIdentifier(t.Context())
	if err != nil {
		return store.SyncPoint{Identity: "id:test-deployment", IssuedAt: time.Now(), Watermark: 1}
	}
	return store.SyncPoint{Identity: "pg:" + strconv.FormatInt(id, 10), IssuedAt: time.Now(), Watermark: 1}
}

// once returns a hook that runs f the first time stage is reached.
func once(stage string, f func()) func(string) {
	var o sync.Once
	return func(s string) {
		if s == stage {
			o.Do(f)
		}
	}
}

func changeIDs(p store.TripPage) []uuid.UUID {
	ids := make([]uuid.UUID, len(p.Trips))
	for i, tr := range p.Trips {
		ids[i] = tr.ID
	}
	return ids
}

func deletionIDs(p store.TripDeletionPage) []uuid.UUID {
	ids := make([]uuid.UUID, len(p.Deletions))
	for i, d := range p.Deletions {
		ids[i] = d.ID
	}
	return ids
}

// TestSyncWatermarkTakenWithFirstPage is #176: a trip that commits between
// the moment the walk's token is taken and the moment its first page is
// read must come back in the next walk. The store reads both in one
// REPEATABLE READ snapshot, so the write is either on the page or at or
// above the token's watermark, never lost.
func TestSyncWatermarkTakenWithFirstPage(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	a, err := q.UpsertTrip(ctx, tripParams(member, vehicle, newID(), "in_progress", t0))
	if err != nil {
		t.Fatal(err)
	}
	var b dbgen.UpsertTripRow
	db.SetTripsHook(trips, once("checked", func() {
		// Commits on another connection after the token is taken and
		// before the page is read.
		var err error
		if b, err = q.UpsertTrip(ctx, tripParams(member, vehicle, newID(), "in_progress", t0)); err != nil {
			t.Error(err)
		}
	}))
	first, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: origin(t, q), Snapshot: true, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if got := changeIDs(first); !containsID(got, a.ID) || containsID(got, b.ID) {
		t.Fatalf("first page = %v, want A and not B (written after the snapshot)", got)
	}
	if first.Snapshot == nil {
		t.Fatal("no snapshot on the first page")
	}
	next, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: *first.Snapshot, Snapshot: true, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if !containsID(changeIDs(next), b.ID) {
		t.Fatalf("next walk = %v, missing B (committed after the token was taken)", changeIDs(next))
	}
}

// TestSyncLateCommitAppearsInNextWalk: a transaction that is still open
// while a walk runs, and commits after it, is in the next walk.
func TestSyncLateCommitAppearsInNextWalk(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	late, err := q.WithTx(tx).UpsertTrip(ctx, tripParams(member, vehicle, newID(), "in_progress", t0))
	if err != nil {
		t.Fatal(err)
	}
	walk, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: origin(t, q), Snapshot: true, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if containsID(changeIDs(walk), late.ID) {
		t.Fatal("uncommitted trip visible")
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	next, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: *walk.Snapshot, Snapshot: true, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if !containsID(changeIDs(next), late.ID) {
		t.Fatalf("next walk = %v, missing the late commit", changeIDs(next))
	}
}

// TestSyncDeletionTakenWithFirstPage is #176 for GET /v1/trips/deletions.
func TestSyncDeletionTakenWithFirstPage(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	a, err := q.UpsertTrip(ctx, tripParams(member, vehicle, newID(), "completed", t0))
	if err != nil {
		t.Fatal(err)
	}
	db.SetTripsHook(trips, once("checked", func() {
		if _, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: a.ID}); err != nil {
			t.Error(err)
		}
	}))
	first, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: origin(t, q), Snapshot: true, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if len(first.Deletions) != 0 {
		t.Fatalf("first page = %v, want none (deleted after the snapshot)", deletionIDs(first))
	}
	next, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: *first.Snapshot, Snapshot: true, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if got := deletionIDs(next); len(got) != 1 || got[0] != a.ID {
		t.Fatalf("next deletions walk = %v, want A", got)
	}
}

// TestSyncTokenOfAnotherDeployment: a token whose identity isn't this
// cluster's is expired.
func TestSyncTokenOfAnotherDeployment(t *testing.T) {
	t.Parallel()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	since := origin(t, q)
	since.Identity = "pg:1"
	if _, err := trips.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: since, Limit: 10}); err != store.ErrSyncWindowExpired {
		t.Fatalf("changes with a foreign token: %v", err)
	}
	if _, err := trips.ListTripDeletions(t.Context(), member, store.TripDeletionsQuery{Since: since, Limit: 10}); err != store.ErrSyncWindowExpired {
		t.Fatalf("deletions with a foreign token: %v", err)
	}
}
