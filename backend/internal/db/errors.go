package db

import (
	"context"
	"errors"
	"net"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/puddle/v2"

	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// ErrKeptRacing means a create or upsert gave up after maxAttempts
// because the row that blocked it kept vanishing before it could be read
// (a concurrent delete or purge). Retrying shortly is likely to work, so
// Classify makes it FailureBusy (503 server_busy with Retry-After).
var ErrKeptRacing = errors.New("kept racing a concurrent delete; retry")

// Classify sorts a storage error into a store.Failure (#178): lock and
// statement timeouts (55P03, 57014), deadlocks and serialization failures
// (40P01, 40001), idle session timeouts (25P03, 57P05) and context deadlines (a pool acquire or query that
// outlived the request) are FailureBusy; connection failures and server
// shutdowns, a read-only cluster (25006), connect-time errors (dial,
// auth, 53300, 3D000), a closed pool and ErrClusterChanged are
// FailureUnavailable.
func Classify(err error) store.Failure {
	if errors.Is(err, ErrKeptRacing) {
		return store.FailureBusy
	}
	if errors.Is(err, ErrClusterChanged) {
		return store.FailureUnavailable
	}
	// Before the PgError branch: a connect-time error (dial, TLS, auth,
	// too many connections, unknown database), even one carrying a
	// PgError, means the database can't be reached right now.
	var ce *pgconn.ConnectError
	if errors.As(err, &ce) || errors.Is(err, puddle.ErrClosedPool) {
		return store.FailureUnavailable
	}
	var pg *pgconn.PgError
	if errors.As(err, &pg) {
		switch {
		case pg.Code == "55P03", pg.Code == "57014", pg.Code == "40P01", pg.Code == "40001",
			// The server closed a session it found idle: in a transaction
			// (idle_in_transaction_session_timeout, e.g. the process was
			// paused mid-request) or outside one (idle_session_timeout).
			// The pool drops the connection; a retry gets a fresh one.
			pg.Code == "25P03", pg.Code == "57P05":
			return store.FailureBusy
		case strings.HasPrefix(pg.Code, "08"), pg.Code == "57P01", pg.Code == "57P02", pg.Code == "57P03",
			// read_only_sql_transaction: the cluster is read-only (e.g. the
			// old side of a blue/green switchover, or a standby).
			pg.Code == "25006",
			// Connection-level, in case one arrives without a ConnectError:
			// invalid authorization (class 28: 28000, 28P01), too many
			// connections (53300), unknown database (3D000).
			strings.HasPrefix(pg.Code, "28"), pg.Code == "53300", pg.Code == "3D000":
			return store.FailureUnavailable
		}
		return store.FailureOther
	}
	var ne net.Error
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return store.FailureBusy
	case errors.As(err, &ne):
		return store.FailureUnavailable
	}
	return store.FailureOther
}
