package httpapi

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/mail"
	"os"
	"strings"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/google/uuid"
	"github.com/santhosh-tekuri/jsonschema/v6"
	"github.com/santhosh-tekuri/jsonschema/v6/kind"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Request body limits (REQUIREMENTS.md §7, proposal).
const (
	// MaxCompressedBody is the largest gzip body accepted on upload operations.
	MaxCompressedBody = 1 << 20
	// MaxUploadBody is the largest uncompressed (or inflated) upload body.
	MaxUploadBody = 8 << 20
	// MaxBody is the largest body accepted on every other operation. Their
	// largest legitimate body (a vehicle or profile) is well under 2 KiB.
	MaxBody = 16 << 10
	// bodyReadTimeout bounds how long a non-upload body may take to arrive
	// once it holds a body slot.
	bodyReadTimeout = 5 * time.Second
	// maxFieldErrors caps the errors list of a 422.
	maxFieldErrors = 20
)

// uploadOperations accept Content-Encoding: gzip and the larger body limit.
// Keys are lower case: oapi-codegen's embedded spec capitalises operationIds.
var uploadOperations = map[string]bool{
	"uploadtrips":          true,
	"uploadtelemetrybatch": true,
	"createdtcreport":      true,
}

func isUpload(operationID string) bool { return uploadOperations[strings.ToLower(operationID)] }

func init() {
	// Formats used by the spec that kin-openapi doesn't validate by default.
	openapi3.DefineStringFormatValidator("uuid", openapi3.NewRegexpFormatValidator(openapi3.FormatOfStringForUUIDOfRFC9562))
	openapi3.DefineStringFormatValidator("email", openapi3.NewCallbackValidator(validateEmailFormat))
}

// errEmailFormat never contains the address.
var errEmailFormat = errors.New("not an e-mail address")

// validateEmailFormat accepts a bare addr-spec (no display name, no angle
// brackets) with a dotted domain.
//
// Surrounding whitespace is ignored: the spec says addresses are trimmed
// before use, and the handler normalises them.
func validateEmailFormat(s string) error {
	s = strings.TrimSpace(s)
	a, err := mail.ParseAddress(s)
	if err != nil || a.Name != "" || a.Address != s {
		return errEmailFormat
	}
	at := strings.LastIndexByte(s, '@')
	if at < 1 || !strings.Contains(s[at+1:], ".") {
		return errEmailFormat
	}
	return nil
}

// TokenVerifier checks an access token and returns its member id and issue
// time.
type TokenVerifier interface {
	VerifyIssued(raw string) (memberID string, issuedAt time.Time, err error)
}

// revocationCheck is the per-request check that the member behind a valid
// token still exists, isn't deleted and hasn't revoked the token
// (DELETE /v1/me). Nil without a database: in-memory members can't be
// deleted.
type revocationCheck struct {
	profiles store.Profiles
	logger   *slog.Logger
	classify func(error) store.Failure
}

// openAPIMiddleware is the front door for every /v1 request. In order, it:
//  1. finds the OpenAPI operation (404/405 problem otherwise);
//  2. authenticates operations that require bearerAuth (401) and puts the
//     member id in the context, before any body is read;
//  3. for upload operations, takes the member's single upload claim (429)
//     and one of a few processing slots (503 with Retry-After when none
//     frees up in time), and gives the body a short read deadline (408);
//  4. enforces Content-Encoding (gzip on upload operations only, else 415)
//     and the body limits (413), stream-inflating gzip bodies;
//  5. pre-scans the JSON for maxItems, value-count and depth limits without
//     decoding it (422/413/400), see prescan;
//  6. validates parameters and body against the spec (400/415/422), failing
//     fast on large bodies;
//  7. runs requests carrying an Idempotency-Key at most once (replay, 409,
//     422), see idempotency.
type openAPIMiddleware struct {
	doc               *openapi3.T
	router            routers.Router
	tokens            TokenVerifier
	uploads           *uploadLimiter
	uploadReadTimeout time.Duration
	bodies            bodyPools
	idempotency       *idempotency     // nil without a store
	revocation        *revocationCheck // nil without a database
}

func newOpenAPIMiddleware(tokens TokenVerifier, uploads *uploadLimiter, uploadReadTimeout time.Duration, bodies bodyPools) (*openAPIMiddleware, error) {
	doc, err := api.GetSpec()
	if err != nil {
		return nil, fmt.Errorf("load embedded spec: %w", err)
	}
	if err := applyServerLimits(doc); err != nil {
		return nil, fmt.Errorf("server limits: %w", err)
	}
	// Match on paths only: the spec's servers are placeholders, and the real
	// host is whatever the deployment is reached under.
	doc.Servers = nil
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		return nil, fmt.Errorf("build OpenAPI router: %w", err)
	}
	return &openAPIMiddleware{doc: doc, router: router, tokens: tokens, uploads: uploads, uploadReadTimeout: uploadReadTimeout, bodies: bodies}, nil
}

func (m *openAPIMiddleware) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		route, pathParams, p := m.findRoute(r)
		if p != nil {
			problem.Write(w, r, p)
			return
		}
		if info := reqctx.InfoFrom(r.Context()); info != nil {
			info.Route = route.Path
			info.OperationID = route.Operation.OperationID
		}

		// Authenticate before touching the body, so an anonymous client can
		// never make the server read, inflate or buffer anything.
		if requiresBearer(m.doc, route.Operation) {
			memberID, p := m.authenticate(r)
			if p != nil {
				problem.Write(w, r, p)
				return
			}
			r = r.WithContext(reqctx.WithMemberID(r.Context(), memberID))
		}

		// Upload bodies can be up to 8 MiB in memory each, so only a few are
		// read and processed at once; the slot is taken before the body is
		// read (reading first would buffer unbounded memory) and held until
		// the handler returns. A slow sender holds it for at most
		// UPLOAD_READ_TIMEOUT, and only one per member.
		upload := isUpload(route.Operation.OperationID)
		var body []byte
		if upload {
			// Header parameters (Idempotency-Key) are checked before the
			// upload claim, so a request missing its key gets 400, not 429.
			if err := m.validateHeaders(r, route, pathParams); err != nil {
				problem.Write(w, r, mapValidationError(err))
				return
			}
			memberID, _ := reqctx.MemberID(r.Context())
			release, p := m.uploads.acquire(r.Context(), memberID)
			if p != nil {
				// A retry of an upload that is still running holds the
				// member's claim itself: answer the spec's 409
				// idempotency_in_flight for it. 429 upload_in_progress
				// is for a different upload.
				if p.Code == problem.CodeUploadInProgress && m.idempotency != nil &&
					acceptsIdempotencyKey(route.Operation) && m.idempotency.inFlight(r, route) {
					p = idempotencyInFlight()
				}
				problem.Write(w, r, p)
				return
			}
			defer release()
			// A slot is held while the body arrives, so a slow or stalled
			// client gets a short deadline rather than the server's 30 s
			// ReadTimeout. Not every ResponseWriter supports it (tests).
			if m.uploadReadTimeout > 0 {
				_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(m.uploadReadTimeout))
			}
			if body, p = readBody(r, true); p != nil {
				problem.Write(w, r, p)
				return
			}
		} else if hasBody(r) {
			// Every other body (at most MaxBody) is read first, under a
			// short deadline and without a processing slot, so slow senders
			// can't hold slots. Anonymous requests count against a cap on
			// anonymous requests in flight from here to the end, which
			// bounds their buffered bodies. Then the request takes a slot
			// from its pool (anonymous or member) for prescan, validation,
			// decoding and the handler.
			_, member := reqctx.MemberID(r.Context())
			if !member {
				release, p := m.bodies.anonReads.tryAcquire()
				if p != nil {
					problem.Write(w, r, p)
					return
				}
				defer release()
			}
			_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(bodyReadTimeout))
			if body, p = readBody(r, false); p != nil {
				problem.Write(w, r, p)
				return
			}
			pool := m.bodies.members
			if !member {
				pool = m.bodies.anonymous
			}
			release, p := pool.acquire(r.Context())
			if p != nil {
				problem.Write(w, r, p)
				return
			}
			defer release()
		} else if body, p = readBody(r, false); p != nil {
			problem.Write(w, r, p)
			return
		}
		values := 0
		if len(body) > 0 {
			maxValues := MaxBodyValues
			if upload {
				maxValues = MaxJSONValues
			}
			var p *problem.Problem
			values, p = prescan(body, requestBodySchema(route.Operation, r.Header.Get("Content-Type")), maxValues)
			if p != nil {
				problem.Write(w, r, p)
				return
			}
		}

		if err := m.validate(r, route, pathParams, body, false); err != nil {
			// Fail-fast validation reports the first error only. For small
			// bodies, validate again to list every invalid field: its cost
			// is bounded by the body size and value count.
			if len(body) <= maxMultiErrorBody && values <= maxMultiErrorValues {
				if all := m.validate(r, route, pathParams, body, true); all != nil {
					err = all
				}
			}
			problem.Write(w, r, mapValidationError(err))
			return
		}
		setBody(r, body) // the validator consumed it
		if m.idempotency != nil && r.Header.Get("Idempotency-Key") != "" && acceptsIdempotencyKey(route.Operation) {
			m.idempotency.serve(w, r, route, pathParams, body, next)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// maxMultiErrorBody is the largest body validated with MultiError, which
// lists every invalid field. kin-openapi's MultiError validates every array
// item even past maxItems and builds an error per item, which costs about
// 4 KB of memory per byte-sized item ({}): 1.3 GB for a 1 MiB body. Larger
// bodies are validated fail-fast and report their first invalid field.
//
// maxMultiErrorValues also caps the value count, so the second pass costs at
// most a few MiB even for a body of tiny invalid items.
const (
	maxMultiErrorBody   = 8 << 10
	maxMultiErrorValues = 1024
)

// validateHeaders validates the operation's header parameters only.
func (m *openAPIMiddleware) validateHeaders(r *http.Request, route *routers.Route, pathParams map[string]string) error {
	input := &openapi3filter.RequestValidationInput{
		Request: r, PathParams: pathParams, Route: route,
		Options: &openapi3filter.Options{
			SkipSettingDefaults: true,
			AuthenticationFunc:  func(context.Context, *openapi3filter.AuthenticationInput) error { return nil },
		},
	}
	for _, p := range route.Operation.Parameters {
		if p.Value == nil || p.Value.In != openapi3.ParameterInHeader {
			continue
		}
		if err := openapi3filter.ValidateParameter(r.Context(), input, p.Value); err != nil {
			return err
		}
	}
	return nil
}

// hasBody reports whether r may carry a body (it has one of unknown or
// non-zero length).
func hasBody(r *http.Request) bool {
	return r.Body != nil && r.Body != http.NoBody && r.ContentLength != 0
}

// validate runs kin-openapi request validation over body.
func (m *openAPIMiddleware) validate(r *http.Request, route *routers.Route, pathParams map[string]string, body []byte, multi bool) error {
	setBody(r, body)
	input := &openapi3filter.RequestValidationInput{
		Request:    r,
		PathParams: pathParams,
		Route:      route,
		Options: &openapi3filter.Options{
			MultiError:          multi,
			SkipSettingDefaults: true, // keep the body byte-exact for idempotency fingerprints
			// Authentication already happened in wrap.
			AuthenticationFunc: func(context.Context, *openapi3filter.AuthenticationInput) error { return nil },
		},
	}
	if r.Method == http.MethodHead {
		input.Request = r.Clone(r.Context())
		input.Request.Method = http.MethodGet
	}
	return openapi3filter.ValidateRequest(r.Context(), input)
}

// requestBodySchema returns the operation's body schema for contentType, or
// nil when there is none (the validator then reports 415 or ignores the body).
func requestBodySchema(op *openapi3.Operation, contentType string) *openapi3.Schema {
	if op.RequestBody == nil || op.RequestBody.Value == nil {
		return nil
	}
	mt, _, err := mime.ParseMediaType(contentType)
	if err != nil {
		return nil
	}
	media := op.RequestBody.Value.Content.Get(mt)
	if media == nil || media.Schema == nil {
		return nil
	}
	return media.Schema.Value
}

// findRoute returns the operation for r. HEAD is served by the GET operation.
func (m *openAPIMiddleware) findRoute(r *http.Request) (*routers.Route, map[string]string, *problem.Problem) {
	lookup := r
	if r.Method == http.MethodHead {
		lookup = r.Clone(r.Context())
		lookup.Method = http.MethodGet
	}
	route, params, err := m.router.FindRoute(lookup)
	switch {
	case err == nil:
		return route, params, nil
	case errors.Is(err, routers.ErrMethodNotAllowed):
		allow := m.allowedMethods(r)
		return nil, nil, problem.New(http.StatusMethodNotAllowed, problem.CodeMethodNotAllowed,
			"This method is not supported on this resource.").WithHeader("Allow", strings.Join(allow, ", "))
	default:
		return nil, nil, problem.New(http.StatusNotFound, problem.CodeNotFound, "No such resource.")
	}
}

func (m *openAPIMiddleware) allowedMethods(r *http.Request) []string {
	var allow []string
	for _, method := range []string{http.MethodGet, http.MethodHead, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete} {
		probe := r.Clone(r.Context())
		probe.Method = method
		if method == http.MethodHead {
			probe.Method = http.MethodGet
		}
		if _, _, err := m.router.FindRoute(probe); err == nil {
			allow = append(allow, method)
		}
	}
	return allow
}

// requiresBearer reports whether op needs bearerAuth (the document default
// unless the operation overrides security, e.g. with `security: []`).
func requiresBearer(doc *openapi3.T, op *openapi3.Operation) bool {
	reqs := doc.Security
	if op.Security != nil {
		reqs = *op.Security
	}
	for _, req := range reqs {
		if _, ok := req["bearerAuth"]; ok {
			return true
		}
	}
	return false
}

func (m *openAPIMiddleware) authenticate(r *http.Request) (string, *problem.Problem) {
	const realm = `Bearer realm="orange-box"`
	h := r.Header.Get("Authorization")
	scheme, raw, ok := strings.Cut(h, " ")
	if h == "" || !ok || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(raw) == "" {
		return "", problem.New(http.StatusUnauthorized, problem.CodeUnauthorized, "A bearer access token is required.").
			WithHeader("WWW-Authenticate", realm)
	}
	memberID, issuedAt, err := m.tokens.VerifyIssued(strings.TrimSpace(raw))
	switch {
	case errors.Is(err, auth.ErrTokenExpired):
		return "", problem.New(http.StatusUnauthorized, problem.CodeTokenExpired, "The access token has expired.").
			WithHeader("WWW-Authenticate", realm+`, error="invalid_token", error_description="expired"`)
	case err != nil:
		return "", problem.New(http.StatusUnauthorized, problem.CodeInvalidToken, "The access token is invalid.").
			WithHeader("WWW-Authenticate", realm+`, error="invalid_token"`)
	}
	if m.revocation != nil {
		if p := m.revocation.check(r.Context(), memberID, issuedAt); p != nil {
			if p.Status == http.StatusUnauthorized {
				p = p.WithHeader("WWW-Authenticate", realm+`, error="invalid_token", error_description="revoked"`)
			}
			return "", p
		}
	}
	return memberID, nil
}

// check runs on every authenticated request (spec: deleteMe), so a deleted
// member's outstanding access tokens get 401 at once rather than at their
// 15-minute expiry. A failed check fails closed (503 or 500), never open.
func (c *revocationCheck) check(ctx context.Context, memberID string, issuedAt time.Time) *problem.Problem {
	member, err := uuid.Parse(memberID)
	if err != nil { // Verify already checked sub is a UUID
		return problem.New(http.StatusUnauthorized, problem.CodeInvalidToken, "The access token is invalid.")
	}
	revoked, err := c.profiles.TokenRevoked(ctx, member, issuedAt)
	if err != nil {
		c.logger.ErrorContext(ctx, "token revocation check failed",
			slog.String("request_id", reqctx.RequestID(ctx)), slog.Any("err", err))
		if sp := storageProblem(c.classify, err); sp != nil {
			return sp
		}
		return problem.Internal()
	}
	if revoked {
		return problem.New(http.StatusUnauthorized, problem.CodeInvalidToken, "The access token has been revoked; sign in again.")
	}
	return nil
}

// errTooLarge is returned by limitReader once its limit is exceeded.
var errTooLarge = errors.New("body too large")

// limitReader reads at most n bytes from r and fails with errTooLarge as
// soon as the input has more, so oversized input is never read in full.
type limitReader struct {
	r io.Reader
	n int64 // bytes still allowed
}

func (l *limitReader) Read(p []byte) (int, error) {
	if l.n < 0 {
		return 0, errTooLarge
	}
	if int64(len(p)) > l.n+1 {
		p = p[:l.n+1] // one extra byte detects overflow
	}
	n, err := l.r.Read(p)
	l.n -= int64(n)
	if l.n < 0 {
		return n, errTooLarge
	}
	return n, err
}

// readBody enforces Content-Encoding and the size limits and returns the
// (inflated) body. Only upload operations may send gzip. Gzip is inflated as
// a stream straight from the connection: neither the compressed nor the
// inflated input is ever read past its limit.
func readBody(r *http.Request, upload bool) ([]byte, *problem.Problem) {
	encoding := strings.TrimSpace(r.Header.Get("Content-Encoding"))
	gzipped := false
	switch {
	case encoding == "" || strings.EqualFold(encoding, "identity"):
	case strings.EqualFold(encoding, "gzip") && upload:
		gzipped = true
	default:
		return nil, problem.New(http.StatusUnsupportedMediaType, problem.CodeUnsupportedEncoding,
			"Only Content-Encoding gzip is supported, and only on batch uploads.").
			WithHeader("Accept-Encoding", acceptEncoding(upload))
	}

	limit := int64(MaxBody)
	switch {
	case gzipped:
		limit = MaxCompressedBody
	case upload:
		limit = MaxUploadBody
	}
	tooLarge := problem.New(http.StatusRequestEntityTooLarge, problem.CodePayloadTooLarge, "The request body is too large.")
	if r.ContentLength > limit {
		return nil, tooLarge
	}
	if r.Body == nil || r.Body == http.NoBody {
		return nil, nil
	}

	var src io.Reader = &limitReader{r: r.Body, n: limit}
	if gzipped {
		zr, err := gzip.NewReader(src)
		if err != nil {
			if errors.Is(err, errTooLarge) {
				return nil, tooLarge
			}
			if errors.Is(err, os.ErrDeadlineExceeded) {
				return nil, bodyTimeout()
			}
			return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The gzip body is malformed.")
		}
		defer func() { _ = zr.Close() }()
		src = &limitReader{r: zr, n: MaxUploadBody}
	}

	maxOut := limit
	if gzipped {
		maxOut = MaxUploadBody
	}
	hint := int64(0)
	if !gzipped {
		hint = r.ContentLength
	}
	body, err := readCapped(src, hint, maxOut+1)
	if err != nil {
		if errors.Is(err, errTooLarge) {
			return nil, tooLarge
		}
		if errors.Is(err, os.ErrDeadlineExceeded) {
			return nil, bodyTimeout()
		}
		if gzipped {
			return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The gzip body is malformed.")
		}
		return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request body could not be read.")
	}
	if gzipped {
		r.Header.Del("Content-Encoding")
	}
	return body, nil
}

// bodyTimeout is the problem for an upload body that missed its read
// deadline. The connection can't be reused after a timed-out read.
func bodyTimeout() *problem.Problem {
	return problem.New(http.StatusRequestTimeout, problem.CodeRequestTimeout,
		"The request body was not received in time.").WithHeader("Connection", "close")
}

// readCapped reads src to EOF into a buffer that starts at hint (or 32 KiB)
// and doubles, but never beyond maxCap-1 bytes (the body limit), so a body
// at the limit costs about 2× the limit in allocations rather than
// bytes.Buffer's 4×. More than maxCap-1 bytes is errTooLarge.
func readCapped(src io.Reader, hint, maxCap int64) ([]byte, error) {
	size := int64(32 << 10)
	if hint > 0 {
		size = hint + 1 // +1 so a body of exactly hint bytes sees EOF without growing
	}
	b := make([]byte, 0, min(size, maxCap))
	for {
		if len(b) == cap(b) {
			if int64(cap(b)) >= maxCap-1 {
				// Full at the limit: probe for one more byte rather than
				// reallocating the whole buffer to detect overflow.
				var one [1]byte
				n, err := io.ReadFull(src, one[:])
				switch {
				case n > 0 || errors.Is(err, errTooLarge):
					return nil, errTooLarge
				case errors.Is(err, io.EOF):
					return b, nil
				default:
					return nil, err
				}
			}
			nb := make([]byte, len(b), min(2*int64(cap(b)), maxCap))
			copy(nb, b)
			b = nb
		}
		n, err := src.Read(b[len(b):cap(b)])
		b = b[:len(b)+n]
		if errors.Is(err, io.EOF) {
			return b, nil
		}
		if err != nil {
			return nil, err
		}
	}
}

func acceptEncoding(upload bool) string {
	if upload {
		return "gzip, identity"
	}
	return "identity"
}

// setBody replaces both Body and GetBody (so redirects and retries inside
// the process see the inflated body too) and fixes Content-Length.
func setBody(r *http.Request, body []byte) {
	if body == nil {
		r.Body = http.NoBody
		r.GetBody = func() (io.ReadCloser, error) { return http.NoBody, nil }
		r.ContentLength = 0
		return
	}
	r.Body = io.NopCloser(bytes.NewReader(body))
	r.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(body)), nil }
	r.ContentLength = int64(len(body))
	r.Header.Set("Content-Length", fmt.Sprint(len(body)))
}

// mapValidationError turns a kin-openapi error into a problem. Messages are
// built from the schema only, never from the submitted values, because
// validator messages can echo them (e-mail addresses, VINs, codes).
func mapValidationError(err error) *problem.Problem {
	var reqErrs []*openapi3filter.RequestError
	var multi openapi3.MultiError
	if errors.As(err, &multi) {
		for _, e := range multi {
			var re *openapi3filter.RequestError
			if errors.As(e, &re) {
				reqErrs = append(reqErrs, re)
			}
		}
	} else {
		var re *openapi3filter.RequestError
		if errors.As(err, &re) {
			reqErrs = append(reqErrs, re)
		}
	}
	if len(reqErrs) == 0 {
		return problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request is invalid.")
	}

	// Parameters first: a bad query, path or header parameter is a 400.
	for _, re := range reqErrs {
		if re.Parameter != nil {
			return problem.New(http.StatusBadRequest, problem.CodeBadRequest,
				fmt.Sprintf("The %s parameter %q is missing or invalid.", re.Parameter.In, re.Parameter.Name))
		}
	}

	var fields []problem.FieldError
	for _, re := range reqErrs {
		if re.RequestBody == nil {
			continue
		}
		switch {
		case strings.HasPrefix(re.Reason, "header Content-Type has unexpected value"):
			return problem.New(http.StatusUnsupportedMediaType, problem.CodeUnsupportedMedia,
				"The Content-Type is not supported by this operation.")
		case errors.Is(re.Err, openapi3filter.ErrInvalidRequired), errors.Is(re.Err, openapi3filter.ErrInvalidEmptyValue):
			return problem.New(http.StatusBadRequest, problem.CodeBadRequest, "A request body is required.")
		}
		var pe *openapi3filter.ParseError
		if errors.As(re.Err, &pe) {
			return problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request body is not valid JSON.")
		}
		fields = append(fields, fieldErrors(re.Err)...)
	}
	if len(fields) == 0 {
		return problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request is invalid.")
	}
	fields = dedupe(fields)
	if len(fields) > maxFieldErrors {
		fields = fields[:maxFieldErrors]
	}
	detail := "1 field is invalid"
	if len(fields) > 1 {
		detail = fmt.Sprintf("%d fields are invalid", len(fields))
	}
	return problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, detail).
		WithTitle("Validation failed").WithErrors(fields...)
}

// fieldErrors flattens kin-openapi's error tree (MultiError, SchemaError
// chains from its OpenAPI 3.0 validator, and JSON Schema 2020-12 errors) into
// pointer + detail pairs.
func fieldErrors(err error) []problem.FieldError {
	var out []problem.FieldError
	walkSchemaErrors(err, &out, 0)
	if len(out) == 0 {
		out = append(out, problem.FieldError{Pointer: "", Detail: "is invalid"})
	}
	return out
}

func walkSchemaErrors(err error, out *[]problem.FieldError, depth int) {
	if err == nil || depth > 32 {
		return
	}
	switch e := err.(type) { //nolint:errorlint // walking the tree node by node on purpose
	case openapi3.MultiError:
		for _, sub := range e {
			walkSchemaErrors(sub, out, depth+1)
		}
	case *openapi3filter.RequestError:
		walkSchemaErrors(e.Err, out, depth+1)
	case *jsonschema.ValidationError:
		collectLeaves(e, out)
	case *openapi3.SchemaError:
		var ve *jsonschema.ValidationError
		if errors.As(e, &ve) {
			collectLeaves(ve, out)
			return
		}
		var multi openapi3.MultiError
		var inner *openapi3.SchemaError
		if errors.As(e.Origin, &multi) || errors.As(e.Origin, &inner) {
			walkSchemaErrors(e.Origin, out, depth+1)
			return
		}
		*out = append(*out, problem.FieldError{Pointer: pointer(e.JSONPointer()), Detail: describeField(e.SchemaField, e.Schema)})
	default:
		if u := errors.Unwrap(err); u != nil {
			walkSchemaErrors(u, out, depth+1)
		}
	}
}

// describeField explains a failed keyword from kin-openapi's 3.0 validator
// using schema values only.
func describeField(field string, s *openapi3.Schema) string {
	switch field {
	case "required":
		return "is required"
	case "type", "nullable":
		return "has the wrong type"
	case "format":
		if s != nil && s.Format != "" {
			return "must be a valid " + s.Format
		}
		return "has an invalid format"
	case "pattern":
		return "has an invalid format"
	case "enum", "const":
		return "is not an allowed value"
	case "maxLength":
		if s != nil && s.MaxLength != nil {
			return fmt.Sprintf("must be at most %d characters", *s.MaxLength)
		}
	case "minLength":
		if s != nil {
			return fmt.Sprintf("must be at least %d characters", s.MinLength)
		}
	case "maximum":
		if s != nil && s.Max != nil {
			return fmt.Sprintf("must be at most %v", *s.Max)
		}
	case "minimum":
		if s != nil && s.Min != nil {
			return fmt.Sprintf("must be at least %v", *s.Min)
		}
	case "maxItems":
		if s != nil && s.MaxItems != nil {
			return fmt.Sprintf("must have at most %d items", *s.MaxItems)
		}
	case "minItems":
		if s != nil {
			return fmt.Sprintf("must have at least %d items", s.MinItems)
		}
	case "additionalProperties":
		return "has properties that are not allowed"
	case "oneOf", "anyOf":
		return "does not match any of the allowed shapes"
	}
	return "is invalid"
}

func collectLeaves(ve *jsonschema.ValidationError, out *[]problem.FieldError) {
	if len(ve.Causes) > 0 {
		for _, c := range ve.Causes {
			collectLeaves(c, out)
		}
		return
	}
	ptr := pointer(ve.InstanceLocation)
	if k, ok := ve.ErrorKind.(*kind.Required); ok {
		for _, name := range k.Missing { // property names come from the schema
			*out = append(*out, problem.FieldError{Pointer: ptr + "/" + escapePointer(name), Detail: "is required"})
		}
		return
	}
	*out = append(*out, problem.FieldError{Pointer: ptr, Detail: describe(ve.ErrorKind)})
}

// describe explains a failed keyword using schema values only.
func describe(k jsonschema.ErrorKind) string {
	switch k := k.(type) {
	case *kind.Type:
		return "must be of type " + strings.Join(k.Want, " or ")
	case *kind.Format:
		return "must be a valid " + k.Want
	case *kind.Pattern:
		return "has an invalid format"
	case *kind.Enum, *kind.Const:
		return "is not an allowed value"
	case *kind.MinLength:
		return fmt.Sprintf("must be at least %d characters", k.Want)
	case *kind.MaxLength:
		return fmt.Sprintf("must be at most %d characters", k.Want)
	case *kind.Minimum:
		return "must be at least " + k.Want.FloatString(2)
	case *kind.Maximum:
		return "must be at most " + k.Want.FloatString(2)
	case *kind.ExclusiveMinimum:
		return "must be greater than " + k.Want.FloatString(2)
	case *kind.ExclusiveMaximum:
		return "must be less than " + k.Want.FloatString(2)
	case *kind.MinItems:
		return fmt.Sprintf("must have at least %d items", k.Want)
	case *kind.MaxItems:
		return fmt.Sprintf("must have at most %d items", k.Want)
	case *kind.MinProperties:
		return fmt.Sprintf("must have at least %d properties", k.Want)
	case *kind.AdditionalProperties:
		return "has properties that are not allowed"
	case *kind.FalseSchema:
		return "is not allowed"
	case *kind.OneOf, *kind.AnyOf:
		return "does not match any of the allowed shapes"
	default:
		return "is invalid"
	}
}

func pointer(tokens []string) string {
	var b strings.Builder
	for _, t := range tokens {
		b.WriteByte('/')
		b.WriteString(escapePointer(t))
	}
	return b.String()
}

func escapePointer(s string) string {
	return strings.NewReplacer("~", "~0", "/", "~1").Replace(s)
}

func dedupe(in []problem.FieldError) []problem.FieldError {
	seen := map[problem.FieldError]bool{}
	out := in[:0]
	for _, f := range in {
		if !seen[f] {
			seen[f] = true
			out = append(out, f)
		}
	}
	return out
}

// newRequestID returns a UUIDv7 (time-ordered, so logs sort naturally).
func newRequestID() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}
