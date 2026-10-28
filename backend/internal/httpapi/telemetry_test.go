package httpapi

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/objstore"
)

// flakyObjects is an object store whose writes, reads or deletes can be
// made to fail.
type flakyObjects struct {
	*objstore.Memory
	failPut, failGet, failDelete atomic.Bool
}

var errObjects = errors.New("object storage down")

func (f *flakyObjects) Put(ctx context.Context, key string, body []byte) error {
	if f.failPut.Load() {
		return errObjects
	}
	return f.Memory.Put(ctx, key, body)
}

func (f *flakyObjects) Get(ctx context.Context, key string) ([]byte, error) {
	if f.failGet.Load() {
		return nil, errObjects
	}
	return f.Memory.Get(ctx, key)
}

func (f *flakyObjects) Delete(ctx context.Context, key string) error {
	if f.failDelete.Load() {
		return errObjects
	}
	return f.Memory.Delete(ctx, key)
}

// newTelemetryEnv is newDBTestEnv with access to the object store.
func newTelemetryEnv(t *testing.T) (*testEnv, *flakyObjects, *pgxpool.Pool) {
	t.Helper()
	pool := dbtest.New(t)
	trips, err := db.NewTrips(t.Context(), pool, "test-deployment")
	if err != nil {
		t.Fatal(err)
	}
	objects := &flakyObjects{Memory: objstore.NewMemory()}
	e := newTestEnvWith(t, true, func(d *Deps) {
		d.Members = db.NewMembers(pool)
		d.Vehicles = db.NewVehicles(pool)
		d.Trips = trips
		d.Telemetry = db.NewTelemetry(pool)
		d.Objects = objects
		d.Idempotency = db.NewIdempotency(pool)
		d.ClassifyError = db.Classify
	})
	return e, objects, pool
}

func sampleJSON(t string, speed float64) string {
	return fmt.Sprintf(`{"t":%q,"speedKmh":%g,"rpm":1800,"lat":12.97,"lon":77.59,"mil":false,"coolantC":null,"accel":{"x":0.1,"y":0,"z":9.8},"pids":{"0x5E":1.25}}`, t, speed)
}

func telemetryJSON(clientBatch, clientTrip uuid.UUID, vehicle string, samples, events []string) string {
	return fmt.Sprintf(`{"clientBatchId":%q,"clientTripId":%q,"vehicleId":%q,"samples":[%s],"events":[%s]}`,
		clientBatch, clientTrip, vehicle, strings.Join(samples, ","), strings.Join(events, ","))
}

// sendTelemetry posts a batch under key (a fresh one if "").
func (m *member) sendTelemetry(body, key string) response {
	m.e.t.Helper()
	if key == "" {
		key = uuid.NewString()
	}
	return m.call(http.MethodPost, "/v1/telemetry/batches", body, "Idempotency-Key", key)
}

func ackOf(t *testing.T, r response) string {
	t.Helper()
	if r.status != http.StatusAccepted {
		t.Fatalf("upload: %d %s", r.status, r.body)
	}
	m := r.json(t)
	return fmt.Sprintf("%s a=%v/%v d=%v/%v", m["status"], m["acceptedSamples"], m["acceptedEvents"], m["droppedSamples"], m["droppedEvents"])
}

// readAll walks a read-back endpoint and returns every item.
func (m *member) readAll(path string, limit int) []map[string]any {
	m.e.t.Helper()
	var out []map[string]any
	cursor := ""
	for range 1000 {
		u := fmt.Sprintf("%s?limit=%d", path, limit)
		if cursor != "" {
			u += "&cursor=" + q(cursor)
		}
		r := m.call(http.MethodGet, u, "")
		if r.status != http.StatusOK {
			m.e.t.Fatalf("GET %s: %d %s", u, r.status, r.body)
		}
		j := r.json(m.e.t)
		items := j["items"].([]any)
		if len(items) > limit {
			m.e.t.Fatalf("%d items, limit %d", len(items), limit)
		}
		for _, it := range items {
			out = append(out, it.(map[string]any))
		}
		next, _ := j["nextCursor"].(string)
		if next == "" {
			if _, ok := j["nextCursor"]; !ok {
				m.e.t.Fatalf("nextCursor missing")
			}
			return out
		}
		if len(items) == 0 {
			m.e.t.Fatalf("empty page with a nextCursor")
		}
		cursor = next
	}
	m.e.t.Fatal("walk did not end")
	return nil
}

func times(items []map[string]any) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it["t"].(string)
	}
	return out
}

func TestTelemetryUploadAndReadBack(t *testing.T) {
	t.Parallel()
	e, objects, _ := newTelemetryEnv(t)
	m := e.member("tel-upload")
	vehicle := m.vehicle()
	trip, batch1 := uuid.New(), uuid.New()

	// Telemetry before its trip summary is normal: accepted, never 404.
	body := telemetryJSON(batch1, trip, vehicle,
		[]string{
			sampleJSON("2026-10-23T14:00:02Z", 30),
			sampleJSON("2026-10-23T14:00:00.123456789Z", 10), // truncated to .123
			sampleJSON("2026-10-23T14:00:01Z", 20),
			sampleJSON("2026-10-23T14:00:00.123999Z", 99), // same ms as the second: dropped
		},
		[]string{
			`{"clientEventId":"` + uuid.NewString() + `","t":"2026-10-23T14:00:01.5Z","type":"harsh_brake","value":0.45,"lat":12.97,"lon":77.59}`,
			`{"t":"2026-10-23T14:00:01.500999Z","type":"harsh_brake","active":true}`, // fallback key matches the first
			`{"t":"2026-10-23T14:00:02Z","type":"mil_on","dtc":"P0420"}`,
			`{"t":"2026-10-23T14:00:02Z","type":"mil_on","dtc":null}`, // no code: another event
		})
	key := uuid.NewString()
	r := m.sendTelemetry(body, key)
	if got := ackOf(t, r); got != "accepted a=3/3 d=1/1" {
		t.Fatalf("ack = %s", got)
	}
	if r.json(t)["clientBatchId"] != batch1.String() {
		t.Errorf("clientBatchId = %v", r.json(t)["clientBatchId"])
	}
	if keys := objects.Keys(); len(keys) != 1 || !strings.HasPrefix(keys[0], "members/"+m.id+"/trips/"+trip.String()+"/") {
		t.Fatalf("objects = %v", keys)
	}
	// The same request replays byte for byte; the same clientBatchId under
	// another key is a duplicate with the original counts, and stores nothing.
	if again := m.sendTelemetry(body, key); again.status != r.status || !bytes.Equal(again.body, r.body) {
		t.Errorf("replay: %d %s", again.status, again.body)
	}
	dup := telemetryJSON(batch1, trip, vehicle, []string{sampleJSON("2026-10-23T15:00:00Z", 1)}, nil)
	if got := ackOf(t, m.sendTelemetry(dup, "")); got != "duplicate a=3/3 d=1/1" {
		t.Errorf("duplicate ack = %s", got)
	}
	if n := len(objects.Keys()); n != 1 {
		t.Errorf("objects after duplicate = %d", n)
	}

	// Read-back needs the trip: 404 until its summary arrives.
	if r := m.call(http.MethodGet, "/v1/trips/client:"+trip.String()+"/samples", ""); r.status != http.StatusNotFound {
		t.Fatalf("samples before summary: %d %s", r.status, r.body)
	}
	tripID := m.upload(tripJSON(trip, vehicle, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))[0]["id"].(string)

	// A later batch overlaps: t=1 s again (the first stored sample wins) and
	// two new samples; its events repeat stored ones and add one.
	batch2 := telemetryJSON(uuid.New(), trip, vehicle,
		[]string{sampleJSON("2026-10-23T14:00:01Z", 77), sampleJSON("2026-10-23T14:00:03Z", 40), sampleJSON("2026-10-23T13:59:59Z", 5)},
		[]string{`{"t":"2026-10-23T14:00:01.5Z","type":"harsh_brake"}`, `{"t":"2026-10-23T14:00:03Z","type":"overspeed","value":121}`})
	// Duplicates of earlier batches aren't detected at upload time.
	if got := ackOf(t, m.sendTelemetry(batch2, "")); got != "accepted a=3/2 d=0/0" {
		t.Errorf("batch 2 ack = %s", got)
	}

	for _, ref := range []string{tripID, "client:" + trip.String()} {
		for _, limit := range []int{1, 2, 5000} {
			items := m.readAll("/v1/trips/"+ref+"/samples", limit)
			want := []string{"2026-10-23T13:59:59.000Z", "2026-10-23T14:00:00.123Z", "2026-10-23T14:00:01.000Z", "2026-10-23T14:00:02.000Z", "2026-10-23T14:00:03.000Z"}
			if got := times(items); !slices.Equal(got, want) {
				t.Fatalf("samples (limit %d) = %v, want %v", limit, got, want)
			}
			if items[1]["speedKmh"] != float64(10) || items[2]["speedKmh"] != float64(20) {
				t.Errorf("first stored sample must win: %v %v", items[1], items[2])
			}
			if p := items[1]["pids"].(map[string]any); p["0x5E"] != 1.25 {
				t.Errorf("pids = %v", p)
			}
			if v, ok := items[1]["coolantC"]; !ok || v != nil {
				t.Errorf("explicit null lost: %v", items[1])
			}
		}
	}
	events := m.readAll("/v1/trips/"+tripID+"/events", 2)
	var got []string
	for _, ev := range events {
		s := fmt.Sprintf("%s@%s", ev["type"], ev["t"])
		if d, ok := ev["dtc"]; ok {
			s += fmt.Sprintf(":%v", d)
		}
		if ev["active"] != true {
			s += ":inactive"
		}
		got = append(got, s)
	}
	want := []string{"harsh_brake@2026-10-23T14:00:01.500Z", "mil_on@2026-10-23T14:00:02.000Z:P0420", "mil_on@2026-10-23T14:00:02.000Z", "overspeed@2026-10-23T14:00:03.000Z"}
	slices.Sort(got[1:3]) // equal t: id order, either way round
	slices.Sort(want[1:3])
	if !slices.Equal(got, want) {
		t.Errorf("events = %v, want %v", got, want)
	}
	if events[0]["clientEventId"] == nil || events[0]["value"] != 0.45 {
		t.Errorf("event fields = %v", events[0])
	}

	// Another member sees none of it.
	other := e.member("tel-other")
	for _, p := range []string{"/v1/trips/" + tripID + "/samples", "/v1/trips/client:" + trip.String() + "/events"} {
		if r := other.call(http.MethodGet, p, ""); r.status != http.StatusNotFound {
			t.Errorf("other member %s: %d", p, r.status)
		}
	}

	// Deleting the trip erases its telemetry, objects included; a late
	// batch is acknowledged as trip_deleted, and a replay of a batch
	// accepted before the delete is too.
	if r := m.call(http.MethodDelete, "/v1/trips/"+tripID, ""); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d %s", r.status, r.body)
	}
	if keys := objects.Keys(); len(keys) != 0 {
		t.Errorf("objects after delete = %v", keys)
	}
	late := telemetryJSON(uuid.New(), trip, vehicle, []string{sampleJSON("2026-10-23T14:10:00Z", 1)}, nil)
	if got := ackOf(t, m.sendTelemetry(late, "")); got != "trip_deleted a=0/0 d=0/0" {
		t.Errorf("late ack = %s", got)
	}
	if got := ackOf(t, m.sendTelemetry(body, key)); got != "trip_deleted a=0/0 d=0/0" {
		t.Errorf("replay after delete = %s", got)
	}
	if keys := objects.Keys(); len(keys) != 0 {
		t.Errorf("late batch stored an object: %v", keys)
	}
}

func TestTelemetryVehicleChecks(t *testing.T) {
	t.Parallel()
	e, objects, _ := newTelemetryEnv(t)
	m, other := e.member("tel-vehicle"), e.member("tel-vehicle-other")
	theirs := other.vehicle()
	for _, v := range []string{theirs, uuid.NewString(), "not-a-uuid"} {
		r := m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), v, []string{sampleJSON("2026-10-23T14:00:00Z", 1)}, nil), "")
		if r.status != http.StatusNotFound || r.problemCode(t) != "vehicle_not_found" {
			t.Errorf("vehicle %s: %d %s", v, r.status, r.body)
		}
	}
	if keys := objects.Keys(); len(keys) != 0 {
		t.Errorf("objects = %v", keys)
	}
	// A deleted vehicle is not found either.
	mine := m.vehicle()
	etag := m.call(http.MethodGet, "/v1/vehicles/"+mine, "").header.Get("ETag")
	if r := m.call(http.MethodDelete, "/v1/vehicles/"+mine, "", "If-Match", etag); r.status != http.StatusNoContent {
		t.Fatalf("delete vehicle: %d %s", r.status, r.body)
	}
	r := m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), mine, nil, []string{`{"t":"2026-10-23T14:00:00Z","type":"idling"}`}), "")
	if r.status != http.StatusNotFound || r.problemCode(t) != "vehicle_not_found" {
		t.Errorf("deleted vehicle: %d %s", r.status, r.body)
	}
}

func TestTelemetryCascadeDeleteRemovesObjects(t *testing.T) {
	t.Parallel()
	e, objects, _ := newTelemetryEnv(t)
	m := e.member("tel-cascade")
	vehicle, keep := m.vehicle(), m.vehicle()
	withSummary, orphan, kept := uuid.New(), uuid.New(), uuid.New()
	m.upload(tripJSON(withSummary, vehicle, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))
	for _, b := range []struct {
		trip    uuid.UUID
		vehicle string
	}{{withSummary, vehicle}, {orphan, vehicle}, {kept, keep}} {
		ackOf(t, m.sendTelemetry(telemetryJSON(uuid.New(), b.trip, b.vehicle, []string{sampleJSON("2026-10-23T14:00:00Z", 1)}, nil), ""))
	}
	if n := len(objects.Keys()); n != 3 {
		t.Fatalf("objects = %d", n)
	}
	etag := m.call(http.MethodGet, "/v1/vehicles/"+vehicle, "").header.Get("ETag")
	if r := m.call(http.MethodDelete, "/v1/vehicles/"+vehicle+"?cascade=true", "", "If-Match", etag); r.status != http.StatusNoContent {
		t.Fatalf("cascade delete: %d %s", r.status, r.body)
	}
	keys := objects.Keys()
	if len(keys) != 1 || !strings.Contains(keys[0], kept.String()) {
		t.Errorf("objects after cascade = %v", keys)
	}
}

func TestTelemetryLimits(t *testing.T) {
	t.Parallel()
	e, _, _ := newTelemetryEnv(t)
	m := e.member("tel-limits")
	vehicle := m.vehicle()
	base := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	samples := make([]string, 5001)
	for i := range samples {
		samples[i] = `{"t":"` + base.Add(time.Duration(i)*time.Second).Format(time.RFC3339) + `"}`
	}
	r := m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), vehicle, samples, nil), "")
	if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" || !strings.Contains(string(r.body), `"/samples"`) {
		t.Errorf("5001 samples: %d %.300s", r.status, r.body)
	}
	// 5000 samples (gzipped, as the app sends them) and 500 events are fine.
	events := make([]string, 500)
	for i := range events {
		events[i] = `{"t":"` + base.Add(time.Duration(i)*time.Second).Format(time.RFC3339) + `","type":"idling"}`
	}
	var gz bytes.Buffer
	zw := gzip.NewWriter(&gz)
	_, _ = zw.Write([]byte(telemetryJSON(uuid.New(), uuid.New(), vehicle, samples[:5000], events)))
	_ = zw.Close()
	r = m.e.do(http.MethodPost, "/v1/telemetry/batches", &gz, "Authorization", "Bearer "+m.token,
		"Content-Type", "application/json", "Content-Encoding", "gzip", "Idempotency-Key", uuid.NewString())
	if got := ackOf(t, r); got != "accepted a=5000/500 d=0/0" {
		t.Errorf("full batch = %s", got)
	}
	r = m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), vehicle, nil, append(events, events[0])), "")
	if r.status != http.StatusUnprocessableEntity || !strings.Contains(string(r.body), `"/events"`) {
		t.Errorf("501 events: %d %.300s", r.status, r.body)
	}
	// Timestamps with an offset are rejected, as UtcInstant says.
	r = m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), vehicle, []string{`{"t":"2026-10-23T19:30:00+05:30"}`}, nil), "")
	if r.status != http.StatusUnprocessableEntity {
		t.Errorf("offset: %d %s", r.status, r.body)
	}
	// The Idempotency-Key is required.
	if r := m.call(http.MethodPost, "/v1/telemetry/batches", telemetryJSON(uuid.New(), uuid.New(), vehicle, nil, nil)); r.status != http.StatusBadRequest {
		t.Errorf("no key: %d %s", r.status, r.body)
	}
}

// TestTelemetryReadBackAcrossBatches checks the page logic against many
// overlapping, out-of-order batches: every t once, in order, the earliest
// stored batch's sample for each, at every page size.
func TestTelemetryReadBackAcrossBatches(t *testing.T) {
	t.Parallel()
	e, _, _ := newTelemetryEnv(t)
	m := e.member("tel-pages")
	vehicle := m.vehicle()
	trip := uuid.New()
	base := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	first := map[string]float64{} // t -> speed of the first batch that sent it
	// 40 batches (more than one chunk of batch rows) of 7 samples each,
	// starting at offsets that go back and forth and overlap.
	for b := range 40 {
		start := (b * 37) % 60
		var samples []string
		for i := range 7 {
			ts := base.Add(time.Duration(start+i*2) * time.Second).Format("2006-01-02T15:04:05.000Z")
			if _, ok := first[ts]; !ok {
				first[ts] = float64(b)
			}
			samples = append(samples, sampleJSON(ts, float64(b)))
		}
		ackOf(t, m.sendTelemetry(telemetryJSON(uuid.New(), trip, vehicle, samples, nil), ""))
	}
	m.upload(tripJSON(trip, vehicle, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))
	var want []string
	for ts := range first {
		want = append(want, ts)
	}
	slices.Sort(want)
	for _, limit := range []int{1, 3, 7, 50, 1000} {
		items := m.readAll("/v1/trips/client:"+trip.String()+"/samples", limit)
		if got := times(items); !slices.Equal(got, want) {
			t.Fatalf("limit %d: %v\nwant %v", limit, got, want)
		}
		for _, it := range items {
			if it["speedKmh"] != first[it["t"].(string)] {
				t.Fatalf("limit %d: %v from batch %v, want batch %v", limit, it["t"], it["speedKmh"], first[it["t"].(string)])
			}
		}
	}
	for _, c := range []string{"bm9wZQ", "!!", "czE6eA"} { // "nope", not base64, "s1:x"
		if r := m.call(http.MethodGet, "/v1/trips/client:"+trip.String()+"/samples?cursor="+c, ""); r.status != http.StatusBadRequest {
			t.Errorf("cursor %q: %d", c, r.status)
		}
		if r := m.call(http.MethodGet, "/v1/trips/client:"+trip.String()+"/events?cursor="+c, ""); r.status != http.StatusBadRequest {
			t.Errorf("event cursor %q: %d", c, r.status)
		}
	}
}

func TestTelemetryObjectStoreFailures(t *testing.T) {
	t.Parallel()
	e, objects, pool := newTelemetryEnv(t)
	m := e.member("tel-r2")
	vehicle := m.vehicle()
	trip := uuid.New()
	body := telemetryJSON(uuid.New(), trip, vehicle, []string{sampleJSON("2026-10-23T14:00:00Z", 1)}, []string{`{"t":"2026-10-23T14:00:00Z","type":"idling"}`})
	key := uuid.NewString()

	// A failing write is a retryable 503 and stores nothing; the key is
	// released, so the retry runs.
	objects.failPut.Store(true)
	r := m.sendTelemetry(body, key)
	if r.status != http.StatusServiceUnavailable || r.problemCode(t) != "service_unavailable" || r.header.Get("Retry-After") == "" {
		t.Fatalf("put failure: %d %v %s", r.status, r.header, r.body)
	}
	var n int
	if err := pool.QueryRow(t.Context(), "SELECT count(*) FROM telemetry_batches").Scan(&n); err != nil || n != 0 {
		t.Fatalf("batches after failure = %d, %v", n, err)
	}
	objects.failPut.Store(false)
	if got := ackOf(t, m.sendTelemetry(body, key)); got != "accepted a=1/1 d=0/0" {
		t.Fatalf("retry = %s", got)
	}

	// A failing read is a 503 too.
	tripID := m.upload(tripJSON(trip, vehicle, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))[0]["id"].(string)
	objects.failGet.Store(true)
	if r := m.call(http.MethodGet, "/v1/trips/"+tripID+"/samples", ""); r.status != http.StatusServiceUnavailable {
		t.Errorf("get failure: %d %s", r.status, r.body)
	}
	objects.failGet.Store(false)

	// A failing delete still deletes the trip; the object is logged as left.
	objects.failDelete.Store(true)
	if r := m.call(http.MethodDelete, "/v1/trips/"+tripID, ""); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d %s", r.status, r.body)
	}
	if !strings.Contains(e.logs.String(), "telemetry objects left in object storage") {
		t.Errorf("no log for the left object")
	}
	if n := len(objects.Keys()); n != 1 {
		t.Errorf("objects = %d", n)
	}
}

func TestTelemetryWithoutDatabase(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	m := e.member("nodb-telemetry")
	for _, c := range []struct{ method, path, body string }{
		{http.MethodPost, "/v1/telemetry/batches", telemetryJSON(uuid.New(), uuid.New(), uuid.NewString(), nil, nil)},
		{http.MethodGet, "/v1/trips/" + uuid.NewString() + "/samples", ""},
		{http.MethodGet, "/v1/trips/" + uuid.NewString() + "/events", ""},
	} {
		r := m.call(c.method, c.path, c.body, "Idempotency-Key", uuid.NewString())
		if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
			t.Errorf("%s %s: %d %s", c.method, c.path, r.status, r.body)
		}
	}
}
