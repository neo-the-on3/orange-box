package httpapi

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
)

// uploadLimiter caps how many upload bodies are buffered and processed at
// once. Each can take up to MaxUploadBody of memory, so N slots bound upload
// memory to roughly N × (body + decode/validation) whatever the traffic.
//
// A member may also have only one upload in flight: the app uploads its
// batches one after another, and the limit stops one account from holding
// every slot with slow or stalled bodies.
type uploadLimiter struct {
	slots chan struct{}
	wait  time.Duration

	mu     sync.Mutex
	active map[string]struct{} // members with an upload in flight
}

func newUploadLimiter(n int, wait time.Duration) *uploadLimiter {
	if n < 1 {
		n = 1
	}
	return &uploadLimiter{slots: make(chan struct{}, n), wait: wait, active: map[string]struct{}{}}
}

const (
	// retryAfterUploadBusy is the Retry-After (seconds) when no slot frees up.
	retryAfterUploadBusy = 5
	// retryAfterUploadInProgress is the Retry-After (seconds) when the member
	// already has an upload in flight.
	retryAfterUploadInProgress = 1
)

// acquire takes the member's single upload claim and then a global slot,
// waiting at most l.wait for the slot. It returns a release func, or a 429
// problem (the member already has an upload in flight) or a 503 problem
// (the server is saturated). An empty memberID skips the per-member check.
func (l *uploadLimiter) acquire(ctx context.Context, memberID string) (release func(), p *problem.Problem) {
	if memberID != "" {
		l.mu.Lock()
		_, busy := l.active[memberID]
		if !busy {
			l.active[memberID] = struct{}{}
		}
		l.mu.Unlock()
		if busy {
			return nil, problem.New(http.StatusTooManyRequests, problem.CodeUploadInProgress,
				"Another upload from this account is still being processed; send batches one at a time.").
				WithTitle("Upload in progress").WithRetryAfter(retryAfterUploadInProgress)
		}
	}
	unclaim := func() {
		if memberID != "" {
			l.mu.Lock()
			delete(l.active, memberID)
			l.mu.Unlock()
		}
	}

	select {
	case l.slots <- struct{}{}:
		return func() { <-l.slots; unclaim() }, nil
	default:
	}
	t := time.NewTimer(l.wait)
	defer t.Stop()
	select {
	case l.slots <- struct{}{}:
		return func() { <-l.slots; unclaim() }, nil
	case <-t.C:
	case <-ctx.Done():
	}
	unclaim()
	return nil, problem.New(http.StatusServiceUnavailable, problem.CodeUploadsBusy,
		"Too many uploads are being processed; retry shortly.").
		WithTitle("Server busy").WithRetryAfter(retryAfterUploadBusy)
}

// bodyLimiter caps how many non-upload request bodies (auth, profile,
// vehicle, ...) are read, decoded and validated at once, whoever sends them.
// Each is at most MaxBody bytes and MaxBodyValues values, so N slots bound
// that memory to a few MiB; a burst of anonymous bodies queues briefly and
// then gets 503 instead of growing the heap. Rate limiting per client is
// #114.
type bodyLimiter struct {
	slots chan struct{}
	wait  time.Duration
}

func newBodyLimiter(n int, wait time.Duration) *bodyLimiter {
	if n < 1 {
		n = 1
	}
	return &bodyLimiter{slots: make(chan struct{}, n), wait: wait}
}

// retryAfterServerBusy is the Retry-After (seconds) when no body slot frees up.
const retryAfterServerBusy = 1

func (l *bodyLimiter) acquire(ctx context.Context) (release func(), p *problem.Problem) {
	release = func() { <-l.slots }
	select {
	case l.slots <- struct{}{}:
		return release, nil
	default:
	}
	t := time.NewTimer(l.wait)
	defer t.Stop()
	select {
	case l.slots <- struct{}{}:
		return release, nil
	case <-t.C:
	case <-ctx.Done():
	}
	return nil, problem.New(http.StatusServiceUnavailable, problem.CodeServerBusy,
		"Too many requests are being processed; retry shortly.").
		WithTitle("Server busy").WithRetryAfter(retryAfterServerBusy)
}
