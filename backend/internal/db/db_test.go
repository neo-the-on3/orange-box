package db_test

import (
	"context"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

func TestOpenSessionDefaults(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	url := dbtest.NewDatabase(t)
	show := func(pool *pgxpool.Pool, setting string) string {
		t.Helper()
		var v string
		if err := pool.QueryRow(ctx, "SHOW "+setting).Scan(&v); err != nil {
			t.Fatal(err)
		}
		return v
	}
	pool := dbtest.Open(t, url)
	if got := show(pool, "statement_timeout"); got != "15s" {
		t.Errorf("default statement_timeout = %q, want 15s", got)
	}
	if got := show(pool, "idle_in_transaction_session_timeout"); got != "15s" {
		t.Errorf("default idle_in_transaction_session_timeout = %q, want 15s", got)
	}
	sep := "?"
	if strings.Contains(url, "?") {
		sep = "&"
	}
	if got := show(dbtest.Open(t, url+sep+"statement_timeout=5s"), "statement_timeout"); got != "5s" {
		t.Errorf("statement_timeout from the URL = %q, want 5s", got)
	}
}

func TestCheckServerVersion(t *testing.T) {
	t.Parallel()
	for num, wantErr := range map[int]string{
		90624:  "PostgreSQL 9.624 is not supported",
		140009: "PostgreSQL 14.9 is not supported: the schema needs PostgreSQL 15 or newer",
		150000: "",
		170011: "",
		180000: "",
	} {
		err := db.CheckServerVersion(num)
		switch {
		case wantErr == "" && err != nil:
			t.Errorf("%d: unexpected error %v", num, err)
		case wantErr != "" && (err == nil || !strings.Contains(err.Error(), wantErr)):
			t.Errorf("%d: err = %v, want it to contain %q", num, err, wantErr)
		}
	}
}
