// Package httpapi wires the generated strict server to net/http: routing,
// validation, auth, problem+json errors and the middleware stack.
package httpapi

import (
	"errors"
	"log/slog"
	"net/http"
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
	// Now is the clock (time.Now if nil).
	Now func() time.Time
}

// NewRouter returns the root handler. Middleware runs outermost first:
// request id → access log → panic recovery → default headers → timeout →
// OpenAPI (route, encoding, limits, auth, validation) → generated handlers.
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

	oa, err := newOpenAPIMiddleware(d.Tokens)
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
// generated code. The validator normally catches these first; the error
// text is never sent, because it can contain the submitted value.
func (e errorHandlers) request(w http.ResponseWriter, r *http.Request, _ error) {
	problem.Write(w, r, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request is malformed."))
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
