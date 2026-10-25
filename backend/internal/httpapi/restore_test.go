package httpapi

import (
	"net/http"
	"net/url"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/synctoken"
)

// deploymentEnv is a test server on pool with SYNC_DEPLOYMENT_ID
// deploymentID and sync key key: two of them on one database are the
// deployment before and after a restore.
func deploymentEnv(t *testing.T, pool *pgxpool.Pool, deploymentID string, key []byte) *testEnv {
	t.Helper()
	trips, err := db.NewTrips(t.Context(), pool, deploymentID)
	if err != nil {
		t.Fatal(err)
	}
	return newTestEnvWith(t, true, func(d *Deps) {
		d.Members = db.NewMembers(pool)
		d.Vehicles = db.NewVehicles(pool)
		d.Trips = trips
		d.Idempotency = db.NewIdempotency(pool)
		d.ClassifyError = db.Classify
		d.SyncTokenKey = key
	})
}

func (r response) problemReason(t *testing.T) string {
	t.Helper()
	reason, _ := r.json(t)["reason"].(string)
	return reason
}

// fullWalk walks GET /v1/trips without since, a page per trip, and
// returns the trips by clientTripId and the walk's nextSince.
func (m *member) fullWalk() (map[string]map[string]any, string) {
	m.e.t.Helper()
	out := map[string]map[string]any{}
	path := "/v1/trips?limit=1"
	for {
		pg, r := m.page(path)
		if r.status != 0 && r.status != http.StatusOK {
			m.e.t.Fatalf("%s: %d %s", path, r.status, r.body)
		}
		for _, it := range pg.items {
			out[it["clientTripId"].(string)] = it
		}
		if pg.nextCursor == nil {
			return out, pg.nextSince.(string)
		}
		path = "/v1/trips?limit=1&cursor=" + url.QueryEscape(pg.nextCursor.(string))
	}
}

// TestTripSyncAfterRestore: after a restore (a new SYNC_DEPLOYMENT_ID on
// the same cluster), tokens and cursors of the old deployment answer 400
// sync_window_expired with reason deployment_changed (not purged or
// invalid), and the recovery flow the spec describes works: a full walk,
// re-creating lost vehicles, then re-uploading the local trips the walk
// didn't return with the ordinary idempotent batch upload.
func TestTripSyncAfterRestore(t *testing.T) {
	t.Parallel()
	pool := dbtest.New(t)
	key := synctoken.GenerateKey()
	before := deploymentEnv(t, pool, "test-before", key)
	m := before.member("restore")

	// Before the restore point: vehicle v, trip kept, trip deleted.
	v := m.vehicle()
	kept, deleted := uuid.New(), uuid.New()
	m.upload(tripJSON(kept, v, "completed", "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z"),
		tripJSON(deleted, v, "completed", "2026-10-02T08:00:00Z", "2026-10-02T09:00:00Z"))
	_, since := m.walk("/v1/trips")
	pg, _ := m.page("/v1/trips?limit=1")
	cursor := pg.nextCursor.(string)
	delPath := "/v1/trips/deletions?since=" + url.QueryEscape(since.(string))
	byClient, _ := m.fullWalk()
	if r := m.call(http.MethodDelete, "/v1/trips/"+byClient[deleted.String()]["id"].(string), ""); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d %s", r.status, r.body)
	}
	// The restore point: the trip rows (and tombstones) that exist now.
	keep := map[uuid.UUID]bool{}
	rows, err := pool.Query(t.Context(), `SELECT id FROM trips WHERE member_id = $1`, m.id)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			t.Fatal(err)
		}
		keep[id] = true
	}
	rows.Close()

	// After the restore point (lost by the restore): vehicle w, a trip on v
	// (lost), a trip on w (lost), and a trip created and deleted (its row
	// and tombstone lost).
	wClient := uuid.New()
	rw := m.call(http.MethodPost, "/v1/vehicles", withClientID(i20, wClient))
	if rw.status != http.StatusCreated {
		t.Fatalf("vehicle w: %d %s", rw.status, rw.body)
	}
	w := rw.json(t)["id"].(string)
	lost, lostOnW, ghost := uuid.New(), uuid.New(), uuid.New()
	tLost := tripJSON(lost, v, "completed", "2026-10-03T08:00:00Z", "2026-10-03T09:00:00Z")
	tGhost := tripJSON(ghost, v, "completed", "2026-10-05T08:00:00Z", "2026-10-05T09:00:00Z")
	m.upload(tLost, tripJSON(lostOnW, w, "completed", "2026-10-04T08:00:00Z", "2026-10-04T09:00:00Z"), tGhost)
	oldIDs, _ := m.fullWalk()
	if r := m.call(http.MethodDelete, "/v1/trips/"+oldIDs[ghost.String()]["id"].(string), ""); r.status != http.StatusNoContent {
		t.Fatalf("delete ghost: %d %s", r.status, r.body)
	}

	// The restore: rows written after the restore point are gone.
	var ids []uuid.UUID
	for _, it := range oldIDs {
		if id := uuid.MustParse(it["id"].(string)); !keep[id] {
			ids = append(ids, id)
		}
	}
	if _, err := pool.Exec(t.Context(), `DELETE FROM trips WHERE member_id = $1 AND id = ANY ($2)`, m.id, ids); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(t.Context(), `DELETE FROM vehicles WHERE id = $1`, w); err != nil {
		t.Fatal(err)
	}

	after := deploymentEnv(t, pool, "test-after", key)
	m2 := after.member("restore")
	if m2.id != m.id {
		t.Fatalf("member id changed: %s vs %s", m2.id, m.id)
	}

	// 1. Old tokens and cursors: deployment_changed.
	for name, path := range map[string]string{
		"changes":        "/v1/trips?since=" + url.QueryEscape(since.(string)),
		"deletions":      delPath,
		"history cursor": "/v1/trips?limit=1&cursor=" + url.QueryEscape(cursor),
	} {
		r := m2.call(http.MethodGet, path, "")
		if r.status != http.StatusBadRequest || r.problemCode(t) != "sync_window_expired" || r.problemReason(t) != "deployment_changed" {
			t.Errorf("%s: %d %s, want 400 sync_window_expired/deployment_changed", name, r.status, r.body)
		}
	}
	// Ordinary failures keep their reasons.
	if r := m2.call(http.MethodGet, "/v1/trips?since="+url.QueryEscape(tamper(since.(string))), ""); r.problemReason(t) != "invalid" {
		t.Errorf("tampered token: %s, want reason invalid", r.body)
	}
	// A token from the other deployment's key is invalid, not
	// deployment_changed (the app then must not re-upload).
	other := deploymentEnv(t, pool, "test-after", synctoken.GenerateKey())
	if r := other.member("restore").call(http.MethodGet, "/v1/trips?since="+url.QueryEscape(since.(string)), ""); r.problemReason(t) != "invalid" {
		t.Errorf("token under a rotated key: %s, want reason invalid", r.body)
	}

	// 2. Full walk: only what the restore kept.
	server, newSince := m2.fullWalk()
	if len(server) != 1 || server[kept.String()] == nil {
		t.Fatalf("after restore the server has %v, want only the kept trip", server)
	}
	// 3. Lost vehicle w: re-created by its clientVehicleId, new server id.
	rw2 := m2.call(http.MethodPost, "/v1/vehicles", withClientID(i20, wClient))
	if rw2.status != http.StatusCreated {
		t.Fatalf("re-create w: %d %s", rw2.status, rw2.body)
	}
	w2 := rw2.json(t)["id"].(string)
	if w2 == w {
		t.Fatal("re-created vehicle kept its id")
	}
	// Uploading a lost trip with the lost vehicle id fails per item.
	if got := statuses(m2.upload(tripJSON(lostOnW, w, "completed", "2026-10-04T08:00:00Z", "2026-10-04T09:00:00Z"))); got != "rejected:vehicle_not_found" {
		t.Errorf("trip on the lost vehicle id: %s", got)
	}
	// 4. Re-upload every local trip the walk didn't return (the kept one
	// too, to show it is harmless), fresh Idempotency-Key. The trip deleted
	// before the restore point is still a tombstone: trip_deleted.
	res := m2.upload(tripJSON(kept, v, "completed", "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z"), tLost,
		tripJSON(lostOnW, w2, "completed", "2026-10-04T08:00:00Z", "2026-10-04T09:00:00Z"),
		tripJSON(deleted, v, "completed", "2026-10-02T08:00:00Z", "2026-10-02T09:00:00Z"))
	if got := statuses(res); got != "unchanged,created,created,rejected:trip_deleted" {
		t.Fatalf("re-upload: %s", got)
	}
	if res[0]["id"] != server[kept.String()]["id"] {
		t.Errorf("kept trip id changed: %v", res[0]["id"])
	}
	if res[1]["id"] == oldIDs[lost.String()]["id"] {
		t.Error("a re-created trip should get a new server id")
	}
	// The trip created and deleted after the restore point lost its
	// tombstone: a device that never saw the deletion re-creates it. This is
	// the documented limit of the recovery (the app skips trips deleted on
	// this device).
	if got := statuses(m2.upload(tGhost)); got != "created" {
		t.Errorf("ghost re-upload: %s", got)
	}
	// The new token works, and the incremental walk sees the re-uploads.
	ids2, _ := m2.walk("/v1/trips?since=" + url.QueryEscape(newSince))
	if len(ids2) < 3 {
		t.Errorf("incremental walk after recovery: %d trips, want the 3 re-created", len(ids2))
	}
}

// TestTripWalkAcrossInstances: two instances of one deployment (same
// database, SYNC_TOKEN_KEY and SYNC_DEPLOYMENT_ID) behind a round-robin
// load balancer: every page of a full walk, a sync walk and a deletions
// walk may land on either, and all of them finish.
func TestTripWalkAcrossInstances(t *testing.T) {
	t.Parallel()
	pool := dbtest.New(t)
	key := synctoken.GenerateKey()
	envs := []*member{
		deploymentEnv(t, pool, "prod-1", key).member("rr"),
		deploymentEnv(t, pool, "prod-1", key).member("rr"),
	}
	a := envs[0]
	v := a.vehicle()
	var ids []uuid.UUID
	for i := range 6 {
		id := uuid.New()
		ids = append(ids, id)
		a.upload(tripJSON(id, v, "completed", "2026-10-0"+string(rune('1'+i))+"T08:00:00Z", "2026-10-10T00:00:00Z"))
	}
	walk := func(path string) (n int, since string) {
		t.Helper()
		p := path
		for i := 0; ; i++ {
			pg, r := envs[i%2].page(p)
			if r.status != 0 && r.status != http.StatusOK {
				t.Fatalf("%s on instance %d: %d %s", p, i%2, r.status, r.body)
			}
			n += len(pg.items)
			if pg.nextCursor == nil {
				s, _ := pg.nextSince.(string)
				return n, s
			}
			sep := "&"
			if !strings.Contains(path, "?") {
				sep = "?"
			}
			p = path + sep + "cursor=" + url.QueryEscape(pg.nextCursor.(string))
		}
	}
	n, since := walk("/v1/trips?limit=1")
	if n != 6 || since == "" {
		t.Fatalf("full walk: %d trips, since %q", n, since)
	}
	byClient, _ := a.fullWalk()
	for _, id := range ids[:3] {
		if r := envs[1].call(http.MethodDelete, "/v1/trips/"+byClient[id.String()]["id"].(string), ""); r.status != http.StatusNoContent {
			t.Fatalf("delete: %d %s", r.status, r.body)
		}
	}
	if n, _ := walk("/v1/trips/deletions?limit=1&since=" + url.QueryEscape(since)); n < 3 {
		t.Errorf("deletions walk: %d, want at least 3", n)
	}
	for range 3 {
		a.upload(tripJSON(uuid.New(), v, "completed", "2026-10-09T08:00:00Z", "2026-10-11T00:00:00Z"))
	}
	if n, _ := walk("/v1/trips?limit=1&since=" + url.QueryEscape(since)); n < 3 {
		t.Errorf("changes walk: %d, want at least 3", n)
	}
}
