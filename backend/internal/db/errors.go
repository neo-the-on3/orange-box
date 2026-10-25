package db

import (
	"context"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Classify sorts a storage error into a store.Failure (#178): lock and
// statement timeouts (55P03, 57014), deadlocks and serialization failures
// (40P01, 40001), idle session timeouts (25P03, 57P05) and context deadlines (a pool acquire or query that
// outlived the request) are FailureBusy; connection failures and server
// shutdowns, a read-only cluster (25006) and ErrClusterChanged are
// FailureUnavailable.
func Classify(err error) store.Failure {
	if errors.Is(err, ErrClusterChanged) {
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
			pg.Code == "25006":
			return store.FailureUnavailable
		}
		return store.FailureOther
	}
	var ce *pgconn.ConnectError
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return store.FailureBusy
	case errors.As(err, &ce):
		return store.FailureUnavailable
	}
	return store.FailureOther
}
