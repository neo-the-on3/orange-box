package httpapi

import (
	"bytes"
	"compress/gzip"
	"errors"
	"io"
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
	if elapsed > 3*time.Second {
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

	second := &countingReader{r: strings.NewReader(telemetryBody("2026-10-23T14:05:00Z"))}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, uploadRequest(second, "Authorization", "Bearer "+token))
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
