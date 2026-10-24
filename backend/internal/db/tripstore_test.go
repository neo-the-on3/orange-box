package db_test

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func upload(clientID, vehicle uuid.UUID, state string, snapshot time.Time) store.TripUpload {
	return store.TripUpload{ClientTripID: clientID, TripFields: store.TripFields{
		VehicleID: vehicle, State: state, StartedAt: snapshot.Add(-time.Hour), SnapshotAt: snapshot,
		DistanceKm: ptr(12.5),
	}}
}

func outcomes(rs []store.UpsertResult) []store.UpsertOutcome {
	out := make([]store.UpsertOutcome, len(rs))
	for i, r := range rs {
		out[i] = r.Outcome
	}
	return out
}

func TestTripStoreUpsertOutcomes(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member, other := newMember(t, q), newMember(t, q)
	vehicle, otherVehicle := insertVehicle(t, q, member, nil), insertVehicle(t, q, other, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	a, b, c := newID(), newID(), newID()

	res, err := trips.UpsertTrips(ctx, member, []store.TripUpload{
		upload(a, vehicle, "in_progress", t0),
		upload(b, otherVehicle, "in_progress", t0), // another member's vehicle
		upload(a, vehicle, "in_progress", t0),      // same snapshot again, in the batch
		upload(c, vehicle, "completed", t0),
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []store.UpsertOutcome{store.TripCreated, store.TripVehicleNotFound, store.TripUnchanged, store.TripCreated}
	if got := outcomes(res); fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("outcomes = %v, want %v", got, want)
	}
	if res[0].ID != res[2].ID || res[1].ID != uuid.Nil {
		t.Fatalf("ids = %+v", res)
	}
	steps := []struct {
		item store.TripUpload
		want store.UpsertOutcome
	}{
		{upload(a, vehicle, "in_progress", t0.Add(-time.Minute)), store.TripUnchanged}, // older snapshot
		{upload(a, vehicle, "in_progress", t0.Add(time.Minute)), store.TripUpdated},
		{upload(a, vehicle, "completed", t0.Add(2*time.Minute)), store.TripUpdated},
		{upload(a, vehicle, "in_progress", t0.Add(3*time.Minute)), store.TripUnchanged}, // completed is immutable
		{upload(c, vehicle, "completed", t0.Add(time.Hour)), store.TripUnchanged},
	}
	for i, s := range steps {
		r, err := trips.UpsertTrips(ctx, member, []store.TripUpload{s.item})
		if err != nil || r[0].Outcome != s.want || r[0].ID != res[0].ID && s.item.ClientTripID == a {
			t.Fatalf("step %d: %+v, %v; want %v", i, r, err, s.want)
		}
	}
	got, err := trips.GetTrip(ctx, member, store.TripRef{ClientID: &a})
	if err != nil || got.State != "completed" || !got.SnapshotAt.Equal(t0.Add(2*time.Minute)) {
		t.Fatalf("trip a = %+v, %v", got, err)
	}

	// Delete, then a late snapshot is trip_deleted, also when its vehicle
	// is gone; another member's same clientTripId is unaffected.
	if _, err := trips.DeleteTrip(ctx, member, store.TripRef{ID: &res[0].ID}); err != nil {
		t.Fatal(err)
	}
	r, err := trips.UpsertTrips(ctx, member, []store.TripUpload{
		upload(a, vehicle, "in_progress", t0.Add(time.Hour)),
		upload(a, uuid.New(), "in_progress", t0.Add(time.Hour)),
	})
	if err != nil || r[0].Outcome != store.TripDeleted || r[1].Outcome != store.TripDeleted {
		t.Fatalf("late snapshot after delete: %+v, %v", r, err)
	}
	r, err = trips.UpsertTrips(ctx, other, []store.TripUpload{upload(a, otherVehicle, "in_progress", t0)})
	if err != nil || r[0].Outcome != store.TripCreated {
		t.Fatalf("other member's same clientTripId: %+v, %v", r, err)
	}
}

// TestTripStoreMemberScope: nothing of another member can be read,
// listed or deleted.
func TestTripStoreMemberScope(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member, stranger := newMember(t, q), newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	clientID := newID()
	res, err := trips.UpsertTrips(ctx, member, []store.TripUpload{upload(clientID, vehicle, "completed", t0)})
	if err != nil {
		t.Fatal(err)
	}
	id := res[0].ID
	for name, ref := range map[string]store.TripRef{"id": {ID: &id}, "client id": {ClientID: &clientID}} {
		if _, err := trips.GetTrip(ctx, stranger, ref); !errors.Is(err, store.ErrNotFound) {
			t.Errorf("stranger GetTrip by %s: %v", name, err)
		}
		if _, err := trips.DeleteTrip(ctx, stranger, ref); !errors.Is(err, store.ErrNotFound) {
			t.Errorf("stranger DeleteTrip by %s: %v", name, err)
		}
	}
	h, err := trips.ListTripHistory(ctx, stranger, store.TripHistoryQuery{Limit: 10})
	if err != nil || len(h.Trips) != 0 {
		t.Fatalf("stranger history: %+v, %v", h, err)
	}
	if c, err := trips.ListTripChanges(ctx, stranger, store.TripChangesQuery{Since: origin(t, q), Limit: 10}); err != nil || len(c.Trips) != 0 {
		t.Fatalf("stranger changes: %+v, %v", c, err)
	}
	if _, err := trips.DeleteTrip(ctx, member, store.TripRef{ID: &id}); err != nil {
		t.Fatal(err)
	}
	if d, err := trips.ListTripDeletions(ctx, stranger, store.TripDeletionsQuery{Since: origin(t, q), Limit: 10}); err != nil || len(d.Deletions) != 0 {
		t.Fatalf("stranger deletions: %+v, %v", d, err)
	}
	if days, err := trips.DrivingDays(ctx, stranger, t0.Add(-48*time.Hour), t0.Add(48*time.Hour), "UTC", nil); err != nil || len(days) != 0 {
		t.Fatalf("stranger days: %+v, %v", days, err)
	}
	// Deleting twice is not found; the trip is gone from every live read.
	if _, err := trips.DeleteTrip(ctx, member, store.TripRef{ID: &id}); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
	if h, _ := trips.ListTripHistory(ctx, member, store.TripHistoryQuery{Limit: 10}); len(h.Trips) != 0 {
		t.Fatalf("history after delete: %+v", h.Trips)
	}
	if days, _ := trips.DrivingDays(ctx, member, t0.Add(-48*time.Hour), t0.Add(48*time.Hour), "UTC", nil); len(days) != 0 {
		t.Fatalf("days after delete: %+v", days)
	}
}

// TestTripStoreUpsertRacesDelete: a snapshot racing a trip delete, or a
// new trip racing a cascade delete of its vehicle, never leaves a live
// trip behind once the delete has returned.
func TestTripStoreUpsertRacesDelete(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	vehicles := db.NewVehicles(pool)
	member := newMember(t, q)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	for round := range 40 {
		vehicle := insertVehicle(t, q, member, nil)
		clientID := newID()
		res, err := trips.UpsertTrips(ctx, member, []store.TripUpload{upload(clientID, vehicle, "in_progress", t0)})
		if err != nil {
			t.Fatal(err)
		}
		id := res[0].ID
		newTrip := newID()
		var wg sync.WaitGroup
		var upErr, delErr, cascErr error
		var up []store.UpsertResult
		wg.Add(3)
		go func() {
			defer wg.Done()
			up, upErr = trips.UpsertTrips(ctx, member, []store.TripUpload{
				upload(clientID, vehicle, "in_progress", t0.Add(time.Duration(round+1)*time.Second)),
				upload(newTrip, vehicle, "in_progress", t0),
			})
		}()
		go func() {
			defer wg.Done()
			_, delErr = trips.DeleteTrip(ctx, member, store.TripRef{ID: &id})
		}()
		go func() {
			defer wg.Done()
			_, cascErr = vehicles.DeleteVehicle(ctx, member, vehicle, func(store.Vehicle) error { return nil }, true)
		}()
		wg.Wait()
		if upErr != nil || cascErr != nil || (delErr != nil && !errors.Is(delErr, store.ErrNotFound)) {
			t.Fatalf("round %d: upsert %v, delete %v, cascade %v", round, upErr, delErr, cascErr)
		}
		for _, ref := range []store.TripRef{{ClientID: &clientID}, {ClientID: &newTrip}} {
			if tr, err := trips.GetTrip(ctx, member, ref); !errors.Is(err, store.ErrNotFound) {
				t.Fatalf("round %d: a trip survived the deletes: %+v, %v (upsert %+v)", round, tr, err, up)
			}
		}
		if o := up[0].Outcome; o != store.TripUpdated && o != store.TripDeleted && o != store.TripVehicleNotFound {
			t.Fatalf("round %d: racing snapshot got %v", round, o)
		}
		if o := up[1].Outcome; o != store.TripCreated && o != store.TripVehicleNotFound {
			t.Fatalf("round %d: racing new trip got %v", round, o)
		}
	}
}

// walkChanges walks a full sync walk from since with page size limit,
// checking that positions strictly increase, and returns the trip ids and
// the walk's new token.
func walkChanges(t *testing.T, trips *db.Trips, member uuid.UUID, since store.SyncPoint, limit int) ([]uuid.UUID, store.SyncPoint) {
	t.Helper()
	var ids []uuid.UUID
	var snap *store.SyncPoint
	var after *store.ChangePos
	for {
		p, err := trips.ListTripChanges(context.Background(), member, store.TripChangesQuery{Since: since, Snapshot: after == nil, After: after, Limit: limit})
		if err != nil {
			t.Fatal(err)
		}
		if after == nil {
			snap = p.Snapshot
		}
		for _, tr := range p.Trips {
			if after != nil && (tr.Change.Xid < after.Xid || tr.Change.Xid == after.Xid && string(tr.Change.ID[:]) <= string(after.ID[:])) {
				t.Fatalf("position %+v not after %+v", tr.Change, *after)
			}
			c := tr.Change
			after = &c
			ids = append(ids, tr.ID)
		}
		if len(p.Trips) < limit {
			return ids, *snap
		}
	}
}

// TestTripStorePaginationAcrossConcurrentWrites: while trips are created
// and updated during a paged walk (history or sync), every trip live at
// the end is in the walk or in the next sync walk from its token.
func TestTripStorePaginationAcrossConcurrentWrites(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 1, 8, 0, 0, 0, time.UTC)
	var clientIDs []uuid.UUID
	for i := range 30 {
		cid := newID()
		clientIDs = append(clientIDs, cid)
		if _, err := trips.UpsertTrips(ctx, member, []store.TripUpload{upload(cid, vehicle, "in_progress", t0.Add(time.Duration(i)*time.Hour))}); err != nil {
			t.Fatal(err)
		}
	}
	writes := func(stop <-chan struct{}, done chan<- struct{}) {
		defer close(done)
		for i := 0; ; i++ {
			select {
			case <-stop:
				return
			default:
			}
			items := []store.TripUpload{upload(newID(), vehicle, "in_progress", t0.Add(time.Duration(i)*time.Minute))}
			cid := clientIDs[rand.IntN(len(clientIDs))]
			items = append(items, upload(cid, vehicle, "in_progress", time.Now()))
			if _, err := trips.UpsertTrips(ctx, member, items); err != nil {
				t.Error(err)
				return
			}
		}
	}
	allLive := func() map[uuid.UUID]bool {
		rows, err := pool.Query(ctx, `SELECT id FROM trips WHERE member_id = $1 AND deleted_at IS NULL`, member)
		if err != nil {
			t.Fatal(err)
		}
		defer rows.Close()
		out := map[uuid.UUID]bool{}
		for rows.Next() {
			var id uuid.UUID
			if err := rows.Scan(&id); err != nil {
				t.Fatal(err)
			}
			out[id] = true
		}
		return out
	}

	t.Run("sync", func(t *testing.T) {
		stop, done := make(chan struct{}), make(chan struct{})
		go writes(stop, done)
		seen, next := walkChanges(t, trips, member, origin(t, q), 4)
		close(stop)
		<-done
		more, _ := walkChanges(t, trips, member, next, 7)
		got := map[uuid.UUID]bool{}
		for _, id := range append(seen, more...) {
			got[id] = true
		}
		for id := range allLive() {
			if !got[id] {
				t.Fatalf("trip %s missed by the walk and the next walk", id)
			}
		}
	})
	t.Run("history", func(t *testing.T) {
		stop, done := make(chan struct{}), make(chan struct{})
		go writes(stop, done)
		got := map[uuid.UUID]bool{}
		var after *store.HistoryPos
		var snap *store.SyncPoint
		for {
			p, err := trips.ListTripHistory(ctx, member, store.TripHistoryQuery{Snapshot: after == nil, After: after, Limit: 5})
			if err != nil {
				t.Fatal(err)
			}
			if after == nil {
				snap = p.Snapshot
			}
			for _, tr := range p.Trips {
				if after != nil && (tr.StartedAt.After(after.StartedAt) || tr.StartedAt.Equal(after.StartedAt) && string(tr.ID[:]) >= string(after.ID[:])) {
					t.Fatalf("history position not after the cursor")
				}
				after = &store.HistoryPos{StartedAt: tr.StartedAt, ID: tr.ID}
				got[tr.ID] = true
			}
			if len(p.Trips) < 5 {
				break
			}
		}
		close(stop)
		<-done
		more, _ := walkChanges(t, trips, member, *snap, 9) //nolint:contextcheck // t-scoped helper
		for _, id := range more {
			got[id] = true
		}
		for id := range allLive() {
			if !got[id] {
				t.Fatalf("trip %s missed by the history walk and the sync walk from its token", id)
			}
		}
	})
}

// TestTripStoreConcurrentStress runs batches, deletes and walks for one
// member at once (run it under -race) and then checks that a sync from the
// start returns exactly the live trips and the deletions walk every
// tombstone.
func TestTripStoreConcurrentStress(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 1, 8, 0, 0, 0, time.UTC)
	clientIDs := make([]uuid.UUID, 25)
	for i := range clientIDs {
		clientIDs[i] = newID()
	}
	var wg sync.WaitGroup
	for w := range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r := rand.New(rand.NewPCG(uint64(w), 1))
			for i := range 30 {
				switch r.IntN(4) {
				case 0, 1:
					var items []store.TripUpload
					for range 1 + r.IntN(5) {
						state := "in_progress"
						if r.IntN(4) == 0 {
							state = "completed"
						}
						items = append(items, upload(clientIDs[r.IntN(len(clientIDs))], vehicle, state, t0.Add(time.Duration(w*100+i)*time.Second)))
					}
					if _, err := trips.UpsertTrips(ctx, member, items); err != nil {
						t.Error(err)
						return
					}
				case 2:
					cid := clientIDs[r.IntN(len(clientIDs))]
					if _, err := trips.DeleteTrip(ctx, member, store.TripRef{ClientID: &cid}); err != nil && !errors.Is(err, store.ErrNotFound) {
						t.Error(err)
						return
					}
				default:
					if _, err := trips.ListTripChanges(ctx, member, store.TripChangesQuery{Since: origin(t, q), Snapshot: true, Limit: 3}); err != nil { //nolint:contextcheck // t-scoped helper
						t.Error(err)
						return
					}
				}
			}
		}()
	}
	wg.Wait()

	live, tombstones := map[uuid.UUID]bool{}, map[uuid.UUID]bool{}
	for _, cid := range clientIDs {
		tr, err := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: cid})
		switch {
		case err != nil:
		case tr.DeletedAt != nil:
			tombstones[tr.ID] = true
		default:
			live[tr.ID] = true
		}
	}
	seen, _ := walkChanges(t, trips, member, origin(t, q), 4)
	got := map[uuid.UUID]bool{}
	for _, id := range seen {
		if got[id] {
			t.Fatalf("trip %s twice in one walk", id)
		}
		got[id] = true
	}
	if len(got) != len(live) {
		t.Fatalf("walk has %d trips, %d are live", len(got), len(live))
	}
	for id := range live {
		if !got[id] {
			t.Fatalf("live trip %s missing from the walk", id)
		}
	}
	d, err := trips.ListTripDeletions(ctx, member, store.TripDeletionsQuery{Since: origin(t, q), Limit: 100})
	if err != nil || len(d.Deletions) != len(tombstones) {
		t.Fatalf("deletions = %d, %v; want %d", len(d.Deletions), err, len(tombstones))
	}
	for _, del := range d.Deletions {
		if !tombstones[del.ID] || del.DeletedAt.IsZero() {
			t.Fatalf("unexpected deletion %+v", del)
		}
	}
}
