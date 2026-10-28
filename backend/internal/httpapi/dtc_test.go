package httpapi

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
)

// newVehicle creates a vehicle for m and returns its id.
func (m *member) newVehicle() string {
	m.e.t.Helper()
	r := m.call(http.MethodPost, "/v1/vehicles", i20)
	if r.status != http.StatusCreated {
		m.e.t.Fatalf("create vehicle: %d %s", r.status, r.body)
	}
	return r.json(m.e.t)["id"].(string)
}

func dtcPath(vehicle string) string { return "/v1/vehicles/" + vehicle + "/dtc-reports" }

func readReport(clientID uuid.UUID, readAt string) string {
	return fmt.Sprintf(`{"clientReportId":%q,"kind":"read","readAt":%q,"mil":true,"stored":["P0420","P0171"],"pending":[],`+
		`"vin":"KMHB341ABCD123456","odometerKm":42000,"distanceSinceMilKm":12}`, clientID, readAt)
}

func clearedReport(clientID uuid.UUID, readAt string) string {
	return fmt.Sprintf(`{"clientReportId":%q,"kind":"cleared","readAt":%q,"mil":null,"stored":["P0420","P0171"],"pending":[]}`, clientID, readAt)
}

func (m *member) postDtc(vehicle, body string) response {
	m.e.t.Helper()
	return m.call(http.MethodPost, dtcPath(vehicle), body, "Idempotency-Key", uuid.NewString())
}

func TestDtcReportCreate(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("dtc")
	vehicle := m.newVehicle()
	clientID := uuid.New()

	// An offset readAt is stored and returned in UTC, to the microsecond.
	body, key := readReport(clientID, "2026-10-23T19:35:00.123456789+05:30"), uuid.NewString()
	r := m.call(http.MethodPost, dtcPath(vehicle), body, "Idempotency-Key", key)
	if r.status != http.StatusCreated {
		t.Fatalf("create: %d %s", r.status, r.body)
	}
	rep := r.json(t)
	id, _ := rep["id"].(string)
	if _, err := uuid.Parse(id); err != nil || rep["vehicleId"] != vehicle || rep["clientReportId"] != clientID.String() ||
		rep["kind"] != "read" || rep["readAt"] != "2026-10-23T14:05:00.123456Z" || rep["mil"] != true ||
		fmt.Sprint(rep["stored"]) != "[P0420 P0171]" || fmt.Sprint(rep["pending"]) != "[]" || rep["vin"] != "KMHB341ABCD123456" ||
		rep["odometerKm"] != float64(42000) || rep["distanceSinceMilKm"] != float64(12) || rep["clientTripId"] != nil {
		t.Fatalf("created: %s", r.body)
	}
	if loc := r.header.Get("Location"); loc != "" {
		t.Errorf("201 has Location %q; there is no single-report URL", loc)
	}
	if _, ok := rep["permanent"]; ok {
		t.Errorf("a list left out came back: %s", r.body)
	}
	if created, _ := rep["createdAt"].(string); !strings.HasSuffix(created, "Z") {
		t.Errorf("createdAt %q", created)
	}

	// The same Idempotency-Key replays the 201.
	if again := m.call(http.MethodPost, dtcPath(vehicle), body, "Idempotency-Key", key); again.status != http.StatusCreated || !bytes.Equal(again.body, r.body) {
		t.Fatalf("replay: %d %s", again.status, again.body)
	}
	// A new key with the same clientReportId: 200 with the stored report,
	// unchanged, even when the body differs.
	dup := m.postDtc(vehicle, clearedReport(clientID, "2026-10-24T00:00:00Z"))
	if dup.status != http.StatusOK || dup.json(t)["id"] != id || dup.json(t)["kind"] != "read" {
		t.Fatalf("duplicate clientReportId: %d %s", dup.status, dup.body)
	}
	if loc := dup.header.Get("Location"); loc != "" {
		t.Errorf("200 has Location %q", loc)
	}

	// A read whose MIL couldn't be read, gzipped.
	var gz bytes.Buffer
	zw := gzip.NewWriter(&gz)
	_, _ = zw.Write([]byte(`{"clientReportId":"` + uuid.NewString() + `","kind":"read","readAt":"2026-10-23T15:00:00Z","mil":null,"permanent":["P0420"]}`))
	_ = zw.Close()
	z := e.do(http.MethodPost, dtcPath(vehicle), &gz, "Authorization", "Bearer "+m.token, "Content-Type", "application/json",
		"Content-Encoding", "gzip", "Idempotency-Key", uuid.NewString())
	if z.status != http.StatusCreated || z.json(t)["mil"] != nil || fmt.Sprint(z.json(t)["permanent"]) != "[P0420]" {
		t.Fatalf("gzip, unknown MIL: %d %s", z.status, z.body)
	}
	if _, ok := z.json(t)["mil"]; !ok {
		t.Errorf("mil is required in the response: %s", z.body)
	}
}

// A cleared report (mode 04) has defined contents: the codes cleared in
// stored and pending, no permanent list (mode 04 can't clear permanent
// codes) and mil null (unknown until the next read).
func TestDtcReportClearedContents(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("cleared")
	vehicle := m.newVehicle()

	r := m.postDtc(vehicle, clearedReport(uuid.New(), "2026-10-23T14:05:00Z"))
	if r.status != http.StatusCreated || r.json(t)["kind"] != "cleared" || r.json(t)["mil"] != nil || fmt.Sprint(r.json(t)["stored"]) != "[P0420 P0171]" {
		t.Fatalf("cleared: %d %s", r.status, r.body)
	}
	// Lists left out (the last read didn't read that mode) are fine.
	if r := m.postDtc(vehicle, `{"clientReportId":"`+uuid.NewString()+`","kind":"cleared","readAt":"2026-10-23T14:05:00Z","mil":null}`); r.status != http.StatusCreated {
		t.Fatalf("cleared without lists: %d %s", r.status, r.body)
	}
	for _, c := range []struct {
		name, body string
		pointers   []string
	}{
		{"permanent", `"mil":null,"permanent":[]`, []string{"/permanent"}},
		{"mil true", `"mil":true`, []string{"/mil"}},
		{"mil false", `"mil":false`, []string{"/mil"}},
		{"both", `"mil":false,"permanent":["P0420"]`, []string{"/mil", "/permanent"}},
	} {
		body := `{"clientReportId":"` + uuid.NewString() + `","kind":"cleared","readAt":"2026-10-23T14:05:00Z","stored":["P0420"],` + c.body + `}`
		r := m.postDtc(vehicle, body)
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
			t.Errorf("%s: %d %s", c.name, r.status, r.body)
			continue
		}
		var got []string
		for _, fe := range r.json(t)["errors"].([]any) {
			got = append(got, fe.(map[string]any)["pointer"].(string))
		}
		if fmt.Sprint(got) != fmt.Sprint(c.pointers) {
			t.Errorf("%s: pointers %v, want %v", c.name, got, c.pointers)
		}
	}
	// Nothing invalid was stored.
	if items := m.call(http.MethodGet, dtcPath(vehicle), "").json(t)["items"].([]any); len(items) != 2 {
		t.Errorf("stored %d reports, want 2", len(items))
	}
}

func TestDtcReportList(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("list")
	vehicle, other := m.newVehicle(), m.newVehicle()
	var want []string
	for _, at := range []string{"2026-10-23T14:00:00Z", "2026-10-23T16:00:00Z", "2026-10-23T15:00:00Z"} {
		r := m.postDtc(vehicle, readReport(uuid.New(), at))
		if r.status != http.StatusCreated {
			t.Fatal(r.status, string(r.body))
		}
		want = append(want, r.json(t)["id"].(string))
	}
	m.postDtc(other, readReport(uuid.New(), "2026-10-23T17:00:00Z"))
	want = []string{want[1], want[2], want[0]} // newest readAt first

	var got []string
	path := dtcPath(vehicle) + "?limit=2"
	for range 5 {
		r := m.call(http.MethodGet, path, "")
		if r.status != http.StatusOK {
			t.Fatalf("list: %d %s", r.status, r.body)
		}
		page := r.json(t)
		for _, it := range page["items"].([]any) {
			got = append(got, it.(map[string]any)["id"].(string))
		}
		next, _ := page["nextCursor"].(string)
		if next == "" {
			break
		}
		path = dtcPath(vehicle) + "?limit=2&cursor=" + next
	}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("pages: %v, want %v", got, want)
	}
	if r := m.call(http.MethodGet, dtcPath(vehicle)+"?limit=3", ""); r.json(t)["nextCursor"] != nil {
		t.Errorf("a full last page has a nextCursor: %s", r.body)
	}
	for _, c := range []string{"bm9wZQ", "!!", "ZDE6eDp5", "ZDE6MTox"} {
		if r := m.call(http.MethodGet, dtcPath(vehicle)+"?cursor="+c, ""); r.status != http.StatusBadRequest || r.problemCode(t) != "bad_request" {
			t.Errorf("cursor %q: %d %s", c, r.status, r.body)
		}
	}
}

func TestDtcReportVehicleScope(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m, stranger := e.member("owner"), e.member("stranger")
	vehicle := m.newVehicle()
	if r := m.postDtc(vehicle, readReport(uuid.New(), "2026-10-23T14:00:00Z")); r.status != http.StatusCreated {
		t.Fatal(r.status, string(r.body))
	}
	for _, path := range []string{dtcPath(vehicle), dtcPath(uuid.NewString()), dtcPath("not-a-uuid")} {
		for _, who := range []*member{stranger, m} {
			if who == m && path == dtcPath(vehicle) {
				continue
			}
			if r := who.postDtc(strings.TrimSuffix(strings.TrimPrefix(path, "/v1/vehicles/"), "/dtc-reports"), readReport(uuid.New(), "2026-10-23T14:00:00Z")); r.status != http.StatusNotFound || r.problemCode(t) != "not_found" {
				t.Errorf("POST %s: %d %s", path, r.status, r.body)
			}
			if r := who.call(http.MethodGet, path, ""); r.status != http.StatusNotFound || r.problemCode(t) != "not_found" {
				t.Errorf("GET %s: %d %s", path, r.status, r.body)
			}
		}
	}
}

// A cascade vehicle delete removes its reports; the vehicle's report list
// and new reports for it are then 404.
func TestDtcReportCascadeDelete(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("cascade")
	vehicle := m.newVehicle()
	clientID := uuid.New()
	if r := m.postDtc(vehicle, readReport(clientID, "2026-10-23T14:00:00Z")); r.status != http.StatusCreated {
		t.Fatal(r.status, string(r.body))
	}
	if r := m.call(http.MethodDelete, "/v1/vehicles/"+vehicle+"?cascade=true", "", "If-Match", "*"); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodGet, dtcPath(vehicle), ""); r.status != http.StatusNotFound {
		t.Errorf("list after delete: %d", r.status)
	}
	if r := m.postDtc(vehicle, readReport(uuid.New(), "2026-10-23T15:00:00Z")); r.status != http.StatusNotFound {
		t.Errorf("upload after delete: %d", r.status)
	}
	// The report is gone, so its clientReportId can be used again.
	next := m.newVehicle()
	if r := m.postDtc(next, readReport(clientID, "2026-10-23T14:00:00Z")); r.status != http.StatusCreated || r.json(t)["vehicleId"] != next {
		t.Errorf("clientReportId after the cascade: %d %s", r.status, r.body)
	}
}

// Without a database the DTC operations answer 501.
func TestDtcReportsWithoutDatabase(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	m := e.member("nodb")
	id := uuid.NewString()
	for _, r := range []response{
		m.postDtc(id, readReport(uuid.New(), "2026-10-23T14:00:00Z")),
		m.call(http.MethodGet, dtcPath(id), ""),
	} {
		if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
			t.Errorf("%d %s", r.status, r.body)
		}
	}
}

// Both DTC operations answer 503 (server_busy, service_unavailable) with
// Retry-After when Postgres is busy or unreachable, so the spec declares it
// on both rather than leaving it to default.
func TestDtcOperationsDeclare503(t *testing.T) {
	t.Parallel()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	item := doc.Paths.Find("/v1/vehicles/{vehicleId}/dtc-reports")
	if item == nil {
		t.Fatal("no DTC reports path")
	}
	for method, op := range map[string]*openapi3.Operation{"POST": item.Post, "GET": item.Get} {
		if op == nil || op.Responses.Status(http.StatusServiceUnavailable) == nil {
			t.Errorf("%s %s doesn't declare 503", method, "/v1/vehicles/{vehicleId}/dtc-reports")
		}
	}
}
