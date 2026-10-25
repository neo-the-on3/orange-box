package httpapi

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
)

// countingReader counts the bytes the server pulled from the request body.
type countingReader struct {
	r     io.Reader
	n     atomic.Int64
	first chan struct{} // closed on the first Read, if set
	once  atomic.Bool
}

func (c *countingReader) Read(p []byte) (int, error) {
	if c.first != nil && c.once.CompareAndSwap(false, true) {
		close(c.first)
	}
	n, err := c.r.Read(p)
	c.n.Add(int64(n))
	return n, err
}

// gzipBomb compresses size zero bytes; the result is about size/1000.
func gzipBomb(t *testing.T, size int64) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw, _ := gzip.NewWriterLevel(&buf, gzip.DefaultCompression)
	if _, err := io.CopyN(zw, zeroReader{}, size); err != nil {
		t.Fatal(err)
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) { clear(p); return len(p), nil }

func uploadRequest(body io.Reader, headers ...string) *http.Request {
	req := httptest.NewRequest(http.MethodPost, "/v1/telemetry/batches", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", uuid.NewString())
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	return req
}

// Regression for the PR #163 review: an unauthenticated gzip bomb was read
// and inflated before the bearer check. Now not a single byte is read.
func TestGzipBombUnauthenticated(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	bomb := gzipBomb(t, 64<<20)
	for name, authz := range map[string]string{"none": "", "bad token": "Bearer nope"} {
		body := &countingReader{r: bytes.NewReader(bomb)}
		req := uploadRequest(body, "Content-Encoding", "gzip")
		if authz != "" {
			req.Header.Set("Authorization", authz)
		}
		rec := httptest.NewRecorder()
		e.h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Errorf("%s: status %d %s", name, rec.Code, rec.Body)
		}
		if n := body.n.Load(); n != 0 {
			t.Errorf("%s: read %d body bytes before authenticating", name, n)
		}
	}
}

// An authenticated bomb (~64 KiB inflating to 64 MiB) is cut off once the
// inflated stream passes MaxUploadBody: only a few KiB of the compressed body
// are consumed, allocation stays bounded and the answer is fast. Not
// parallel, so the allocation figure is this test's own.
func TestGzipBombAuthenticated(t *testing.T) {
	e := newTestEnv(t, true)
	token, _ := e.signIn("bomb@test.orangebox.invalid")
	bomb := gzipBomb(t, 64<<20)
	if len(bomb) > MaxCompressedBody {
		t.Fatalf("bomb is %d bytes, over the compressed limit", len(bomb))
	}

	body := &countingReader{r: bytes.NewReader(bomb)}
	req := uploadRequest(body, "Content-Encoding", "gzip", "Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	start := time.Now()
	e.h.ServeHTTP(rec, req)
	elapsed := time.Since(start)
	runtime.ReadMemStats(&after)

	if rec.Code != http.StatusRequestEntityTooLarge || !strings.Contains(rec.Body.String(), `"batch_too_large"`) {
		t.Fatalf("status %d %s", rec.Code, rec.Body)
	}
	// Inflating MaxUploadBody+1 bytes of zeros needs ~8 KiB of input; allow
	// for read-ahead but nowhere near the whole bomb.
	if n := body.n.Load(); n > 32<<10 {
		t.Errorf("consumed %d of %d compressed bytes", n, len(bomb))
	}
	// The body buffer's growth costs at most ~2×MaxUploadBody; 64 MiB or
	// more would mean the limit isn't applied to the inflated stream.
	if alloc := after.TotalAlloc - before.TotalAlloc; alloc > 24<<20 {
		t.Errorf("allocated %d MiB", alloc>>20)
	}
	if elapsed > bombTimeLimit {
		t.Errorf("took %v", elapsed)
	}
}

// With every upload slot taken, a further upload waits UploadQueueWait and
// then gets 503 uploads_busy with Retry-After, without its body being read.
func TestUploadConcurrencyLimit(t *testing.T) {
	t.Parallel()
	e := newTestEnvWith(t, true, func(d *Deps) {
		d.UploadConcurrency = 1
		d.UploadQueueWait = 50 * time.Millisecond
	})
	token, _ := e.signIn("busy@test.orangebox.invalid")
	other, _ := e.signIn("busy2@test.orangebox.invalid")

	// The first upload takes the slot and then stalls mid-body.
	pr, pw := io.Pipe()
	slow := &countingReader{r: pr, first: make(chan struct{})}
	done := make(chan int)
	go func() {
		rec := httptest.NewRecorder()
		e.h.ServeHTTP(rec, uploadRequest(slow, "Authorization", "Bearer "+token))
		done <- rec.Code
	}()
	select {
	case <-slow.first:
	case <-time.After(5 * time.Second):
		t.Fatal("first upload never started reading its body")
	}

	// Another member's upload finds no free slot: 503.
	second := &countingReader{r: strings.NewReader(telemetryBody("2026-10-23T14:05:00Z"))}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, uploadRequest(second, "Authorization", "Bearer "+other))
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), `"uploads_busy"`) ||
		rec.Header().Get("Retry-After") == "" {
		t.Errorf("second upload: %d %v %s", rec.Code, rec.Header(), rec.Body)
	}
	if second.n.Load() != 0 {
		t.Errorf("busy upload's body was read")
	}
	// Non-upload operations aren't limited.
	if r := e.do(http.MethodGet, "/v1/health", nil); r.status != http.StatusOK {
		t.Errorf("health while uploads busy: %d", r.status)
	}

	// Finishing the first upload frees the slot.
	_, _ = io.WriteString(pw, telemetryBody("2026-10-23T14:05:00Z"))
	_ = pw.Close()
	if code := <-done; code != http.StatusNotImplemented {
		t.Errorf("first upload: %d", code)
	}
	rec = httptest.NewRecorder()
	e.h.ServeHTTP(rec, uploadRequest(strings.NewReader(telemetryBody("2026-10-23T14:05:00Z")), "Authorization", "Bearer "+token))
	if rec.Code != http.StatusNotImplemented {
		t.Errorf("upload after release: %d %s", rec.Code, rec.Body)
	}
}

func TestLimitReader(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		in   int
		want error
	}{{0, nil}, {9, nil}, {10, nil}, {11, errTooLarge}, {1000, errTooLarge}} {
		var buf bytes.Buffer
		_, err := buf.ReadFrom(&limitReader{r: strings.NewReader(strings.Repeat("x", tc.in)), n: 10})
		if !errors.Is(err, tc.want) {
			t.Errorf("%d bytes: err %v, want %v", tc.in, err, tc.want)
		}
		if buf.Len() > 11 {
			t.Errorf("%d bytes: buffered %d", tc.in, buf.Len())
		}
	}
}

// gzipJSON compresses prefix + n×item (comma separated) + suffix.
func gzipJSON(t *testing.T, prefix, item, suffix string, n int) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	_, _ = io.WriteString(zw, prefix)
	chunk := strings.Repeat(item+",", 4096)
	for ; n > 4096; n -= 4096 {
		_, _ = io.WriteString(zw, chunk)
	}
	_, _ = io.WriteString(zw, strings.Repeat(item+",", n-1)+item+suffix)
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// measure serves req once and returns the recorder, the bytes allocated and
// the time taken.
func measure(h http.Handler, req *http.Request) (*httptest.ResponseRecorder, uint64, time.Duration) {
	rec := httptest.NewRecorder()
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	start := time.Now()
	h.ServeHTTP(rec, req)
	elapsed := time.Since(start)
	runtime.ReadMemStats(&after)
	return rec, after.TotalAlloc - before.TotalAlloc, elapsed
}

// Regression for the second PR #163 review: kin-openapi's MultiError
// validated every item of `{"trips":[{},{},…]}` regardless of maxItems
// (1.36 GB and 16 s for 1 MiB inflated). The pre-scan stops at item 101.
// Not parallel, so the allocation figures are the test's own.
func TestArrayBombMaxItems(t *testing.T) {
	e := newTestEnv(t, true)
	token, _ := e.signIn("arraybomb@test.orangebox.invalid")
	// ~8 MiB of `{},` inflated from ~16 KB of gzip.
	bomb := gzipJSON(t, `{"trips":[`, `{}`, `]}`, (MaxUploadBody-32)/3)
	if len(bomb) > MaxCompressedBody {
		t.Fatalf("bomb is %d bytes", len(bomb))
	}
	req := httptest.NewRequest(http.MethodPost, "/v1/trips/batch", bytes.NewReader(bomb))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Content-Encoding", "gzip")
	req.Header.Set("Idempotency-Key", uuid.NewString())
	req.Header.Set("Authorization", "Bearer "+token)
	rec, alloc, elapsed := measure(e.h, req)
	t.Logf("8 MiB of {} items (%d B gzip): %d, %d MiB allocated, %v", len(bomb), rec.Code, alloc>>20, elapsed)

	if rec.Code != http.StatusUnprocessableEntity ||
		!strings.Contains(rec.Body.String(), `{"pointer":"/trips","detail":"must have at most 100 items"}`) {
		t.Fatalf("status %d %s", rec.Code, rec.Body)
	}
	// The inflated body buffer is ~16 MiB of allocations; decoding the
	// 2.8M objects would be hundreds of MiB.
	if alloc > 40<<20 {
		t.Errorf("allocated %d MiB", alloc>>20)
	}
	if elapsed > bombTimeLimit {
		t.Errorf("took %v", elapsed)
	}
}

// Arrays the schema leaves unbounded (here under a property the schema
// doesn't declare) are covered by the MaxJSONValues budget: over it is 413
// before decoding; under it, fail-fast validation keeps the cost
// proportional to the body. A trip's dtc lists, unbounded in the spec, have
// a server-side maxItems (limits.go), so prescan stops them early.
func TestArrayBombUnbounded(t *testing.T) {
	e := newTestEnv(t, true)
	token, _ := e.signIn("nestedbomb@test.orangebox.invalid")
	post := func(n int) (*httptest.ResponseRecorder, uint64, time.Duration) {
		body := gzipJSON(t, `{"trips":[{"x":{"y":[`, `{}`, `]}}]}`, n)
		req := httptest.NewRequest(http.MethodPost, "/v1/trips/batch", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Content-Encoding", "gzip")
		req.Header.Set("Idempotency-Key", uuid.NewString())
		req.Header.Set("Authorization", "Bearer "+token)
		return measure(e.h, req)
	}

	rec, alloc, elapsed := post((MaxUploadBody - 64) / 3)
	t.Logf("over budget: %d, %d MiB allocated, %v", rec.Code, alloc>>20, elapsed)
	if rec.Code != http.StatusRequestEntityTooLarge || !strings.Contains(rec.Body.String(), `"batch_too_large"`) {
		t.Fatalf("over budget: status %d %s", rec.Code, rec.Body)
	}
	if alloc > 40<<20 || elapsed > bombTimeLimit {
		t.Errorf("over budget: allocated %d MiB in %v", alloc>>20, elapsed)
	}

	rec, alloc, elapsed = post(MaxJSONValues - 16)
	t.Logf("under budget: %d, %d MiB allocated, %v", rec.Code, alloc>>20, elapsed)
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("under budget: status %d %s", rec.Code, rec.Body)
	}
	// Decoding 500k empty objects into map[string]any is ~100 B each.
	if alloc > 160<<20 || elapsed > bombTimeLimit {
		t.Errorf("under budget: allocated %d MiB in %v", alloc>>20, elapsed)
	}
}

// dtc.stored has a server-side maxItems: prescan reports the 129th code
// without decoding the rest.
func TestArrayBombDtcCodes(t *testing.T) {
	e := newTestEnv(t, true)
	token, _ := e.signIn("dtcbomb@test.orangebox.invalid")
	body := gzipJSON(t, `{"trips":[{"dtc":{"stored":[`, `"P0420"`, `]}}]}`, (MaxUploadBody-64)/8)
	req := httptest.NewRequest(http.MethodPost, "/v1/trips/batch", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Content-Encoding", "gzip")
	req.Header.Set("Idempotency-Key", uuid.NewString())
	req.Header.Set("Authorization", "Bearer "+token)
	rec, alloc, elapsed := measure(e.h, req)
	t.Logf("1M DTC codes: %d, %d MiB allocated, %v", rec.Code, alloc>>20, elapsed)
	if rec.Code != http.StatusUnprocessableEntity ||
		!strings.Contains(rec.Body.String(), `{"pointer":"/trips/0/dtc/stored","detail":"must have at most 128 items"}`) {
		t.Fatalf("status %d %s", rec.Code, rec.Body)
	}
	if alloc > 40<<20 || elapsed > bombTimeLimit {
		t.Errorf("allocated %d MiB in %v", alloc>>20, elapsed)
	}
}

// A member gets one upload at a time: a second one while the first is in
// flight is 429 upload_in_progress, without its body being read.
func TestUploadOnePerMember(t *testing.T) {
	t.Parallel()
	e := newTestEnvWith(t, true, func(d *Deps) { d.UploadConcurrency = 4 })
	token, _ := e.signIn("one@test.orangebox.invalid")

	pr, pw := io.Pipe()
	slow := &countingReader{r: pr, first: make(chan struct{})}
	done := make(chan int)
	go func() {
		rec := httptest.NewRecorder()
		e.h.ServeHTTP(rec, uploadRequest(slow, "Authorization", "Bearer "+token))
		done <- rec.Code
	}()
	select {
	case <-slow.first:
	case <-time.After(5 * time.Second):
		t.Fatal("first upload never started reading its body")
	}

	second := &countingReader{r: strings.NewReader(telemetryBody("2026-10-23T14:05:00Z"))}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, uploadRequest(second, "Authorization", "Bearer "+token))
	if rec.Code != http.StatusTooManyRequests || !strings.Contains(rec.Body.String(), `"upload_in_progress"`) ||
		rec.Header().Get("Retry-After") == "" {
		t.Errorf("second upload: %d %v %s", rec.Code, rec.Header(), rec.Body)
	}
	if second.n.Load() != 0 {
		t.Errorf("second upload's body was read")
	}

	_, _ = io.WriteString(pw, telemetryBody("2026-10-23T14:05:00Z"))
	_ = pw.Close()
	if code := <-done; code != http.StatusNotImplemented {
		t.Errorf("first upload: %d", code)
	}
	rec = httptest.NewRecorder()
	e.h.ServeHTTP(rec, uploadRequest(strings.NewReader(telemetryBody("2026-10-23T14:05:00Z")), "Authorization", "Bearer "+token))
	if rec.Code != http.StatusNotImplemented {
		t.Errorf("upload after the first finished: %d %s", rec.Code, rec.Body)
	}
}

// A stalled upload body is cut off after UploadReadTimeout with 408 and its
// slot is freed, instead of holding it for the server's 30 s ReadTimeout.
func TestUploadReadDeadline(t *testing.T) {
	t.Parallel()
	e := newTestEnvWith(t, true, func(d *Deps) {
		d.UploadConcurrency = 1
		d.UploadReadTimeout = 300 * time.Millisecond
	})
	token, _ := e.signIn("stall@test.orangebox.invalid")

	conn, err := net.Dial("tcp", e.srv.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	// Headers promise 1000 bytes; only a few arrive.
	_, _ = io.WriteString(conn, "POST /v1/telemetry/batches HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"+
		"Idempotency-Key: "+uuid.NewString()+"\r\nAuthorization: Bearer "+token+"\r\nContent-Length: 1000\r\n\r\n{\"clie")
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	start := time.Now()
	res, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusRequestTimeout {
		t.Errorf("status %d", res.StatusCode)
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Errorf("took %v", d)
	}

	// The slot is free again.
	r := e.do(http.MethodPost, "/v1/telemetry/batches", strings.NewReader(telemetryBody("2026-10-23T14:05:00Z")),
		"Content-Type", "application/json", "Idempotency-Key", uuid.NewString(), "Authorization", "Bearer "+token)
	if r.status != http.StatusNotImplemented {
		t.Errorf("upload after timeout: %d %s", r.status, r.body)
	}
}

// bombTimeLimit only catches a pathological (e.g. quadratic) slowdown:
// the allocation bounds are what these tests check. Wall-clock time
// varies too much on a loaded runner under -race (4.7 s seen for ~1.5 s
// of work) to be a budget.
const bombTimeLimit = 30 * time.Second
