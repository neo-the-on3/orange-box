package db

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"sync"
	"sync/atomic"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrClusterChanged means the database endpoint now reaches another Postgres
// cluster (another system_identifier) than the one the instance's sync
// identity was computed for, e.g. a blue/green switchover or a failover to
// another cluster behind the same address. Transaction ids of the two
// don't compare, so serving sync from it could silently skip writes: the
// instance fails closed (503, unhealthy) until it is restarted, which
// recomputes the identity (clients then get deployment_changed).
var ErrClusterChanged = errors.New("db: the database endpoint now reaches another cluster; restart this instance")

// ClusterGuard pins a pool to the cluster whose system_identifier the sync
// identity binds (SYNC_IDENTITY_SOURCE=cluster_and_deployment_id). Install
// AfterConnect on the pool (Options.Guard); NewTripsWith pins the
// identifier it read at startup. Every new connection then reads
// system_identifier once (no per-request query) and is refused if it
// differs, which trips the guard: it logs an ERROR (identity digests
// only), resets the pool so existing connections are closed too, and from
// then on every connection, sync walk and readiness check fails with
// ErrClusterChanged.
//
// Connections already open when the endpoint is switched keep reaching
// the old cluster, which is consistent with the cached identity; they are
// replaced within MaxConnIdleTime or connMaxLifetime, and the first
// replacement that reaches the new cluster trips the guard.
type ClusterGuard struct {
	logger       *slog.Logger
	deploymentID string
	read         func(context.Context, *pgx.Conn) (int64, error)

	mu       sync.Mutex
	expected *int64
	pool     *pgxpool.Pool

	changed   atomic.Bool
	forbidden atomic.Bool // 42501 already logged
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
	g.mu.Unlock()
}

// pin sets the identifier new connections must have.
func (g *ClusterGuard) pin(id int64) {
	g.mu.Lock()
	g.expected = &id
	g.mu.Unlock()
}

// Err is ErrClusterChanged once the guard has tripped, else nil.
func (g *ClusterGuard) Err() error {
	if g != nil && g.changed.Load() {
		return ErrClusterChanged
	}
	return nil
}

// AfterConnect is the pool's AfterConnect hook.
func (g *ClusterGuard) AfterConnect(ctx context.Context, conn *pgx.Conn) error {
	if g.changed.Load() {
		return ErrClusterChanged
	}
	g.mu.Lock()
	expected := g.expected
	g.mu.Unlock()
	if expected == nil {
		return nil
	}
	id, err := g.read(ctx, conn)
	if err != nil {
		var pg *pgconn.PgError
		if errors.As(err, &pg) && pg.Code == "42501" {
			// The privilege was revoked after startup: keep serving (the
			// identity is cached), but say the check is off.
			if g.forbidden.CompareAndSwap(false, true) {
				g.logger.Warn("cannot check new database connections against the sync identity's cluster: " +
					"pg_control_system() is no longer permitted (42501); grant it back, or a switch to another cluster goes unnoticed")
			}
			return nil
		}
		return err
	}
	if id != *expected {
		g.trip(*expected, id)
		return ErrClusterChanged
	}
	return nil
}

func (g *ClusterGuard) trip(expected, found int64) {
	if !g.changed.CompareAndSwap(false, true) {
		return
	}
	g.logger.Error("DATABASE CLUSTER CHANGED: the database endpoint now reaches another Postgres cluster than the one "+
		"this instance's sync identity was computed for; refusing all database work (503, unhealthy) until restarted",
		slog.String("identity", DeploymentIdentity(strconv.FormatInt(expected, 10), g.deploymentID)),
		slog.String("found_identity", DeploymentIdentity(strconv.FormatInt(found, 10), g.deploymentID)))
	g.mu.Lock()
	pool := g.pool
	g.mu.Unlock()
	if pool != nil {
		// Close the connections that still reach the old cluster too. Not
		// from inside the hook: Reset waits for nothing, but keep the
		// connect path short.
		go pool.Reset()
	}
}
