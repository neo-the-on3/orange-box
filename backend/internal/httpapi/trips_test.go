package httpapi

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
)

// vehicle creates a vehicle for m and returns its id.
func (m *member) vehicle() string {
	m.e.t.Helper()
	r := m.call(http.MethodPost, "/v1/vehicles", i20)
	if r.status != http.StatusCreated {
		m.e.t.Fatalf("create vehicle: %d %s", r.status, r.body)
	}
	return r.json(m.e.t)["id"].(string)
}

// tripJSON is a TripUpload.
func tripJSON(clientID uuid.UUID, vehicle, state, startedAt, snapshotAt string) string {
	return fmt.Sprintf(`{"clientTripId":%q,"vehicleId":%q,"state":%q,"startedAt":%q,"snapshotAt":%q,`+
		`"distanceKm":12.5,"durationS":900,"maxRpm":4200,"fuelUsedL":null,"ecoScore":81.5,`+
		`"speedBands":[{"fromKmh":0,"toKmh":10,"seconds":30},{"fromKmh":110,"toKmh":null,"seconds":4}],`+
		`"dtc":{"stored":["P0420"]},"app":{"version":"1.0.0","os":"android"}}`,
		clientID, vehicle, state, startedAt, snapshotAt)
}

func batch(trips ...string) string { return `{"trips":[` + strings.Join(trips, ",") + `]}` }

// upload posts a batch under a fresh Idempotency-Key and returns its
// results.
func (m *member) upload(trips ...string) []map[string]any {
	m.e.t.Helper()
	r := m.call(http.MethodPost, "/v1/trips/batch", batch(trips...), "Idempotency-Key", uuid.NewString())
	if r.status != http.StatusOK {
		m.e.t.Fatalf("upload: %d %s", r.status, r.body)
	}
	return results(m.e.t, r)
}

func results(t *testing.T, r response) []map[string]any {
	t.Helper()
	var out []map[string]any
	for _, x := range r.json(t)["results"].([]any) {
		out = append(out, x.(map[string]any))
	}
	return out
}

func statuses(rs []map[string]any) string {
	var s []string
	for _, r := range rs {
		st := r["status"].(string)
		if p, ok := r["problem"].(map[string]any); ok {
			st += ":" + p["code"].(string)
		}
		s = append(s, st)
	}
	return strings.Join(s, ",")
}

// page is a decoded TripPage or TripDeletionPage.
type page struct {
	items      []map[string]any
	nextCursor any
	nextSince  any
}

func (m *member) page(path string) (page, response) {
	m.e.t.Helper()
	r := m.call(http.MethodGet, path, "")
	if r.status != http.StatusOK {
		return page{}, r
	}
	j := r.json(m.e.t)
	p := page{nextCursor: j["nextCursor"], nextSince: j["nextSince"]}
	if _, ok := j["nextCursor"]; !ok {
		m.e.t.Errorf("%s: nextCursor missing", path)
	}
	if _, ok := j["nextSince"]; !ok {
		m.e.t.Errorf("%s: nextSince missing", path)
	}
	for _, x := range j["items"].([]any) {
		p.items = append(p.items, x.(map[string]any))
	}
	return p, r
}

// walk follows path (with query, without cursor) to its last page,
// checking nextSince is null except there, and returns the item ids and
// the last page's nextSince.
func (m *member) walk(path string) ([]string, any) {
	m.e.t.Helper()
	var ids []string
	cursor := ""
	for {
		p := path
		if cursor != "" {
			sep := "?"
			if strings.Contains(p, "?") {
				sep = "&"
			}
			p += sep + "cursor=" + url.QueryEscape(cursor)
		}
		pg, r := m.page(p)
		if r.status != 0 && r.status != http.StatusOK {
			m.e.t.Fatalf("%s: %d %s", p, r.status, r.body)
		}
		for _, it := range pg.items {
			ids = append(ids, it["id"].(string))
		}
		if pg.nextCursor == nil {
			return ids, pg.nextSince
		}
		if pg.nextSince != nil {
			m.e.t.Fatalf("%s: nextSince on a page that is not the last", p)
		}
		cursor = pg.nextCursor.(string)
	}
}

func q(s any) string { return url.QueryEscape(s.(string)) }

func TestTripBatchUpsertAndRead(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("trips")
	other := e.member("trips-other")
	vehicle, otherVehicle := m.vehicle(), other.vehicle()
	a, b, c, d := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	start := "2026-10-23T13:05:00Z"

	key := uuid.NewString()
	body := batch(
		tripJSON(a, vehicle, "in_progress", start, "2026-10-23T14:05:00.123456789Z"),
		tripJSON(b, "not-a-uuid", "in_progress", start, "2026-10-23T14:05:00Z"),
		tripJSON(c, otherVehicle, "in_progress", start, "2026-10-23T14:05:00Z"),
		tripJSON(d, vehicle, "completed", "2026-10-23T15:05:00+05:30", "2026-10-23T16:00:00Z"),
	)
	r := m.call(http.MethodPost, "/v1/trips/batch", body, "Idempotency-Key", key)
	if r.status != http.StatusOK {
		t.Fatalf("upload: %d %s", r.status, r.body)
	}
	res := results(t, r)
	if got := statuses(res); got != "created,rejected:vehicle_not_found,rejected:vehicle_not_found,created" {
		t.Fatalf("results = %s", got)
	}
	if res[0]["clientId"] != a.String() || res[0]["id"] == nil || res[1]["id"] != nil {
		t.Fatalf("results = %v", res)
	}
	if _, present := res[1]["id"]; !present {
		t.Error("rejected item omits id instead of null")
	}
	if p := res[1]["problem"].(map[string]any); p["requestId"] != r.header.Get("X-Request-Id") || p["status"] != float64(404) {
		t.Errorf("item problem = %v", p)
	}
	id := res[0]["id"].(string)

	// Replay: the stored response byte for byte; other body: 422; no key: 400.
	if again := m.call(http.MethodPost, "/v1/trips/batch", body, "Idempotency-Key", key); again.status != http.StatusOK || !bytes.Equal(again.body, r.body) {
		t.Fatalf("replay: %d %s", again.status, again.body)
	}
	if r := m.call(http.MethodPost, "/v1/trips/batch", batch(tripJSON(a, vehicle, "in_progress", start, start)), "Idempotency-Key", key); r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "idempotency_key_reused" {
		t.Fatalf("reused key: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodPost, "/v1/trips/batch", batch(tripJSON(a, vehicle, "in_progress", start, start))); r.status != http.StatusBadRequest {
		t.Fatalf("no Idempotency-Key: %d %s", r.status, r.body)
	}

	// Timestamps are truncated to milliseconds: the same instant with fewer
	// digits is not newer. Older is ignored, newer replaces, completed is final.
	for _, step := range []struct{ snapshot, state, want string }{
		{"2026-10-23T14:05:00.123Z", "in_progress", "unchanged"},
		{"2026-10-23T14:04:00Z", "in_progress", "unchanged"},
		{"2026-10-23T14:06:00Z", "in_progress", "updated"},
		{"2026-10-23T14:07:00Z", "completed", "updated"},
		{"2026-10-23T14:08:00Z", "in_progress", "unchanged"},
	} {
		got := m.upload(tripJSON(a, vehicle, step.state, start, step.snapshot))
		if statuses(got) != step.want || got[0]["id"] != id {
			t.Fatalf("snapshot %s %s: %v, want %s", step.snapshot, step.state, got, step.want)
		}
	}

	for _, path := range []string{"/v1/trips/" + id, "/v1/trips/client:" + a.String()} {
		r := m.call(http.MethodGet, path, "")
		if r.status != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, r.status, r.body)
		}
		tr := r.json(t)
		if tr["id"] != id || tr["clientTripId"] != a.String() || tr["state"] != "completed" || tr["snapshotAt"] != "2026-10-23T14:07:00Z" ||
			tr["vehicleId"] != vehicle || tr["maxRpm"] != float64(4200) || tr["simulated"] != false || tr["fuelUsedL"] != nil {
			t.Fatalf("GET %s = %v", path, tr)
		}
		if bands := tr["speedBands"].([]any); len(bands) != 2 || bands[1].(map[string]any)["toKmh"] != nil {
			t.Errorf("speedBands = %v", tr["speedBands"])
		}
		if tr["dtc"].(map[string]any)["stored"].([]any)[0] != "P0420" || tr["app"].(map[string]any)["os"] != "android" {
			t.Errorf("dtc/app = %v %v", tr["dtc"], tr["app"])
		}
	}
	// An offset in the request is stored as the same instant in UTC.
	if r := m.call(http.MethodGet, "/v1/trips/client:"+d.String(), ""); r.json(t)["startedAt"] != "2026-10-23T09:35:00Z" {
		t.Errorf("startedAt with offset = %v", r.json(t)["startedAt"])
	}
	for _, path := range []string{"/v1/trips/" + uuid.NewString(), "/v1/trips/nope", "/v1/trips/client:nope", "/v1/trips/client:" + uuid.NewString()} {
		if r := m.call(http.MethodGet, path, ""); r.status != http.StatusNotFound || r.problemCode(t) != "not_found" {
			t.Errorf("GET %s: %d %s", path, r.status, r.body)
		}
	}
}

// TestTripMemberScope: another member's trips can't be read, deleted,
// listed or synced, even with the owner's ids, and a token or cursor of
// one member is useless to another.
func TestTripMemberScope(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m, stranger := e.member("owner"), e.member("stranger")
	vehicle := m.vehicle()
	a := uuid.New()
	id := m.upload(tripJSON(a, vehicle, "completed", "2026-10-23T13:00:00Z", "2026-10-23T14:00:00Z"))[0]["id"].(string)
	for i := range 3 {
		m.upload(tripJSON(uuid.New(), vehicle, "completed", fmt.Sprintf("2026-10-2%dT13:00:00Z", i), "2026-10-23T14:00:00Z"))
	}
	for _, path := range []string{"/v1/trips/" + id, "/v1/trips/client:" + a.String()} {
		if r := stranger.call(http.MethodGet, path, ""); r.status != http.StatusNotFound {
			t.Errorf("stranger GET %s: %d", path, r.status)
		}
		if r := stranger.call(http.MethodDelete, path, ""); r.status != http.StatusNotFound {
			t.Errorf("stranger DELETE %s: %d", path, r.status)
		}
	}
	// The stranger's own upload of the same clientTripId is a separate trip.
	if got := statuses(stranger.upload(tripJSON(a, stranger.vehicle(), "completed", "2026-10-23T13:00:00Z", "2026-10-23T14:00:00Z"))); got != "created" {
		t.Fatalf("stranger same clientTripId: %s", got)
	}
	ids, since := m.walk("/v1/trips?limit=2")
	if len(ids) != 4 || since == nil {
		t.Fatalf("owner walk = %v, %v", ids, since)
	}
	if ids, _ := stranger.walk("/v1/trips"); len(ids) != 1 || ids[0] == id {
		t.Fatalf("stranger walk = %v", ids)
	}
	if r := stranger.call(http.MethodGet, "/v1/trips?since="+q(since), ""); r.status != http.StatusBadRequest || r.problemCode(t) != "sync_window_expired" {
		t.Errorf("owner's token used by the stranger: %d %s", r.status, r.body)
	}
	if r := stranger.call(http.MethodGet, "/v1/trips/deletions?since="+q(since), ""); r.status != http.StatusBadRequest || r.problemCode(t) != "sync_window_expired" {
		t.Errorf("owner's token used by the stranger for deletions: %d %s", r.status, r.body)
	}
	first, _ := m.page("/v1/trips?limit=2")
	if r := stranger.call(http.MethodGet, "/v1/trips?cursor="+q(first.nextCursor), ""); r.status != http.StatusBadRequest || r.problemCode(t) != "bad_request" {
		t.Errorf("owner's cursor used by the stranger: %d %s", r.status, r.body)
	}
	if r := stranger.call(http.MethodGet, "/v1/trips/days?from=2026-10-01&to=2026-10-31", ""); r.status != http.StatusOK || len(r.json(t)["items"].([]any)) != 1 {
		t.Errorf("stranger days: %d %s", r.status, r.body)
	}
}

// TestTripDeleteAndSync walks the spec's call order: full walk → S; a
// change and a delete; trips?since=S gives the change (not the deleted
// trip), deletions?since=S the tombstone; late snapshots are trip_deleted.
func TestTripDeleteAndSync(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("sync")
	vehicle := m.vehicle()
	a, b := uuid.New(), uuid.New()
	res := m.upload(
		tripJSON(a, vehicle, "in_progress", "2026-10-23T13:00:00Z", "2026-10-23T14:00:00Z"),
		tripJSON(b, vehicle, "in_progress", "2026-10-22T13:00:00Z", "2026-10-22T14:00:00Z"),
	)
	ida, idb := res[0]["id"].(string), res[1]["id"].(string)
	ids, s := m.walk("/v1/trips")
	if len(ids) != 2 || ids[0] != ida || s == nil {
		t.Fatalf("full walk = %v, %v (history order: newest first)", ids, s)
	}
	// Nothing changed: an empty walk, with a fresh token anyway.
	if ids, s2 := m.walk("/v1/trips?since=" + q(s)); s2 == nil {
		t.Fatalf("empty sync walk: %v, nextSince %v", ids, s2)
	}
	m.upload(tripJSON(a, vehicle, "completed", "2026-10-23T13:00:00Z", "2026-10-23T15:00:00Z"))
	if r := m.call(http.MethodDelete, "/v1/trips/client:"+b.String(), ""); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodDelete, "/v1/trips/"+idb, ""); r.status != http.StatusNotFound {
		t.Fatalf("second delete: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodGet, "/v1/trips/"+idb, ""); r.status != http.StatusNotFound {
		t.Fatalf("GET deleted: %d", r.status)
	}
	changed, s2 := m.walk("/v1/trips?since=" + q(s))
	if !containsString(changed, ida) || containsString(changed, idb) || s2 == nil {
		t.Fatalf("trips since S = %v, want A and not B", changed)
	}
	dels, _ := m.walk("/v1/trips/deletions?since=" + q(s))
	if len(dels) != 1 || dels[0] != idb {
		t.Fatalf("deletions since S = %v, want B", dels)
	}
	dp, _ := m.page("/v1/trips/deletions?since=" + q(s))
	if d := dp.items[0]; d["clientTripId"] != b.String() || d["vehicleId"] != vehicle || d["deletedAt"] == nil {
		t.Errorf("tombstone = %v", d)
	}
	if got := statuses(m.upload(tripJSON(b, vehicle, "completed", "2026-10-22T13:00:00Z", "2026-10-22T16:00:00Z"))); got != "rejected:trip_deleted" {
		t.Fatalf("late snapshot after delete: %s", got)
	}
	if ids, _ := m.walk("/v1/trips"); len(ids) != 1 || ids[0] != ida {
		t.Fatalf("full walk after delete = %v", ids)
	}
}

func containsString(xs []string, x string) bool {
	for _, y := range xs {
		if y == x {
			return true
		}
	}
	return false
}

// TestTripPagingRules covers cursors and tokens: limit, filters, the
// since/cursor rules and tampering.
func TestTripPagingRules(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("paging")
	v1, v2 := m.vehicle(), m.vehicle()
	var all []string
	for i := range 7 {
		v := v1
		if i%2 == 1 {
			v = v2
		}
		res := m.upload(tripJSON(uuid.New(), v, "completed", fmt.Sprintf("2026-10-1%dT08:00:00Z", i), "2026-10-20T00:00:00Z"))
		all = append(all, res[0]["id"].(string))
	}
	ids, s := m.walk("/v1/trips?limit=3")
	if len(ids) != 7 || ids[0] != all[6] || ids[6] != all[0] || s == nil {
		t.Fatalf("history walk = %v", ids)
	}
	// Filtered walks end without nextSince.
	byVehicle, fs := m.walk("/v1/trips?limit=2&vehicleId=" + v2)
	if len(byVehicle) != 3 || fs != nil {
		t.Fatalf("vehicle walk = %v, nextSince %v", byVehicle, fs)
	}
	ranged, fs := m.walk("/v1/trips?limit=2&from=2026-10-12T00:00:00Z&to=2026-10-15T00:00:00Z")
	if len(ranged) != 3 || fs != nil {
		t.Fatalf("from/to walk = %v, nextSince %v", ranged, fs)
	}
	if ids, _ := m.walk("/v1/trips?vehicleId=not-a-uuid"); len(ids) != 0 {
		t.Fatalf("unknown vehicle id = %v", ids)
	}
	syncFirst, _ := m.page("/v1/trips?limit=3")
	cur := syncFirst.nextCursor.(string)

	for name, c := range map[string]struct {
		path string
		code string
	}{
		"since with vehicleId":        {"/v1/trips?vehicleId=" + v1 + "&since=" + q(s), "bad_request"},
		"since with from":             {"/v1/trips?from=2026-10-12T00:00:00Z&since=" + q(s), "bad_request"},
		"cursor with other filter":    {"/v1/trips?vehicleId=" + v1 + "&cursor=" + url.QueryEscape(cur), "bad_request"},
		"history cursor with since":   {"/v1/trips?since=" + q(s) + "&cursor=" + url.QueryEscape(cur), "bad_request"},
		"tampered cursor":             {"/v1/trips?cursor=" + url.QueryEscape(tamper(cur)), "bad_request"},
		"tampered token":              {"/v1/trips?since=" + url.QueryEscape(tamper(s.(string))), "sync_window_expired"},
		"garbage token":               {"/v1/trips?since=abc", "sync_window_expired"},
		"garbage deletions token":     {"/v1/trips/deletions?since=abc", "sync_window_expired"},
		"history cursor on deletions": {"/v1/trips/deletions?since=" + q(s) + "&cursor=" + url.QueryEscape(cur), "bad_request"},
		"limit out of range":          {"/v1/trips?limit=101", ""},
		"deletions without since":     {"/v1/trips/deletions", ""},
	} {
		r := m.call(http.MethodGet, c.path, "")
		if r.status != http.StatusBadRequest || (c.code != "" && r.problemCode(t) != c.code) {
			t.Errorf("%s: %d %s, want 400 %s", name, r.status, r.body, c.code)
		}
	}
	// Changes after S, so a sync walk from S has more than one page.
	for i := range 5 {
		m.upload(tripJSON(uuid.New(), v1, "completed", fmt.Sprintf("2026-10-0%dT08:00:00Z", i+1), "2026-10-20T00:00:00Z"))
	}
	// A changes cursor with a different since is rejected; with the same
	// since, or without since, it continues.
	_, s2 := m.walk("/v1/trips")
	sp, _ := m.page("/v1/trips?limit=2&since=" + q(s))
	if sp.nextCursor == nil {
		t.Fatalf("sync first page has no cursor: %+v", sp)
	}
	sc := url.QueryEscape(sp.nextCursor.(string))
	if r := m.call(http.MethodGet, "/v1/trips?since="+q(s2)+"&cursor="+sc, ""); r.status != http.StatusBadRequest || r.problemCode(t) != "bad_request" {
		t.Errorf("cursor with another since: %d %s", r.status, r.body)
	}
	for _, path := range []string{"/v1/trips?limit=2&since=" + q(s) + "&cursor=" + sc, "/v1/trips?limit=2&cursor=" + sc} {
		if r := m.call(http.MethodGet, path, ""); r.status != http.StatusOK {
			t.Errorf("%s: %d %s", path, r.status, r.body)
		}
	}
	changes, s3 := m.walk("/v1/trips?limit=2&since=" + q(s))
	if s3 == nil {
		t.Fatalf("sync walk = %v without nextSince", changes)
	}
	// The walk has the 5 trips written after S, each once. Delivery is at
	// least once: while any older transaction is open on the cluster, S's
	// watermark is held back and the walk may also repeat trips S already
	// covered, but only those.
	seen := map[string]int{}
	for _, id := range changes {
		seen[id]++
		if seen[id] > 1 {
			t.Fatalf("trip %s twice in one walk (cursor is exclusive)", id)
		}
	}
	newer := 0
	for id := range seen {
		if !containsString(all, id) {
			newer++
		}
	}
	if newer != 5 {
		t.Fatalf("sync walk since S = %v: %d trips written after S, want 5", changes, newer)
	}
}

// clusterXmin is the oldest transaction id still running on the cluster,
// which is what a sync token issued now carries as its watermark.
func clusterXmin(t *testing.T, pool *pgxpool.Pool) uint64 {
	t.Helper()
	var x uint64
	if err := pool.QueryRow(t.Context(), `SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint`).Scan(&x); err != nil {
		t.Fatal(err)
	}
	return x
}

// awaitXminPast waits (up to 5 s) for the cluster's xmin to pass xid. The
// other tests' transactions are short; a long one elsewhere on a shared
// database can hold it back for longer, which legitimately keeps expiring
// the tokens issued meanwhile.
func awaitXminPast(t *testing.T, pool *pgxpool.Pool, xid uint64) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); clusterXmin(t, pool) <= xid && time.Now().Before(deadline); {
		time.Sleep(20 * time.Millisecond)
	}
}

// tamper flips a bit in the middle of an opaque string.
func tamper(s string) string {
	b := []byte(s)
	i := len(b) / 2
	if b[i] == 'A' {
		b[i] = 'B'
	} else {
		b[i] = 'A'
	}
	return string(b)
}

// TestTripSyncWindowExpired: once a tombstone a token might need is
// purged, both sync endpoints answer 400 sync_window_expired, and a full
// walk gives a working token again.
func TestTripSyncWindowExpired(t *testing.T) {
	t.Parallel()
	e, pool := newDBTestEnvPool(t)
	m := e.member("expiry")
	vehicle := m.vehicle()
	a := uuid.New()
	m.upload(tripJSON(a, vehicle, "completed", "2026-06-01T08:00:00Z", "2026-06-01T09:00:00Z"))
	_, old := m.walk("/v1/trips")
	if r := m.call(http.MethodDelete, "/v1/trips/client:"+a.String(), ""); r.status != http.StatusNoContent {
		t.Fatal(r.status)
	}
	ctx := context.Background()
	// Age the tombstone past the retention and purge it.
	if _, err := pool.Exec(ctx, `UPDATE trips SET deleted_at = now() - interval '91 days' WHERE client_trip_id = $1`, a); err != nil {
		t.Fatal(err)
	}
	if _, err := dbgen.New(pool).PurgeTripTombstones(ctx, dbgen.PurgeTripTombstonesParams{DeletedBefore: time.Now().Add(-90 * 24 * time.Hour), MaxRows: 100}); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/v1/trips?since=" + q(old), "/v1/trips/deletions?since=" + q(old)} {
		r := m.call(http.MethodGet, path, "")
		if r.status != http.StatusBadRequest || r.problemCode(t) != "sync_window_expired" {
			t.Fatalf("%s: %d %s", path, r.status, r.body)
		}
		if title := r.json(t)["title"]; title != "Sync window expired" {
			t.Errorf("title = %v", title)
		}
	}
	// After the purge the clientTripId is new again.
	if got := statuses(m.upload(tripJSON(a, vehicle, "completed", "2026-06-01T08:00:00Z", "2026-06-01T09:00:00Z"))); got != "created" {
		t.Fatalf("upload after purge: %s", got)
	}
	// A new full walk's token works once nothing that might not have seen
	// the purged tombstone is still running. Its watermark is the cluster
	// xmin when it is issued: above the purge boundary it is accepted; at or
	// below it (an older transaction is still open somewhere on the
	// cluster) it expires too, which is right (at least once). Assert
	// whichever the xmin around the walk decides.
	var boundary uint64
	if err := pool.QueryRow(ctx, `SELECT max_writer_xid::text::bigint FROM trip_purge_boundaries WHERE member_id = $1`, m.id).Scan(&boundary); err != nil {
		t.Fatal(err)
	}
	awaitXminPast(t, pool, boundary)
	before := clusterXmin(t, pool)
	_, fresh := m.walk("/v1/trips")
	after := clusterXmin(t, pool)
	r := m.call(http.MethodGet, "/v1/trips/deletions?since="+q(fresh), "")
	switch {
	case before > boundary:
		if r.status != http.StatusOK {
			t.Fatalf("fresh token above the purge boundary: %d %s", r.status, r.body)
		}
	case after <= boundary:
		t.Logf("an older transaction holds the cluster xmin at or below the purge boundary %d; the fresh token must expire too", boundary)
		if r.status != http.StatusBadRequest || r.problemCode(t) != "sync_window_expired" {
			t.Fatalf("fresh token at or below the purge boundary: %d %s", r.status, r.body)
		}
	default: // the xmin passed the boundary during the walk: either is right
		if r.status != http.StatusOK && r.problemCode(t) != "sync_window_expired" {
			t.Fatalf("fresh token: %d %s", r.status, r.body)
		}
	}
}

func TestDrivingDays(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("days")
	v1, v2 := m.vehicle(), m.vehicle()
	// 20:00Z on the 22nd is 01:30 on the 23rd in Kolkata.
	for _, c := range []struct{ vehicle, start string }{
		{v1, "2026-10-22T20:00:00Z"}, {v1, "2026-10-22T10:00:00Z"}, {v2, "2026-10-23T10:00:00Z"}, {v1, "2026-11-30T10:00:00Z"},
	} {
		m.upload(tripJSON(uuid.New(), c.vehicle, "completed", c.start, c.start))
	}
	days := func(query string) string {
		t.Helper()
		r := m.call(http.MethodGet, "/v1/trips/days?"+query, "")
		if r.status != http.StatusOK {
			t.Fatalf("days?%s: %d %s", query, r.status, r.body)
		}
		var s []string
		for _, x := range r.json(t)["items"].([]any) {
			d := x.(map[string]any)
			s = append(s, fmt.Sprintf("%s=%v", d["date"], d["tripCount"]))
		}
		return strings.Join(s, ",")
	}
	if got := days("from=2026-10-01&to=2026-10-31"); got != "2026-10-22=2,2026-10-23=1" {
		t.Errorf("UTC days = %s", got)
	}
	if got := days("from=2026-10-01&to=2026-10-31&tz=Asia/Kolkata"); got != "2026-10-22=1,2026-10-23=2" {
		t.Errorf("Kolkata days = %s", got)
	}
	if got := days("from=2026-10-23&to=2026-10-23&tz=Asia/Kolkata&vehicleId=" + v1); got != "2026-10-23=1" {
		t.Errorf("one day, one vehicle = %s", got)
	}
	if got := days("from=2026-10-01&to=2026-12-31&vehicleId=nope"); got != "" {
		t.Errorf("unknown vehicle = %s", got)
	}
	if got := days("from=2026-01-01&to=2026-12-31"); got != "2026-10-22=2,2026-10-23=1,2026-11-30=1" {
		t.Errorf("365 days = %s", got)
	}
	// Backward-compatible aliases (Android reports Asia/Calcutta) count like
	// their canonical zones. 20:00Z and 10:00Z on the 22nd, 10:00Z on the 23rd.
	for alias, canonical := range map[string]string{
		"Asia/Calcutta":        "Asia/Kolkata",
		"Asia/Katmandu":        "Asia/Kathmandu",
		"US/Pacific":           "America/Los_Angeles",
		"America/Buenos_Aires": "America/Argentina/Buenos_Aires",
		"Europe/Kiev":          "Europe/Kyiv",
		"Asia/Saigon":          "Asia/Ho_Chi_Minh",
		"Etc/GMT-14":           "Pacific/Kiritimati",
	} {
		got, want := days("from=2026-10-01&to=2026-10-31&tz="+url.QueryEscape(alias)), days("from=2026-10-01&to=2026-10-31&tz="+url.QueryEscape(canonical))
		if got != want || got == "" {
			t.Errorf("tz=%s: %s, %s: %s", alias, got, canonical, want)
		}
	}
	if got := days("from=2026-10-01&to=2026-10-31&tz=Asia/Calcutta"); got != "2026-10-22=1,2026-10-23=2" {
		t.Errorf("Calcutta days = %s", got)
	}
	if got := days("from=2026-10-01&to=2026-10-31&tz=US/Pacific"); got != "2026-10-22=2,2026-10-23=1" {
		t.Errorf("US/Pacific days = %s", got)
	}
	for _, query := range []string{"from=2026-10-02&to=2026-10-01", "from=2026-01-01&to=2027-01-02", "from=2026-10-01&to=2026-10-31&tz=Mars/Base", "from=2026-10-01&to=2026-10-31&tz=Local", "from=2026-10-01&to=2026-10-31&tz=Factory", "from=2026-10-01&to=2026-10-31&tz=posixrules", "from=2026-10-01&to=2026-10-31&tz=../etc/passwd", "from=2026-10-01"} {
		if r := m.call(http.MethodGet, "/v1/trips/days?"+query, ""); r.status != http.StatusBadRequest {
			t.Errorf("days?%s: %d %s", query, r.status, r.body)
		}
	}
}

// TestDrivingDaysSkippedMidnight: in America/Santiago, 2026-09-06 starts
// at 01:00 (DST skips midnight); the days around it still split right.
func TestDrivingDaysSkippedMidnight(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("days-dst")
	v := m.vehicle()
	for _, start := range []string{"2026-09-06T03:30:00Z" /* 23:30 on the 5th, UTC-4 */, "2026-09-06T04:30:00Z" /* 01:30 on the 6th, UTC-3 */, "2026-09-07T02:30:00Z" /* 23:30 on the 6th */} {
		m.upload(tripJSON(uuid.New(), v, "completed", start, start))
	}
	r := m.call(http.MethodGet, "/v1/trips/days?from=2026-09-04&to=2026-09-08&tz=America/Santiago", "")
	var got []string
	for _, x := range r.json(t)["items"].([]any) {
		d := x.(map[string]any)
		got = append(got, fmt.Sprintf("%s=%v", d["date"], d["tripCount"]))
	}
	if strings.Join(got, ",") != "2026-09-05=1,2026-09-06=2" {
		t.Fatalf("Santiago days = %v", got)
	}
}

// TestTripsConcurrentHTTP drives uploads, deletes and sync walks at once
// over HTTP (run it under -race), then checks a sync from the start has
// every live trip exactly once and the deletions walk every deleted one.
func TestTripsConcurrentHTTP(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("stress")
	vehicle := m.vehicle()
	_, start := m.walk("/v1/trips")
	var mu sync.Mutex
	live, deleted := map[string]bool{}, map[string]bool{}
	var wg sync.WaitGroup
	for w := range 6 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range 8 {
				body := batch(tripJSON(uuid.New(), vehicle, "in_progress", "2026-10-23T13:00:00Z", fmt.Sprintf("2026-10-23T14:%02d:%02dZ", w, i)))
				key := uuid.NewString()
				var r response
				// One upload per member at a time: a 429 upload_in_progress
				// is retried, as a client would.
				for {
					r = m.e.do(http.MethodPost, "/v1/trips/batch", strings.NewReader(body),
						"Authorization", "Bearer "+m.token, "Content-Type", "application/json", "Idempotency-Key", key)
					if r.status != http.StatusTooManyRequests {
						break
					}
					time.Sleep(time.Millisecond)
				}
				if r.status != http.StatusOK {
					t.Errorf("upload: %d %s", r.status, r.body)
					return
				}
				id := r.json(t)["results"].([]any)[0].(map[string]any)["id"].(string)
				if i%3 == 0 {
					if r := m.e.do(http.MethodDelete, "/v1/trips/"+id, nil, "Authorization", "Bearer "+m.token); r.status != http.StatusNoContent {
						t.Errorf("delete: %d %s", r.status, r.body)
						return
					}
					mu.Lock()
					deleted[id] = true
					mu.Unlock()
				} else {
					mu.Lock()
					live[id] = true
					mu.Unlock()
				}
				if r := m.e.do(http.MethodGet, "/v1/trips?limit=5&since="+q(start), nil, "Authorization", "Bearer "+m.token); r.status != http.StatusOK {
					t.Errorf("walk: %d %s", r.status, r.body)
					return
				}
			}
		}()
	}
	wg.Wait()
	ids, _ := m.walk("/v1/trips?limit=7&since=" + q(start))
	got := map[string]int{}
	for _, id := range ids {
		got[id]++
	}
	if len(got) != len(live) {
		t.Fatalf("sync walk has %d trips, %d are live", len(got), len(live))
	}
	for id := range live {
		if got[id] != 1 {
			t.Fatalf("live trip %s appears %d times", id, got[id])
		}
	}
	dels, _ := m.walk("/v1/trips/deletions?limit=4&since=" + q(start))
	if len(dels) != len(deleted) {
		t.Fatalf("deletions walk has %d, %d were deleted", len(dels), len(deleted))
	}
	for _, id := range dels {
		if !deleted[id] {
			t.Fatalf("unexpected deletion %s", id)
		}
	}
}

// Without a database the trip operations answer 501.
func TestTripsWithoutDatabase(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	m := e.member("nodb-trips")
	since := "c2luY2U"
	for _, c := range []struct{ method, path, body string }{
		{http.MethodPost, "/v1/trips/batch", batch(tripJSON(uuid.New(), uuid.NewString(), "in_progress", "2026-10-23T13:00:00Z", "2026-10-23T13:00:00Z"))},
		{http.MethodGet, "/v1/trips", ""},
		{http.MethodGet, "/v1/trips/deletions?since=" + since, ""},
		{http.MethodGet, "/v1/trips/days?from=2026-10-01&to=2026-10-31", ""},
		{http.MethodGet, "/v1/trips/" + uuid.NewString(), ""},
		{http.MethodDelete, "/v1/trips/" + uuid.NewString(), ""},
	} {
		r := m.call(c.method, c.path, c.body, "Idempotency-Key", uuid.NewString())
		if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
			t.Errorf("%s %s: %d %s", c.method, c.path, r.status, r.body)
		}
	}
}
