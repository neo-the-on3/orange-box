package db

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrClusterChanged means the database endpoint now reaches another Postgres
// cluster (another system_identifier) than the one the instance's sync
// identity was computed for, e.g. a blue/green switchover or a failover to
// another cluster behind the same address, or that the cluster can no
// longer be checked (pg_control_system() refused, 42501). Transaction ids
// of two clusters don't compare, so serving sync could silently skip
// writes: the instance fails closed (503, unhealthy) until it is
// restarted, which recomputes the identity (clients then get
// deployment_changed).
var ErrClusterChanged = errors.New("db: the database endpoint reaches another cluster, or can't be checked; restart this instance")

// Bounds of the background cluster check interval
// (SYNC_CLUSTER_CHECK_INTERVAL).
const (
	DefaultClusterCheckInterval = 15 * time.Second
	MinClusterCheckInterval     = 10 * time.Second
	MaxClusterCheckInterval     = 30 * time.Second
)

// clusterProbeTimeout bounds one background check (connect, read, close),
// so a switch is detected within the interval plus this.
const clusterProbeTimeout = 5 * time.Second

// ClusterGuard pins a pool to the cluster whose system_identifier the sync
// identity binds (SYNC_IDENTITY_SOURCE=cluster_and_deployment_id):
//
//   - NewTripsWith pins the identifier it read at startup, then resets the
//     pool, so every connection in use from then on was checked.
//   - Every new pool connection reads system_identifier once
//     (AfterConnect; no per-request query).
//   - Run checks every interval on a fresh connection outside the pool,
//     so a switch is noticed even when the pool is full and busy and its
//     connections all still reach the old cluster.
//
// Another identifier, or 42501 (pg_control_system() refused, so the
// cluster can't be checked), trips the guard for good: one ERROR (identity
// digests only), a pool reset, and from then on no new connection is even
// dialled (BeforeConnect), so every database request, sync walk and
// readiness check fails with ErrClusterChanged (503) until a restart. A
// transient error doesn't trip it: the connection or check just fails.
type ClusterGuard struct {
	logger       *slog.Logger
	deploymentID string
	read         func(context.Context, *pgx.Conn) (int64, error)

	mu       sync.Mutex
	expected *int64
	pool     *pgxpool.Pool
	connCfg  *pgx.ConnConfig

	changed atomic.Bool
}

// ClusterGuardOptions configures NewClusterGuard.
type ClusterGuardOptions struct {
	Logger       *slog.Logger
	DeploymentID string // only for the digests in log lines
	// Read reads a connection's system_identifier; nil means
	// pg_control_system(). Tests replace it to simulate a switch.
	Read func(context.Context, *pgx.Conn) (int64, error)
}

// NewClusterGuard returns an unpinned guard: it checks nothing until
// pinned.
func NewClusterGuard(opts ClusterGuardOptions) *ClusterGuard {
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	if opts.Read == nil {
		opts.Read = func(ctx context.Context, conn *pgx.Conn) (int64, error) {
			var id int64
			err := conn.QueryRow(ctx, "SELECT system_identifier FROM pg_control_system()").Scan(&id)
			return id, err
		}
	}
	return &ClusterGuard{logger: opts.Logger, deploymentID: opts.DeploymentID, read: opts.Read}
}

func (g *ClusterGuard) attach(pool *pgxpool.Pool) {
	g.mu.Lock()
	g.pool = pool
	g.connCfg = pool.Config().ConnConfig.Copy()
	g.mu.Unlock()
}

// pin sets the identifier connections must have, and resets the pool so
// connections opened before (unchecked) are replaced by checked ones.
func (g *ClusterGuard) pin(id int64) {
	g.mu.Lock()
	g.expected = &id
	pool := g.pool
	g.mu.Unlock()
	if pool != nil {
		pool.Reset()
	}
}

func (g *ClusterGuard) pinned() (int64, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.expected == nil {
		return 0, false
	}
	return *g.expected, true
}

// Err is ErrClusterChanged once the guard has tripped, else nil.
func (g *ClusterGuard) Err() error {
	if g != nil && g.changed.Load() {
		return ErrClusterChanged
	}
	return nil
}

// BeforeConnect is the pool's BeforeConnect hook: once tripped, nothing
// is dialled.
func (g *ClusterGuard) BeforeConnect(context.Context, *pgx.ConnConfig) error {
	return g.Err()
}

// AfterConnect is the pool's AfterConnect hook.
func (g *ClusterGuard) AfterConnect(ctx context.Context, conn *pgx.Conn) error {
	if err := g.Err(); err != nil {
		return err
	}
	return g.check(ctx, conn)
}

// check reads conn's system_identifier: another one, or 42501, trips the
// guard; another error is returned as is.
func (g *ClusterGuard) check(ctx context.Context, conn *pgx.Conn) error {
	expected, ok := g.pinned()
	if !ok {
		return nil
	}
	id, err := g.read(ctx, conn)
	if err != nil {
		var pg *pgconn.PgError
		if errors.As(err, &pg) && pg.Code == "42501" {
			g.trip("pg_control_system() is refused (42501), so the cluster can't be checked; "+
				"GRANT EXECUTE ON FUNCTION pg_control_system() TO the server's role (or use SYNC_IDENTITY_SOURCE=deployment_id_only on every instance), then restart",
				expected, nil)
			return ErrClusterChanged
		}
		return err
	}
	if id != expected {
		g.trip("the database endpoint now reaches another Postgres cluster than the one this instance's sync identity was computed for", expected, &id)
		return ErrClusterChanged
	}
	return nil
}

func (g *ClusterGuard) trip(cause string, expected int64, found *int64) {
	if !g.changed.CompareAndSwap(false, true) {
		return
	}
	attrs := []any{slog.String("cause", cause),
		slog.String("identity", DeploymentIdentity(strconv.FormatInt(expected, 10), g.deploymentID))}
	if found != nil {
		attrs = append(attrs, slog.String("found_identity", DeploymentIdentity(strconv.FormatInt(*found, 10), g.deploymentID)))
	}
	g.logger.Error("DATABASE CLUSTER CHANGED: refusing all database work (503, unhealthy) until restarted", attrs...)
	g.mu.Lock()
	pool := g.pool
	g.mu.Unlock()
	if pool != nil {
		// Close the connections that still reach the old cluster too (not
		// from inside a hook).
		go pool.Reset()
	}
}

// Run checks the cluster every interval on a fresh connection outside the
// pool (same settings, no pool hooks) until ctx ends or the guard trips.
// A switch is so detected within interval plus clusterProbeTimeout,
// however busy the pool is. Connection errors are logged, never tripped
// on.
func (g *ClusterGuard) Run(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		if g.Err() != nil {
			return
		}
		if _, ok := g.pinned(); ok {
			g.probe(ctx)
		}
	}
}

func (g *ClusterGuard) probe(ctx context.Context) {
	g.mu.Lock()
	cfg := g.connCfg
	g.mu.Unlock()
	if cfg == nil {
		return
	}
	pctx, cancel := context.WithTimeout(ctx, clusterProbeTimeout)
	defer cancel()
	conn, err := pgx.ConnectConfig(pctx, cfg.Copy())
	if err != nil {
		if ctx.Err() == nil {
			g.logger.Warn("cluster check: cannot connect; retrying next interval", slog.String("error_class", errorClass(err)))
		}
		return
	}
	defer func() {
		cctx, ccancel := context.WithTimeout(context.WithoutCancel(ctx), time.Second)
		defer ccancel()
		_ = conn.Close(cctx)
	}()
	if err := g.check(pctx, conn); err != nil && !errors.Is(err, ErrClusterChanged) && ctx.Err() == nil {
		g.logger.Warn("cluster check failed; retrying next interval", slog.String("error_class", errorClass(err)))
	}
}
