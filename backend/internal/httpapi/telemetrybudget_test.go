package httpapi

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
)

// pidsJSON is a pids object with n entries, keyed 0x00, 0x01, ...
func pidsJSON(n int) string {
	parts := make([]string, n)
	for i := range n {
		parts[i] = fmt.Sprintf(`"0x%02X":%d.5`, i%256, i)
		if i >= 256 {
			parts[i] = fmt.Sprintf(`"0x%03X":%d.5`, i, i)
		}
	}
	return "{" + strings.Join(parts, ",") + "}"
}

func (m *member) sendTelemetryGzip(body string) response {
	m.e.t.Helper()
	var gz bytes.Buffer
	zw := gzip.NewWriter(&gz)
	if _, err := zw.Write([]byte(body)); err != nil {
		m.e.t.Fatal(err)
	}
	if err := zw.Close(); err != nil {
		m.e.t.Fatal(err)
	}
	r := m.e.do(http.MethodPost, "/v1/telemetry/batches", &gz, "Authorization", "Bearer "+m.token,
		"Content-Type", "application/json", "Content-Encoding", "gzip", "Idempotency-Key", uuid.NewString())
	return r
}

// A sample has at most 256 pids (one per mode 01 PID).
func TestSamplePidsLimit(t *testing.T) {
	t.Parallel()
	e, _, _ := newTelemetryEnv(t)
	m := e.member("tel-pids")
	vehicle := m.vehicle()
	sample := func(pids int) string {
		return `{"t":"2026-10-23T14:00:00.000Z","pids":` + pidsJSON(pids) + `}`
	}
	r := m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), vehicle, []string{sample(257)}, nil), "")
	if r.status != http.StatusUnprocessableEntity || fmt.Sprint(pointers(t, r)) != "[/samples/0/pids]" {
		t.Errorf("257 pids: %d %s", r.status, r.body)
	}
	if r := m.sendTelemetry(telemetryJSON(uuid.New(), uuid.New(), vehicle, []string{sample(256)}, nil), ""); r.status != http.StatusAccepted {
		t.Errorf("256 pids: %d %s", r.status, r.body)
	}
}

// Sample read-back pages end early at samplePageBudget bytes of JSON, so a
// trip of large samples can't make one huge response (or hold one in
// memory); walking still returns every sample once, in order.
func TestSampleReadBackBounded(t *testing.T) {
	t.Parallel()
	e, _, _ := newTelemetryEnv(t)
	m := e.member("tel-budget")
	vehicle := m.vehicle()
	trip := uuid.New()
	base := time.Date(2026, 10, 23, 14, 0, 0, 0, time.UTC)
	pids := pidsJSON(256)
	const batches, perBatch = 4, 500
	var want []string
	for b := range batches {
		samples := make([]string, perBatch)
		for i := range perBatch {
			// Interleaved batches: batch b has samples b, b+4, b+8, ...
			ts := base.Add(time.Duration(i*batches+b) * time.Second).Format("2006-01-02T15:04:05.000Z")
			samples[i] = `{"t":"` + ts + `","speedKmh":1,"pids":` + pids + `}`
			want = append(want, ts)
		}
		ackOf(t, m.sendTelemetryGzip(telemetryJSON(uuid.New(), trip, vehicle, samples, nil)))
	}
	m.upload(tripJSON(trip, vehicle, "completed", "2026-10-23T14:00:00Z", "2026-10-23T15:00:00Z"))
	slices.Sort(want)

	path := "/v1/trips/client:" + trip.String() + "/samples?limit=5000"
	var got []string
	pages := 0
	for cursor := ""; ; pages++ {
		u := path
		if cursor != "" {
			u += "&cursor=" + q(cursor)
		}
		r := m.call(http.MethodGet, u, "")
		if r.status != http.StatusOK {
			t.Fatalf("GET: %d %.300s", r.status, r.body)
		}
		if len(r.body) > samplePageBudget+1024 {
			t.Fatalf("page %d is %d bytes, budget %d", pages, len(r.body), samplePageBudget)
		}
		j := r.json(t)
		items := j["items"].([]any)
		if len(items) == 0 {
			t.Fatalf("page %d is empty", pages)
		}
		for _, it := range items {
			got = append(got, it.(map[string]any)["t"].(string))
		}
		next, _ := j["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if pages < 2 {
		t.Errorf("%d pages: the budget never ended a page", pages+1)
	}
	if !slices.Equal(got, want) {
		t.Errorf("walk returned %d samples, want %d (in order, once each)", len(got), len(want))
	}
}

// Telemetry operations answer 503 when object storage or the database is
// unavailable, so each declares it.
func TestTelemetryOperationsDeclare503(t *testing.T) {
	t.Parallel()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	for path, method := range map[string]string{
		"/v1/telemetry/batches":      http.MethodPost,
		"/v1/trips/{tripId}/samples": http.MethodGet,
		"/v1/trips/{tripId}/events":  http.MethodGet,
	} {
		item := doc.Paths.Find(path)
		if item == nil || item.GetOperation(method) == nil {
			t.Fatalf("no %s %s", method, path)
		}
		if item.GetOperation(method).Responses.Status(http.StatusServiceUnavailable) == nil {
			t.Errorf("%s %s doesn't declare 503", method, path)
		}
	}
}
