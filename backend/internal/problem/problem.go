// Package problem writes RFC 9457 problem details (application/problem+json).
//
// Every error response of the API goes through here so that it always has a
// stable machine-readable code and the request id, and never contains PII,
// tokens, request values or stack traces.
package problem

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
)

// TypeBase prefixes the problem type URI of coded problems. It mirrors the
// placeholder host used in openapi-v2.yaml; clients key off `code`, not `type`.
const TypeBase = "https://api.example.invalid/problems/"

// ContentType is the media type of problem responses.
const ContentType = "application/problem+json"

// Stable problem codes. Codes in openapi-v2.yaml keep their spelling.
const (
	CodeBadRequest          = "bad_request"
	CodeValidationFailed    = "validation_failed"
	CodeUnauthorized        = "unauthorized"
	CodeInvalidToken        = "invalid_token"
	CodeTokenExpired        = "token_expired"
	CodeNotFound            = "not_found"
	CodeMethodNotAllowed    = "method_not_allowed"
	CodePayloadTooLarge     = "batch_too_large"
	CodeUnsupportedEncoding = "unsupported_encoding"
	CodeUnsupportedMedia    = "unsupported_media_type"
	CodeInvalidGrant        = "invalid_grant"
	CodeOtpAttemptsExceeded = "otp_attempts_exceeded"
	CodeOtpLocked           = "otp_locked"
	CodeTestAddressRejected = "test_address_rejected"
	CodeEmailUnavailable    = "email_sign_in_unavailable"
	CodeNotImplemented      = "not_implemented"
	CodeUploadsBusy         = "uploads_busy"
	CodeUploadInProgress    = "upload_in_progress"
	CodeServerBusy          = "server_busy"
	CodeRequestTimeout      = "request_timeout"
	CodeEtagMismatch        = "etag_mismatch"
	CodePreconditionReq     = "precondition_required"
	CodeIdempotencyReused   = "idempotency_key_reused"
	CodeIdempotencyInFlight = "idempotency_in_flight"
	CodeVehicleDeleted      = "vehicle_deleted"
	CodeVehicleNotFound     = "vehicle_not_found"
	CodeTripDeleted         = "trip_deleted"
	CodeSyncWindowExpired   = "sync_window_expired"
	CodeInternal            = "internal_error"
	CodeUnavailable         = "service_unavailable"
)

// FieldError points at one invalid field of the request body.
type FieldError struct {
	// Pointer is an RFC 6901 JSON Pointer into the request body.
	Pointer string `json:"pointer"`
	// Detail says what is wrong without repeating the submitted value.
	Detail string `json:"detail"`
}

// Problem is an RFC 9457 problem details object as defined by the Problem
// schema in openapi-v2.yaml.
type Problem struct {
	Type      string       `json:"type"`
	Title     string       `json:"title"`
	Status    int          `json:"status"`
	Detail    string       `json:"detail,omitempty"`
	Instance  string       `json:"instance,omitempty"`
	Code      string       `json:"code,omitempty"`
	RequestID string       `json:"requestId"`
	Errors    []FieldError `json:"errors,omitempty"`

	// RetryAfter, when positive, is sent as the Retry-After header (seconds).
	RetryAfter int `json:"-"`
	// Headers are extra response headers (e.g. Allow, WWW-Authenticate).
	Headers http.Header `json:"-"`
}

// New returns a problem with the given status, code and detail. The title is
// the HTTP status text; use WithTitle for a more specific one.
func New(status int, code, detail string) *Problem {
	p := &Problem{Status: status, Title: http.StatusText(status), Code: code, Detail: detail, Type: "about:blank"}
	if code != "" {
		p.Type = TypeBase + strings.ReplaceAll(code, "_", "-")
	}
	return p
}

// WithTitle sets a specific title.
func (p *Problem) WithTitle(title string) *Problem { p.Title = title; return p }

// WithErrors attaches field errors.
func (p *Problem) WithErrors(errs ...FieldError) *Problem { p.Errors = errs; return p }

// WithRetryAfter sets the Retry-After header in seconds.
func (p *Problem) WithRetryAfter(seconds int) *Problem { p.RetryAfter = seconds; return p }

// WithHeader adds a response header.
func (p *Problem) WithHeader(name, value string) *Problem {
	if p.Headers == nil {
		p.Headers = http.Header{}
	}
	p.Headers.Add(name, value)
	return p
}

// Error implements error so a *Problem can travel up from handlers.
func (p *Problem) Error() string { return p.Code + ": " + p.Detail }

// Write sends p for request r. It fills in requestId and, unless set,
// instance (the request path, which only goes back to the caller; logs use
// the route template instead).
func Write(w http.ResponseWriter, r *http.Request, p *Problem) {
	out := *p
	out.RequestID = reqctx.RequestID(r.Context())
	if out.Instance == "" && r.URL != nil {
		out.Instance = r.URL.EscapedPath()
	}
	h := w.Header()
	for k, vs := range p.Headers {
		for _, v := range vs {
			h.Add(k, v)
		}
	}
	if p.RetryAfter > 0 {
		h.Set("Retry-After", strconv.Itoa(p.RetryAfter))
	}
	h.Set("Content-Type", ContentType)
	h.Set("Cache-Control", "no-store")
	h.Del("Content-Length")
	h.Del("ETag")
	w.WriteHeader(out.Status)
	_ = json.NewEncoder(w).Encode(out) // the client may be gone; nothing left to do
}

// Internal is the generic 500 problem; it never carries error details.
func Internal() *Problem {
	return New(http.StatusInternalServerError, CodeInternal, "An unexpected error occurred.").
		WithTitle("Internal Server Error")
}
