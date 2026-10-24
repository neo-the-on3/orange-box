package db_test

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

// schema lists the public schema's tables, columns, indexes, constraints,
// functions and triggers (goose's own table excluded), sorted.
func schema(t *testing.T, pool *pgxpool.Pool) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(), `
		SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
		       || ' ' || coalesce(column_default, '')
		FROM information_schema.columns
		WHERE table_schema = 'public' AND table_name <> 'goose_db_version'
		UNION ALL
		SELECT 'index ' || indexdef FROM pg_indexes
		WHERE schemaname = 'public' AND tablename <> 'goose_db_version'
		UNION ALL
		SELECT 'constraint ' || conrelid::regclass::text || ' ' || conname || ' ' || pg_get_constraintdef(oid)
		FROM pg_constraint
		WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text <> 'goose_db_version'
		UNION ALL
		SELECT 'function ' || p.oid::regprocedure::text FROM pg_proc p
		WHERE p.pronamespace = 'public'::regnamespace
		UNION ALL
		SELECT 'trigger ' || tgrelid::regclass::text || ' ' || tgname FROM pg_trigger WHERE NOT tgisinternal
		ORDER BY 1`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatal(err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return out
}

// TestMigrationsUpDown applies the migrations one at a time, checking that
// each one's Down restores exactly the schema before its Up, then rolls
// everything back to an empty schema and applies it all again.
func TestMigrationsUpDown(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.Open(t, dbtest.NewDatabase(t))
	m, err := db.NewMigrator(pool)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = m.Close() }()

	sources := m.ListSources()
	if len(sources) == 0 {
		t.Fatal("no migrations embedded")
	}
	if got := schema(t, pool); len(got) != 0 {
		t.Fatalf("new database not empty: %v", got)
	}
	var prev int64
	for _, src := range sources {
		before := schema(t, pool)
		if _, err := m.UpByOne(ctx); err != nil {
			t.Fatalf("up %s: %v", src.Path, err)
		}
		after := schema(t, pool)
		if slices.Equal(before, after) {
			t.Errorf("%s changed nothing", src.Path)
		}
		if _, err := m.DownTo(ctx, prev); err != nil {
			t.Fatalf("down %s: %v", src.Path, err)
		}
		if got := schema(t, pool); !slices.Equal(got, before) {
			t.Fatalf("down %s did not restore the schema:\n got %v\nwant %v", src.Path, got, before)
		}
		if _, err := m.UpByOne(ctx); err != nil {
			t.Fatalf("re-up %s: %v", src.Path, err)
		}
		if got := schema(t, pool); !slices.Equal(got, after) {
			t.Fatalf("re-up %s gave a different schema", src.Path)
		}
		prev = src.Version
	}

	full := schema(t, pool)
	if _, err := m.DownTo(ctx, 0); err != nil {
		t.Fatalf("down to 0: %v", err)
	}
	if got := schema(t, pool); len(got) != 0 {
		t.Fatalf("schema not empty after down to 0: %v", got)
	}
	if _, err := m.Up(ctx); err != nil {
		t.Fatalf("up: %v", err)
	}
	if got := schema(t, pool); !slices.Equal(got, full) {
		t.Fatal("second full up gave a different schema")
	}
	if pending, err := m.HasPending(ctx); err != nil || pending {
		t.Fatalf("HasPending = %v, %v after up", pending, err)
	}
}

func TestCheckMigrated(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.Open(t, dbtest.NewDatabase(t))
	if err := db.CheckMigrated(ctx, pool); !errors.Is(err, db.ErrNotMigrated) {
		t.Fatalf("empty database: err = %v, want ErrNotMigrated", err)
	}
	m, err := db.NewMigrator(pool)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = m.Close() }()
	if _, err := m.UpByOne(ctx); err != nil {
		t.Fatal(err)
	}
	if err := db.CheckMigrated(ctx, pool); !errors.Is(err, db.ErrNotMigrated) {
		t.Fatalf("partly migrated: err = %v, want ErrNotMigrated", err)
	}
	if _, err := m.Up(ctx); err != nil {
		t.Fatal(err)
	}
	if err := db.CheckMigrated(ctx, pool); err != nil {
		t.Fatalf("migrated: %v", err)
	}
	// A database migrated by a newer binary is refused, not silently used.
	if _, err := pool.Exec(ctx, `INSERT INTO goose_db_version (version_id, is_applied) VALUES (99999, true)`); err != nil {
		t.Fatal(err)
	}
	if err := db.CheckMigrated(ctx, pool); err == nil || errors.Is(err, db.ErrNotMigrated) {
		t.Fatalf("newer schema: err = %v, want a version error", err)
	}
}

func TestOpenRejectsBadURLWithoutEchoingIt(t *testing.T) {
	t.Parallel()
	_, err := db.Open(context.Background(), db.Options{URL: "postgres://user:s3cret@[bad"})
	if err == nil {
		t.Fatal("Open succeeded")
	}
	if got := err.Error(); strings.Contains(got, "s3cret") {
		t.Errorf("error leaks the password: %v", got)
	}
}
