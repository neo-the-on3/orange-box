package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
	"github.com/neo-the-on3/orange-box/backend/internal/testutil"
)

var testCode = testutil.RandomTestCode()

// syncBuffer is a goroutine-safe log sink.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.String()
}

type testEnv struct {
	t      *testing.T
	srv    *httptest.Server
	logs   *syncBuffer
	tokens *auth.Tokens
}

func newTestEnv(t *testing.T, testMode bool) *testEnv {
	t.Helper()
	key, kid, err := auth.GenerateDevKey()
	if err != nil {
		t.Fatal(err)
	}
	tokens, err := auth.NewTokens("orange-box", kid, key)
	if err != nil {
		t.Fatal(err)
	}
	logs := &syncBuffer{}
	h, err := NewRouter(Deps{
		Logger:         logging.New(logs, slog.LevelDebug),
		Tokens:         tokens,
		Members:        auth.NewMemoryMembers(),
		Codes:          auth.NewPendingCodes(),
		TestMode:       testMode,
		TestCode:       testCode,
		RequestTimeout: 5 * time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return &testEnv{t: t, srv: srv, logs: logs, tokens: tokens}
}

type response struct {
	status int
	header http.Header
	body   []byte
}

func (r response) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(r.body, &m); err != nil {
		t.Fatalf("decode %q: %v", r.body, err)
	}
	return m
}

func (r response) problemCode(t *testing.T) string {
	t.Helper()
	if ct := r.header.Get("Content-Type"); ct != "application/problem+json" {
		t.Fatalf("Content-Type = %q, want application/problem+json (body %s)", ct, r.body)
	}
	m := r.json(t)
	if m["requestId"] != r.header.Get("X-Request-Id") || m["requestId"] == "" {
		t.Errorf("requestId %v doesn't match X-Request-Id %q", m["requestId"], r.header.Get("X-Request-Id"))
	}
	if int(m["status"].(float64)) != r.status {
		t.Errorf("problem status %v != HTTP status %d", m["status"], r.status)
	}
	code, _ := m["code"].(string)
	return code
}

func (e *testEnv) do(method, path string, body io.Reader, headers ...string) response {
	e.t.Helper()
	req, err := http.NewRequest(method, e.srv.URL+path, body)
	if err != nil {
		e.t.Fatal(err)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := e.srv.Client().Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	b, err := io.ReadAll(res.Body)
	if err != nil {
		e.t.Fatal(err)
	}
	return response{status: res.StatusCode, header: res.Header, body: b}
}

func (e *testEnv) postJSON(path, body string, headers ...string) response {
	e.t.Helper()
	return e.do(http.MethodPost, path, strings.NewReader(body), append([]string{"Content-Type", "application/json"}, headers...)...)
}

// signIn runs the test-mode flow and returns the access token and member id.
func (e *testEnv) signIn(email string) (string, string) {
	e.t.Helper()
	if r := e.postJSON("/v1/auth/otp", `{"email":"`+email+`"}`); r.status != http.StatusAccepted {
		e.t.Fatalf("otp: %d %s", r.status, r.body)
	}
	r := e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"`+email+`","code":"`+testCode+`"}`)
	if r.status != http.StatusOK {
		e.t.Fatalf("token: %d %s", r.status, r.body)
	}
	m := r.json(e.t)
	return m["accessToken"].(string), m["memberId"].(string)
}
