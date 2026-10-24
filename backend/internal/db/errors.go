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
// (40P01, 40001) and context deadlines (a pool acquire or query that
// outlived the request) are FailureBusy; connection failures and server
// shutdowns are FailureUnavailable.
func Classify(err error) store.Failure {
	var pg *pgconn.PgError
	if errors.As(err, &pg) {
		switch {
		case pg.Code == "55P03", pg.Code == "57014", pg.Code == "40P01", pg.Code == "40001":
			return store.FailureBusy
		case strings.HasPrefix(pg.Code, "08"), pg.Code == "57P01", pg.Code == "57P02", pg.Code == "57P03":
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
