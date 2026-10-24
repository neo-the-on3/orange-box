// Package db is the Postgres storage layer: the goose migrations, the
// connection pool, and stores built on the sqlc queries in package dbgen.
//
// Schema changes go in a new numbered file under migrations/ (never edit a
// released one) and queries in queries/; `make generate` regenerates dbgen.
package db

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Session defaults applied when DATABASE_URL does not set them. They bound
// how long one statement or an idle transaction can hold the sync
// watermark back (openapi-v2.yaml, Conventions → Incremental sync).
var sessionDefaults = map[string]string{
	"statement_timeout":                   "15s",
	"idle_in_transaction_session_timeout": "15s",
	"lock_timeout":                        "5s",
	"application_name":                    "orange-box-backend",
}

// Options configures Open.
type Options struct {
	// URL is a libpq-style connection string or URL (DATABASE_URL).
	URL string
	// MaxConns caps the pool size; 0 keeps pgx's default.
	MaxConns int
	// ForMigrations opens a pool for `server migrate` and MIGRATE_ON_START:
	// statement_timeout is 0 (even if DATABASE_URL sets one), so a long
	// data migration isn't killed halfway, while lock_timeout still applies
	// (#178).
	ForMigrations bool
}

// Open connects a pool, applies the session defaults and pings the server.
// It does not check the schema version; see CheckMigrated.
func Open(ctx context.Context, opts Options) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(opts.URL)
	if err != nil {
		// The parse error may echo the URL, password included.
		return nil, errors.New("DATABASE_URL is not a valid Postgres connection string")
	}
	for k, v := range sessionDefaults {
		if _, ok := cfg.ConnConfig.RuntimeParams[k]; !ok {
			cfg.ConnConfig.RuntimeParams[k] = v
		}
	}
	if opts.ForMigrations {
		cfg.ConnConfig.RuntimeParams["statement_timeout"] = "0"
		cfg.ConnConfig.RuntimeParams["application_name"] = "orange-box-migrate"
	}
	if opts.MaxConns > 0 && opts.MaxConns <= math.MaxInt32 {
		cfg.MaxConns = int32(opts.MaxConns)
	}
	cfg.MaxConnIdleTime = 5 * time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("open database pool: %w", err)
	}
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("connect to database: %w", err)
	}
	var version int
	if err := pool.QueryRow(pingCtx, "SELECT current_setting('server_version_num')::int").Scan(&version); err != nil {
		pool.Close()
		return nil, fmt.Errorf("read server version: %w", err)
	}
	if err := CheckServerVersion(version); err != nil {
		pool.Close()
		return nil, err
	}
	return pool, nil
}

// MinServerVersion is the oldest supported PostgreSQL (server_version_num):
// the schema uses NULLS NOT DISTINCT, new in PostgreSQL 15.
const MinServerVersion = 150000

// CheckServerVersion rejects a server_version_num below MinServerVersion.
// Open calls it, so the server and `server migrate` both refuse an old
// server before touching the schema.
func CheckServerVersion(num int) error {
	if num < MinServerVersion {
		return fmt.Errorf("PostgreSQL %d.%d is not supported: the schema needs PostgreSQL 15 or newer (server_version_num %d < %d)",
			num/10000, num%10000, num, MinServerVersion)
	}
	return nil
}
