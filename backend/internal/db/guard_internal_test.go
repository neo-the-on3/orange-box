package db

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func TestClusterGuard(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	var logs lockedBuffer
	var current atomic.Int64
	var readErr atomic.Pointer[error]
	var reads atomic.Int32
	current.Store(7311001)
	g := NewClusterGuard(ClusterGuardOptions{
		Logger: slog.New(slog.NewJSONHandler(&logs, nil)), DeploymentID: "prod-1",
		Read: func(context.Context, *pgx.Conn) (int64, error) {
			reads.Add(1)
			if e := readErr.Load(); e != nil {
				return 0, *e
			}
			return current.Load(), nil
		},
	})
	if err := g.AfterConnect(ctx, nil); err != nil || reads.Load() != 0 {
		t.Fatalf("unpinned guard: %v, %d reads", err, reads.Load())
	}
	g.pin(7311001)
	if err := g.AfterConnect(ctx, nil); err != nil || g.Err() != nil {
		t.Fatalf("same cluster: %v", err)
	}
	// A transient read error fails that connection only.
	transient := error(&pgconn.PgError{Code: "08006"})
	readErr.Store(&transient)
	if err := g.AfterConnect(ctx, nil); !errors.Is(err, transient) || g.Err() != nil {
		t.Fatalf("transient: %v, guard %v", err, g.Err())
	}
	// 42501 after startup: allowed, warned once.
	forbidden := error(&pgconn.PgError{Code: "42501"})
	readErr.Store(&forbidden)
	for range 3 {
		if err := g.AfterConnect(ctx, nil); err != nil || g.Err() != nil {
			t.Fatalf("42501: %v", err)
		}
	}
	if n := strings.Count(logs.String(), "no longer permitted"); n != 1 {
		t.Errorf("42501 warned %d times", n)
	}
	readErr.Store(nil)
	// Another cluster: refused, tripped for good, logged with digests only.
	current.Store(7999002)
	if err := g.AfterConnect(ctx, nil); !errors.Is(err, ErrClusterChanged) || !errors.Is(g.Err(), ErrClusterChanged) {
		t.Fatalf("other cluster: %v, guard %v", err, g.Err())
	}
	current.Store(7311001)
	before := reads.Load()
	if err := g.AfterConnect(ctx, nil); !errors.Is(err, ErrClusterChanged) || reads.Load() != before {
		t.Fatalf("after tripping, back to the old cluster: %v", err)
	}
	out := logs.String()
	if strings.Count(out, "DATABASE CLUSTER CHANGED") != 1 || !strings.Contains(out, `"level":"ERROR"`) ||
		!strings.Contains(out, DeploymentIdentity("7311001", "prod-1")) || !strings.Contains(out, DeploymentIdentity("7999002", "prod-1")) {
		t.Errorf("log: %s", out)
	}
	for _, raw := range []string{"7311001", "7999002", "prod-1"} {
		if strings.Contains(out, raw) {
			t.Errorf("log leaks %q: %s", raw, out)
		}
	}
	var nilGuard *ClusterGuard
	if nilGuard.Err() != nil {
		t.Error("nil guard reports an error")
	}
}

func TestIdentityRetryLogsEachAttempt(t *testing.T) {
	t.Parallel()
	var logs lockedBuffer
	read, _ := reader(1000, &pgconn.PgError{Code: "08006", Message: "secret-host:5432"})
	_, err := NewTripsWith(t.Context(), nil, TripsOptions{DeploymentID: "prod-1", Backoff: fastBackoff, readSystemID: read,
		Logger: slog.New(slog.NewJSONHandler(&logs, nil))})
	if err == nil {
		t.Fatal("no error")
	}
	out := logs.String()
	if n := strings.Count(out, "retrying"); n != len(fastBackoff) {
		t.Errorf("%d retry lines, want %d: %s", n, len(fastBackoff), out)
	}
	if !strings.Contains(out, `"error_class":"sqlstate 08006"`) || strings.Contains(out, "secret-host") {
		t.Errorf("log: %s", out)
	}
	for i := 1; i <= len(fastBackoff); i++ {
		if !strings.Contains(out, `"attempt":`+strconv.Itoa(i)+`,`) {
			t.Errorf("attempt %d not logged", i)
		}
	}
	// The documented upper bound: attempts × timeout + waits ≈ 20 s.
	var waits = identityAttemptTimeout * 7
	for _, d := range DefaultIdentityBackoff {
		waits += d
	}
	if len(DefaultIdentityBackoff) != 6 || waits.Seconds() > 21 {
		t.Errorf("startup bound %v, documented as about 20 s", waits)
	}
}
