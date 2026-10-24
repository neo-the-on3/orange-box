package db_test

import (
	"context"
	"errors"
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
	if _, err := trips.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: since, Limit: 10}); !errors.Is(err, store.ErrSyncWindowExpired) {
		t.Fatalf("changes with a foreign token: %v", err)
	}
	if _, err := trips.ListTripDeletions(t.Context(), member, store.TripDeletionsQuery{Since: since, Limit: 10}); !errors.Is(err, store.ErrSyncWindowExpired) {
		t.Fatalf("deletions with a foreign token: %v", err)
	}
}

// agedTombstone writes a completed trip, deletes it and backdates the
// tombstone to deletedAt, returning the tombstone's id.
func agedTombstone(t *testing.T, pool *pgxpool.Pool, q *dbgen.Queries, member, vehicle uuid.UUID, deletedAt time.Time) uuid.UUID {
	t.Helper()
	ctx := t.Context()
	r, err := q.UpsertTrip(ctx, tripParams(member, vehicle, newID(), "completed", deletedAt.Add(-time.Hour)))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: r.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE trips SET deleted_at = $2 WHERE id = $1`, r.ID, deletedAt); err != nil {
		t.Fatal(err)
	}
	return r.ID
}

// TestSyncPurgeBetweenCheckAndRead is #177: a purge that commits after the
// token was checked against the purge boundary and before the deletions
// page is read must not make tombstones the token needs vanish silently.
// The check and the read share one snapshot, so the page still has them,
// and the next page answers sync_window_expired.
func TestSyncPurgeBetweenCheckAndRead(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	old := time.Date(2026, 6, 1, 8, 0, 0, 0, time.UTC)
	a := agedTombstone(t, pool, q, member, vehicle, old)
	b := agedTombstone(t, pool, q, member, vehicle, old.Add(time.Minute))
	since := origin(t, q) // issued before the purge, so the purge expires it

	db.SetTripsHook(trips, once("checked", func() {
		if _, err := q.PurgeTripTombstones(ctx, dbgen.PurgeTripTombstonesParams{DeletedBefore: old.Add(24 * time.Hour), MaxRows: 100}); err != nil {
			t.Error(err)
		}
	}))
	first, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: since, Snapshot: true, Limit: 1})
	if err != nil {
		t.Fatalf("first page: %v", err)
	}
	if got := deletionIDs(first); len(got) != 1 || (got[0] != a && got[0] != b) {
		t.Fatalf("first page = %v, want one of the tombstones checked against the old boundary", got)
	}
	last := first.Deletions[0].Change
	if _, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: since, After: &last, Limit: 1}); !errors.Is(err, store.ErrSyncWindowExpired) {
		t.Fatalf("page after the purge: %v, want ErrSyncWindowExpired", err)
	}
	if _, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: since, Snapshot: true, Limit: 10}); !errors.Is(err, store.ErrSyncWindowExpired) {
		t.Fatalf("trips walk with the purged token: %v, want ErrSyncWindowExpired", err)
	}
}

// TestSyncPurgeBoundaryExpiry: a token is expired once a tombstone it
// might not have seen is purged, and only then; a token taken after the
// purge works.
func TestSyncPurgeBoundaryExpiry(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	other := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	otherVehicle := insertVehicle(t, q, other, nil)
	old := time.Date(2026, 6, 1, 8, 0, 0, 0, time.UTC)
	agedTombstone(t, pool, q, member, vehicle, old)
	agedTombstone(t, pool, q, other, otherVehicle, old)

	if before, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: origin(t, q), Limit: 10}); err != nil || len(before.Deletions) != 1 {
		t.Fatalf("before purge: %+v, %v", before, err)
	}
	if _, err := q.PurgeTripTombstones(ctx, dbgen.PurgeTripTombstonesParams{DeletedBefore: old.Add(24 * time.Hour), MaxRows: 100}); err != nil {
		t.Fatal(err)
	}
	if _, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: origin(t, q), Limit: 10}); !errors.Is(err, store.ErrSyncWindowExpired) {
		t.Fatalf("old token after purge: %v", err)
	}
	b, err := q.GetTripPurgeBoundary(ctx, member)
	if err != nil {
		t.Fatal(err)
	}
	// Strictly above the boundary in both time and watermark: accepted.
	above := origin(t, q)
	above.IssuedAt, above.Watermark = b.MaxDeletedAt.Add(time.Microsecond), b.MaxWriterXid+1
	if _, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: above, Limit: 10}); err != nil {
		t.Fatalf("token above the boundary: %v", err)
	}
	// At the boundary in either one: expired.
	for name, sp := range map[string]store.SyncPoint{
		"issuedAt at the purged deletedAt": {Identity: above.Identity, IssuedAt: b.MaxDeletedAt, Watermark: above.Watermark},
		"watermark at the purged xid":      {Identity: above.Identity, IssuedAt: above.IssuedAt, Watermark: b.MaxWriterXid},
	} {
		if _, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: sp, Limit: 10}); !errors.Is(err, store.ErrSyncWindowExpired) {
			t.Errorf("%s: %v, want ErrSyncWindowExpired", name, err)
		}
	}
	// The boundary is per member.
	stranger := newMember(t, q)
	if _, err := trips.ListTripChanges(ctx, stranger, store.TripChangesQuery{Since: origin(t, q), Limit: 10}); err != nil {
		t.Fatalf("member without purges: %v", err)
	}
}
