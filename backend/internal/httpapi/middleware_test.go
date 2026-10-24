package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
)

func TestRequestIDMiddleware(t *testing.T) {
	t.Parallel()
	var seen string
	h := requestID(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		seen = reqctx.RequestID(r.Context())
	}))
	for in, keep := range map[string]bool{
		"0192F1B2-7C1E-7A55-9F0E-2B1D7D2C9A14":   true, // normalised to lower case
		"":                                       false,
		"not-a-uuid":                             false,
		"{0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a14}": false,
		"urn:uuid:0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a14": false,
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.Header.Set("X-Request-Id", in)
		h.ServeHTTP(rec, req)
		got := rec.Header().Get("X-Request-Id")
		if got != seen {
			t.Errorf("%q: header %q != context %q", in, got, seen)
		}
		if keep && got != strings.ToLower(in) {
			t.Errorf("%q: not echoed, got %q", in, got)
		}
		if !keep && (got == in || len(got) != 36) {
			t.Errorf("%q: not replaced, got %q", in, got)
		}
	}
}

func TestRecoverer(t *testing.T) {
	t.Parallel()
	var logs bytes.Buffer
	logger := logging.New(&logs, slog.LevelDebug)

	h := requestID(recoverer(logger)(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic("boom for driver@example.com")
	})))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusInternalServerError || rec.Header().Get("Content-Type") != problem.ContentType {
		t.Fatalf("got %d %v", rec.Code, rec.Header())
	}
	var p map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &p)
	if p["code"] != "internal_error" || p["requestId"] == "" || strings.Contains(rec.Body.String(), "boom") {
		t.Errorf("body = %s", rec.Body)
	}
	if !strings.Contains(logs.String(), "panic serving request") || strings.Contains(logs.String(), "driver@example.com") {
		t.Errorf("logs = %s", logs.String())
	}

	// After headers are written, the status is left alone.
	h = recoverer(logger)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusAccepted)
		panic("late")
	}))
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusAccepted {
		t.Errorf("late panic: %d", rec.Code)
	}

	// http.ErrAbortHandler is re-raised for net/http to handle.
	h = recoverer(logger)(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { panic(http.ErrAbortHandler) }))
	func() {
		defer func() {
			if v := recover(); v != http.ErrAbortHandler { //nolint:errorlint // identity check of the panic value
				t.Errorf("recovered %v, want ErrAbortHandler", v)
			}
		}()
		h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))
	}()
}

func TestTimeoutMiddleware(t *testing.T) {
	t.Parallel()
	var deadline time.Time
	var ok bool
	timeout(time.Minute)(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		deadline, ok = r.Context().Deadline()
	})).ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))
	if !ok || time.Until(deadline) > time.Minute || time.Until(deadline) < 50*time.Second {
		t.Errorf("deadline = %v, %v", deadline, ok)
	}
}

func TestAccessLog(t *testing.T) {
	t.Parallel()
	var logs bytes.Buffer
	logger := logging.New(&logs, slog.LevelDebug)
	h := requestID(accessLog(logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		info := reqctx.InfoFrom(r.Context())
		info.Route, info.OperationID = "/v1/vehicles/{vehicleId}", "getVehicle"
		_ = reqctx.WithMemberID(r.Context(), "m-1")
		w.WriteHeader(http.StatusTeapot)
		_, _ = io.WriteString(w, "hi")
	})))
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/v1/vehicles/1HGCM82633A004352?email=a@b.co", nil))
	var line map[string]any
	if err := json.Unmarshal(logs.Bytes(), &line); err != nil {
		t.Fatalf("%v: %s", err, logs.String())
	}
	want := map[string]any{"msg": "request", "method": "GET", "route": "/v1/vehicles/{vehicleId}", "status": float64(418),
		"bytes": float64(2), "operation": "getVehicle", "member_id": "m-1"}
	for k, v := range want {
		if line[k] != v {
			t.Errorf("%s = %v, want %v", k, line[k], v)
		}
	}
	if strings.Contains(logs.String(), "1HGCM82633A004352") || strings.Contains(logs.String(), "a@b.co") {
		t.Errorf("raw path or query logged: %s", logs.String())
	}
}

func TestDefaultHeaders(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()
	defaultHeaders(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Cache-Control", "private, max-age=60") // operations may override
	})).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Header().Get("Cache-Control") != "private, max-age=60" || rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Errorf("headers = %v", rec.Header())
	}
}

func TestReadBodyReplacesBodyAndGetBody(t *testing.T) {
	t.Parallel()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(gz(t, `{"a":1}`)))
	req.Header.Set("Content-Encoding", "gzip")
	body, p := readBody(req, true)
	if p != nil {
		t.Fatal(p)
	}
	setBody(req, body)
	if req.Header.Get("Content-Encoding") != "" || req.ContentLength != 7 || req.Header.Get("Content-Length") != "7" {
		t.Errorf("headers not updated: %v %d", req.Header, req.ContentLength)
	}
	for range 2 {
		rc, err := req.GetBody()
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(rc)
		if string(b) != `{"a":1}` {
			t.Errorf("GetBody = %q", b)
		}
	}
	b, _ := io.ReadAll(req.Body)
	if string(b) != `{"a":1}` {
		t.Errorf("Body = %q", b)
	}

	// Content-Length over the limit is refused before reading.
	req = httptest.NewRequest(http.MethodPost, "/", strings.NewReader("{}"))
	req.ContentLength = MaxBody + 1
	if _, p := readBody(req, false); p == nil || p.Status != http.StatusRequestEntityTooLarge {
		t.Errorf("declared length: %v", p)
	}
	// A gzip body over 1 MiB compressed is refused.
	req = httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(make([]byte, MaxCompressedBody+1)))
	req.ContentLength = -1
	req.Header.Set("Content-Encoding", "gzip")
	if _, p := readBody(req, true); p == nil || p.Status != http.StatusRequestEntityTooLarge {
		t.Errorf("compressed limit: %v", p)
	}
}

func TestMapValidationErrorFallbacks(t *testing.T) {
	t.Parallel()
	if p := mapValidationError(errors.New("x")); p.Status != http.StatusBadRequest {
		t.Errorf("plain error: %+v", p)
	}
	if got := pointer([]string{"a/b", "c~d", "0"}); got != "/a~1b/c~0d/0" {
		t.Errorf("pointer = %q", got)
	}
}

func TestEmailFormat(t *testing.T) {
	t.Parallel()
	for in, ok := range map[string]bool{
		"driver@example.com":           true,
		"a.b+c@test.orangebox.invalid": true,
		"driver@localhost":             false,
		"Driver <driver@example.com>":  false,
		"<driver@example.com>":         false,
		"driver":                       false,
		"":                             false,
		" driver@example.com":          false,
	} {
		if err := validateEmailFormat(in); (err == nil) != ok {
			t.Errorf("validateEmailFormat(%q) = %v", in, err)
		}
	}
}

func TestResponseErrorHandler(t *testing.T) {
	t.Parallel()
	var logs bytes.Buffer
	e := errorHandlers{logger: logging.New(&logs, slog.LevelDebug)}
	req := httptest.NewRequest(http.MethodGet, "/", nil).WithContext(reqctx.WithRequestID(context.Background(), "rid"))

	rec := httptest.NewRecorder()
	e.response(rec, req, errors.New("db said: driver@example.com is bad"))
	if rec.Code != 500 || strings.Contains(rec.Body.String(), "driver@") || strings.Contains(logs.String(), "driver@") {
		t.Errorf("500: %d %s / %s", rec.Code, rec.Body, logs.String())
	}
	rec = httptest.NewRecorder()
	e.response(rec, req, api.ErrNotImplemented)
	if rec.Code != http.StatusNotImplemented {
		t.Errorf("501: %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	e.response(rec, req, problem.New(http.StatusConflict, "idempotency_in_flight", "x"))
	if rec.Code != http.StatusConflict {
		t.Errorf("problem: %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	e.request(rec, req, errors.New("bad value driver@example.com"))
	if rec.Code != http.StatusBadRequest || strings.Contains(rec.Body.String(), "driver@") {
		t.Errorf("request error: %d %s", rec.Code, rec.Body)
	}
}

func TestNewRouterRequiresDeps(t *testing.T) {
	t.Parallel()
	if _, err := NewRouter(Deps{}); err == nil {
		t.Error("NewRouter accepted empty deps")
	}
}
