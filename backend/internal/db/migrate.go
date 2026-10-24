package db

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/lock"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the embedded goose migrations.
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // the embed pattern guarantees the directory exists
	}
	return sub
}

// Migrator wraps a goose provider over the embedded migrations. Close it
// when done; it owns its database/sql handle.
type Migrator struct {
	*goose.Provider
	db *sql.DB
}

// NewMigrator returns a migrator that uses pool's connection settings. A
// Postgres advisory lock serialises concurrent migrators (for example two
// instances starting at once).
func NewMigrator(pool *pgxpool.Pool) (*Migrator, error) {
	sqlDB := stdlib.OpenDBFromPool(pool)
	locker, err := lock.NewPostgresSessionLocker()
	if err != nil {
		_ = sqlDB.Close()
		return nil, err
	}
	p, err := goose.NewProvider(goose.DialectPostgres, sqlDB, Migrations(), goose.WithSessionLocker(locker))
	if err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("load migrations: %w", err)
	}
	return &Migrator{Provider: p, db: sqlDB}, nil
}

// Close releases the database/sql handle (not the pool).
func (m *Migrator) Close() error { return m.db.Close() }

// ErrNotMigrated means the database schema is behind the embedded migrations.
var ErrNotMigrated = errors.New("database schema is not up to date: run `server migrate up`")

// CheckMigrated returns ErrNotMigrated (wrapped) when any embedded migration
// has not been applied, and an error when the database is ahead of this
// binary (a newer release migrated it).
func CheckMigrated(ctx context.Context, pool *pgxpool.Pool) error {
	m, err := NewMigrator(pool)
	if err != nil {
		return err
	}
	defer func() { _ = m.Close() }()
	current, target, err := m.GetVersions(ctx)
	if err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	switch {
	case current < target:
		return fmt.Errorf("%w (database at %d, binary needs %d)", ErrNotMigrated, current, target)
	case current > target:
		return fmt.Errorf("database schema version %d is newer than this binary's %d", current, target)
	}
	return nil
}
