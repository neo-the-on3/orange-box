package httpapi

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
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

// slowSender opens a raw connection that announces a body of n bytes and
// then sends one byte every interval until ctx ends: a slowloris client.
func slowSender(ctx context.Context, t *testing.T, addr, path, auth string, n int, interval time.Duration) {
	t.Helper()
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Error(err)
		return
	}
	hdr := "POST " + path + " HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: " + strconv.Itoa(n) + "\r\n"
	if auth != "" {
		hdr += "Authorization: Bearer " + auth + "\r\nIdempotency-Key: " + uuid.NewString() + "\r\n"
	}
	if _, err := io.WriteString(conn, hdr+"\r\n{"); err != nil {
		t.Error(err)
	}
	go func() {
		defer conn.Close()
		tick := time.NewTicker(interval)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				if _, err := io.WriteString(conn, " "); err != nil {
					return
				}
			}
		}
	}()
}

// Regression for the fourth #163 review: the body slot was taken before the
// body was read, so 32 anonymous connections dripping 1 byte/s made sign-ins
// and POST /v1/vehicles return 503. Bodies are now read before a slot is
// taken, and anonymous and member requests have separate pools.
func TestSlowAnonymousSendersDontStarve(t *testing.T) {
	t.Parallel()
	e := newTestEnvWith(t, true, func(d *Deps) {
		d.BodyConcurrency = 2
		d.AnonBodyConcurrency = 2
		d.UploadQueueWait = 200 * time.Millisecond
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	addr := e.srv.Listener.Addr().String()
	for range 32 {
		slowSender(ctx, t, addr, "/v1/auth/token", "", 1000, 100*time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond) // every slow body is now being read

	for i := range 8 {
		token, _ := e.signIn("slow" + strconv.Itoa(i) + "@test.orangebox.invalid") // fails the test on any non-2xx
		r := e.do(http.MethodPost, "/v1/vehicles", strings.NewReader(`{"make":"Hyundai","model":"i20","fuelType":"gasoline"}`),
			"Content-Type", "application/json", "Idempotency-Key", uuid.NewString(), "Authorization", "Bearer "+token)
		if r.status != http.StatusNotImplemented {
			t.Errorf("POST /v1/vehicles during the slow senders: %d %s", r.status, r.body)
		}
	}
}

// Anonymous requests in flight (reading included) are capped, which bounds
// buffered anonymous bodies. Beyond the cap an anonymous request gets 503
// server_busy at once, without its body being read; members are unaffected.
func TestAnonymousReadCap(t *testing.T) {
	t.Parallel()
	e := newTestEnvWith(t, true, func(d *Deps) { d.AnonReadConcurrency = 2 })
	token, _ := e.signIn("member@test.orangebox.invalid")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	addr := e.srv.Listener.Addr().String()
	for range 2 {
		slowSender(ctx, t, addr, "/v1/auth/otp", "", 1000, 100*time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond)

	body := &countingReader{r: strings.NewReader(`{"email":"x@test.orangebox.invalid"}`)}
	req := httptest.NewRequest(http.MethodPost, "/v1/auth/otp", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, req)
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), `"server_busy"`) ||
		rec.Header().Get("Retry-After") != "1" {
		t.Errorf("anonymous beyond the cap: %d %v %s", rec.Code, rec.Header(), rec.Body)
	}
	if body.n.Load() != 0 {
		t.Error("body read beyond the cap")
	}
	// A member's write doesn't count against the anonymous cap.
	r := e.do(http.MethodPost, "/v1/vehicles", strings.NewReader(`{"make":"Hyundai","model":"i20","fuelType":"gasoline"}`),
		"Content-Type", "application/json", "Idempotency-Key", uuid.NewString(), "Authorization", "Bearer "+token)
	if r.status != http.StatusNotImplemented {
		t.Errorf("member during the anonymous cap: %d %s", r.status, r.body)
	}
	cancel()
}

// With every processing slot of a pool taken, a further request waits
// UploadQueueWait and then gets 503 server_busy.
func TestBodyLimiter(t *testing.T) {
	t.Parallel()
	l := newBodyLimiter(1, 20*time.Millisecond)
	release, p := l.acquire(context.Background())
	if p != nil {
		t.Fatal(p.Detail)
	}
	if _, p := l.acquire(context.Background()); p == nil || p.Status != http.StatusServiceUnavailable ||
		p.Code != "server_busy" || p.RetryAfter != 1 {
		t.Errorf("full pool: %+v", p)
	}
	release()
	if r2, p := l.acquire(context.Background()); p != nil {
		t.Errorf("after release: %+v", p)
	} else {
		r2()
	}
}
