package objstore

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestValidateKey(t *testing.T) {
	t.Parallel()
	for _, k := range []string{"a", "members/0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a14/trips/x/b.json.gz", "A_b-c.d"} {
		if err := ValidateKey(k); err != nil {
			t.Errorf("%q: %v", k, err)
		}
	}
	for _, k := range []string{"", "/a", "a/", "a//b", "../a", "a/./b", "a b", "a\\b", "ä", strings.Repeat("a", 1025)} {
		if err := ValidateKey(k); !errors.Is(err, ErrInvalidKey) {
			t.Errorf("%q: %v, want ErrInvalidKey", k, err)
		}
	}
}

// conformance runs the Store contract against s.
func conformance(t *testing.T, s Store) {
	t.Helper()
	ctx := context.Background()
	const key = "members/m1/trips/t1/b1.json.gz"
	if _, err := s.Get(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("get missing: %v", err)
	}
	if err := s.Delete(ctx, key); err != nil {
		t.Fatalf("delete missing: %v", err)
	}
	body := []byte("\x1f\x8b binary \x00 body")
	if err := s.Put(ctx, key, body); err != nil {
		t.Fatalf("put: %v", err)
	}
	body[0] = 'X' // the store must not keep the caller's slice
	got, err := s.Get(ctx, key)
	if err != nil || string(got) != "\x1f\x8b binary \x00 body" {
		t.Fatalf("get = %q, %v", got, err)
	}
	if err := s.Put(ctx, key, []byte("v2")); err != nil {
		t.Fatalf("overwrite: %v", err)
	}
	if got, _ := s.Get(ctx, key); string(got) != "v2" {
		t.Fatalf("after overwrite = %q", got)
	}
	if err := s.Put(ctx, key+"x", nil); err != nil {
		t.Fatalf("put empty: %v", err)
	}
	if got, err := s.Get(ctx, key+"x"); err != nil || len(got) != 0 {
		t.Fatalf("get empty = %q, %v", got, err)
	}
	if err := s.Delete(ctx, key); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := s.Get(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("get deleted: %v", err)
	}
	for _, bad := range []string{"../escape", ""} {
		if err := s.Put(ctx, bad, nil); !errors.Is(err, ErrInvalidKey) {
			t.Errorf("put %q: %v", bad, err)
		}
		if _, err := s.Get(ctx, bad); !errors.Is(err, ErrInvalidKey) {
			t.Errorf("get %q: %v", bad, err)
		}
		if err := s.Delete(ctx, bad); !errors.Is(err, ErrInvalidKey) {
			t.Errorf("delete %q: %v", bad, err)
		}
	}
}

func TestMemory(t *testing.T) {
	t.Parallel()
	m := NewMemory()
	conformance(t, m)
	if k := m.Keys(); len(k) != 1 {
		t.Errorf("keys = %v", k)
	}
}

func TestDir(t *testing.T) {
	t.Parallel()
	d, err := NewDir(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	conformance(t, d)
	if _, err := NewDir(""); err == nil {
		t.Error("empty dir accepted")
	}
}

// fakeS3 is a minimal path-style S3 server: PUT, GET and DELETE of
// /<bucket>/<key>, signed requests only.
type fakeS3 struct {
	bucket  string
	mu      sync.Mutex
	objects map[string][]byte
	fail    bool // answer 500 to everything
}

func (f *fakeS3) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !strings.HasPrefix(r.Header.Get("Authorization"), "AWS4-HMAC-SHA256 Credential=test-key/") {
		http.Error(w, "unsigned", http.StatusForbidden)
		return
	}
	if f.fail {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = io.WriteString(w, `<?xml version="1.0"?><Error><Code>InternalError</Code><Message>boom</Message></Error>`)
		return
	}
	key, ok := strings.CutPrefix(r.URL.Path, "/"+f.bucket+"/")
	if !ok {
		http.Error(w, "bucket", http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch r.Method {
	case http.MethodPut:
		b, _ := io.ReadAll(r.Body)
		f.objects[key] = b
		w.Header().Set("ETag", `"x"`)
	case http.MethodGet:
		b, ok := f.objects[key]
		if !ok {
			w.Header().Set("Content-Type", "application/xml")
			w.WriteHeader(http.StatusNotFound)
			_, _ = io.WriteString(w, `<?xml version="1.0"?><Error><Code>NoSuchKey</Code><Message>missing</Message></Error>`)
			return
		}
		_, _ = w.Write(b)
	case http.MethodDelete:
		delete(f.objects, key)
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func newFakeS3(t *testing.T) (*fakeS3, *S3) {
	t.Helper()
	f := &fakeS3{bucket: "telemetry", objects: map[string][]byte{}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	s, err := NewS3(S3Options{Endpoint: srv.URL, Bucket: "telemetry", AccessKeyID: "test-key", SecretAccessKey: "test-secret", HTTPClient: srv.Client(), MaxAttempts: 1})
	if err != nil {
		t.Fatal(err)
	}
	return f, s
}

func TestS3(t *testing.T) {
	t.Parallel()
	f, s := newFakeS3(t)
	conformance(t, s)
	if len(f.objects) != 1 {
		t.Errorf("server objects = %v", f.objects)
	}
}

func TestS3Errors(t *testing.T) {
	t.Parallel()
	f, s := newFakeS3(t)
	f.fail = true
	ctx := context.Background()
	if err := s.Put(ctx, "k", []byte("x")); err == nil {
		t.Error("put: no error")
	}
	if _, err := s.Get(ctx, "k"); err == nil || errors.Is(err, ErrNotFound) {
		t.Errorf("get: %v", err)
	}
	if err := s.Delete(ctx, "k"); err == nil {
		t.Error("delete: no error")
	}
}

func TestNewS3Options(t *testing.T) {
	t.Parallel()
	ok := S3Options{Endpoint: "https://acct.r2.cloudflarestorage.com", Bucket: "b", AccessKeyID: "k", SecretAccessKey: "s"}
	if _, err := NewS3(ok); err != nil {
		t.Fatal(err)
	}
	for name, o := range map[string]S3Options{
		"no endpoint":   {Bucket: "b", AccessKeyID: "k", SecretAccessKey: "s"},
		"bad scheme":    {Endpoint: "ftp://x", Bucket: "b", AccessKeyID: "k", SecretAccessKey: "s"},
		"no bucket":     {Endpoint: ok.Endpoint, AccessKeyID: "k", SecretAccessKey: "s"},
		"no access key": {Endpoint: ok.Endpoint, Bucket: "b", SecretAccessKey: "s"},
		"no secret":     {Endpoint: ok.Endpoint, Bucket: "b", AccessKeyID: "k"},
	} {
		if _, err := NewS3(o); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
