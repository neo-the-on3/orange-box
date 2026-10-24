package httpapi

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/mail"
	"strings"

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
)

// Request body limits (REQUIREMENTS.md §7, proposal).
const (
	// MaxCompressedBody is the largest gzip body accepted on upload operations.
	MaxCompressedBody = 1 << 20
	// MaxUploadBody is the largest uncompressed (or inflated) upload body.
	MaxUploadBody = 8 << 20
	// MaxBody is the largest body accepted on every other operation.
	MaxBody = 64 << 10
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
func validateEmailFormat(s string) error {
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

// TokenVerifier checks an access token and returns its member id.
type TokenVerifier interface {
	Verify(raw string) (memberID string, err error)
}

// openAPIMiddleware is the front door for every /v1 request. In order, it:
//  1. finds the OpenAPI operation (404/405 problem otherwise);
//  2. enforces Content-Encoding (gzip on upload operations only, else 415)
//     and the body limits (413), inflating gzip bodies;
//  3. authenticates operations that require bearerAuth (401) and puts the
//     member id in the context;
//  4. validates parameters and body against the spec (400/415/422).
type openAPIMiddleware struct {
	doc    *openapi3.T
	router routers.Router
	tokens TokenVerifier
}

func newOpenAPIMiddleware(tokens TokenVerifier) (*openAPIMiddleware, error) {
	doc, err := api.GetSpec()
	if err != nil {
		return nil, fmt.Errorf("load embedded spec: %w", err)
	}
	// Match on paths only: the spec's servers are placeholders, and the real
	// host is whatever the deployment is reached under.
	doc.Servers = nil
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		return nil, fmt.Errorf("build OpenAPI router: %w", err)
	}
	return &openAPIMiddleware{doc: doc, router: router, tokens: tokens}, nil
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

		body, p := readBody(r, isUpload(route.Operation.OperationID))
		if p != nil {
			problem.Write(w, r, p)
			return
		}
		setBody(r, body)

		if requiresBearer(m.doc, route.Operation) {
			memberID, p := m.authenticate(r)
			if p != nil {
				problem.Write(w, r, p)
				return
			}
			r = r.WithContext(reqctx.WithMemberID(r.Context(), memberID))
		}

		input := &openapi3filter.RequestValidationInput{
			Request:    r,
			PathParams: pathParams,
			Route:      route,
			Options: &openapi3filter.Options{
				MultiError:          true,
				SkipSettingDefaults: true, // keep the body byte-exact for idempotency fingerprints
				// Authentication already happened above.
				AuthenticationFunc: func(context.Context, *openapi3filter.AuthenticationInput) error { return nil },
			},
		}
		if r.Method == http.MethodHead {
			input.Request = r.Clone(r.Context())
			input.Request.Method = http.MethodGet
		}
		if err := openapi3filter.ValidateRequest(r.Context(), input); err != nil {
			problem.Write(w, r, mapValidationError(err))
			return
		}
		setBody(r, body) // the validator consumed it
		next.ServeHTTP(w, r)
	})
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
	memberID, err := m.tokens.Verify(strings.TrimSpace(raw))
	switch {
	case errors.Is(err, auth.ErrTokenExpired):
		return "", problem.New(http.StatusUnauthorized, problem.CodeTokenExpired, "The access token has expired.").
			WithHeader("WWW-Authenticate", realm+`, error="invalid_token", error_description="expired"`)
	case err != nil:
		return "", problem.New(http.StatusUnauthorized, problem.CodeInvalidToken, "The access token is invalid.").
			WithHeader("WWW-Authenticate", realm+`, error="invalid_token"`)
	}
	return memberID, nil
}

// readBody enforces Content-Encoding and the size limits and returns the
// (inflated) body. Only upload operations may send gzip.
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
	raw, err := io.ReadAll(io.LimitReader(r.Body, limit+1))
	if err != nil {
		return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The request body could not be read.")
	}
	if int64(len(raw)) > limit {
		return nil, tooLarge
	}
	if !gzipped {
		return raw, nil
	}

	zr, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The gzip body is malformed.")
	}
	defer func() { _ = zr.Close() }()
	inflated, err := io.ReadAll(io.LimitReader(zr, MaxUploadBody+1))
	if err != nil {
		return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The gzip body is malformed.")
	}
	if len(inflated) > MaxUploadBody {
		return nil, tooLarge
	}
	r.Header.Del("Content-Encoding")
	return inflated, nil
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
