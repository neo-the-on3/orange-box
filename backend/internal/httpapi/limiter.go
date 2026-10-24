package httpapi

import (
	"context"
	"net/http"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
)

// uploadLimiter caps how many upload bodies are buffered and processed at
// once. Each can take up to MaxUploadBody of memory, so N slots bound upload
// memory to roughly N × 8 MiB (plus buffer growth) whatever the traffic.
type uploadLimiter struct {
	slots chan struct{}
	wait  time.Duration
}

func newUploadLimiter(n int, wait time.Duration) *uploadLimiter {
	if n < 1 {
		n = 1
	}
	return &uploadLimiter{slots: make(chan struct{}, n), wait: wait}
}

// retryAfterUploadBusy is the Retry-After (seconds) when no slot frees up.
const retryAfterUploadBusy = 5

// acquire takes a slot, waiting at most l.wait. It returns a release func,
// or a 503 problem when the server is saturated.
func (l *uploadLimiter) acquire(ctx context.Context) (release func(), p *problem.Problem) {
	select {
	case l.slots <- struct{}{}:
		return func() { <-l.slots }, nil
	default:
	}
	t := time.NewTimer(l.wait)
	defer t.Stop()
	select {
	case l.slots <- struct{}{}:
		return func() { <-l.slots }, nil
	case <-t.C:
	case <-ctx.Done():
	}
	return nil, problem.New(http.StatusServiceUnavailable, problem.CodeUploadsBusy,
		"Too many uploads are being processed; retry shortly.").
		WithTitle("Server busy").WithRetryAfter(retryAfterUploadBusy)
}
