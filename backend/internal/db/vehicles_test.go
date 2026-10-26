package db_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

var errStale = errors.New("stale")

func ifVersion(v int64) func(store.Vehicle) error {
	return func(cur store.Vehicle) error {
		if cur.Version != v {
			return errStale
		}
		return nil
	}
}

func fields() store.VehicleFields {
	year := 2021
	return store.VehicleFields{Make: ptr("Hyundai"), Model: ptr("i20"), FuelType: "gasoline", ModelYear: &year}
}

func TestVehicleStoreCreateDedup(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	s := db.NewVehicles(pool)
	member := newMember(t, q)
	clientID := newID()

	v, outcome, err := s.CreateVehicle(ctx, member, &clientID, fields())
	if err != nil || outcome != store.Created || v.Version != 1 || *v.Make != "Hyundai" || *v.ModelYear != 2021 || *v.ClientVehicleID != clientID {
		t.Fatalf("create: %+v %v %v", v, outcome, err)
	}
	other := fields()
	other.Make = ptr("Kia")
	again, outcome, err := s.CreateVehicle(ctx, member, &clientID, other)
	if err != nil || outcome != store.Existing || again.ID != v.ID || *again.Make != "Hyundai" {
		t.Fatalf("duplicate: %+v %v %v", again, outcome, err)
	}
	// Another member's identical client id is independent.
	if _, outcome, err := s.CreateVehicle(ctx, newMember(t, q), &clientID, fields()); err != nil || outcome != store.Created {
		t.Fatalf("other member: %v %v", outcome, err)
	}
	if _, err := s.DeleteVehicle(ctx, member, v.ID, ifVersion(1), false); err != nil {
		t.Fatal(err)
	}
	if deleted, err := s.ClientVehicleDeleted(ctx, member, clientID); err != nil || !deleted {
		t.Fatalf("ClientVehicleDeleted = %v, %v", deleted, err)
	}
	if _, outcome, err := s.CreateVehicle(ctx, member, &clientID, fields()); err != nil || outcome != store.Deleted {
		t.Fatalf("re-create tombstoned: %v %v", outcome, err)
	}
	if _, err := s.GetVehicle(ctx, member, v.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("get tombstone: %v", err)
	}
}

// Concurrent creates with the same clientVehicleId store exactly one
// vehicle; every other caller gets that vehicle back.
func TestVehicleStoreConcurrentCreate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	s := db.NewVehicles(pool)
	member := newMember(t, dbgen.New(pool))
	for round := range 5 {
		clientID := newID()
		const n = 8
		var wg sync.WaitGroup
		ids := make([]uuid.UUID, n)
		outcomes := make([]store.CreateOutcome, n)
		errs := make([]error, n)
		for i := range n {
			wg.Go(func() {
				v, o, err := s.CreateVehicle(ctx, member, &clientID, fields())
				ids[i], outcomes[i], errs[i] = v.ID, o, err
			})
		}
		wg.Wait()
		created := 0
		for i := range n {
			if errs[i] != nil {
				t.Fatalf("round %d: %v", round, errs[i])
			}
			if outcomes[i] == store.Created {
				created++
			}
			if ids[i] != ids[0] {
				t.Fatalf("round %d: different vehicles %v and %v", round, ids[0], ids[i])
			}
		}
		if created != 1 {
			t.Fatalf("round %d: %d creates, want 1", round, created)
		}
	}
	if page, _ := s.ListVehicles(ctx, member, true, nil, 100); len(page) != 5 {
		t.Fatalf("%d vehicles, want 5", len(page))
	}
}

// A delete racing a PATCH with the same If-Match: exactly one wins. Either
// the patch lands and the delete's If-Match is stale, or the delete lands
// and the patch finds nothing.
func TestVehicleStoreDeleteRacesPatch(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	s := db.NewVehicles(pool)
	member := newMember(t, dbgen.New(pool))
	patchWins, deleteWins := 0, 0
	for round := range 30 {
		var clientID *uuid.UUID
		if round%2 == 0 {
			id := newID()
			clientID = &id // tombstone path; odd rounds take the hard-delete path
		}
		v, _, err := s.CreateVehicle(ctx, member, clientID, fields())
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		var patchErr, deleteErr error
		wg.Go(func() {
			_, patchErr = s.UpdateVehicle(ctx, member, v.ID, ifVersion(1), func(f store.VehicleFields) store.VehicleFields {
				f.Model = ptr("i20 N")
				return f
			})
		})
		wg.Go(func() { _, deleteErr = s.DeleteVehicle(ctx, member, v.ID, ifVersion(1), true) })
		wg.Wait()
		switch {
		case patchErr == nil && errors.Is(deleteErr, errStale):
			patchWins++
			if got, err := s.GetVehicle(ctx, member, v.ID); err != nil || *got.Model != "i20 N" || got.Version != 2 {
				t.Fatalf("round %d: after patch won: %+v %v", round, got, err)
			}
		case deleteErr == nil && errors.Is(patchErr, store.ErrNotFound):
			deleteWins++
			if _, err := s.GetVehicle(ctx, member, v.ID); !errors.Is(err, store.ErrNotFound) {
				t.Fatalf("round %d: deleted vehicle still readable: %v", round, err)
			}
		default:
			t.Fatalf("round %d: patch err %v, delete err %v", round, patchErr, deleteErr)
		}
	}
	t.Logf("patch won %d, delete won %d", patchWins, deleteWins)
}

func TestVehicleStoreUpdate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	s := db.NewVehicles(pool)
	member := newMember(t, dbgen.New(pool))
	v, _, err := s.CreateVehicle(ctx, member, nil, fields())
	if err != nil {
		t.Fatal(err)
	}
	same, err := s.UpdateVehicle(ctx, member, v.ID, ifVersion(1), func(f store.VehicleFields) store.VehicleFields { return f })
	if err != nil || same.Version != 1 || !same.UpdatedAt.Equal(v.UpdatedAt) {
		t.Fatalf("no-op patch bumped the version: %+v %v", same, err)
	}
	time.Sleep(2 * time.Millisecond)
	up, err := s.UpdateVehicle(ctx, member, v.ID, ifVersion(1), func(f store.VehicleFields) store.VehicleFields {
		f.ModelYear, f.Archived = nil, true
		return f
	})
	if err != nil || up.Version != 2 || up.ModelYear != nil || !up.Archived || !up.UpdatedAt.After(v.UpdatedAt) {
		t.Fatalf("patch: %+v %v", up, err)
	}
	if _, err := s.UpdateVehicle(ctx, member, v.ID, ifVersion(1), func(f store.VehicleFields) store.VehicleFields { return f }); !errors.Is(err, errStale) {
		t.Fatalf("stale If-Match: %v", err)
	}
	if _, err := s.UpdateVehicle(ctx, newMember(t, dbgen.New(pool)), v.ID, ifVersion(2), func(f store.VehicleFields) store.VehicleFields { return f }); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("other member: %v", err)
	}
	if live, _ := s.ListVehicles(ctx, member, false, nil, 10); len(live) != 0 {
		t.Error("archived vehicle listed without includeArchived")
	}
	// Concurrent patches under If-Match: * (check accepts any version) all
	// apply, each to the latest version.
	var wg sync.WaitGroup
	for range 6 {
		wg.Go(func() {
			if _, err := s.UpdateVehicle(ctx, member, v.ID, func(store.Vehicle) error { return nil }, func(f store.VehicleFields) store.VehicleFields {
				odo := int64(0)
				if f.OdometerKm != nil {
					odo = *f.OdometerKm
				}
				odo++
				f.OdometerKm = &odo
				return f
			}); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	if got, _ := s.GetVehicle(ctx, member, v.ID); got.OdometerKm == nil || *got.OdometerKm != 6 || got.Version != 8 {
		t.Fatalf("lost update: %+v", got)
	}
}

func TestVehicleStoreCascadeDelete(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	s := db.NewVehicles(pool)
	member := newMember(t, q)
	keep, _, _ := s.CreateVehicle(ctx, member, nil, fields())
	gone, _, _ := s.CreateVehicle(ctx, member, nil, fields())
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	for _, v := range []uuid.UUID{keep.ID, gone.ID, gone.ID} {
		if _, err := q.UpsertTrip(ctx, tripParams(member, v, newID(), "completed", t0)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := q.InsertDtcReport(ctx, dbgen.InsertDtcReportParams{
		ID: newID(), MemberID: member, VehicleID: gone.ID, ClientReportID: newID(), Kind: "read", ReadAt: t0, Mil: true,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.DeleteVehicle(ctx, member, gone.ID, ifVersion(2), true); !errors.Is(err, errStale) {
		t.Fatalf("stale: %v", err)
	}
	res, err := s.DeleteVehicle(ctx, member, gone.ID, ifVersion(1), true)
	if err != nil || res.TripsDeleted != 2 {
		t.Fatalf("cascade: %+v %v", res, err)
	}
	if left, _ := q.ListTrips(ctx, dbgen.ListTripsParams{MemberID: member, MaxRows: 10}); len(left) != 1 || *left[0].VehicleID != keep.ID {
		t.Fatalf("trips left: %+v", left)
	}
	if reports, _ := q.ListDtcReports(ctx, dbgen.ListDtcReportsParams{MemberID: member, VehicleID: gone.ID, MaxRows: 10}); len(reports) != 0 {
		t.Fatalf("dtc reports left: %d", len(reports))
	}
	// Without cascade the trips stay (with a dangling vehicle id).
	if _, err := s.DeleteVehicle(ctx, member, keep.ID, ifVersion(1), false); err != nil {
		t.Fatal(err)
	}
	if left, _ := q.ListTrips(ctx, dbgen.ListTripsParams{MemberID: member, MaxRows: 10}); len(left) != 1 {
		t.Fatalf("non-cascade delete removed trips: %+v", left)
	}
	if _, err := s.DeleteVehicle(ctx, member, keep.ID, ifVersion(1), false); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
}

func TestIdempotencyStore(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	s := db.NewIdempotency(pool)
	sc := store.IdempotencyScope{MemberID: newMember(t, dbgen.New(pool)), Method: "POST", Route: "/v1/vehicles", Key: newID()}
	tok := newID()
	if ok, err := s.Claim(ctx, sc, []byte("fp"), tok, time.Now().Add(time.Minute), time.Now().Add(time.Hour)); !ok || err != nil {
		t.Fatalf("claim: %v %v", ok, err)
	}
	if ok, err := s.Claim(ctx, sc, []byte("fp"), newID(), time.Now().Add(time.Minute), time.Now().Add(time.Hour)); ok || err != nil {
		t.Fatalf("second claim: %v %v", ok, err)
	}
	if rec, err := s.Get(ctx, sc); err != nil || rec.Completed || string(rec.Fingerprint) != "fp" {
		t.Fatalf("get in progress: %+v %v", rec, err)
	}
	if ok, err := s.Complete(ctx, sc, newID(), 201, nil, nil); ok || err != nil {
		t.Fatalf("complete with the wrong token: %v %v", ok, err)
	}
	if ok, err := s.Complete(ctx, sc, tok, 201, map[string]string{"ETag": `"1"`}, []byte(`{"id":"x"}`)); !ok || err != nil {
		t.Fatalf("complete: %v %v", ok, err)
	}
	rec, err := s.Get(ctx, sc)
	if err != nil || !rec.Completed || rec.Status != 201 || rec.Headers["ETag"] != `"1"` || string(rec.Body) != `{"id":"x"}` {
		t.Fatalf("get completed: %+v %v", rec, err)
	}
	other := sc
	other.Key = newID()
	if _, err := s.Get(ctx, other); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("get missing: %v", err)
	}
}

// 50 concurrent read-modify-write patches (If-Match: *) all apply in turn:
// none fails and none is lost.
func TestVehicleStoreUpdateStress(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	s := db.NewVehicles(pool)
	member := newMember(t, dbgen.New(pool))
	v, _, err := s.CreateVehicle(ctx, member, nil, fields())
	if err != nil {
		t.Fatal(err)
	}
	const n = 50
	var wg sync.WaitGroup
	for range n {
		wg.Go(func() {
			if _, err := s.UpdateVehicle(ctx, member, v.ID, func(store.Vehicle) error { return nil }, func(f store.VehicleFields) store.VehicleFields {
				odo := int64(1)
				if f.OdometerKm != nil {
					odo = *f.OdometerKm + 1
				}
				f.OdometerKm = &odo
				return f
			}); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	got, err := s.GetVehicle(ctx, member, v.ID)
	if err != nil || got.OdometerKm == nil || *got.OdometerKm != n || got.Version != n+1 {
		t.Fatalf("lost update: odometer %v version %d %v", got.OdometerKm, got.Version, err)
	}
}
