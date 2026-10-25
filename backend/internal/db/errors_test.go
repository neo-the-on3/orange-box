package db_test

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
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
		"read only":          {&pgconn.PgError{Code: "25006"}, store.FailureUnavailable},
		"cluster changed":    {fmt.Errorf("acquire: %w", db.ErrClusterChanged), store.FailureUnavailable},
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

// TestClassifyConnectErrors: errors at connect time (and from a closed
// pool) are FailureUnavailable (503 with Retry-After), not 500, including
// ones that carry a Postgres error code.
func TestClassifyConnectErrors(t *testing.T) {
	t.Parallel()
	base, err := url.Parse(dbtest.BaseURL(t))
	if err != nil {
		t.Fatal(err)
	}
	connect := func(u *url.URL) error {
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		defer cancel()
		conn, err := pgx.Connect(ctx, u.String())
		if err == nil {
			_ = conn.Close(ctx)
		}
		return err
	}
	wrongPassword := *base
	wrongPassword.User = url.UserPassword(base.User.Username(), "definitely-not-the-password")
	noDatabase := *base
	noDatabase.Path = "/ob_no_such_database_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	unreachable := *base
	unreachable.Host = "127.0.0.1:1"
	cases := map[string]*url.URL{"unknown database": &noDatabase, "unreachable": &unreachable}
	if _, hasPassword := base.User.Password(); hasPassword {
		cases["wrong password"] = &wrongPassword // trust auth would let it in
	}
	for name, u := range cases {
		err := connect(u)
		if err == nil {
			t.Errorf("%s: connected", name)
			continue
		}
		if got := db.Classify(err); got != store.FailureUnavailable {
			t.Errorf("%s: %v classified as %v", name, err, got)
		}
	}
	pool := dbtest.Open(t, dbtest.NewDatabase(t))
	pool.Close()
	_, err = pool.Exec(t.Context(), "SELECT 1")
	if got := db.Classify(err); err == nil || got != store.FailureUnavailable {
		t.Errorf("closed pool: %v classified as %v", err, got)
	}
	for code, want := range map[string]store.Failure{"28P01": store.FailureUnavailable, "28000": store.FailureUnavailable, "53300": store.FailureUnavailable, "3D000": store.FailureUnavailable} {
		if got := db.Classify(&pgconn.PgError{Code: code}); got != want {
			t.Errorf("%s: %v", code, got)
		}
	}
}
