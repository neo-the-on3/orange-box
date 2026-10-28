package db_test

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func telemetryBatch(clientTrip, vehicle uuid.UUID, first time.Time, n int, events ...store.TelemetryEvent) store.TelemetryBatch {
	id := newID()
	b := store.TelemetryBatch{
		ID: id, ClientBatchID: newID(), ClientTripID: clientTrip, VehicleID: vehicle,
		Counts:    store.BatchCounts{AcceptedSamples: n, AcceptedEvents: len(events), DroppedSamples: 1},
		BodyBytes: 1234, Events: events,
	}
	if n > 0 {
		key := "members/x/trips/" + clientTrip.String() + "/" + id.String() + ".json.gz"
		last := first.Add(time.Duration(n-1) * time.Second)
		b.ObjectKey, b.FirstSampleT, b.LastSampleT = &key, &first, &last
	}
	return b
}

func ev(t time.Time, typ string, clientID *uuid.UUID) store.TelemetryEvent {
	return store.TelemetryEvent{ClientEventID: clientID, T: t, Type: typ, Active: true}
}

func TestTelemetryStoreOutcomes(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	tel := db.NewTelemetry(pool)
	trips := newTrips(t, pool)
	member, other := newMember(t, q), newMember(t, q)
	vehicle, otherVehicle := insertVehicle(t, q, member, nil), insertVehicle(t, q, other, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	trip := newID()

	b := telemetryBatch(trip, vehicle, t0, 10, ev(t0, "harsh_brake", nil))
	if o, _, err := tel.CheckBatch(ctx, member, b.ClientBatchID, trip, vehicle, nil); err != nil || o != store.BatchAccepted {
		t.Fatalf("check new: %v %v", o, err)
	}
	o, c, err := tel.StoreBatch(ctx, member, b)
	if err != nil || o != store.BatchAccepted || c != b.Counts {
		t.Fatalf("store: %v %+v %v", o, c, err)
	}
	// Same clientBatchId again (other content): duplicate with the original counts.
	dup := telemetryBatch(trip, vehicle, t0, 3)
	dup.ClientBatchID = b.ClientBatchID
	if o, c, err := tel.CheckBatch(ctx, member, dup.ClientBatchID, trip, vehicle, nil); err != nil || o != store.BatchDuplicate || c != b.Counts {
		t.Fatalf("check dup: %v %+v %v", o, c, err)
	}
	if o, c, err := tel.StoreBatch(ctx, member, dup); err != nil || o != store.BatchDuplicate || c != b.Counts {
		t.Fatalf("store dup: %v %+v %v", o, c, err)
	}
	// Another member may use the same clientBatchId; ids are per member.
	theirs := telemetryBatch(trip, otherVehicle, t0, 1)
	theirs.ClientBatchID = b.ClientBatchID
	if o, _, err := tel.StoreBatch(ctx, other, theirs); err != nil || o != store.BatchAccepted {
		t.Fatalf("other member: %v %v", o, err)
	}
	// Someone else's vehicle, or an unknown one: not found.
	for _, v := range []uuid.UUID{otherVehicle, newID()} {
		nb := telemetryBatch(trip, v, t0, 1)
		if o, _, err := tel.CheckBatch(ctx, member, nb.ClientBatchID, trip, v, nil); err != nil || o != store.BatchVehicleNotFound {
			t.Errorf("check vehicle %v: %v %v", v, o, err)
		}
		if o, _, err := tel.StoreBatch(ctx, member, nb); err != nil || o != store.BatchVehicleNotFound {
			t.Errorf("store vehicle %v: %v %v", v, o, err)
		}
	}
	// Only the member's own data comes back.
	if bs, err := tel.ListSampleBatches(ctx, member, trip, nil, nil, 10); err != nil || len(bs) != 1 || bs[0].ID != b.ID {
		t.Fatalf("batches = %+v, %v", bs, err)
	}
	if es, err := tel.ListEvents(ctx, member, trip, nil, 10); err != nil || len(es) != 1 || es[0].Type != "harsh_brake" || !es[0].T.Equal(t0) {
		t.Fatalf("events = %+v, %v", es, err)
	}

	// Delete the trip: telemetry goes, the keys come back, and later
	// batches are trip_deleted (a tombstone beats an unknown vehicle).
	if _, err := trips.UpsertTrips(ctx, member, []store.TripUpload{upload(trip, vehicle, "completed", t0)}); err != nil {
		t.Fatal(err)
	}
	keys, err := trips.DeleteTrip(ctx, member, store.TripRef{ClientID: &trip})
	if err != nil || len(keys) != 1 || keys[0] != *b.ObjectKey {
		t.Fatalf("delete trip: %v %v", keys, err)
	}
	if bs, _ := tel.ListSampleBatches(ctx, member, trip, nil, nil, 10); len(bs) != 0 {
		t.Errorf("batches after delete = %+v", bs)
	}
	if es, _ := tel.ListEvents(ctx, member, trip, nil, 10); len(es) != 0 {
		t.Errorf("events after delete = %+v", es)
	}
	for _, v := range []uuid.UUID{vehicle, newID()} {
		late := telemetryBatch(trip, v, t0, 2)
		if o, _, err := tel.CheckBatch(ctx, member, late.ClientBatchID, trip, v, nil); err != nil || o != store.BatchTripDeleted {
			t.Errorf("check late: %v %v", o, err)
		}
		if o, c, err := tel.StoreBatch(ctx, member, late); err != nil || o != store.BatchTripDeleted || c != (store.BatchCounts{}) {
			t.Errorf("store late: %v %+v %v", o, c, err)
		}
	}
	// The other member's telemetry for the same clientTripId is untouched.
	if bs, _ := tel.ListSampleBatches(ctx, other, trip, nil, nil, 10); len(bs) != 1 {
		t.Errorf("other member's batches = %+v", bs)
	}
}

func TestTelemetryEventsDedupAndPaging(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	tel := db.NewTelemetry(pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	trip := newID()
	id1, id2 := newID(), newID()
	code := "P0420"
	withDtc := ev(t0.Add(3*time.Second), "dtc_on", nil)
	withDtc.Dtc = &code
	cleared := ev(t0.Add(3*time.Second), "dtc_on", nil)
	cleared.Dtc, cleared.Active = &code, false

	first := telemetryBatch(trip, vehicle, t0, 0,
		ev(t0, "harsh_brake", nil), ev(t0.Add(time.Second), "idling", &id1), withDtc, cleared)
	if o, _, err := tel.StoreBatch(ctx, member, first); err != nil || o != store.BatchAccepted {
		t.Fatal(o, err)
	}
	// A later batch resends: the first event now with an id (fallback key
	// matches), id1 at another time (id matches), and one new event.
	second := telemetryBatch(trip, vehicle, t0, 0,
		ev(t0, "harsh_brake", &id2), ev(t0.Add(9*time.Second), "idling", &id1), ev(t0.Add(2*time.Second), "overspeed", nil))
	if o, c, err := tel.StoreBatch(ctx, member, second); err != nil || o != store.BatchAccepted || c.AcceptedEvents != 3 {
		t.Fatal(o, c, err)
	}
	var got []string
	var after *store.EventPos
	for range 10 {
		page, err := tel.ListEvents(ctx, member, trip, after, 2)
		if err != nil {
			t.Fatal(err)
		}
		for _, e := range page {
			s := e.Type
			if !e.Active {
				s += "-off"
			}
			got = append(got, s)
		}
		if len(page) < 2 {
			break
		}
		last := page[len(page)-1]
		after = &store.EventPos{T: last.T, ID: last.ID}
	}
	want := "[harsh_brake idling overspeed dtc_on dtc_on-off]"
	if s := fmtSlice(got); s != want {
		t.Errorf("events = %s, want %s", s, want)
	}
}

func fmtSlice(s []string) string {
	out := "["
	for i, x := range s {
		if i > 0 {
			out += " "
		}
		out += x
	}
	return out + "]"
}

func TestTelemetrySampleBatchPaging(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	tel := db.NewTelemetry(pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	trip := newID()
	// Uploaded out of order: 20-29 s, 0-9 s, 10-19 s, and one without samples.
	var ids []uuid.UUID
	for _, off := range []int{20, 0, 10} {
		b := telemetryBatch(trip, vehicle, t0.Add(time.Duration(off)*time.Second), 10)
		if _, _, err := tel.StoreBatch(ctx, member, b); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, b.ID)
	}
	if _, _, err := tel.StoreBatch(ctx, member, telemetryBatch(trip, vehicle, t0, 0)); err != nil {
		t.Fatal(err)
	}
	all, err := tel.ListSampleBatches(ctx, member, trip, nil, nil, 10)
	if err != nil || len(all) != 3 || all[0].ID != ids[1] || all[1].ID != ids[2] || all[2].ID != ids[0] {
		t.Fatalf("all = %+v, %v", all, err)
	}
	if !all[0].FirstSampleT.Equal(t0) || !all[0].LastSampleT.Equal(t0.Add(9*time.Second)) {
		t.Errorf("range = %+v", all[0])
	}
	// Only batches with samples after 15 s.
	cut := t0.Add(15 * time.Second)
	later, err := tel.ListSampleBatches(ctx, member, trip, &cut, nil, 10)
	if err != nil || len(later) != 2 || later[0].ID != ids[2] {
		t.Fatalf("later = %+v, %v", later, err)
	}
	// Keyset continuation.
	next, err := tel.ListSampleBatches(ctx, member, trip, nil, &all[0], 1)
	if err != nil || len(next) != 1 || next[0].ID != ids[2] {
		t.Fatalf("next = %+v, %v", next, err)
	}
}

// TestTelemetryRacesTripDelete: a trip created and deleted while a
// telemetry upload for it runs must never keep the upload's batch behind
// its tombstone (the per-trip advisory lock), however they interleave.
func TestTelemetryRacesTripDelete(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	tel := db.NewTelemetry(pool)
	trips := newTrips(t, pool)
	member := newMember(t, q)
	vehicle := insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	for range 40 {
		trip := newID()
		var wg sync.WaitGroup
		var outcome store.BatchOutcome
		wg.Go(func() {
			var err error
			if outcome, _, err = tel.StoreBatch(ctx, member, telemetryBatch(trip, vehicle, t0, 5, ev(t0, "idling", nil))); err != nil {
				t.Error(err)
			}
		})
		wg.Go(func() {
			if _, err := trips.UpsertTrips(ctx, member, []store.TripUpload{upload(trip, vehicle, "completed", t0)}); err != nil {
				t.Error(err)
				return
			}
			if _, err := trips.DeleteTrip(ctx, member, store.TripRef{ClientID: &trip}); err != nil {
				t.Error(err)
			}
		})
		wg.Wait()
		bs, err := tel.ListSampleBatches(ctx, member, trip, nil, nil, 10)
		if err != nil {
			t.Fatal(err)
		}
		es, err := tel.ListEvents(ctx, member, trip, nil, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(bs) != 0 || len(es) != 0 {
			t.Fatalf("outcome %v: batches %d and events %d survived the trip's tombstone", outcome, len(bs), len(es))
		}
	}
}

// TestTelemetryCascadeDelete: a vehicle cascade delete removes its trips'
// telemetry and batches uploaded for the vehicle without a trip summary,
// and returns their object keys.
func TestTelemetryCascadeDelete(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	tel := db.NewTelemetry(pool)
	trips := newTrips(t, pool)
	vehicles := db.NewVehicles(pool)
	member := newMember(t, q)
	vehicle, keep := insertVehicle(t, q, member, nil), insertVehicle(t, q, member, nil)
	t0 := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	withTrip, orphan, kept := newID(), newID(), newID()
	if _, err := trips.UpsertTrips(ctx, member, []store.TripUpload{upload(withTrip, vehicle, "completed", t0)}); err != nil {
		t.Fatal(err)
	}
	want := map[string]bool{}
	for _, b := range []store.TelemetryBatch{
		telemetryBatch(withTrip, vehicle, t0, 3), telemetryBatch(orphan, vehicle, t0, 3), telemetryBatch(kept, keep, t0, 3),
	} {
		if _, _, err := tel.StoreBatch(ctx, member, b); err != nil {
			t.Fatal(err)
		}
		if b.VehicleID == vehicle {
			want[*b.ObjectKey] = true
		}
	}
	res, err := vehicles.DeleteVehicle(ctx, member, vehicle, func(store.Vehicle) error { return nil }, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.ObjectKeys) != 2 || !want[res.ObjectKeys[0]] || !want[res.ObjectKeys[1]] {
		t.Errorf("keys = %v, want %v", res.ObjectKeys, want)
	}
	if bs, _ := tel.ListSampleBatches(ctx, member, kept, nil, nil, 10); len(bs) != 1 {
		t.Errorf("other vehicle's batches = %v", bs)
	}
	// Later uploads for the deleted vehicle's trip are trip_deleted.
	if o, _, err := tel.StoreBatch(ctx, member, telemetryBatch(withTrip, keep, t0, 1)); err != nil || o != store.BatchTripDeleted {
		t.Errorf("late batch: %v %v", o, err)
	}
	if o, _, err := tel.StoreBatch(ctx, member, telemetryBatch(orphan, vehicle, t0, 1)); err != nil || o != store.BatchVehicleNotFound {
		t.Errorf("deleted vehicle: %v %v", o, err)
	}
}
