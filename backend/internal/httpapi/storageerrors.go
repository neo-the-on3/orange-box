package httpapi

import (
	"context"
	"errors"
	"net/http"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// storageProblem maps a busy or unreachable database (or failing object
// storage) to a 503 (#178), or
// returns nil for any other error. Lock and statement timeouts, deadlocks
// and waits that outlived the request (context deadline, pool acquire) are
// 503 server_busy with Retry-After: 1; an unreachable database is 503
// service_unavailable with Retry-After: 5. classify (db.Classify) may be
// nil, e.g. without a database.
func storageProblem(classify func(error) store.Failure, err error) *problem.Problem {
	f := store.FailureOther
	if classify != nil {
		f = classify(err)
	}
	var oe *objectStoreError
	if f == store.FailureOther && errors.As(err, &oe) && !errors.Is(err, context.DeadlineExceeded) {
		f = store.FailureUnavailable // object storage (R2) failed
	}
	if f == store.FailureOther && errors.Is(err, context.DeadlineExceeded) {
		f = store.FailureBusy
	}
	switch f {
	case store.FailureBusy:
		return problem.New(http.StatusServiceUnavailable, problem.CodeServerBusy,
			"The server is busy; retry shortly.").
			WithTitle("Server busy").WithRetryAfter(retryAfterServerBusy)
	case store.FailureUnavailable:
		return problem.New(http.StatusServiceUnavailable, problem.CodeUnavailable,
			"A backing service is unavailable; retry later.").
			WithTitle("Service unavailable").WithRetryAfter(retryAfterUnavailable)
	default:
		return nil
	}
}
