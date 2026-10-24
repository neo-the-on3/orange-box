package db_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

func ptr[T any](v T) *T { return &v }

func newID() uuid.UUID { return uuid.Must(uuid.NewV7()) }

func newMember(t *testing.T, q *dbgen.Queries) uuid.UUID {
	t.Helper()
	id := uuid.Must(uuid.NewV7())
	email := fmt.Sprintf("m-%s@test.orangebox.invalid", id)
	if _, err := q.InsertMember(context.Background(), dbgen.InsertMemberParams{ID: id, Email: email}); err != nil {
		t.Fatal(err)
	}
	return id
}

func insertVehicle(t *testing.T, q *dbgen.Queries, member uuid.UUID, clientID *uuid.UUID) uuid.UUID {
	t.Helper()
	v, err := q.InsertVehicle(context.Background(), dbgen.InsertVehicleParams{
		ID: newID(), MemberID: member, ClientVehicleID: clientID,
		Make: "Hyundai", Model: "i20", FuelType: "gasoline", ModelYear: ptr[int16](2021),
	})
	if err != nil {
		t.Fatalf("insert vehicle: %v", err)
	}
	return v.ID
}

func tripParams(member, vehicle, clientTrip uuid.UUID, state string, snapshot time.Time) dbgen.UpsertTripParams {
	return dbgen.UpsertTripParams{
		ID: newID(), MemberID: member, ClientTripID: clientTrip, VehicleID: vehicle,
		State: state, StartedAt: snapshot.Add(-time.Hour), SnapshotAt: snapshot,
		DistanceKm: ptr(12.5), Simulated: false,
		SpeedBands: json.RawMessage(`[{"fromKmh":0,"toKmh":10,"seconds":30}]`),
		Dtc:        json.RawMessage(`{"stored":["P0420"]}`),
	}
}

func isNoRows(err error) bool { return errors.Is(err, pgx.ErrNoRows) }

func TestVehicleTombstones(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	clientID := newID()
	id := insertVehicle(t, q, member, &clientID)

	// Same clientVehicleId again: no row (the handler answers 200 with it).
	if _, err := q.InsertVehicle(ctx, dbgen.InsertVehicleParams{ID: newID(), MemberID: member, ClientVehicleID: &clientID, Make: "x", Model: "y", FuelType: "diesel"}); !isNoRows(err) {
		t.Fatalf("duplicate clientVehicleId: err = %v, want no rows", err)
	}
	// Another member's identical client id is unaffected.
	insertVehicle(t, q, newMember(t, q), &clientID)
	// Vehicles without a client id never clash.
	insertVehicle(t, q, member, nil)
	insertVehicle(t, q, member, nil)

	if ok, err := q.VehicleExists(ctx, dbgen.VehicleExistsParams{MemberID: member, ID: id}); err != nil || !ok {
		t.Fatalf("VehicleExists = %v, %v", ok, err)
	}
	if _, err := q.TombstoneVehicle(ctx, dbgen.TombstoneVehicleParams{MemberID: member, ID: id, ExpectedVersion: 7}); !isNoRows(err) {
		t.Fatalf("stale If-Match: err = %v", err)
	}
	ts, err := q.TombstoneVehicle(ctx, dbgen.TombstoneVehicleParams{MemberID: member, ID: id, ExpectedVersion: 1})
	if err != nil || ts.DeletedAt == nil {
		t.Fatalf("TombstoneVehicle: %+v, %v", ts, err)
	}
	if ok, _ := q.VehicleExists(ctx, dbgen.VehicleExistsParams{MemberID: member, ID: id}); ok {
		t.Error("tombstoned vehicle still exists")
	}
	row, err := q.GetVehicleByClientID(ctx, dbgen.GetVehicleByClientIDParams{MemberID: member, ClientVehicleID: clientID})
	if err != nil || row.DeletedAt == nil || row.Make != nil || row.Vin != nil || row.ModelYear != nil {
		t.Fatalf("tombstone keeps data or is missing: %+v, %v", row, err)
	}
	// Re-creating it still conflicts (handler answers 410).
	if _, err := q.InsertVehicle(ctx, dbgen.InsertVehicleParams{ID: newID(), MemberID: member, ClientVehicleID: &clientID, Make: "x", Model: "y", FuelType: "diesel"}); !isNoRows(err) {
		t.Fatalf("re-create tombstoned: err = %v", err)
	}
	page, err := q.ListVehicles(ctx, dbgen.ListVehiclesParams{MemberID: member, IncludeArchived: true, MaxRows: 10})
	if err != nil || len(page) != 2 {
		t.Fatalf("ListVehicles = %d rows, %v; want the 2 live ones", len(page), err)
	}

	// After the retention period the tombstone is purged and the id is new again.
	if _, err := q.PurgeVehicleTombstones(ctx, dbgen.PurgeVehicleTombstonesParams{DeletedBefore: time.Now().Add(time.Hour), MaxRows: 100}); err != nil {
		t.Fatal(err)
	}
	insertVehicle(t, q, member, &clientID)
}

func TestVehicleUpdateAndArchive(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	id := insertVehicle(t, q, member, nil)

	v, err := q.UpdateVehicle(ctx, dbgen.UpdateVehicleParams{
		MemberID: member, ID: id, ExpectedVersion: 1,
		Make: "Hyundai", Model: "i20 N", FuelType: "gasoline", Archived: true, Vin: ptr("KMHB341ABCD123456"),
	})
	if err != nil || v.Version != 2 || *v.Model != "i20 N" || v.ModelYear != nil || !v.Archived {
		t.Fatalf("UpdateVehicle: %+v, %v", v, err)
	}
	if _, err := q.UpdateVehicle(ctx, dbgen.UpdateVehicleParams{MemberID: member, ID: id, ExpectedVersion: 1, Make: "a", Model: "b", FuelType: "c"}); !isNoRows(err) {
		t.Errorf("stale version: err = %v", err)
	}
	if page, _ := q.ListVehicles(ctx, dbgen.ListVehiclesParams{MemberID: member, MaxRows: 10}); len(page) != 0 {
		t.Errorf("archived vehicle listed without includeArchived")
	}
	// Another member can't see or change it.
	other := newMember(t, q)
	if _, err := q.GetVehicle(ctx, dbgen.GetVehicleParams{MemberID: other, ID: id}); !isNoRows(err) {
		t.Errorf("other member reads the vehicle: %v", err)
	}
	if n, err := q.DeleteVehicle(ctx, dbgen.DeleteVehicleParams{MemberID: other, ID: id, ExpectedVersion: 2}); err != nil || n != 0 {
		t.Errorf("other member deletes the vehicle: %d, %v", n, err)
	}
	if n, err := q.DeleteVehicle(ctx, dbgen.DeleteVehicleParams{MemberID: member, ID: id, ExpectedVersion: 2}); err != nil || n != 1 {
		t.Errorf("DeleteVehicle = %d, %v", n, err)
	}
}

func TestTripUpsertRules(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	clientTrip := newID()
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	created, err := q.UpsertTrip(ctx, tripParams(member, vehicle, clientTrip, "in_progress", t0))
	if err != nil || !created.Inserted {
		t.Fatalf("first snapshot: %+v, %v", created, err)
	}
	// An older or equal snapshot is ignored.
	for _, at := range []time.Time{t0.Add(-time.Minute), t0} {
		if _, err := q.UpsertTrip(ctx, tripParams(member, vehicle, clientTrip, "in_progress", at)); !isNoRows(err) {
			t.Fatalf("snapshot at %v: err = %v, want no rows", at, err)
		}
	}
	// A newer one updates the same trip.
	p := tripParams(member, vehicle, clientTrip, "completed", t0.Add(time.Minute))
	p.DistanceKm = ptr(20.0)
	updated, err := q.UpsertTrip(ctx, p)
	if err != nil || updated.Inserted || updated.ID != created.ID {
		t.Fatalf("newer snapshot: %+v, %v (created %v)", updated, err, created.ID)
	}
	// A completed trip is immutable.
	if _, err := q.UpsertTrip(ctx, tripParams(member, vehicle, clientTrip, "in_progress", t0.Add(time.Hour))); !isNoRows(err) {
		t.Fatalf("snapshot after completion: err = %v, want no rows", err)
	}
	trip, err := q.GetTrip(ctx, dbgen.GetTripParams{MemberID: member, ID: created.ID})
	if err != nil || *trip.State != "completed" || *trip.DistanceKm != 20 || string(trip.Dtc) != `{"stored": ["P0420"]}` {
		t.Fatalf("stored trip: %+v, %v", trip, err)
	}
	if trip.App != nil || trip.EndedAt != nil || trip.Simulated == nil || *trip.Simulated {
		t.Errorf("absent fields must be stored as NULL: app=%q ended=%v simulated=%v", trip.App, trip.EndedAt, trip.Simulated)
	}

	// Delete: data erased, tombstone kept, and a late snapshot never recreates it.
	ts, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: created.ID})
	if err != nil || ts.ClientTripID != clientTrip {
		t.Fatalf("TombstoneTrip: %+v, %v", ts, err)
	}
	if _, err := q.GetTrip(ctx, dbgen.GetTripParams{MemberID: member, ID: created.ID}); !isNoRows(err) {
		t.Errorf("deleted trip still readable: %v", err)
	}
	if _, err := q.UpsertTrip(ctx, tripParams(member, vehicle, clientTrip, "in_progress", t0.Add(2*time.Hour))); !isNoRows(err) {
		t.Fatalf("late snapshot after delete: err = %v, want no rows", err)
	}
	tomb, err := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: clientTrip})
	if err != nil || tomb.DeletedAt == nil || tomb.StartedAt != nil || tomb.DistanceKm != nil || tomb.Dtc != nil || tomb.SpeedBands != nil {
		t.Fatalf("tombstone: %+v, %v", tomb, err)
	}
	if tomb.VehicleID == nil || *tomb.VehicleID != vehicle {
		t.Errorf("tombstone lost its vehicle id")
	}
	// Deleting twice finds nothing.
	if _, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: created.ID}); !isNoRows(err) {
		t.Errorf("second delete: %v", err)
	}
	// Another member's identical clientTripId is unaffected.
	other := newMember(t, q)
	if r, err := q.UpsertTrip(ctx, tripParams(other, insertVehicle(t, q, other, nil), clientTrip, "in_progress", t0)); err != nil || !r.Inserted {
		t.Fatalf("other member, same clientTripId: %+v, %v", r, err)
	}
}

func TestTripListHistory(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	v1, v2 := insertVehicle(t, q, member, nil), insertVehicle(t, q, member, nil)
	base := time.Date(2026, 10, 1, 8, 0, 0, 0, time.UTC)
	for i := range 5 {
		v := v1
		if i%2 == 1 {
			v = v2
		}
		if _, err := q.UpsertTrip(ctx, tripParams(member, v, newID(), "completed", base.Add(time.Duration(i)*24*time.Hour))); err != nil {
			t.Fatal(err)
		}
	}
	// Page through newest first, 2 at a time.
	var got []time.Time
	var cursorAt *time.Time
	var cursorID uuid.UUID
	for {
		page, err := q.ListTrips(ctx, dbgen.ListTripsParams{MemberID: member, CursorStartedAt: cursorAt, CursorID: cursorID, MaxRows: 2})
		if err != nil {
			t.Fatal(err)
		}
		for _, tr := range page {
			got = append(got, *tr.StartedAt)
		}
		if len(page) < 2 {
			break
		}
		last := page[len(page)-1]
		cursorAt, cursorID = last.StartedAt, last.ID
	}
	if len(got) != 5 {
		t.Fatalf("walked %d trips, want 5", len(got))
	}
	for i := 1; i < len(got); i++ {
		if !got[i].Before(got[i-1]) {
			t.Fatalf("not newest first: %v", got)
		}
	}
	byVehicle, err := q.ListTripsByVehicle(ctx, dbgen.ListTripsByVehicleParams{MemberID: member, VehicleID: v2, MaxRows: 10})
	if err != nil || len(byVehicle) != 2 {
		t.Fatalf("by vehicle: %d, %v", len(byVehicle), err)
	}
	from, to := base.Add(24*time.Hour-time.Hour), base.Add(3*24*time.Hour-time.Hour)
	ranged, err := q.ListTrips(ctx, dbgen.ListTripsParams{MemberID: member, StartedFrom: &from, StartedBefore: &to, MaxRows: 10})
	if err != nil || len(ranged) != 2 {
		t.Fatalf("from/to: %d, %v", len(ranged), err)
	}
}

// TestSyncWatermark checks the property sync tokens rely on: a write that
// has not committed when the watermark is taken is still returned by a
// walk from that watermark, whenever it commits.
func TestSyncWatermark(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)

	walk := func(watermark uint64) []uuid.UUID {
		t.Helper()
		var ids []uuid.UUID
		var cx uint64
		var cid uuid.UUID
		for {
			page, err := q.ListTripChanges(ctx, dbgen.ListTripChangesParams{MemberID: member, Watermark: watermark, CursorXid: cx, CursorID: cid, MaxRows: 2})
			if err != nil {
				t.Fatal(err)
			}
			for _, tr := range page {
				ids = append(ids, tr.ID)
			}
			if len(page) < 2 {
				return ids
			}
			cx, cid = page[len(page)-1].WriterXid, page[len(page)-1].ID
		}
	}

	a, err := q.UpsertTrip(ctx, tripParams(member, vehicle, newID(), "in_progress", t0))
	if err != nil {
		t.Fatal(err)
	}
	w1, err := q.CurrentSyncWatermark(ctx)
	if err != nil {
		t.Fatal(err)
	}
	// No assertion that walk(w1) is empty: xids are cluster-wide, so any
	// transaction open elsewhere (other tests) holds the watermark back and
	// A may come back again. That is the documented at-least-once delivery.

	// B is written in a transaction that is still open when w2 is taken.
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	b, err := q.WithTx(tx).UpsertTrip(ctx, tripParams(member, vehicle, newID(), "in_progress", t0))
	if err != nil {
		t.Fatal(err)
	}
	w2, err := q.CurrentSyncWatermark(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if ids := walk(w2); containsID(ids, b.ID) {
		t.Fatalf("uncommitted trip visible: %v", ids)
	}
	if w2 > writerXidIn(t, tx, b.ID) {
		t.Fatalf("watermark %d is above the open transaction's write", w2)
	}
	// Meanwhile A changes in an autocommit transaction.
	if _, err := q.UpsertTrip(ctx, tripParams(member, vehicle, mustClientID(t, q, member, a.ID), "in_progress", t0.Add(time.Minute))); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	got := walk(w2)
	if len(got) != 2 || !containsID(got, a.ID) || !containsID(got, b.ID) {
		t.Fatalf("walk from w2 = %v, want A (updated) and B (late commit)", got)
	}
	// Walks from w1 see both too (inclusive, at least once).
	if got := walk(w1); len(got) != 2 {
		t.Fatalf("walk from w1 = %v", got)
	}

	// Deletions walk sees the tombstone; the trips walk no longer returns it.
	w3, _ := q.CurrentSyncWatermark(ctx)
	if _, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: b.ID}); err != nil {
		t.Fatal(err)
	}
	dels, err := q.ListTripDeletions(ctx, dbgen.ListTripDeletionsParams{MemberID: member, Watermark: w3, MaxRows: 10})
	if err != nil || len(dels) != 1 || dels[0].ID != b.ID || dels[0].DeletedAt.IsZero() {
		t.Fatalf("deletions = %+v, %v", dels, err)
	}
	if got := walk(w1); len(got) != 1 || got[0] != a.ID {
		t.Fatalf("trips walk after delete = %v, want only A", got)
	}
	// Another member's walk is empty.
	if rows, _ := q.ListTripChanges(ctx, dbgen.ListTripChangesParams{MemberID: newMember(t, q), MaxRows: 10}); len(rows) != 0 {
		t.Errorf("other member sees %d trips", len(rows))
	}
	if _, err := q.SystemIdentifier(ctx); err != nil {
		t.Logf("system identifier not readable here (a configured deployment id is the fallback): %v", err)
	}
}

func writerXidIn(t *testing.T, tx pgx.Tx, id uuid.UUID) uint64 {
	t.Helper()
	var x uint64
	if err := tx.QueryRow(context.Background(), `SELECT writer_xid FROM trips WHERE id = $1`, id).Scan(&x); err != nil {
		t.Fatal(err)
	}
	return x
}

func mustClientID(t *testing.T, q *dbgen.Queries, member, id uuid.UUID) uuid.UUID {
	t.Helper()
	tr, err := q.GetTrip(context.Background(), dbgen.GetTripParams{MemberID: member, ID: id})
	if err != nil {
		t.Fatal(err)
	}
	return tr.ClientTripID
}

func containsID(ids []uuid.UUID, id uuid.UUID) bool {
	for _, x := range ids {
		if x == id {
			return true
		}
	}
	return false
}

func TestPurgeTripTombstonesRecordsBoundary(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 6, 1, 8, 0, 0, 0, time.UTC)

	var xids []uint64
	clientIDs := []uuid.UUID{newID(), newID(), newID()}
	for i, cid := range clientIDs {
		r, err := q.UpsertTrip(ctx, tripParams(member, vehicle, cid, "completed", t0))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := q.TombstoneTrip(ctx, dbgen.TombstoneTripParams{MemberID: member, ID: r.ID}); err != nil {
			t.Fatal(err)
		}
		// Age the first two tombstones past the retention period.
		if i < 2 {
			if _, err := pool.Exec(ctx, `UPDATE trips SET deleted_at = $2 WHERE id = $1`, r.ID, t0.Add(time.Duration(i)*time.Hour)); err != nil {
				t.Fatal(err)
			}
		}
		tr, _ := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: cid})
		xids = append(xids, tr.WriterXid)
	}
	if _, err := q.GetTripPurgeBoundary(ctx, member); !isNoRows(err) {
		t.Fatalf("boundary before purge: %v", err)
	}
	moved, err := q.PurgeTripTombstones(ctx, dbgen.PurgeTripTombstonesParams{DeletedBefore: t0.Add(24 * time.Hour), MaxRows: 100})
	if err != nil || len(moved) != 1 || moved[0] != member {
		t.Fatalf("purge: %v, %v", moved, err)
	}
	b, err := q.GetTripPurgeBoundary(ctx, member)
	if err != nil || !b.MaxDeletedAt.Equal(t0.Add(time.Hour)) || b.MaxWriterXid != max(xids[0], xids[1]) {
		t.Fatalf("boundary = %+v, %v; want deleted_at %v, xid %d", b, err, t0.Add(time.Hour), max(xids[0], xids[1]))
	}
	// The recent tombstone stays, and those purged can be uploaded as new trips.
	if tr, err := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: clientIDs[2]}); err != nil || tr.DeletedAt == nil {
		t.Fatalf("recent tombstone: %+v, %v", tr, err)
	}
	if r, err := q.UpsertTrip(ctx, tripParams(member, vehicle, clientIDs[0], "in_progress", t0)); err != nil || !r.Inserted {
		t.Fatalf("re-upload after purge: %+v, %v", r, err)
	}
	// A later purge of an older-looking tombstone never lowers the boundary.
	if _, err := pool.Exec(ctx, `UPDATE trips SET deleted_at = $2 WHERE client_trip_id = $1`, clientIDs[2], t0.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	aged, _ := q.GetTripByClientID(ctx, dbgen.GetTripByClientIDParams{MemberID: member, ClientTripID: clientIDs[2]})
	xids[2] = aged.WriterXid // the ageing UPDATE rewrote it
	if _, err := q.PurgeTripTombstones(ctx, dbgen.PurgeTripTombstonesParams{DeletedBefore: t0.Add(24 * time.Hour), MaxRows: 100}); err != nil {
		t.Fatal(err)
	}
	b2, _ := q.GetTripPurgeBoundary(ctx, member)
	if !b2.MaxDeletedAt.Equal(b.MaxDeletedAt) || b2.MaxWriterXid != xids[2] {
		t.Fatalf("boundary after second purge = %+v, want deleted_at kept at %v and xid %d", b2, b.MaxDeletedAt, xids[2])
	}
}

func TestEventDeduplication(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	trip := newID()
	insert := func(events string) int64 {
		t.Helper()
		n, err := q.InsertTripEvents(ctx, dbgen.InsertTripEventsParams{MemberID: member, ClientTripID: trip, Events: json.RawMessage(events)})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	id1, id2 := newID(), newID()
	// No id, active absent (true), dtc absent; an in-batch duplicate; one with an id.
	n := insert(`[
		{"t":"2026-10-23T14:05:00.123Z","type":"mil_on","dtc":"P0420"},
		{"t":"2026-10-23T14:05:00.123Z","type":"harsh_brake","value":0.6},
		{"t":"2026-10-23T14:05:00.123Z","type":"harsh_brake","active":true,"dtc":null},
		{"client_event_id":"` + id1.String() + `","t":"2026-10-23T14:06:00Z","type":"overspeed"}]`)
	if n != 3 {
		t.Fatalf("first batch stored %d events, want 3", n)
	}
	// Retry: the event first sent without an id now has one (fallback key
	// matches); the overspeed one is re-sent with the same id but a changed
	// value (id matches); active=false is a different event.
	n = insert(`[
		{"client_event_id":"` + id2.String() + `","t":"2026-10-23T14:05:00.123Z","type":"harsh_brake"},
		{"client_event_id":"` + id1.String() + `","t":"2026-10-23T14:06:00.5Z","type":"overspeed"},
		{"t":"2026-10-23T14:05:00.123Z","type":"mil_on","active":false,"dtc":"P0420"}]`)
	if n != 1 {
		t.Fatalf("retry batch stored %d events, want 1 (the active=false one)", n)
	}
	rows, err := q.ListTripEvents(ctx, dbgen.ListTripEventsParams{MemberID: member, ClientTripID: trip, MaxRows: 2})
	if err != nil || len(rows) != 2 {
		t.Fatalf("page 1: %d, %v", len(rows), err)
	}
	last := rows[len(rows)-1]
	rest, err := q.ListTripEvents(ctx, dbgen.ListTripEventsParams{MemberID: member, ClientTripID: trip, CursorT: &last.T, CursorID: last.ID, MaxRows: 10})
	if err != nil || len(rest) != 2 {
		t.Fatalf("page 2: %d, %v", len(rest), err)
	}
	all := slices.Concat(rows, rest)
	for i := 1; i < len(all); i++ {
		if all[i].T.Before(all[i-1].T) {
			t.Fatal("events not in time order")
		}
	}
	if !all[0].T.Equal(time.Date(2026, 10, 23, 14, 5, 0, 123e6, time.UTC)) || !all[0].Active {
		t.Errorf("first event = %+v", all[0])
	}
}

func TestTelemetryBatchesAndDelete(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	trip, otherTrip, orphanTrip := newID(), newID(), newID()

	batch := func(clientTrip uuid.UUID, key string) dbgen.InsertTelemetryBatchParams {
		return dbgen.InsertTelemetryBatchParams{
			ID: newID(), MemberID: member, ClientBatchID: newID(), ClientTripID: clientTrip, VehicleID: vehicle,
			ObjectKey: ptr(key), AcceptedSamples: 10, AcceptedEvents: 1, BodyBytes: 2048,
		}
	}
	p := batch(trip, "k1")
	if _, err := q.InsertTelemetryBatch(ctx, p); err != nil {
		t.Fatal(err)
	}
	dup := p
	dup.ID, dup.AcceptedSamples = newID(), 99
	if _, err := q.InsertTelemetryBatch(ctx, dup); !isNoRows(err) {
		t.Fatalf("duplicate clientBatchId: %v", err)
	}
	orig, err := q.GetTelemetryBatchByClientID(ctx, dbgen.GetTelemetryBatchByClientIDParams{MemberID: member, ClientBatchID: p.ClientBatchID})
	if err != nil || orig.AcceptedSamples != 10 {
		t.Fatalf("original counts: %+v, %v", orig, err)
	}
	for _, b := range []dbgen.InsertTelemetryBatchParams{batch(trip, "k2"), batch(otherTrip, "k3"), batch(orphanTrip, "k4")} {
		if _, err := q.InsertTelemetryBatch(ctx, b); err != nil {
			t.Fatal(err)
		}
	}
	for _, ct := range []uuid.UUID{trip, otherTrip, orphanTrip} {
		if _, err := q.InsertTripEvents(ctx, dbgen.InsertTripEventsParams{MemberID: member, ClientTripID: ct, Events: json.RawMessage(`[{"t":"2026-10-23T14:05:00Z","type":"idling"}]`)}); err != nil {
			t.Fatal(err)
		}
	}
	if bs, _ := q.ListTripBatches(ctx, dbgen.ListTripBatchesParams{MemberID: member, ClientTripID: trip}); len(bs) != 2 {
		t.Fatalf("trip has %d batches, want 2", len(bs))
	}

	// Deleting one trip's telemetry leaves the others.
	keys, err := q.DeleteTelemetry(ctx, dbgen.DeleteTelemetryParams{MemberID: member, ClientTripIds: []uuid.UUID{trip}})
	if err != nil || len(keys) != 2 {
		t.Fatalf("delete trip telemetry: %v, %v", keys, err)
	}
	if ev, _ := q.ListTripEvents(ctx, dbgen.ListTripEventsParams{MemberID: member, ClientTripID: trip, MaxRows: 10}); len(ev) != 0 {
		t.Error("events survived the trip delete")
	}
	if ev, _ := q.ListTripEvents(ctx, dbgen.ListTripEventsParams{MemberID: member, ClientTripID: otherTrip, MaxRows: 10}); len(ev) != 1 {
		t.Error("another trip's events were deleted")
	}
	// A vehicle cascade also removes telemetry whose trip summary never arrived.
	keys, err = q.DeleteTelemetry(ctx, dbgen.DeleteTelemetryParams{MemberID: member, ClientTripIds: []uuid.UUID{}, VehicleID: &vehicle})
	if err != nil || len(keys) != 2 {
		t.Fatalf("delete vehicle telemetry: %v, %v", keys, err)
	}
	if ev, _ := q.ListTripEvents(ctx, dbgen.ListTripEventsParams{MemberID: member, ClientTripID: orphanTrip, MaxRows: 10}); len(ev) != 0 {
		t.Error("orphan trip events survived the vehicle cascade")
	}
}

func TestVehicleCascadeTombstonesTrips(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	v1, v2 := insertVehicle(t, q, member, nil), insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	for _, v := range []uuid.UUID{v1, v1, v2} {
		if _, err := q.UpsertTrip(ctx, tripParams(member, v, newID(), "completed", t0)); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := q.TombstoneVehicleTrips(ctx, dbgen.TombstoneVehicleTripsParams{MemberID: member, VehicleID: v1})
	if err != nil || len(rows) != 2 {
		t.Fatalf("cascade: %d, %v", len(rows), err)
	}
	if left, _ := q.ListTrips(ctx, dbgen.ListTripsParams{MemberID: member, MaxRows: 10}); len(left) != 1 || *left[0].VehicleID != v2 {
		t.Fatalf("trips left: %+v", left)
	}
}

func TestDtcReports(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	report := func(at time.Time) dbgen.InsertDtcReportParams {
		return dbgen.InsertDtcReportParams{
			ID: newID(), MemberID: member, VehicleID: vehicle, ClientReportID: newID(),
			Kind: "read", ReadAt: at, Mil: true, Stored: []string{"P0420"}, Pending: []string{},
		}
	}
	first := report(t0)
	r, err := q.InsertDtcReport(ctx, first)
	if err != nil || len(r.Stored) != 1 || r.Pending == nil || len(r.Pending) != 0 || r.Permanent != nil {
		t.Fatalf("insert: %+v, %v (absent arrays must stay NULL, empty ones empty)", r, err)
	}
	dup := first
	dup.ID = newID()
	if _, err := q.InsertDtcReport(ctx, dup); !isNoRows(err) {
		t.Fatalf("duplicate clientReportId: %v", err)
	}
	bad := report(t0)
	bad.Kind = "erased"
	if _, err := q.InsertDtcReport(ctx, bad); err == nil {
		t.Error("unknown kind stored")
	}
	for i := 1; i <= 3; i++ {
		if _, err := q.InsertDtcReport(ctx, report(t0.Add(time.Duration(i)*time.Hour))); err != nil {
			t.Fatal(err)
		}
	}
	page, err := q.ListDtcReports(ctx, dbgen.ListDtcReportsParams{MemberID: member, VehicleID: vehicle, MaxRows: 3})
	if err != nil || len(page) != 3 || !page[0].ReadAt.Equal(t0.Add(3*time.Hour)) {
		t.Fatalf("page 1: %+v, %v", page, err)
	}
	last := page[2]
	rest, err := q.ListDtcReports(ctx, dbgen.ListDtcReportsParams{MemberID: member, VehicleID: vehicle, CursorReadAt: &last.ReadAt, CursorID: last.ID, MaxRows: 3})
	if err != nil || len(rest) != 1 || rest[0].ID != r.ID {
		t.Fatalf("page 2: %+v, %v", rest, err)
	}
	if n, err := q.DeleteVehicleDtcReports(ctx, dbgen.DeleteVehicleDtcReportsParams{MemberID: member, VehicleID: vehicle}); err != nil || n != 4 {
		t.Fatalf("cascade delete: %d, %v", n, err)
	}
}

func TestIdempotencyKeys(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	member := newMember(t, q)
	key := newID()
	fp := []byte("fingerprint-1")
	scope := dbgen.GetIdempotencyKeyParams{MemberID: member, Method: "POST", Route: "/v1/trips/batch", Key: key}
	claim := dbgen.ClaimIdempotencyKeyParams{
		MemberID: member, Method: "POST", Route: "/v1/trips/batch", Key: key, Fingerprint: fp, ClaimToken: newID(),
		LockedUntil: time.Now().Add(30 * time.Second), ExpiresAt: time.Now().Add(24 * time.Hour),
	}
	if _, err := q.ClaimIdempotencyKey(ctx, claim); err != nil {
		t.Fatal(err)
	}
	if _, err := q.ClaimIdempotencyKey(ctx, claim); !isNoRows(err) {
		t.Fatalf("second claim: %v", err)
	}
	// Same key, another route or member: independent.
	other := claim
	other.Route = "/v1/telemetry/batches"
	if _, err := q.ClaimIdempotencyKey(ctx, other); err != nil {
		t.Fatalf("other route: %v", err)
	}
	other = claim
	other.MemberID = newMember(t, q)
	if _, err := q.ClaimIdempotencyKey(ctx, other); err != nil {
		t.Fatalf("other member: %v", err)
	}
	// The lease is live, so it can't be reclaimed yet.
	reclaim := dbgen.ReclaimIdempotencyKeyParams{MemberID: member, Method: "POST", Route: "/v1/trips/batch", Key: key, Fingerprint: fp, ClaimToken: newID(), LockedUntil: time.Now().Add(time.Minute)}
	if _, err := q.ReclaimIdempotencyKey(ctx, reclaim); !isNoRows(err) {
		t.Fatalf("reclaim live lease: %v", err)
	}
	if _, err := pool.Exec(ctx, `UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE key = $1`, key); err != nil {
		t.Fatal(err)
	}
	wrong := reclaim
	wrong.Fingerprint = []byte("other body")
	if _, err := q.ReclaimIdempotencyKey(ctx, wrong); !isNoRows(err) {
		t.Fatalf("reclaim with another fingerprint: %v", err)
	}
	if _, err := q.ReclaimIdempotencyKey(ctx, reclaim); err != nil {
		t.Fatalf("reclaim expired lease: %v", err)
	}
	n, err := q.CompleteIdempotencyKey(ctx, dbgen.CompleteIdempotencyKeyParams{
		MemberID: member, Method: "POST", Route: "/v1/trips/batch", Key: key, ClaimToken: reclaim.ClaimToken,
		ResponseStatus: 200, ResponseBody: []byte(`{"results":[]}`), ResponseHeaders: json.RawMessage(`{"Content-Type":"application/json"}`),
	})
	if err != nil || n != 1 {
		t.Fatalf("complete: %d, %v", n, err)
	}
	got, err := q.GetIdempotencyKey(ctx, scope)
	if err != nil || got.State != "completed" || *got.ResponseStatus != 200 || string(got.ResponseBody) != `{"results":[]}` || got.LockedUntil != nil {
		t.Fatalf("stored key: %+v, %v", got, err)
	}
	release := dbgen.ReleaseIdempotencyKeyParams{MemberID: member, Method: "POST", Route: "/v1/trips/batch", Key: key, ClaimToken: reclaim.ClaimToken}
	if n, _ := q.ReleaseIdempotencyKey(ctx, release); n != 0 {
		t.Error("released a completed key")
	}
	if _, err := pool.Exec(ctx, `UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE key = $1`, key); err != nil {
		t.Fatal(err)
	}
	if n, err := q.PurgeExpiredIdempotencyKeys(ctx, 100); err != nil || n != 3 {
		t.Fatalf("purge: %d, %v", n, err)
	}
}

// TestIdempotencyStaleOwner is #175: after a retry takes over a key whose
// lease ran out, the original (stale) request can neither complete nor
// release it, and the new owner still can.
func TestIdempotencyStaleOwner(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	member := newMember(t, q)
	key := newID()
	fp := []byte("fp")
	stale, fresh := newID(), newID()
	if _, err := q.ClaimIdempotencyKey(ctx, dbgen.ClaimIdempotencyKeyParams{
		MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key, Fingerprint: fp, ClaimToken: stale,
		LockedUntil: time.Now().Add(-time.Second), ExpiresAt: time.Now().Add(24 * time.Hour),
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := q.ReclaimIdempotencyKey(ctx, dbgen.ReclaimIdempotencyKeyParams{
		MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key, Fingerprint: fp, ClaimToken: fresh,
		LockedUntil: time.Now().Add(time.Minute),
	}); err != nil {
		t.Fatalf("takeover: %v", err)
	}
	complete := func(token uuid.UUID) int64 {
		t.Helper()
		n, err := q.CompleteIdempotencyKey(ctx, dbgen.CompleteIdempotencyKeyParams{
			MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key, ClaimToken: token,
			ResponseStatus: 201, ResponseBody: []byte(`{}`),
		})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	release := func(token uuid.UUID) int64 {
		t.Helper()
		n, err := q.ReleaseIdempotencyKey(ctx, dbgen.ReleaseIdempotencyKeyParams{
			MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key, ClaimToken: token,
		})
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	if n := release(stale); n != 0 {
		t.Fatalf("stale owner released the new claim (%d rows)", n)
	}
	if n := complete(stale); n != 0 {
		t.Fatalf("stale owner completed the new claim (%d rows)", n)
	}
	got, err := q.GetIdempotencyKey(ctx, dbgen.GetIdempotencyKeyParams{MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key})
	if err != nil || got.State != "in_progress" || got.ClaimToken == nil || *got.ClaimToken != fresh {
		t.Fatalf("claim after stale attempts: %+v, %v", got, err)
	}
	if n := complete(fresh); n != 1 {
		t.Fatalf("new owner's complete: %d rows", n)
	}
}

// TestIdempotencyExpiredKeyReclaimable is #175: a key past expires_at that
// the purge job hasn't removed yet counts as absent, whatever its state or
// fingerprint.
func TestIdempotencyExpiredKeyReclaimable(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	member := newMember(t, q)
	key := newID()
	first := dbgen.ClaimIdempotencyKeyParams{
		MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key, Fingerprint: []byte("old"), ClaimToken: newID(),
		LockedUntil: time.Now().Add(time.Minute), ExpiresAt: time.Now().Add(24 * time.Hour),
	}
	if _, err := q.ClaimIdempotencyKey(ctx, first); err != nil {
		t.Fatal(err)
	}
	if n, err := q.CompleteIdempotencyKey(ctx, dbgen.CompleteIdempotencyKeyParams{
		MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key, ClaimToken: first.ClaimToken,
		ResponseStatus: 201, ResponseBody: []byte(`{"old":true}`), ResponseHeaders: json.RawMessage(`{}`),
	}); err != nil || n != 1 {
		t.Fatalf("complete: %d, %v", n, err)
	}
	if _, err := pool.Exec(ctx, `UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE key = $1`, key); err != nil {
		t.Fatal(err)
	}
	again := first
	again.Fingerprint, again.ClaimToken = []byte("new"), newID()
	if _, err := q.ClaimIdempotencyKey(ctx, again); err != nil {
		t.Fatalf("claim of an expired key: %v", err)
	}
	got, err := q.GetIdempotencyKey(ctx, dbgen.GetIdempotencyKeyParams{MemberID: member, Method: "POST", Route: "/v1/vehicles", Key: key})
	if err != nil || got.State != "in_progress" || string(got.Fingerprint) != "new" || *got.ClaimToken != again.ClaimToken ||
		got.ResponseStatus != nil || got.ResponseBody != nil || got.ResponseHeaders != nil || got.CompletedAt != nil ||
		!got.ExpiresAt.After(time.Now()) {
		t.Fatalf("replaced key: %+v, %v", got, err)
	}
	// A live key is still not replaced.
	third := again
	third.ClaimToken = newID()
	if _, err := q.ClaimIdempotencyKey(ctx, third); !isNoRows(err) {
		t.Fatalf("claim of a live key: %v", err)
	}
}

func TestDrivingDays(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	q := dbgen.New(dbtest.New(t))
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	// 20:00 UTC on Oct 22 is 01:30 on Oct 23 in Asia/Kolkata.
	for _, start := range []time.Time{
		time.Date(2026, 10, 22, 20, 0, 0, 0, time.UTC),
		time.Date(2026, 10, 23, 5, 0, 0, 0, time.UTC),
		time.Date(2026, 10, 24, 5, 0, 0, 0, time.UTC),
	} {
		p := tripParams(member, vehicle, newID(), "completed", start.Add(time.Hour))
		if _, err := q.UpsertTrip(ctx, p); err != nil {
			t.Fatal(err)
		}
	}
	days, err := q.ListDrivingDays(ctx, dbgen.ListDrivingDaysParams{
		Tz: "Asia/Kolkata", MemberID: member,
		StartedFrom:   time.Date(2026, 10, 20, 0, 0, 0, 0, time.UTC),
		StartedBefore: time.Date(2026, 10, 31, 0, 0, 0, 0, time.UTC),
	})
	if err != nil || len(days) != 2 {
		t.Fatalf("days = %+v, %v", days, err)
	}
	if d := days[0].Day.Time; d.Day() != 23 || days[0].TripCount != 2 || days[1].TripCount != 1 {
		t.Errorf("days = %+v", days)
	}
}
