package db_test

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func TestClassify(t *testing.T) {
	t.Parallel()
	for name, c := range map[string]struct {
		err  error
		want store.Failure
	}{
		"lock_timeout":       {&pgconn.PgError{Code: "55P03"}, store.FailureBusy},
		"statement_timeout":  {fmt.Errorf("list: %w", &pgconn.PgError{Code: "57014"}), store.FailureBusy},
		"deadlock":           {&pgconn.PgError{Code: "40P01"}, store.FailureBusy},
		"idle in tx timeout": {fmt.Errorf("get trip: %w", &pgconn.PgError{Code: "25P03"}), store.FailureBusy},
		"idle session":       {&pgconn.PgError{Code: "57P05"}, store.FailureBusy},
		"deadline":           {fmt.Errorf("acquire: %w", context.DeadlineExceeded), store.FailureBusy},
		"connection failure": {&pgconn.PgError{Code: "08006"}, store.FailureUnavailable},
		"admin shutdown":     {&pgconn.PgError{Code: "57P01"}, store.FailureUnavailable},
		"unique violation":   {&pgconn.PgError{Code: "23505"}, store.FailureOther},
		"plain error":        {errors.New("boom"), store.FailureOther},
		"canceled (client)":  {context.Canceled, store.FailureOther},
	} {
		if got := db.Classify(c.err); got != c.want {
			t.Errorf("%s: Classify = %d, want %d", name, got, c.want)
		}
	}
}

// TestClassifyIdleTimeouts: the errors a real server gives after closing
// an idle session, in a transaction or not, are busy (retryable).
func TestClassifyIdleTimeouts(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	for _, c := range []struct {
		name, setting string
		inTx          bool
		code          string
	}{
		{"idle in transaction", "idle_in_transaction_session_timeout", true, "25P03"},
		{"idle session", "idle_session_timeout", false, "57P05"},
	} {
		conn, err := pool.Acquire(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := conn.Exec(ctx, "SET "+c.setting+" = '100ms'"); err != nil {
			t.Fatal(err)
		}
		if c.inTx {
			if _, err := conn.Exec(ctx, "BEGIN"); err != nil {
				t.Fatal(err)
			}
		}
		time.Sleep(400 * time.Millisecond)
		_, err = conn.Exec(ctx, "SELECT 1")
		var pg *pgconn.PgError
		if !errors.As(err, &pg) || pg.Code != c.code {
			t.Errorf("%s: %v, want SQLSTATE %s", c.name, err, c.code)
		}
		if got := db.Classify(err); got != store.FailureBusy {
			t.Errorf("%s: %v → %d, want busy", c.name, err, got)
		}
		conn.Release()
	}
}

// TestClassifyLockTimeout: a real lock wait that runs out is busy.
func TestClassifyLockTimeout(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `LOCK TABLE trips IN ACCESS EXCLUSIVE MODE`); err != nil {
		t.Fatal(err)
	}
	_, err = pool.Exec(ctx, `SET lock_timeout = '50ms'; SELECT count(*) FROM trips`)
	if got := db.Classify(err); got != store.FailureBusy {
		t.Fatalf("lock wait: %v → %d, want busy", err, got)
	}
	qctx, cancel := context.WithTimeout(ctx, 50*time.Millisecond)
	defer cancel()
	_, err = pool.Exec(qctx, `SELECT pg_sleep(5)`)
	if got := db.Classify(err); got != store.FailureBusy {
		t.Fatalf("deadline: %v → %d, want busy", err, got)
	}
}
