package httpapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	jsonv2 "encoding/json/v2"
	"errors"
	"log/slog"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/routers"
	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Idempotency-Key handling (openapi-v2.yaml, Conventions → Idempotency).
//
// On an operation that declares the Idempotency-Key header, a request with
// the header is run at most once per (member, method, route template, key):
//   - the first request claims the key (with a lease and a claim token),
//     runs, and stores its response, or releases the key if the response is
//     not worth replaying (5xx, 408, 409, 429), so a retry runs again;
//   - a retry with the same fingerprint (path parameters plus the
//     decompressed body) gets the stored response (same status and body);
//   - the same key with another fingerprint gets 422 idempotency_key_reused;
//   - a retry while the first is still running gets 409
//     idempotency_in_flight; once the first one's lease has run out (it
//     died), the retry takes the key over under a new claim token, and the
//     stale request can no longer complete or release it (#175).
//
// An operation may register a replay guard that can override a stored
// replay (POST /v1/vehicles: a deletion tombstone beats it with 410).

const (
	idempotencyTTL = 24 * time.Hour
	// maxStoredResponse is the largest response body stored for replay;
	// a larger one is not stored (the key is released instead).
	maxStoredResponse = 1 << 20
	// idempotencyStoreTimeout bounds the store calls made after the
	// handler, which run even if the client went away.
	idempotencyStoreTimeout = 5 * time.Second
)

// replayedHeaders are the response headers stored with a response.
var replayedHeaders = []string{"Content-Type", "ETag", "Location"}

// replayGuard may answer instead of a stored replay. A nil problem replays.
type replayGuard func(ctx context.Context, member uuid.UUID, body []byte) (*problem.Problem, error)

type idempotency struct {
	store    store.Idempotency
	lease    time.Duration
	guards   map[string]replayGuard // by lower-case operationId
	logger   *slog.Logger
	classify func(error) store.Failure
}

// acceptsIdempotencyKey reports whether op declares the Idempotency-Key header.
func acceptsIdempotencyKey(op *openapi3.Operation) bool {
	for _, p := range op.Parameters {
		if p.Value != nil && p.Value.In == openapi3.ParameterInHeader && strings.EqualFold(p.Value.Name, "Idempotency-Key") {
			return true
		}
	}
	return false
}

// serve runs next under the request's Idempotency-Key. The key has already
// been validated as a UUID and the member authenticated.
func (m *idempotency) serve(w http.ResponseWriter, r *http.Request, route *routers.Route, pathParams map[string]string, body []byte, next http.Handler) {
	member, err := callerID(r.Context())
	if err != nil {
		m.fail(w, r, err)
		return
	}
	key, err := uuid.Parse(r.Header.Get("Idempotency-Key"))
	if err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "Idempotency-Key must be a UUID."))
		return
	}
	scope := store.IdempotencyScope{MemberID: member, Method: r.Method, Route: route.Path, Key: key}
	fp := fingerprint(route.Path, pathParams, body)

	for range 3 {
		token := uuid.New()
		ok, err := m.store.Claim(r.Context(), scope, fp, token, time.Now().Add(m.lease), time.Now().Add(idempotencyTTL))
		if err != nil {
			m.fail(w, r, err)
			return
		}
		if ok {
			m.run(w, r, scope, token, next)
			return
		}
		rec, err := m.store.Get(r.Context(), scope)
		if errors.Is(err, store.ErrNotFound) {
			continue // released or purged since our claim: try again
		}
		if err != nil {
			m.fail(w, r, err)
			return
		}
		if !bytes.Equal(rec.Fingerprint, fp) {
			problem.Write(w, r, problem.New(http.StatusUnprocessableEntity, problem.CodeIdempotencyReused,
				"This Idempotency-Key was already used for a different request.").WithTitle("Idempotency-Key reused"))
			return
		}
		if rec.Completed {
			m.replay(w, r, route, member, body, rec)
			return
		}
		ok, err = m.store.Reclaim(r.Context(), scope, fp, token, time.Now().Add(m.lease))
		if err != nil {
			m.fail(w, r, err)
			return
		}
		if ok {
			m.run(w, r, scope, token, next)
			return
		}
		break
	}
	problem.Write(w, r, problem.New(http.StatusConflict, problem.CodeIdempotencyInFlight,
		"A request with this Idempotency-Key is still being processed; retry shortly.").
		WithTitle("Request in progress").WithRetryAfter(1))
}

// run serves the request, then stores its response or releases the key.
func (m *idempotency) run(w http.ResponseWriter, r *http.Request, scope store.IdempotencyScope, token uuid.UUID, next http.Handler) {
	rec := &replayRecorder{ResponseWriter: w}
	done := false
	defer func() {
		if !done { // the handler panicked: let a retry run again
			m.release(r, scope, token)
		}
	}()
	next.ServeHTTP(rec, r)
	done = true

	status := rec.status
	if status == 0 {
		status = http.StatusOK
	}
	if rec.overflow || !replayable(status) {
		m.release(r, scope, token)
		return
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), idempotencyStoreTimeout)
	defer cancel()
	ok, err := m.store.Complete(ctx, scope, token, status, rec.headers, rec.body.Bytes())
	switch {
	case err != nil:
		// The key stays in progress until its lease runs out; then a retry
		// takes it over and runs again.
		m.logger.ErrorContext(ctx, "store idempotent response", slog.Any("err", err))
	case !ok:
		m.logger.WarnContext(ctx, "idempotency claim lost before the response was stored (lease ran out)")
	}
}

func (m *idempotency) release(r *http.Request, scope store.IdempotencyScope, token uuid.UUID) {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), idempotencyStoreTimeout)
	defer cancel()
	if _, err := m.store.Release(ctx, scope, token); err != nil {
		m.logger.ErrorContext(ctx, "release idempotency key", slog.Any("err", err))
	}
}

func (m *idempotency) replay(w http.ResponseWriter, r *http.Request, route *routers.Route, member uuid.UUID, body []byte, rec store.IdempotencyRecord) {
	if guard := m.guards[strings.ToLower(route.Operation.OperationID)]; guard != nil {
		p, err := guard(r.Context(), member, body)
		if err != nil {
			m.fail(w, r, err)
			return
		}
		if p != nil {
			problem.Write(w, r, p)
			return
		}
	}
	h := w.Header()
	for _, name := range replayedHeaders {
		if v, ok := rec.Headers[name]; ok {
			h.Set(name, v)
		}
	}
	w.WriteHeader(rec.Status)
	_, _ = w.Write(rec.Body)
}

func (m *idempotency) fail(w http.ResponseWriter, r *http.Request, err error) {
	m.logger.ErrorContext(r.Context(), "idempotency",
		slog.String("request_id", reqctx.RequestID(r.Context())), slog.Any("err", err))
	if sp := storageProblem(m.classify, err); sp != nil {
		problem.Write(w, r, sp)
		return
	}
	problem.Write(w, r, problem.Internal())
}

// replayable reports whether a response with status is stored for replay.
// Server errors and statuses that invite a retry are not: the key is
// released so the retry runs again.
func replayable(status int) bool {
	switch {
	case status >= http.StatusInternalServerError:
		return false
	case status == http.StatusRequestTimeout, status == http.StatusConflict, status == http.StatusTooManyRequests:
		return false
	default:
		return true
	}
}

// fingerprint hashes the route template, the path parameters and the
// (decompressed) body, each length-prefixed so no two requests collide by
// concatenation.
func fingerprint(route string, pathParams map[string]string, body []byte) []byte {
	h := sha256.New()
	write := func(b []byte) {
		h.Write(binary.BigEndian.AppendUint64(nil, uint64(len(b))))
		h.Write(b)
	}
	write([]byte(route))
	names := make([]string, 0, len(pathParams))
	for k := range pathParams {
		names = append(names, k)
	}
	slices.Sort(names)
	for _, k := range names {
		write([]byte(k))
		write([]byte(pathParams[k]))
	}
	write(body)
	return h.Sum(nil)
}

// replayRecorder passes the response through and keeps a copy to store.
type replayRecorder struct {
	http.ResponseWriter
	status   int
	headers  map[string]string
	body     bytes.Buffer
	overflow bool
}

func (rr *replayRecorder) WriteHeader(code int) {
	if rr.status == 0 {
		rr.status = code
		rr.headers = map[string]string{}
		for _, name := range replayedHeaders {
			if v := rr.Header().Get(name); v != "" {
				rr.headers[name] = v
			}
		}
	}
	rr.ResponseWriter.WriteHeader(code)
}

func (rr *replayRecorder) Write(b []byte) (int, error) {
	if rr.status == 0 {
		rr.WriteHeader(http.StatusOK)
	}
	if !rr.overflow {
		if rr.body.Len()+len(b) > maxStoredResponse {
			rr.overflow = true
			rr.body = bytes.Buffer{}
		} else {
			rr.body.Write(b)
		}
	}
	return rr.ResponseWriter.Write(b)
}

func (rr *replayRecorder) Unwrap() http.ResponseWriter { return rr.ResponseWriter }

// unmarshalExact decodes JSON with exact-case member names (encoding/json/v2),
// like request bodies.
func unmarshalExact(b []byte, v any) error { return jsonv2.Unmarshal(b, v) }
