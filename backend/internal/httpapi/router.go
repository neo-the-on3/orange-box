// Package httpapi wires the generated strict server to net/http: routing,
// validation, auth, problem+json errors and the middleware stack.
package httpapi

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"reflect"
	"strings"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
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
	// UploadReadTimeout bounds how long an upload body may take to arrive
	// once it has a slot (default 10s).
	UploadReadTimeout time.Duration
	// Now is the clock (time.Now if nil).
	Now func() time.Time
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
	if d.UploadReadTimeout <= 0 {
		d.UploadReadTimeout = 10 * time.Second
	}
	spec, err := api.GetSpec()
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
	}
	errs := errorHandlers{logger: d.Logger}
	strict := api.NewStrictHandlerWithOptions(srv, nil, api.StrictHTTPServerOptions{
		RequestErrorHandlerFunc:  errs.request,
		ResponseErrorHandlerFunc: errs.response,
	})
	mux := http.NewServeMux()
	api.HandlerWithOptions(strict, api.StdHTTPServerOptions{
		BaseRouter:       mux,
		ErrorHandlerFunc: errs.request,
	})

	oa, err := newOpenAPIMiddleware(d.Tokens, newUploadLimiter(d.UploadConcurrency, d.UploadQueueWait), d.UploadReadTimeout)
	if err != nil {
		return nil, err
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
	logger *slog.Logger
}

// request handles parameter-binding and body-decoding errors from the
// generated code. The validator catches most problems first, but JSON
// Schema treats 415.0 and 1e3 as integers while encoding/json won't decode
// them (or out-of-range values) into Go ints; those become a 422 pointing
// at the field. The error text is never sent, because it can contain the
// submitted value.
func (e errorHandlers) request(w http.ResponseWriter, r *http.Request, err error) {
	var ute *json.UnmarshalTypeError
	if errors.As(err, &ute) && ute.Field != "" {
		problem.Write(w, r, problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, "1 field is invalid").
			WithTitle("Validation failed").
			WithErrors(problem.FieldError{Pointer: fieldPointer(ute.Field), Detail: typeDetail(ute.Type)}))
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

func typeDetail(t reflect.Type) string {
	if t == nil {
		return "has the wrong type"
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

// response handles errors returned by strict handlers: a *problem.Problem is
// sent as is, ErrNotImplemented becomes 501 and anything else is a logged 500.
func (e errorHandlers) response(w http.ResponseWriter, r *http.Request, err error) {
	var p *problem.Problem
	switch {
	case errors.As(err, &p):
		problem.Write(w, r, p)
	case errors.Is(err, api.ErrNotImplemented):
		problem.Write(w, r, problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
			"This operation is not implemented yet."))
	default:
		e.logger.ErrorContext(r.Context(), "handler error",
			slog.String("request_id", reqctx.RequestID(r.Context())),
			slog.Any("err", err))
		problem.Write(w, r, problem.Internal())
	}
}
