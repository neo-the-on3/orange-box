// Package httpapi wires the generated strict server to net/http: routing,
// validation, auth, problem+json errors and the middleware stack.
package httpapi

import (
	"context"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"log/slog"
	"net/http"
	"reflect"
	"strings"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/objstore"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
	"github.com/neo-the-on3/orange-box/backend/internal/synctoken"
)

// Deps are the router's collaborators.
type Deps struct {
	Logger  *slog.Logger
	Tokens  *auth.Tokens
	Members auth.MemberStore
	Codes   *auth.PendingCodes
	// TestMode enables test-mode sign-in with TestCode (already validated by config).
	TestMode bool
	TestCode string
	// RequestTimeout bounds each request's context.
	RequestTimeout time.Duration
	// UploadConcurrency caps upload bodies processed at once (default 2).
	UploadConcurrency int
	// UploadQueueWait is how long an upload waits for a slot before 503 (default 2s).
	UploadQueueWait time.Duration
	// BodyConcurrency caps signed-in non-upload requests with a body being
	// pre-scanned, validated, decoded and handled at once (default 16); a
	// further one waits UploadQueueWait, then gets 503 server_busy. Bodies
	// are read before a slot is taken.
	BodyConcurrency int
	// AnonBodyConcurrency is the same for anonymous requests (sign-in),
	// in a separate pool (default 8).
	AnonBodyConcurrency int
	// AnonReadConcurrency caps anonymous requests with a body in flight,
	// reading included (default 1024, i.e. at most 16 MiB of buffered
	// anonymous bodies); beyond it, 503 at once.
	AnonReadConcurrency int
	// UploadReadTimeout bounds how long an upload body may take to arrive
	// once it has a slot (default 10s).
	UploadReadTimeout time.Duration
	// Now is the clock (time.Now if nil).
	Now func() time.Time
	// Ready, if set, is checked by GET /v1/health (e.g. a database ping);
	// an error turns the answer into 503 service_unavailable.
	Ready func(context.Context) error
	// Vehicles stores vehicles. Nil (no database, local/ci/sandbox only)
	// makes the vehicle operations answer 501.
	Vehicles store.Vehicles
	// Idempotency stores Idempotency-Keys. Nil (no database) means the
	// header is validated but not acted on.
	Idempotency store.Idempotency
	// Trips stores trips. Nil (no database) makes the trip operations
	// answer 501.
	Trips store.Trips
	// Telemetry stores telemetry batch metadata and events, and Objects
	// the batches' samples (R2 in production). Without either the
	// telemetry operations answer 501.
	Telemetry store.Telemetry
	Objects   objstore.Store
	// SyncTokenKey signs sync tokens and trip cursors (SYNC_TOKEN_KEY).
	// Nil generates an ephemeral key: tokens then expire on restart.
	SyncTokenKey []byte
	// ClassifyError sorts storage errors (db.Classify) so a busy or
	// unreachable database answers 503 instead of 500 (#178).
	ClassifyError func(error) store.Failure
}

// NewRouter returns the root handler. Middleware runs outermost first:
// request id → access log → panic recovery → default headers → timeout →
// OpenAPI (route, auth, upload slots, encoding, limits, validation) →
// generated handlers.
func NewRouter(d Deps) (http.Handler, error) {
	if d.Logger == nil || d.Tokens == nil || d.Members == nil || d.Codes == nil {
		return nil, errors.New("httpapi: Logger, Tokens, Members and Codes are required")
	}
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.RequestTimeout <= 0 {
		d.RequestTimeout = 30 * time.Second
	}
	if d.UploadConcurrency <= 0 {
		d.UploadConcurrency = 2
	}
	if d.UploadQueueWait <= 0 {
		d.UploadQueueWait = 2 * time.Second
	}
	if d.BodyConcurrency <= 0 {
		d.BodyConcurrency = 16
	}
	if d.AnonBodyConcurrency <= 0 {
		d.AnonBodyConcurrency = 8
	}
	if d.AnonReadConcurrency <= 0 {
		d.AnonReadConcurrency = 1024
	}
	if d.UploadReadTimeout <= 0 {
		d.UploadReadTimeout = 10 * time.Second
	}
	spec, err := api.GetSpec()
	if err != nil {
		return nil, err
	}
	if d.SyncTokenKey == nil {
		d.SyncTokenKey = synctoken.GenerateKey()
	}
	syncCodec, err := synctoken.New(d.SyncTokenKey)
	if err != nil {
		return nil, err
	}
	srv := &Server{
		apiVersion: spec.Info.Version,
		tokens:     d.Tokens,
		members:    d.Members,
		codes:      d.Codes,
		testMode:   d.TestMode,
		testCode:   d.TestCode,
		now:        d.Now,
		ready:      d.Ready,
		logger:     d.Logger,
		vehicles:   d.Vehicles,
		trips:      d.Trips,
		telemetry:  d.Telemetry,
		objects:    d.Objects,
		sync:       syncCodec,
	}
	errs := errorHandlers{logger: d.Logger, classify: d.ClassifyError}
	strict := api.NewStrictHandlerWithOptions(srv, nil, api.StrictHTTPServerOptions{
		RequestErrorHandlerFunc:  errs.request,
		ResponseErrorHandlerFunc: errs.response,
	})
	mux := http.NewServeMux()
	api.HandlerWithOptions(strict, api.StdHTTPServerOptions{
		BaseRouter:       mux,
		ErrorHandlerFunc: errs.request,
	})

	oa, err := newOpenAPIMiddleware(d.Tokens, newUploadLimiter(d.UploadConcurrency, d.UploadQueueWait), d.UploadReadTimeout,
		bodyPools{
			members:   newBodyLimiter(d.BodyConcurrency, d.UploadQueueWait),
			anonymous: newBodyLimiter(d.AnonBodyConcurrency, d.UploadQueueWait),
			anonReads: newReadLimiter(d.AnonReadConcurrency),
		})
	if err != nil {
		return nil, err
	}
	if d.Idempotency != nil {
		oa.idempotency = &idempotency{
			store: d.Idempotency,
			// Outlives the request (its context ends at RequestTimeout), so a
			// live request is never taken over.
			lease:  d.RequestTimeout + 2*idempotencyStoreTimeout,
			guards: map[string]replayGuard{"createvehicle": srv.createVehicleReplayGuard},
			rewrites: map[string]replayRewrite{
				"uploadtrips":          srv.uploadTripsReplay,
				"uploadtelemetrybatch": srv.uploadTelemetryReplay,
			},
			logger:   d.Logger,
			classify: d.ClassifyError,
		}
	}
	var h http.Handler = mux
	h = oa.wrap(h)
	h = timeout(d.RequestTimeout)(h)
	h = defaultHeaders(h)
	h = recoverer(d.Logger)(h)
	h = accessLog(d.Logger)(h)
	h = requestID(h)
	return h, nil
}

type errorHandlers struct {
	logger   *slog.Logger
	classify func(error) store.Failure
}

// request handles parameter-binding and body-decoding errors from the
// generated code. The validator catches most problems first, but JSON
// Schema treats 415.0 and 1e3 as integers while encoding/json won't decode
// them (or out-of-range values) into Go ints; those become a 422 pointing
// at the field. The error text is never sent, because it can contain the
// submitted value.
func (e errorHandlers) request(w http.ResponseWriter, r *http.Request, err error) {
	field := func(ptr string, t reflect.Type) {
		problem.Write(w, r, problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, "1 field is invalid").
			WithTitle("Validation failed").
			WithErrors(problem.FieldError{Pointer: ptr, Detail: typeDetail(t)}))
	}
	// encoding/json (v1) errors come from types with their own UnmarshalJSON
	// (oneOf unions); request bodies themselves are decoded with v2.
	var ute *json.UnmarshalTypeError
	if errors.As(err, &ute) && ute.Field != "" {
		field(fieldPointer(ute.Field), ute.Type)
		return
	}
	var se *jsonv2.SemanticError
	if errors.As(err, &se) && se.JSONPointer != "" && se.GoType != nil {
		field(string(se.JSONPointer), se.GoType)
		return
	}
	problem.Write(w, r, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request is malformed."))
}

// fieldPointer turns encoding/json's dotted field path ("trips.1.distanceKm")
// into a JSON Pointer ("/trips/1/distanceKm").
func fieldPointer(field string) string {
	var b strings.Builder
	for part := range strings.SplitSeq(field, ".") {
		b.WriteByte('/')
		b.WriteString(escapePointer(part))
	}
	return b.String()
}

// nullablePkg is the package of nullable.Nullable[T] (map[bool]T), which
// oapi-codegen uses for nullable fields.
const nullablePkg = "github.com/oapi-codegen/nullable"

func typeDetail(t reflect.Type) string {
	if t == nil {
		return "has the wrong type"
	}
	// Describe the element of *T and nullable.Nullable[T] (#168).
	for {
		switch {
		case t.Kind() == reflect.Pointer:
			t = t.Elem()
			continue
		case t.Kind() == reflect.Map && t.PkgPath() == nullablePkg && t.Key().Kind() == reflect.Bool:
			t = t.Elem()
			continue
		}
		break
	}
	switch t.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return "must be a whole number written without a decimal point or exponent, within range"
	case reflect.Float32, reflect.Float64:
		return "must be a number within range"
	default:
		return "has the wrong type"
	}
}

// statusClientClosedRequest is logged when the client disconnected before
// the response (nginx's 499); the client never sees it.
const statusClientClosedRequest = 499

// response handles errors returned by strict handlers: a *problem.Problem is
// sent as is, ErrNotImplemented becomes 501, a client disconnect 499 (INFO),
// a busy or unreachable database a logged 503 (#178) and anything else is a
// logged 500.
func (e errorHandlers) response(w http.ResponseWriter, r *http.Request, err error) {
	var p *problem.Problem
	switch {
	case errors.As(err, &p):
		problem.Write(w, r, p)
	case errors.Is(err, api.ErrNotImplemented):
		problem.Write(w, r, problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
			"This operation is not implemented yet."))
	case errors.Is(err, context.Canceled) && r.Context().Err() != nil:
		// The client went away mid-request: nobody reads the response, and
		// it isn't a server error. 499 (nginx's "client closed request")
		// keeps it out of 5xx alerting; the access log logs it at INFO.
		e.logger.InfoContext(r.Context(), "client disconnected",
			slog.String("request_id", reqctx.RequestID(r.Context())))
		w.WriteHeader(statusClientClosedRequest)
	default:
		if sp := storageProblem(e.classify, err); sp != nil {
			e.logger.WarnContext(r.Context(), "storage busy or unavailable",
				slog.String("request_id", reqctx.RequestID(r.Context())),
				slog.Any("err", err))
			problem.Write(w, r, sp)
			return
		}
		e.logger.ErrorContext(r.Context(), "handler error",
			slog.String("request_id", reqctx.RequestID(r.Context())),
			slog.Any("err", err))
		problem.Write(w, r, problem.Internal())
	}
}
