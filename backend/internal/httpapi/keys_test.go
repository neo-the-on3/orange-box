package httpapi

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func validTrip() string {
	return `{"clientTripId":"` + uuid.NewString() + `","vehicleId":"v1","state":"completed",` +
		`"startedAt":"2026-10-23T14:05:00Z","snapshotAt":"2026-10-23T14:05:00Z"}`
}

// Regression for the third #163 review: a case variant of a declared
// property ("TRIPS") was an unknown property to kin-openapi but was decoded
// into Trips by encoding/json (case-insensitive, last key wins), so 300
// invalid trips reached the handler. Case variants and duplicate names are
// now 422, and bodies are decoded with encoding/json/v2 (exact names).
func TestCaseVariantAndDuplicateKeys(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("keys@test.orangebox.invalid")
	post := func(body string) response {
		return e.do(http.MethodPost, "/v1/trips/batch", strings.NewReader(body),
			"Content-Type", "application/json", "Idempotency-Key", uuid.NewString(), "Authorization", "Bearer "+token)
	}
	if r := post(`{"trips":[` + validTrip() + `]}`); r.status != http.StatusNotImplemented {
		t.Fatalf("valid batch: %d %s", r.status, r.body)
	}

	bad := `{"state":"hacked","durationS":-5,"vehicleId":"` + strings.Repeat("v", 5000) + `"}`
	for name, tc := range map[string]struct{ body, pointer, detail string }{
		"top-level case variant": {`{"trips":[` + validTrip() + `],"TRIPS":[` + strings.TrimSuffix(strings.Repeat(bad+",", 300), ",") + `]}`,
			"/TRIPS", `must be spelled \"trips\"`},
		"nested case variant": {`{"trips":[` + strings.TrimSuffix(validTrip(), "}") + `,"DurationS":-5}]}`,
			"/trips/0/DurationS", `must be spelled \"durationS\"`},
		"duplicate": {`{"trips":[` + validTrip() + `],"trips":[` + bad + `]}`, "/trips", "duplicate"},
		"nested duplicate": {`{"trips":[` + strings.TrimSuffix(validTrip(), "}") + `,"state":"hacked"}]}`,
			"/trips/0/state", "duplicate"},
	} {
		r := post(tc.body)
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" ||
			!strings.Contains(string(r.body), `"pointer":"`+tc.pointer+`"`) || !strings.Contains(string(r.body), tc.detail) {
			t.Errorf("%s: %d %s", name, r.status, r.body)
		}
		if strings.Contains(string(r.body), "hacked") {
			t.Errorf("%s: value echoed", name)
		}
	}
}

// Non-upload bodies have a 16 KiB size limit and a 4096-value budget.
func TestNonUploadBodyLimits(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	pad := func(n int) string {
		return `{"grantType":"email_otp","email":"a@example.com","code":"123456","pad":[` +
			strings.TrimSuffix(strings.Repeat("0,", n), ",") + `]}`
	}
	if b := pad(MaxBodyValues); len(b) > MaxBody {
		t.Fatalf("test body is %d bytes", len(b))
	}
	r := e.postJSON("/v1/auth/token", pad(MaxBodyValues))
	if r.status != http.StatusRequestEntityTooLarge || r.problemCode(t) != "batch_too_large" {
		t.Errorf("over the value budget: %d %s", r.status, r.body)
	}
	r = e.postJSON("/v1/auth/token", `{"email":"`+strings.Repeat("a", MaxBody)+`"}`)
	if r.status != http.StatusRequestEntityTooLarge {
		t.Errorf("over the size limit: %d %s", r.status, r.body)
	}
}

// With every body slot taken, another body waits UploadQueueWait and then
// gets 503 server_busy without being read; bodiless requests aren't limited.
func TestBodyConcurrencyLimit(t *testing.T) {
	t.Parallel()
	e := newTestEnvWith(t, true, func(d *Deps) {
		d.BodyConcurrency = 1
		d.UploadQueueWait = 50 * time.Millisecond
	})

	pr, pw := io.Pipe()
	slow := &countingReader{r: pr, first: make(chan struct{})}
	done := make(chan int)
	go func() {
		req := httptest.NewRequest(http.MethodPost, "/v1/auth/otp", slow)
		req.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		e.h.ServeHTTP(rec, req)
		done <- rec.Code
	}()
	select {
	case <-slow.first:
	case <-time.After(5 * time.Second):
		t.Fatal("first request never started reading its body")
	}

	second := &countingReader{r: strings.NewReader(`{"email":"b@test.orangebox.invalid"}`)}
	req := httptest.NewRequest(http.MethodPost, "/v1/auth/otp", second)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, req)
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), `"server_busy"`) ||
		rec.Header().Get("Retry-After") != "1" {
		t.Errorf("second body: %d %v %s", rec.Code, rec.Header(), rec.Body)
	}
	if second.n.Load() != 0 {
		t.Error("busy request's body was read")
	}
	if r := e.do(http.MethodGet, "/v1/health", nil); r.status != http.StatusOK {
		t.Errorf("health while bodies busy: %d", r.status)
	}

	_, _ = io.WriteString(pw, `{"email":"a@test.orangebox.invalid"}`)
	_ = pw.Close()
	if code := <-done; code != http.StatusAccepted {
		t.Errorf("first request: %d", code)
	}
	if r := e.postJSON("/v1/auth/otp", `{"email":"c@test.orangebox.invalid"}`); r.status != http.StatusAccepted {
		t.Errorf("after release: %d %s", r.status, r.body)
	}
}
