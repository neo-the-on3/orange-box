// Package dbtest gives integration tests a throwaway Postgres database.
//
// Tests that use it are skipped when DATABASE_URL is unset, unless
// REQUIRE_DATABASE_TESTS=true (set in CI), which makes them fail instead.
// With it set, each call creates a fresh database on that server (the role
// needs CREATEDB) and drops it when the test ends, so tests never touch the
// database DATABASE_URL names and can run in parallel.
package dbtest

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
)

// BaseURL returns DATABASE_URL, skipping the test when it is unset.
func BaseURL(t testing.TB) string {
	t.Helper()
	base := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if base == "" {
		if os.Getenv("REQUIRE_DATABASE_TESTS") == "true" {
			t.Fatal("REQUIRE_DATABASE_TESTS=true but DATABASE_URL is not set")
		}
		t.Skip("DATABASE_URL not set: skipping Postgres integration test")
	}
	return base
}

// NewDatabase creates an empty database and returns its connection URL.
// The database is dropped when the test and its subtests finish.
func NewDatabase(t testing.TB) string {
	t.Helper()
	base := BaseURL(t)
	u, err := url.Parse(base)
	if err != nil || (u.Scheme != "postgres" && u.Scheme != "postgresql") {
		t.Fatal("dbtest: DATABASE_URL must be a postgres:// URL")
	}
	var b [6]byte
	_, _ = rand.Read(b[:])
	name := "ob_test_" + hex.EncodeToString(b[:])

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := pgx.Connect(ctx, base)
	if err != nil {
		t.Fatalf("dbtest: connect: %v", err)
	}
	defer func() { _ = admin.Close(ctx) }()
	if _, err := admin.Exec(ctx, "CREATE DATABASE "+pgx.Identifier{name}.Sanitize()); err != nil {
		t.Fatalf("dbtest: create database: %v", err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		admin, err := pgx.Connect(ctx, base)
		if err != nil {
			t.Errorf("dbtest: connect for cleanup: %v", err)
			return
		}
		defer func() { _ = admin.Close(ctx) }()
		if _, err := admin.Exec(ctx, "DROP DATABASE IF EXISTS "+pgx.Identifier{name}.Sanitize()+" WITH (FORCE)"); err != nil {
			t.Errorf("dbtest: drop database %s: %v", name, err)
		}
	})
	u.Path = "/" + name
	return u.String()
}

// Open returns a pool on url, closed when the test ends.
func Open(t testing.TB, url string) *pgxpool.Pool {
	t.Helper()
	pool, err := db.Open(t.Context(), db.Options{URL: url, MaxConns: 8})
	if err != nil {
		t.Fatalf("dbtest: open: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// New returns a pool on a fresh database with every migration applied.
func New(t testing.TB) *pgxpool.Pool {
	t.Helper()
	pool := Open(t, NewDatabase(t))
	m, err := db.NewMigrator(pool)
	if err != nil {
		t.Fatalf("dbtest: migrator: %v", err)
	}
	defer func() { _ = m.Close() }()
	if _, err := m.Up(t.Context()); err != nil {
		t.Fatalf("dbtest: migrate up: %v", err)
	}
	return pool
}
