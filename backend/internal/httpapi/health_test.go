package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestHealthReadiness(t *testing.T) {
	t.Parallel()
	var down atomic.Bool
	e := newTestEnvWith(t, false, func(d *Deps) {
		d.Ready = func(ctx context.Context) error {
			if _, ok := ctx.Deadline(); !ok {
				return errors.New("readiness check has no deadline")
			}
			if down.Load() {
				return errors.New("dial tcp 10.0.0.1:5432: connect: connection refused")
			}
			return nil
		}
	})
	if r := e.do(http.MethodGet, "/v1/health", nil); r.status != http.StatusOK {
		t.Fatalf("ready: %d %s", r.status, r.body)
	}
	down.Store(true)
	r := e.do(http.MethodGet, "/v1/health", nil)
	if r.status != http.StatusServiceUnavailable || r.header.Get("Retry-After") != "5" ||
		r.header.Get("Content-Type") != "application/problem+json" {
		t.Fatalf("not ready: %d %v %s", r.status, r.header, r.body)
	}
	if m := r.json(t); m["code"] != "service_unavailable" || m["requestId"] == nil {
		t.Errorf("problem = %v", m)
	}
	if strings.Contains(string(r.body), "10.0.0.1") {
		t.Errorf("problem leaks the dependency error: %s", r.body)
	}
	down.Store(false)
	if r := e.do(http.MethodGet, "/v1/health", nil); r.status != http.StatusOK {
		t.Fatalf("recovered: %d", r.status)
	}
}
