package db_test

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
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
	return store.SyncPoint{Identity: identity(t, q, "test-deployment"), IssuedAt: time.Now(), Watermark: 1}
}

// identity is the deployment identity of a store with deploymentID on
// this cluster.
func identity(t *testing.T, q *dbgen.Queries, deploymentID string) string {
	t.Helper()
	id, err := q.SystemIdentifier(t.Context())
	if err != nil {
		return db.DeploymentIdentity("", deploymentID)
	}
	return db.DeploymentIdentity(strconv.FormatInt(id, 10), deploymentID)
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

// TestSyncTokenOfAnotherDeployment: a token or cursor issued under
// another deployment identity, from another cluster (system_identifier)
// or under another SYNC_DEPLOYMENT_ID (a restore that kept the
// cluster), is ErrDeploymentChanged, which is an ErrSyncWindowExpired.
func TestSyncTokenOfAnotherDeployment(t *testing.T) {
	t.Parallel()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	for i := range 3 {
		if _, err := q.UpsertTrip(t.Context(), tripParams(member, vehicle, newID(), "completed", time.Date(2026, 10, 1+i, 8, 0, 0, 0, time.UTC))); err != nil {
			t.Fatal(err)
		}
	}
	sysID := ""
	if id, err := q.SystemIdentifier(t.Context()); err == nil {
		sysID = strconv.FormatInt(id, 10)
	}
	ok := origin(t, q)
	if _, err := trips.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: ok, Limit: 10}); err != nil {
		t.Fatalf("own token: %v", err)
	}
	foreign := map[string]string{
		"other cluster":       db.DeploymentIdentity("1", "test-deployment"),
		"other deployment id": db.DeploymentIdentity(sysID, "test-deployment-restored"),
		"legacy format":       "pg:" + sysID,
	}
	for name, id := range foreign {
		since := ok
		since.Identity = id
		if _, err := trips.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: since, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) || !errors.Is(err, store.ErrSyncWindowExpired) {
			t.Errorf("%s: changes: %v, want ErrDeploymentChanged", name, err)
		}
		if _, err := trips.ListTripDeletions(t.Context(), member, store.TripDeletionsQuery{Since: since, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) {
			t.Errorf("%s: deletions: %v, want ErrDeploymentChanged", name, err)
		}
		// Cursors: a later page of a walk begun under another identity.
		if _, err := trips.ListTripHistory(t.Context(), member, store.TripHistoryQuery{Identity: id, After: &store.HistoryPos{StartedAt: time.Now(), ID: newID()}, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) {
			t.Errorf("%s: history cursor: %v, want ErrDeploymentChanged", name, err)
		}
		if _, err := trips.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: ok, Identity: id, After: &store.ChangePos{Xid: 1, ID: newID()}, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) {
			t.Errorf("%s: changes cursor: %v, want ErrDeploymentChanged", name, err)
		}
		if _, err := trips.ListTripDeletions(t.Context(), member, store.TripDeletionsQuery{Since: ok, Identity: id, After: &store.ChangePos{Xid: 1, ID: newID()}, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) {
			t.Errorf("%s: deletions cursor: %v, want ErrDeploymentChanged", name, err)
		}
	}
	// The same database behind a store with another SYNC_DEPLOYMENT_ID
	// (what operators set after a restore) rejects this store's tokens and
	// cursors, and pages carry the identity they were read under.
	restored, err := db.NewTrips(t.Context(), pool, "test-deployment-restored")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := restored.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: ok, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) {
		t.Errorf("restored store, old token: %v, want ErrDeploymentChanged", err)
	}
	page, err := trips.ListTripHistory(t.Context(), member, store.TripHistoryQuery{Snapshot: true, Limit: 1})
	if err != nil {
		t.Fatal(err)
	}
	if page.Identity != ok.Identity || page.Snapshot.Identity != ok.Identity || len(ok.Identity) != 25 {
		t.Fatalf("page identity %q, snapshot %q, want %q", page.Identity, page.Snapshot.Identity, ok.Identity)
	}
	after := store.HistoryPos{StartedAt: page.Trips[0].StartedAt, ID: page.Trips[0].ID}
	if _, err := restored.ListTripHistory(t.Context(), member, store.TripHistoryQuery{Identity: page.Identity, After: &after, Limit: 10}); !errors.Is(err, store.ErrDeploymentChanged) {
		t.Errorf("restored store, old history cursor: %v, want ErrDeploymentChanged", err)
	}
	if next, err := trips.ListTripHistory(t.Context(), member, store.TripHistoryQuery{Identity: page.Identity, After: &after, Limit: 10}); err != nil || len(next.Trips) != 2 {
		t.Errorf("own history cursor: %d trips, %v", len(next.Trips), err)
	}
	if _, err := db.NewTrips(t.Context(), pool, ""); err == nil {
		t.Error("NewTrips without a deployment id")
	}
}

func TestDeploymentIdentity(t *testing.T) {
	t.Parallel()
	seen := map[string]string{}
	for _, c := range [][2]string{{"1", "2x"}, {"12", "x"}, {"", "a"}, {"7311", "prod-1"}, {"7311", "prod-2"}, {"7312", "prod-1"}} {
		id := db.DeploymentIdentity(c[0], c[1])
		if len(id) != 25 || !strings.HasPrefix(id, "d1:") {
			t.Errorf("DeploymentIdentity(%q, %q) = %q", c[0], c[1], id)
		}
		if prev, dup := seen[id]; dup {
			t.Errorf("DeploymentIdentity(%q, %q) collides with %s", c[0], c[1], prev)
		}
		seen[id] = c[0] + "/" + c[1]
		if db.DeploymentIdentity(c[0], c[1]) != id {
			t.Error("not deterministic")
		}
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

// TestTripsInstancesAgree: instances of a deployment (same database and
// SYNC_DEPLOYMENT_ID) compute the same identity, so tokens and cursors
// move freely between them; the identity is read once, at startup.
func TestTripsInstancesAgree(t *testing.T) {
	t.Parallel()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	a, b := newTrips(t, pool), newTrips(t, pool)
	if a.Identity() != b.Identity() || a.Identity() != identity(t, q, "test-deployment") || a.IdentitySource() != db.IdentityClusterAndDeploymentID {
		t.Fatalf("instances disagree: %q, %q", a.Identity(), b.Identity())
	}
	only, err := db.NewTripsWith(t.Context(), pool, db.TripsOptions{DeploymentID: "test-deployment", IdentitySource: db.IdentityDeploymentIDOnly})
	if err != nil {
		t.Fatal(err)
	}
	if only.Identity() == a.Identity() || only.Identity() != db.DeploymentIdentity("", "test-deployment") {
		t.Errorf("deployment_id_only identity %q", only.Identity())
	}
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	for i := range 4 {
		if _, err := q.UpsertTrip(t.Context(), tripParams(member, vehicle, newID(), "completed", time.Date(2026, 10, 1+i, 8, 0, 0, 0, time.UTC))); err != nil {
			t.Fatal(err)
		}
	}
	// A walk alternating between the instances, page by page.
	page, err := a.ListTripHistory(t.Context(), member, store.TripHistoryQuery{Snapshot: true, Limit: 1})
	if err != nil {
		t.Fatal(err)
	}
	seen := len(page.Trips)
	for i, s := 0, []*db.Trips{b, a}; seen < 4; i++ {
		last := page.Trips[len(page.Trips)-1]
		if page, err = s[i%2].ListTripHistory(t.Context(), member, store.TripHistoryQuery{Identity: page.Identity, After: &store.HistoryPos{StartedAt: last.StartedAt, ID: last.ID}, Limit: 1}); err != nil {
			t.Fatalf("page %d: %v", seen+1, err)
		}
		seen += len(page.Trips)
	}
	if _, err := b.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: origin(t, q), Limit: 10}); err != nil {
		t.Errorf("a token on another instance: %v", err)
	}
}

// TestClusterGuardDefaultRead: with the real pg_control_system() read,
// new connections to the same cluster pass the guard and keep serving.
func TestClusterGuardDefaultRead(t *testing.T) {
	t.Parallel()
	guard := db.NewClusterGuard(db.ClusterGuardOptions{DeploymentID: "test-deployment"})
	pool := dbtest.NewWith(t, db.Options{Guard: guard, MaxConns: 2})
	trips, err := db.NewTripsWith(t.Context(), pool, db.TripsOptions{DeploymentID: "test-deployment", Guard: guard})
	if err != nil {
		t.Fatal(err)
	}
	q := dbgen.New(pool)
	member := newMember(t, q)
	for range 3 {
		pool.Reset()
		if _, err := trips.ListTripChanges(t.Context(), member, store.TripChangesQuery{Since: origin(t, q), Limit: 1}); err != nil || guard.Err() != nil {
			t.Fatalf("after reconnecting to the same cluster: %v, guard %v", err, guard.Err())
		}
	}
	if db.Classify(fmt.Errorf("acquire: %w", db.ErrClusterChanged)) != store.FailureUnavailable {
		t.Error("ErrClusterChanged is not FailureUnavailable")
	}
}

// switchableGuard is a guard whose connections reach "another cluster"
// (system_identifier XOR 1) once switched is set; reads counts its reads.
func switchableGuard(switched *atomic.Bool, reads *atomic.Int32) *db.ClusterGuard {
	return db.NewClusterGuard(db.ClusterGuardOptions{
		DeploymentID: "test-deployment",
		Read: func(ctx context.Context, conn *pgx.Conn) (int64, error) {
			reads.Add(1)
			var id int64
			if err := conn.QueryRow(ctx, "SELECT system_identifier FROM pg_control_system()").Scan(&id); err != nil {
				return 0, err
			}
			if switched.Load() {
				id ^= 1
			}
			return id, nil
		},
	})
}

// TestClusterSwitchDetectedUnderBusyPool: the pool is full and every
// connection is busy (and still reaches the old cluster), so no new pool
// connection is made; the background check on a fresh connection still
// notices the switch within interval + probe timeout, and from then on
// no connection is even dialled.
func TestClusterSwitchDetectedUnderBusyPool(t *testing.T) {
	t.Parallel()
	var switched atomic.Bool
	var reads atomic.Int32
	guard := switchableGuard(&switched, &reads)
	pool := dbtest.NewWith(t, db.Options{Guard: guard, MaxConns: 2})
	trips, err := db.NewTripsWith(t.Context(), pool, db.TripsOptions{DeploymentID: "test-deployment", Guard: guard})
	if err != nil {
		t.Fatal(err)
	}
	// Fill the pool with busy connections.
	var held []*pgxpool.Conn
	for range 2 {
		c, err := pool.Acquire(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		held = append(held, c)
	}
	busy, cancelBusy := context.WithCancel(t.Context())
	var wg sync.WaitGroup
	for _, c := range held {
		wg.Go(func() {
			for busy.Err() == nil {
				_, _ = c.Exec(busy, "SELECT pg_sleep(0.05)")
			}
		})
	}
	const interval = 200 * time.Millisecond
	runCtx, stop := context.WithCancel(t.Context())
	defer stop()
	go guard.Run(runCtx, interval)

	switched.Store(true)
	start := time.Now()
	for guard.Err() == nil {
		if time.Since(start) > interval+6*time.Second { // the bound: interval + clusterProbeTimeout (5 s), plus slack
			t.Fatal("switch not detected under a busy pool")
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Logf("detected after %v (interval %v)", time.Since(start).Round(time.Millisecond), interval)
	cancelBusy()
	wg.Wait()
	for _, c := range held {
		c.Release()
	}
	q := dbgen.New(pool)
	if _, err := trips.ListTripChanges(t.Context(), newID(), store.TripChangesQuery{Since: store.SyncPoint{Identity: trips.Identity(), Watermark: 1}, Limit: 1}); !errors.Is(err, db.ErrClusterChanged) {
		t.Errorf("walk after detection: %v", err)
	}
	before := reads.Load()
	for range 20 {
		if _, err := q.SystemIdentifier(t.Context()); db.Classify(err) != store.FailureUnavailable {
			t.Fatalf("query after detection: %v", err)
		}
	}
	if reads.Load() != before {
		t.Errorf("%d connections dialled and checked after tripping", reads.Load()-before)
	}
}

// TestClusterGuardPinReplacesUncheckedConnections: connections opened
// before the identity was pinned weren't checked; pinning resets the
// pool, so every connection used afterwards went through the check.
func TestClusterGuardPinReplacesUncheckedConnections(t *testing.T) {
	t.Parallel()
	var switched atomic.Bool
	var reads atomic.Int32
	guard := switchableGuard(&switched, &reads)
	pool := dbtest.NewWith(t, db.Options{Guard: guard, MaxConns: 2})
	var pid int
	if err := pool.QueryRow(t.Context(), "SELECT pg_backend_pid()").Scan(&pid); err != nil {
		t.Fatal(err)
	}
	if reads.Load() != 0 {
		t.Fatal("checked before pinning")
	}
	if _, err := db.NewTripsWith(t.Context(), pool, db.TripsOptions{DeploymentID: "test-deployment", Guard: guard}); err != nil {
		t.Fatal(err)
	}
	var after int
	if err := pool.QueryRow(t.Context(), "SELECT pg_backend_pid()").Scan(&after); err != nil {
		t.Fatal(err)
	}
	if after == pid || reads.Load() == 0 {
		t.Errorf("connection from before the pin still in use (pid %d → %d, %d checks)", pid, after, reads.Load())
	}
}

func TestClassifyReadOnly(t *testing.T) {
	t.Parallel()
	pool := dbtest.New(t)
	_, err := pool.Exec(t.Context(), "BEGIN READ ONLY; CREATE TEMP TABLE ro_probe (x int); COMMIT")
	var pg *pgconn.PgError
	if !errors.As(err, &pg) || pg.Code != "25006" {
		t.Fatalf("want 25006, got %v", err)
	}
	if db.Classify(err) != store.FailureUnavailable {
		t.Errorf("25006 classified as %v", db.Classify(err))
	}
}

// TestClusterCheckFailuresUnhealthy: background checks that keep failing
// (e.g. the endpoint now rejects the server's credentials) make readiness
// fail after 3 in a row without latching: the pool keeps serving, and the
// next good check makes it healthy again.
func TestClusterCheckFailuresUnhealthy(t *testing.T) {
	t.Parallel()
	var failing atomic.Bool
	guard := db.NewClusterGuard(db.ClusterGuardOptions{
		DeploymentID: "test-deployment",
		Read: func(ctx context.Context, conn *pgx.Conn) (int64, error) {
			if failing.Load() {
				return 0, &pgconn.PgError{Code: "28P01", Message: "password authentication failed"}
			}
			var id int64
			err := conn.QueryRow(ctx, "SELECT system_identifier FROM pg_control_system()").Scan(&id)
			return id, err
		},
	})
	pool := dbtest.NewWith(t, db.Options{Guard: guard, MaxConns: 2})
	if _, err := db.NewTripsWith(t.Context(), pool, db.TripsOptions{DeploymentID: "test-deployment", Guard: guard}); err != nil {
		t.Fatal(err)
	}
	// Warm the pool before the checks start failing.
	if err := pool.Ping(t.Context()); err != nil {
		t.Fatal(err)
	}
	ctx, stop := context.WithCancel(t.Context())
	defer stop()
	go guard.Run(ctx, 20*time.Millisecond)
	waitFor := func(what string, cond func() bool) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for !cond() {
			if time.Now().After(deadline) {
				t.Fatalf("timed out waiting until %s", what)
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	failing.Store(true)
	waitFor("unhealthy", func() bool { return errors.Is(guard.Ready(), db.ErrClusterUnchecked) })
	if guard.Err() != nil {
		t.Fatalf("latched: %v", guard.Err())
	}
	if _, err := pool.Exec(t.Context(), "SELECT 1"); err != nil {
		t.Errorf("pooled connection while unhealthy: %v", err)
	}
	failing.Store(false)
	waitFor("healthy again", func() bool { return guard.Ready() == nil })
	if guard.Err() != nil {
		t.Errorf("latched after recovery: %v", guard.Err())
	}
}
