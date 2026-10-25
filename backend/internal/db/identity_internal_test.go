package db

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

var fastBackoff = []time.Duration{time.Millisecond, time.Millisecond, time.Millisecond}

// reader fails the first n calls with err, then returns 7311.
func reader(n int32, err error) (func(context.Context) (int64, error), *atomic.Int32) {
	var calls atomic.Int32
	return func(context.Context) (int64, error) {
		if calls.Add(1) <= n {
			return 0, err
		}
		return 7311, nil
	}, &calls
}

func TestNewTripsIdentityStartup(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	connReset := &pgconn.PgError{Code: "08006", Message: "connection failure"}
	forbidden := &pgconn.PgError{Code: "42501", Message: "permission denied for function pg_control_system"}
	want := DeploymentIdentity("7311", "prod-1")

	t.Run("transient errors are retried", func(t *testing.T) {
		t.Parallel()
		for _, err := range []error{connReset, errors.New("unexpected EOF"), context.DeadlineExceeded} {
			read, calls := reader(2, err)
			s, gotErr := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "prod-1", Backoff: fastBackoff, readSystemID: read})
			if gotErr != nil || s.Identity() != want || calls.Load() != 3 {
				t.Errorf("%v twice: %v, identity %v, %d calls", err, gotErr, s, calls.Load())
			}
		}
	})
	t.Run("persistent errors fail startup, never fall back", func(t *testing.T) {
		t.Parallel()
		read, calls := reader(1000, connReset)
		s, err := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "prod-1", Backoff: fastBackoff, readSystemID: read})
		if err == nil || s != nil || !strings.Contains(err.Error(), "4 attempts") || calls.Load() != 4 {
			t.Fatalf("persistent error: %v, %v, %d calls", s, err, calls.Load())
		}
		if errors.Is(err, ErrSystemIdentifierForbidden) {
			t.Error("a connection error reported as insufficient privilege")
		}
	})
	t.Run("default backoff is bounded", func(t *testing.T) {
		t.Parallel()
		var total time.Duration
		for _, d := range DefaultIdentityBackoff {
			total += d
		}
		if total > 10*time.Second || len(DefaultIdentityBackoff) == 0 {
			t.Errorf("default backoff %v (total %v)", DefaultIdentityBackoff, total)
		}
	})
	t.Run("cancelled context stops the retries", func(t *testing.T) {
		t.Parallel()
		cctx, cancel := context.WithCancel(ctx)
		read, calls := reader(1000, connReset)
		go func() { time.Sleep(20 * time.Millisecond); cancel() }()
		start := time.Now()
		if _, err := NewTripsWith(cctx, nil, TripsOptions{DeploymentID: "prod-1", Backoff: []time.Duration{time.Hour}, readSystemID: read}); err == nil || !errors.Is(err, context.Canceled) {
			t.Fatalf("cancelled: %v", err)
		}
		if time.Since(start) > 5*time.Second || calls.Load() != 1 {
			t.Errorf("took %v, %d calls", time.Since(start), calls.Load())
		}
	})
	t.Run("42501 without the setting fails at once", func(t *testing.T) {
		t.Parallel()
		read, calls := reader(1000, forbidden)
		_, err := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "prod-1", Backoff: fastBackoff, readSystemID: read})
		if !errors.Is(err, ErrSystemIdentifierForbidden) || calls.Load() != 1 || !strings.Contains(err.Error(), "SYNC_IDENTITY_SOURCE=deployment_id_only") {
			t.Fatalf("42501: %v, %d calls", err, calls.Load())
		}
	})
	t.Run("42501 with deployment_id_only starts, without reading", func(t *testing.T) {
		t.Parallel()
		read, calls := reader(1000, forbidden)
		s, err := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "prod-1", IdentitySource: IdentityDeploymentIDOnly, readSystemID: read})
		if err != nil || s.Identity() != DeploymentIdentity("", "prod-1") || s.IdentitySource() != IdentityDeploymentIDOnly || calls.Load() != 0 {
			t.Fatalf("deployment_id_only: %v, %d calls", err, calls.Load())
		}
	})
	t.Run("deployment_id_only ignores a readable system_identifier", func(t *testing.T) {
		// Instances agree whatever their roles may read.
		t.Parallel()
		read, _ := reader(0, nil)
		a, _ := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "prod-1", IdentitySource: IdentityDeploymentIDOnly, readSystemID: read})
		b, _ := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "prod-1", IdentitySource: IdentityDeploymentIDOnly})
		if a.Identity() != b.Identity() {
			t.Fatal("deployment_id_only identities differ")
		}
	})
	t.Run("bad options", func(t *testing.T) {
		t.Parallel()
		if _, err := NewTripsWith(ctx, nil, TripsOptions{IdentitySource: IdentityDeploymentIDOnly}); err == nil {
			t.Error("no deployment id accepted")
		}
		if _, err := NewTripsWith(ctx, nil, TripsOptions{DeploymentID: "x", IdentitySource: "cluster"}); err == nil {
			t.Error("unknown identity source accepted")
		}
	})
}
