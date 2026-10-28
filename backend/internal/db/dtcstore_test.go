package db_test

import (
	"context"
	"errors"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func dtcRead(at time.Time) store.DtcReportFields {
	return store.DtcReportFields{
		ClientReportID: newID(), Kind: store.DtcKindRead, ReadAt: at, Mil: ptr(true),
		Stored: []string{"P0420"}, Pending: []string{},
	}
}

func TestDtcStoreCreate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	s := db.NewDtcReports(pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 5, 0, 123456789, time.UTC)

	f := dtcRead(t0)
	f.Vin, f.OdometerKm, f.DistanceSinceMilKm, f.ClientTripID = ptr("KMHB341ABCD123456"), ptr(int64(42000)), ptr(int64(12)), ptr(newID())
	r, outcome, err := s.CreateDtcReport(ctx, member, vehicle, f)
	if err != nil || outcome != store.DtcCreated || r.VehicleID != vehicle || r.ID.Version() != 7 || r.CreatedAt.IsZero() {
		t.Fatalf("create: %+v %v %v", r, outcome, err)
	}
	// Absent lists stay absent and empty ones empty; readAt is kept to the
	// microsecond Postgres stores, truncated rather than rounded.
	if !slices.Equal(r.Stored, []string{"P0420"}) || r.Pending == nil || len(r.Pending) != 0 || r.Permanent != nil ||
		!r.ReadAt.Equal(t0.Truncate(time.Microsecond)) || *r.Vin != "KMHB341ABCD123456" || *r.OdometerKm != 42000 ||
		*r.DistanceSinceMilKm != 12 || *r.ClientTripID != *f.ClientTripID || !*r.Mil {
		t.Fatalf("stored: %+v", r)
	}

	// Same clientReportId, different content (even another vehicle): the
	// original comes back unchanged.
	other := insertVehicle(t, q, member, nil)
	dup := dtcRead(t0.Add(time.Hour))
	dup.ClientReportID, dup.Kind, dup.Mil = f.ClientReportID, store.DtcKindCleared, nil
	again, outcome, err := s.CreateDtcReport(ctx, member, other, dup)
	if err != nil || outcome != store.DtcExisting || again.ID != r.ID || again.VehicleID != vehicle || again.Kind != store.DtcKindRead {
		t.Fatalf("duplicate: %+v %v %v", again, outcome, err)
	}
	// Another member's identical clientReportId is independent, and nobody
	// can write to someone else's vehicle.
	stranger := newMember(t, q)
	if _, _, err := s.CreateDtcReport(ctx, stranger, vehicle, f); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("someone else's vehicle: %v", err)
	}
	if _, outcome, err := s.CreateDtcReport(ctx, stranger, insertVehicle(t, q, stranger, nil), f); err != nil || outcome != store.DtcCreated {
		t.Fatalf("other member: %v %v", outcome, err)
	}
	if _, _, err := s.CreateDtcReport(ctx, member, newID(), dtcRead(t0)); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("unknown vehicle: %v", err)
	}
	if _, err := s.ListDtcReports(ctx, stranger, vehicle, nil, 10); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("list someone else's vehicle: %v", err)
	}

	// A cleared report: MIL unknown, no permanent list.
	c := store.DtcReportFields{ClientReportID: newID(), Kind: store.DtcKindCleared, ReadAt: t0.Add(time.Minute), Stored: []string{"P0420"}, Pending: []string{"P0171"}}
	if r, _, err := s.CreateDtcReport(ctx, member, vehicle, c); err != nil || r.Mil != nil || r.Permanent != nil || r.Kind != store.DtcKindCleared {
		t.Fatalf("cleared: %+v %v", r, err)
	}
}

func TestDtcStoreList(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	s := db.NewDtcReports(pool)
	member := newMember(t, q)
	vehicle, other := insertVehicle(t, q, member, nil), insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	var want []uuid.UUID
	// Two reports share a readAt: id breaks the tie (newest id first).
	for _, at := range []time.Time{t0, t0.Add(time.Hour), t0.Add(time.Hour), t0.Add(2 * time.Hour), t0.Add(-time.Hour)} {
		r, _, err := s.CreateDtcReport(ctx, member, vehicle, dtcRead(at))
		if err != nil {
			t.Fatal(err)
		}
		want = append(want, r.ID)
	}
	if _, _, err := s.CreateDtcReport(ctx, member, other, dtcRead(t0.Add(5*time.Hour))); err != nil {
		t.Fatal(err)
	}
	want = []uuid.UUID{want[3], want[2], want[1], want[0], want[4]}

	var got []uuid.UUID
	var after *store.DtcReportPos
	for range 10 {
		page, err := s.ListDtcReports(ctx, member, vehicle, after, 2)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page {
			got = append(got, r.ID)
		}
		if len(page) < 2 {
			break
		}
		last := page[len(page)-1]
		after = &store.DtcReportPos{ReadAt: last.ReadAt, ID: last.ID}
	}
	if !slices.Equal(got, want) {
		t.Fatalf("order:\n got %v\nwant %v", got, want)
	}
}

// A cascade delete and a report upload for the same vehicle never leave a
// report behind: either the delete waits for the upload and deletes it, or
// the upload finds the vehicle gone.
func TestDtcStoreRacesCascadeDelete(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	s, vehicles := db.NewDtcReports(pool), db.NewVehicles(pool)
	member := newMember(t, q)
	for range 30 {
		v, _, err := vehicles.CreateVehicle(ctx, member, ptr(newID()), fields())
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		var createErr, deleteErr error
		wg.Go(func() { _, _, createErr = s.CreateDtcReport(ctx, member, v.ID, dtcRead(time.Now())) })
		wg.Go(func() { _, deleteErr = vehicles.DeleteVehicle(ctx, member, v.ID, ifVersion(1), true) })
		wg.Wait()
		if deleteErr != nil || (createErr != nil && !errors.Is(createErr, store.ErrNotFound)) {
			t.Fatalf("create %v, delete %v", createErr, deleteErr)
		}
		left, err := q.ListDtcReports(ctx, dbgen.ListDtcReportsParams{MemberID: member, VehicleID: v.ID, MaxRows: 10})
		if err != nil || len(left) != 0 {
			t.Fatalf("reports left behind the cascade delete: %d %v (create %v)", len(left), err, createErr)
		}
	}
}

// Concurrent uploads of one clientReportId store one report.
func TestDtcStoreConcurrentCreate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	s := db.NewDtcReports(pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	f := dtcRead(time.Now())
	var wg sync.WaitGroup
	ids := make([]uuid.UUID, 8)
	errs := make([]error, 8)
	for i := range ids {
		wg.Go(func() {
			r, _, err := s.CreateDtcReport(ctx, member, vehicle, f)
			ids[i], errs[i] = r.ID, err
		})
	}
	wg.Wait()
	for i := range ids {
		if errs[i] != nil || ids[i] != ids[0] {
			t.Fatalf("upload %d: %v %v (want %v)", i, ids[i], errs[i], ids[0])
		}
	}
}
