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
	if err := g.BeforeConnect(ctx, nil); !errors.Is(err, ErrClusterChanged) {
		t.Errorf("BeforeConnect after tripping: %v", err)
	}
	var nilGuard *ClusterGuard
	if nilGuard.Err() != nil {
		t.Error("nil guard reports an error")
	}
}

// TestClusterGuard42501FailsClosed: pg_control_system() refused on a new
// connection (e.g. the endpoint now reaches a cluster where it is
// revoked) can't be checked, so it trips the guard like a mismatch.
func TestClusterGuard42501FailsClosed(t *testing.T) {
	t.Parallel()
	var logs lockedBuffer
	g := NewClusterGuard(ClusterGuardOptions{
		Logger: slog.New(slog.NewJSONHandler(&logs, nil)), DeploymentID: "prod-1",
		Read: func(context.Context, *pgx.Conn) (int64, error) { return 0, &pgconn.PgError{Code: "42501"} },
	})
	if err := g.BeforeConnect(t.Context(), nil); err != nil {
		t.Fatalf("BeforeConnect before tripping: %v", err)
	}
	g.pin(7311001)
	if err := g.AfterConnect(t.Context(), nil); !errors.Is(err, ErrClusterChanged) || !errors.Is(g.Err(), ErrClusterChanged) {
		t.Fatalf("42501: %v, guard %v", err, g.Err())
	}
	out := logs.String()
	if !strings.Contains(out, "DATABASE CLUSTER CHANGED") || !strings.Contains(out, "GRANT EXECUTE ON FUNCTION pg_control_system()") ||
		!strings.Contains(out, DeploymentIdentity("7311001", "prod-1")) || strings.Contains(out, "7311001\"") {
		t.Errorf("log: %s", out)
	}
}

// TestClusterProbeTransient: a background check that can't connect logs
// and doesn't trip the guard.
func TestClusterProbeTransient(t *testing.T) {
	t.Parallel()
	var logs lockedBuffer
	g := NewClusterGuard(ClusterGuardOptions{Logger: slog.New(slog.NewJSONHandler(&logs, nil)), DeploymentID: "prod-1"})
	cfg, err := pgx.ParseConfig("postgres://nobody:secret@127.0.0.1:1/none?connect_timeout=1&sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	g.connCfg = cfg
	g.pin(7311001)
	g.probe(t.Context())
	if g.Err() != nil || g.Ready() != nil {
		t.Fatalf("one failed check: Err %v, Ready %v", g.Err(), g.Ready())
	}
	// Three in a row: unhealthy, not latched, one ERROR.
	g.probe(t.Context())
	if g.Ready() != nil {
		t.Fatal("unhealthy after 2 failures")
	}
	g.probe(t.Context())
	if !errors.Is(g.Ready(), ErrClusterUnchecked) || g.Err() != nil || g.BeforeConnect(t.Context(), nil) != nil {
		t.Fatalf("3 failures: Ready %v, Err %v", g.Ready(), g.Err())
	}
	g.probe(t.Context())
	if n := strings.Count(logs.String(), "CLUSTER UNCHECKED"); n != 1 {
		t.Errorf("%d CLUSTER UNCHECKED errors", n)
	}
	if out := logs.String(); !strings.Contains(out, "cluster check: cannot connect") || strings.Contains(out, "secret") || strings.Contains(out, "127.0.0.1") {
		t.Errorf("log: %s", out)
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
