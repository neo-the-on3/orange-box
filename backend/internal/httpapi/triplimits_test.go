package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"testing"

	"github.com/google/uuid"
)

// maxTrip is a trip at every field limit: the largest the server accepts.
// Non-ASCII characters make the strings as long as they can be in bytes.
func maxTrip(clientID uuid.UUID, vehicle, started string) map[string]any {
	bands := make([]map[string]any, maxTripSpeedBands)
	for i := range bands {
		bands[i] = map[string]any{"fromKmh": 1_000_000_000 + i, "toKmh": 2_000_000_000, "seconds": 1.2345678901234567e+300}
	}
	codes := func(prefix string) []string {
		c := make([]string, maxDtcCodesPerList)
		for i := range c {
			c[i] = fmt.Sprintf("%s%04X", prefix, i)
		}
		return c
	}
	big := 1.2345678901234567e+300
	trip := map[string]any{
		"clientTripId": clientID, "vehicleId": vehicle, "state": "in_progress",
		"startedAt": started, "endedAt": started, "snapshotAt": started,
		"speedBands": bands,
		"dtc":        map[string]any{"stored": codes("P"), "pending": codes("C"), "permanent": codes("B")},
		"simulated":  false,
		"app": map[string]any{
			"version":   strings.Repeat("😀", maxTripAppVersionLength),
			"os":        strings.Repeat("😀", maxTripAppOSLength),
			"osVersion": strings.Repeat("😀", maxTripAppOSVersionLength),
		},
		"durationS": 2_000_000_000, "maxRpm": 2_000_000_000, "harshAccelCount": 2_000_000_000,
		"harshBrakeCount": 2_000_000_000, "harshStartCount": 2_000_000_000, "harshStopCount": 2_000_000_000,
	}
	for _, f := range []string{"distanceKm", "engineDistanceKm", "maxSpeedKmh", "avgSpeedKmh", "fuelUsedL", "fuelEconomyKmPerL",
		"fuelCutTimeS", "idleTimeS", "warmUpTimeS", "accelTimeS", "ecoSpeedTimeS", "overSpeedTimeS", "ecoScore", "safetyScore",
		"batteryMaxV", "batteryAvgV", "coolantMaxC", "coolantAvgC"} {
		trip[f] = big
	}
	return trip
}

func batchOf(t *testing.T, trips ...map[string]any) string {
	t.Helper()
	b, err := json.Marshal(map[string]any{"trips": trips})
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// TestTripSizeLimits reproduces Backend Tester's OOM on PR #183: a member
// uploaded 60 trips of ~8 MiB each (an unbounded app.version), and every
// later GET /v1/trips page cost hundreds of MiB. Oversized fields are now
// rejected with 422 and a pointer, and a page of the largest trips the
// server accepts stays within tripPageBudget and a few MiB of allocation.
//
// Not parallel, so the allocation it measures is its own.
func TestTripSizeLimits(t *testing.T) {
	e := newDBTestEnv(t)
	m := e.member("size-limits")
	vehicle := m.vehicle()
	_, start := m.walk("/v1/trips")

	// The tester's trips, and each other unbounded field, are rejected.
	huge := maxTrip(uuid.New(), vehicle, "2026-10-23T10:00:00Z")
	huge["app"] = map[string]any{"version": strings.Repeat("x", 8<<20-16<<10), "os": "android"}
	tooManyBands := maxTrip(uuid.New(), vehicle, "2026-10-23T10:00:00Z")
	tooManyBands["speedBands"] = make([]map[string]any, 100_000)
	for i := range tooManyBands["speedBands"].([]map[string]any) {
		tooManyBands["speedBands"].([]map[string]any)[i] = map[string]any{"fromKmh": 0, "seconds": 1}
	}
	longVehicle := maxTrip(uuid.New(), strings.Repeat("v", 65), "2026-10-23T10:00:00Z")
	manyCodes := maxTrip(uuid.New(), vehicle, "2026-10-23T10:00:00Z")
	manyCodes["dtc"] = map[string]any{"pending": make([]string, maxDtcCodesPerList+1)}
	for i := range manyCodes["dtc"].(map[string]any)["pending"].([]string) {
		manyCodes["dtc"].(map[string]any)["pending"].([]string)[i] = "P0420"
	}
	longOSVersion := maxTrip(uuid.New(), vehicle, "2026-10-23T10:00:00Z")
	longOSVersion["app"].(map[string]any)["osVersion"] = strings.Repeat("a", maxTripAppOSVersionLength+1)
	for _, c := range []struct {
		name, body, pointer string
	}{
		{"8 MiB app.version", batchOf(t, maxTrip(uuid.New(), vehicle, "2026-10-23T09:00:00Z"), huge), "/trips/1/app/version"},
		{"100k speedBands", batchOf(t, tooManyBands), "/trips/0/speedBands"},
		{"65-character vehicleId", batchOf(t, longVehicle), "/trips/0/vehicleId"},
		{"129 pending DTCs", batchOf(t, manyCodes), "/trips/0/dtc/pending"},
		{"129-character osVersion", batchOf(t, longOSVersion), "/trips/0/app/osVersion"},
	} {
		r := m.call(http.MethodPost, "/v1/trips/batch", c.body, "Idempotency-Key", uuid.NewString())
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
			t.Fatalf("%s: %d %.300s", c.name, r.status, r.body)
		}
		errs, _ := r.json(t)["errors"].([]any)
		if len(errs) == 0 || errs[0].(map[string]any)["pointer"] != c.pointer {
			t.Errorf("%s: errors = %v, want pointer %s", c.name, errs, c.pointer)
		}
	}

	// The largest trip the field limits allow is well under maxTripBytes.
	one, _ := json.Marshal(maxTrip(uuid.New(), vehicle, "2026-10-23T10:00:00Z"))
	if len(one) > maxTripBytes/2 {
		t.Fatalf("a trip at every field limit is %d bytes, more than half of maxTripBytes (%d)", len(one), maxTripBytes)
	}
	t.Logf("largest accepted trip: %d bytes of JSON", len(one))

	// 100 of them, then walk both views at limit=100.
	const n = 100
	ids := map[string]bool{}
	for b := range 2 {
		var trips []map[string]any
		for i := range n / 2 {
			trips = append(trips, maxTrip(uuid.New(), vehicle, fmt.Sprintf("2026-10-%02dT%02d:%02d:00Z", 1+b, i/60, i%60)))
		}
		for _, r := range m.upload(trips2json(t, trips)...) {
			if r["status"] != "created" {
				t.Fatalf("upload: %v", r)
			}
			ids[r["id"].(string)] = true
		}
	}
	for _, view := range []string{"/v1/trips?limit=100", "/v1/trips?limit=100&since=" + q(start)} {
		label := "history"
		if strings.Contains(view, "since=") {
			label = "sync"
		}
		seen, pages, cursor := 0, 0, ""
		for {
			path := view
			if cursor != "" {
				path += "&cursor=" + url.QueryEscape(cursor)
			}
			var before, after runtime.MemStats
			runtime.GC()
			runtime.ReadMemStats(&before)
			r := m.e.do(http.MethodGet, path, nil, "Authorization", "Bearer "+m.token)
			runtime.ReadMemStats(&after)
			if r.status != http.StatusOK {
				t.Fatalf("%s: %d %s", path, r.status, r.body)
			}
			pages++
			alloc := after.TotalAlloc - before.TotalAlloc
			if len(r.body) > tripPageBudget+1024 {
				t.Fatalf("%s: page of %d bytes, budget %d", path, len(r.body), tripPageBudget)
			}
			if alloc > 32<<20 {
				t.Fatalf("%s: serving a page allocated %d MiB", path, alloc>>20)
			}
			j := r.json(t)
			items := j["items"].([]any)
			seen += len(items)
			t.Logf("%s page %d: %d trips, %d KiB, %.1f MiB allocated", label, pages, len(items), len(r.body)>>10, float64(alloc)/(1<<20))
			for _, it := range items {
				if !ids[it.(map[string]any)["id"].(string)] {
					t.Fatalf("unexpected trip %v", it.(map[string]any)["id"])
				}
			}
			next, _ := j["nextCursor"].(string)
			if next == "" {
				if j["nextSince"] == nil {
					t.Fatalf("%s: last page without nextSince", view)
				}
				break
			}
			if j["nextSince"] != nil {
				t.Fatalf("%s: nextSince on a page with a cursor", view)
			}
			cursor = next
		}
		if seen != n || pages < 2 {
			t.Fatalf("%s: %d trips in %d pages, want %d in more than one (the byte budget ends pages early)", view, seen, pages, n)
		}
	}
}

func trips2json(t *testing.T, trips []map[string]any) []string {
	t.Helper()
	out := make([]string, len(trips))
	for i, tr := range trips {
		b, err := json.Marshal(tr)
		if err != nil {
			t.Fatal(err)
		}
		out[i] = string(b)
	}
	return out
}

// TestTripByteCap: a trip larger than maxTripBytes as JSON fails the batch
// with 422 and the trip's pointer. (The field limits keep real trips far
// below it; lower it to reach it.)
func TestTripByteCap(t *testing.T) {
	saved := maxTripBytes
	maxTripBytes = 2 << 10
	t.Cleanup(func() { maxTripBytes = saved })
	e := newDBTestEnv(t)
	m := e.member("byte-cap")
	vehicle := m.vehicle()
	small := tripJSON(uuid.New(), vehicle, "in_progress", "2026-10-23T10:00:00Z", "2026-10-23T10:00:00Z")
	big, _ := json.Marshal(maxTrip(uuid.New(), vehicle, "2026-10-23T10:00:00Z"))
	r := m.call(http.MethodPost, "/v1/trips/batch", batch(small, string(big)), "Idempotency-Key", uuid.NewString())
	if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
		t.Fatalf("over the cap: %d %s", r.status, r.body)
	}
	if p := r.json(t)["errors"].([]any)[0].(map[string]any)["pointer"]; p != "/trips/1" {
		t.Errorf("pointer = %v", p)
	}
	if ids, _ := m.walk("/v1/trips"); len(ids) != 0 {
		t.Fatalf("a rejected batch stored %v", ids)
	}
}
