package httpapi

import (
	"bytes"
	"compress/gzip"
	"net/http"
	"strings"
	"testing"

	"github.com/google/uuid"
)

func TestHealth(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, false)

	r := e.do(http.MethodGet, "/v1/health", nil)
	if r.status != http.StatusOK || r.header.Get("Content-Type") != "application/json" || r.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("GET: %d %v %s", r.status, r.header, r.body)
	}
	m := r.json(t)
	if m["status"] != "ok" || m["apiVersion"] != "1.0.0-draft.8" || m["time"] == nil {
		t.Errorf("body = %v", m)
	}
	if _, err := uuid.Parse(r.header.Get("X-Request-Id")); err != nil {
		t.Errorf("X-Request-Id = %q", r.header.Get("X-Request-Id"))
	}

	if r := e.do(http.MethodHead, "/v1/health", nil); r.status != http.StatusOK || len(r.body) != 0 {
		t.Errorf("HEAD: %d %q", r.status, r.body)
	}
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch} {
		r := e.do(method, "/v1/health", nil)
		if r.status != http.StatusMethodNotAllowed || r.problemCode(t) != "method_not_allowed" {
			t.Errorf("%s: %d %s", method, r.status, r.body)
		}
		if got := r.header.Get("Allow"); got != "GET, HEAD" {
			t.Errorf("%s: Allow = %q", method, got)
		}
	}
	for _, path := range []string{"/v1/health/", "/v1/nope", "/", "/v2/health"} {
		if r := e.do(http.MethodGet, path, nil); r.status != http.StatusNotFound || r.problemCode(t) != "not_found" {
			t.Errorf("GET %s: %d %s", path, r.status, r.body)
		}
	}
}

func TestRequestIDEcho(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, false)
	const id = "0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a14"
	if r := e.do(http.MethodGet, "/v1/health", nil, "X-Request-Id", id); r.header.Get("X-Request-Id") != id {
		t.Errorf("valid id not echoed: %q", r.header.Get("X-Request-Id"))
	}
	r := e.do(http.MethodGet, "/v1/health", nil, "X-Request-Id", "<script>")
	if got := r.header.Get("X-Request-Id"); got == "<script>" || uuid.Validate(got) != nil {
		t.Errorf("invalid id echoed or not replaced: %q", got)
	}
	// Spec operations that declare X-Request-Id as a uuid parameter reject a bad one.
	if r := e.postJSON("/v1/auth/otp", `{"email":"a@example.com"}`, "X-Request-Id", "nope"); r.status != http.StatusBadRequest {
		t.Errorf("bad X-Request-Id param: %d %s", r.status, r.body)
	}
}

func TestTestModeSignIn(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)

	// Surrounding spaces are trimmed (the spec says so), not rejected.
	r := e.postJSON("/v1/auth/otp", `{"email":"  Kiran@Test.OrangeBox.Invalid "}`)
	if r.status != http.StatusAccepted || r.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("otp: %d %v %s", r.status, r.header, r.body)
	}
	if m := r.json(t); m["expiresIn"] != float64(600) || m["resendAfter"] != float64(60) {
		t.Errorf("otp body = %v", m)
	}

	// Wrong code, then the right one (the address is normalised both times).
	r = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"kiran@test.orangebox.invalid","code":"000001"}`)
	if r.status != http.StatusBadRequest || r.problemCode(t) != "invalid_grant" {
		t.Fatalf("wrong code: %d %s", r.status, r.body)
	}
	r = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"KIRAN@test.orangebox.invalid","code":"`+testCode+`","device":{"appVersion":"1.0.0"}}`)
	if r.status != http.StatusOK || r.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("token: %d %s", r.status, r.body)
	}
	m := r.json(t)
	if m["tokenType"] != "Bearer" || m["expiresIn"] != float64(900) || m["isNewMember"] != true ||
		m["refreshExpiresIn"] != float64(2592000) || !strings.HasPrefix(m["refreshToken"].(string), "rt_") {
		t.Errorf("token body = %v", m)
	}
	memberID := m["memberId"].(string)
	if got, err := e.tokens.Verify(m["accessToken"].(string)); err != nil || got != memberID {
		t.Errorf("access token: %q %v, want %q", got, err, memberID)
	}

	// The code is consumed.
	r = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"kiran@test.orangebox.invalid","code":"`+testCode+`"}`)
	if r.status != http.StatusBadRequest || r.problemCode(t) != "invalid_grant" {
		t.Errorf("reused code: %d %s", r.status, r.body)
	}

	// Signing in again returns the same member.
	_, again := e.signIn("kiran@test.orangebox.invalid")
	if again != memberID {
		t.Errorf("second sign-in member %q, want %q", again, memberID)
	}

	// Five wrong attempts invalidate the code, and a fresh request doesn't
	// reset the count: the shared code still can't be used.
	e.postJSON("/v1/auth/otp", `{"email":"x@test.orangebox.invalid"}`)
	var last response
	for range 5 {
		last = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"x@test.orangebox.invalid","code":"000001"}`)
	}
	if last.problemCode(t) != "otp_attempts_exceeded" {
		t.Errorf("5th wrong attempt: %s", last.body)
	}
	e.postJSON("/v1/auth/otp", `{"email":"x@test.orangebox.invalid"}`)
	r = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"x@test.orangebox.invalid","code":"`+testCode+`"}`)
	if r.problemCode(t) != "otp_attempts_exceeded" {
		t.Errorf("right code after lockout and resend: %d %s", r.status, r.body)
	}

	// Asking again within resendAfter keeps the pending code and says how
	// long to wait.
	e.postJSON("/v1/auth/otp", `{"email":"wait@test.orangebox.invalid"}`)
	r = e.postJSON("/v1/auth/otp", `{"email":"wait@test.orangebox.invalid"}`)
	if r.status != http.StatusAccepted {
		t.Fatalf("early resend: %d %s", r.status, r.body)
	}
	if ra := r.json(t)["resendAfter"].(float64); ra < 1 || ra > 60 {
		t.Errorf("early resend resendAfter = %v", ra)
	}

	// Non-test addresses: no e-mail yet.
	r = e.postJSON("/v1/auth/otp", `{"email":"driver@example.com"}`)
	if r.status != http.StatusServiceUnavailable || r.problemCode(t) != "email_sign_in_unavailable" || r.header.Get("Retry-After") == "" {
		t.Errorf("real address otp: %d %v %s", r.status, r.header, r.body)
	}
	r = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"driver@example.com","code":"`+testCode+`"}`)
	if r.status != http.StatusBadRequest || r.problemCode(t) != "invalid_grant" {
		t.Errorf("real address with test code: %d %s", r.status, r.body)
	}

	// Refresh grant is not there yet.
	r = e.postJSON("/v1/auth/token", `{"grantType":"refresh_token","refreshToken":"rt_x"}`)
	if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
		t.Errorf("refresh grant: %d %s", r.status, r.body)
	}

	// Nothing sensitive reached the logs.
	logs := e.logs.String()
	for _, leak := range []string{"kiran@", "driver@example.com", testCode, "eyJ", "rt_"} {
		if strings.Contains(logs, leak) {
			t.Errorf("logs leak %q", leak)
		}
	}
}

func TestTestModeOff(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, false)
	for _, tc := range []struct{ path, body string }{
		{"/v1/auth/otp", `{"email":"a@test.orangebox.invalid"}`},
		{"/v1/auth/token", `{"grantType":"email_otp","email":"a@test.orangebox.invalid","code":"` + testCode + `"}`},
	} {
		r := e.postJSON(tc.path, tc.body)
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "test_address_rejected" {
			t.Errorf("%s: %d %s", tc.path, r.status, r.body)
		}
		if errs, _ := r.json(t)["errors"].([]any); len(errs) != 1 {
			t.Errorf("%s: errors = %v", tc.path, errs)
		}
	}
}

func TestBearerAuth(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("auth@test.orangebox.invalid")

	r := e.do(http.MethodGet, "/v1/me", nil)
	if r.status != http.StatusUnauthorized || r.problemCode(t) != "unauthorized" || !strings.HasPrefix(r.header.Get("WWW-Authenticate"), "Bearer") {
		t.Errorf("no token: %d %v %s", r.status, r.header, r.body)
	}
	for name, h := range map[string]string{
		"garbage":    "Bearer nope",
		"basic":      "Basic dXNlcjpwYXNz",
		"empty":      "Bearer ",
		"tampered":   "Bearer " + token[:len(token)-3] + "abc",
		"no scheme":  token,
		"other kid":  "Bearer " + otherToken(t),
		"double sep": "Bearer  " + token + "x",
	} {
		r := e.do(http.MethodGet, "/v1/me", nil, "Authorization", h)
		if r.status != http.StatusUnauthorized {
			t.Errorf("%s: %d %s", name, r.status, r.body)
		}
	}
	// Valid token: auth passes and the stub answers 501.
	r = e.do(http.MethodGet, "/v1/me", nil, "Authorization", "Bearer "+token)
	if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
		t.Errorf("valid token: %d %s", r.status, r.body)
	}
	r = e.do(http.MethodGet, "/v1/me", nil, "Authorization", "bearer "+token)
	if r.status != http.StatusNotImplemented {
		t.Errorf("lower-case scheme: %d %s", r.status, r.body)
	}
	// Auth runs before body validation.
	r = e.postJSON("/v1/vehicles", `{"bad":true}`)
	if r.status != http.StatusUnauthorized {
		t.Errorf("unauthenticated invalid body: %d %s", r.status, r.body)
	}
}

func otherToken(t *testing.T) string {
	t.Helper()
	other := newTestEnv(t, true)
	tok, _ := other.signIn("o@test.orangebox.invalid")
	return tok
}

// Every operation the skeleton doesn't implement answers 501 problem+json.
func TestUnimplementedOperations(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("stub@test.orangebox.invalid")
	bearer := "Bearer " + token
	vid := uuid.NewString()
	cases := []struct{ method, path, body string }{
		{http.MethodPost, "/v1/auth/logout", `{"refreshToken":"rt_x"}`},
		{http.MethodGet, "/v1/me", ""},
		{http.MethodDelete, "/v1/me", ""},
		{http.MethodGet, "/v1/vehicles", ""},
		{http.MethodGet, "/v1/vehicles/" + vid, ""},
		{http.MethodGet, "/v1/catalogue/makes", ""},
		{http.MethodGet, "/v1/reference/countries", ""},
		{http.MethodGet, "/v1/trips", ""},
		{http.MethodGet, "/v1/trips/deletions?since=tok", ""},
		{http.MethodGet, "/v1/trips/client:" + uuid.NewString(), ""},
		{http.MethodDelete, "/v1/trips/" + vid, ""},
		{http.MethodGet, "/v1/me/points", ""},
	}
	for _, tc := range cases {
		var body *strings.Reader
		headers := []string{"Authorization", bearer}
		if tc.body != "" {
			body = strings.NewReader(tc.body)
			headers = append(headers, "Content-Type", "application/json")
		} else {
			body = strings.NewReader("")
		}
		r := e.do(tc.method, tc.path, body, headers...)
		if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
			t.Errorf("%s %s: %d %s", tc.method, tc.path, r.status, r.body)
		}
	}
}

func gz(t *testing.T, s string) []byte {
	t.Helper()
	var b bytes.Buffer
	w := gzip.NewWriter(&b)
	if _, err := w.Write([]byte(s)); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func telemetryBody(t string) string {
	return `{"clientBatchId":"` + uuid.NewString() + `","clientTripId":"` + uuid.NewString() +
		`","vehicleId":"v1","samples":[{"t":"` + t + `"}]}`
}

func TestContentEncodingAndLimits(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("gz@test.orangebox.invalid")
	h := func(extra ...string) []string {
		return append([]string{"Authorization", "Bearer " + token, "Content-Type", "application/json", "Idempotency-Key", uuid.NewString()}, extra...)
	}

	// gzip on an upload operation is inflated and validated (then 501).
	r := e.do(http.MethodPost, "/v1/telemetry/batches", bytes.NewReader(gz(t, telemetryBody("2026-10-23T14:05:00.123456789Z"))), h("Content-Encoding", "gzip")...)
	if r.status != http.StatusNotImplemented {
		t.Errorf("gzip upload: %d %s", r.status, r.body)
	}
	// identity works too.
	r = e.do(http.MethodPost, "/v1/telemetry/batches", strings.NewReader(telemetryBody("2026-10-23T14:05:00Z")), h("Content-Encoding", "identity")...)
	if r.status != http.StatusNotImplemented {
		t.Errorf("identity upload: %d %s", r.status, r.body)
	}
	// A gzipped body is validated after inflating: bad UtcInstant → 422.
	r = e.do(http.MethodPost, "/v1/telemetry/batches", bytes.NewReader(gz(t, telemetryBody("2026-10-23T14:05:00+00:00"))), h("Content-Encoding", "gzip")...)
	if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" || !strings.Contains(string(r.body), `"/samples/0/t"`) {
		t.Errorf("gzip invalid upload: %d %s", r.status, r.body)
	}
	// Other encodings, or gzip on a non-upload operation → 415.
	for name, tc := range map[string]struct {
		path string
		enc  string
	}{
		"br":            {"/v1/telemetry/batches", "br"},
		"deflate":       {"/v1/telemetry/batches", "deflate"},
		"gzip, gzip":    {"/v1/telemetry/batches", "gzip, gzip"},
		"gzip elsewhr":  {"/v1/vehicles", "gzip"},
		"gzip on token": {"/v1/auth/token", "gzip"},
	} {
		r := e.do(http.MethodPost, tc.path, bytes.NewReader(gz(t, "{}")), h("Content-Encoding", tc.enc)...)
		if r.status != http.StatusUnsupportedMediaType || r.problemCode(t) != "unsupported_encoding" {
			t.Errorf("%s: %d %s", name, r.status, r.body)
		}
	}
	// Malformed gzip → 400.
	r = e.do(http.MethodPost, "/v1/telemetry/batches", strings.NewReader("not gzip"), h("Content-Encoding", "gzip")...)
	if r.status != http.StatusBadRequest {
		t.Errorf("bad gzip: %d %s", r.status, r.body)
	}
	// A gzip bomb is stopped at the inflated limit → 413.
	bomb := gz(t, `{"x":"`+strings.Repeat("a", MaxUploadBody+10)+`"}`)
	if len(bomb) > MaxCompressedBody {
		t.Fatalf("test bomb too big compressed: %d", len(bomb))
	}
	r = e.do(http.MethodPost, "/v1/telemetry/batches", bytes.NewReader(bomb), h("Content-Encoding", "gzip")...)
	if r.status != http.StatusRequestEntityTooLarge || r.problemCode(t) != "batch_too_large" {
		t.Errorf("gzip bomb: %d %s", r.status, r.body)
	}
	// Bodies over the per-operation limit → 413.
	r = e.postJSON("/v1/auth/otp", `{"email":"`+strings.Repeat("a", MaxBody)+`@example.com"}`)
	if r.status != http.StatusRequestEntityTooLarge {
		t.Errorf("big otp body: %d", r.status)
	}
}

func TestValidationErrorsDontEchoValues(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("val@test.orangebox.invalid")
	body := `{"clientReportId":"` + uuid.NewString() + `","kind":"read","readAt":"2026-10-23T14:05:00Z","mil":true,"vin":"secret-vin-1234567"}`
	r := e.postJSON("/v1/vehicles/v1/dtc-reports", body, "Authorization", "Bearer "+token, "Idempotency-Key", uuid.NewString())
	if r.status != http.StatusUnprocessableEntity {
		t.Fatalf("bad vin: %d %s", r.status, r.body)
	}
	if strings.Contains(string(r.body), "secret-vin") || strings.Contains(e.logs.String(), "secret-vin") {
		t.Errorf("VIN echoed: %s", r.body)
	}

	r = e.postJSON("/v1/auth/otp", `{"email":"leak-me-not"}`)
	if r.status != http.StatusUnprocessableEntity || strings.Contains(string(r.body), "leak-me-not") {
		t.Errorf("bad email echoed: %d %s", r.status, r.body)
	}
	r = e.postJSON("/v1/auth/token", `{"grantType":"email_otp","email":"a@test.orangebox.invalid","code":"12ab56"}`)
	if r.status != http.StatusUnprocessableEntity || strings.Contains(string(r.body), "12ab56") {
		t.Errorf("bad code echoed: %d %s", r.status, r.body)
	}
	// Missing required fields name the property (from the schema), not values.
	r = e.postJSON("/v1/auth/otp", `{}`)
	if r.status != http.StatusUnprocessableEntity || !strings.Contains(string(r.body), `"pointer":"/email"`) {
		t.Errorf("missing email: %d %s", r.status, r.body)
	}
	// Missing body, wrong content type, bad JSON, bad query parameter.
	if r := e.do(http.MethodPost, "/v1/auth/otp", nil, "Content-Type", "application/json"); r.status != http.StatusBadRequest {
		t.Errorf("no body: %d %s", r.status, r.body)
	}
	if r := e.do(http.MethodPost, "/v1/auth/otp", strings.NewReader(`email=a@b.co`), "Content-Type", "application/x-www-form-urlencoded"); r.status != http.StatusUnsupportedMediaType {
		t.Errorf("form body: %d %s", r.status, r.body)
	}
	if r := e.postJSON("/v1/auth/otp", `{"email":`); r.status != http.StatusBadRequest {
		t.Errorf("bad json: %d %s", r.status, r.body)
	}
	if r := e.do(http.MethodGet, "/v1/vehicles?limit=0", nil, "Authorization", "Bearer "+token); r.status != http.StatusBadRequest {
		t.Errorf("limit=0: %d %s", r.status, r.body)
	}
}

func TestValidationPointersAndInstance(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("ptr@test.orangebox.invalid")
	vid := uuid.NewString()
	body := `{"stored":[],"pending":[],"permanent":[],"clientReportId":"` + uuid.NewString() +
		`","kind":"read","readAt":"2000-01-01T00:00:00Z","mil":false,"vin":"00000000000000000","clientTripId":"","odometerKm":0}`
	r := e.postJSON("/v1/vehicles/"+vid+"/dtc-reports", body, "Authorization", "Bearer "+token, "Idempotency-Key", uuid.NewString())
	if r.status != http.StatusUnprocessableEntity {
		t.Fatalf("got %d %s", r.status, r.body)
	}
	m := r.json(t)
	if m["instance"] != "/v1/vehicles/"+vid+"/dtc-reports" {
		t.Errorf("instance = %v", m["instance"])
	}
	errs, _ := m["errors"].([]any)
	if len(errs) != 1 || errs[0].(map[string]any)["pointer"] != "/clientTripId" || errs[0].(map[string]any)["detail"] != "must be a valid uuid" {
		t.Errorf("errors = %v", errs)
	}
}

// JSON Schema accepts 415.0 and 1e3 as integers but Go can't decode them
// into an int; out-of-range values neither. All three are a 422 pointing at
// the field, never an opaque 400, and the value isn't echoed.
func TestIntegerAsFloatIs422(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("num@test.orangebox.invalid")
	vid := uuid.NewString()
	for _, n := range []string{"415.0", "1e3", "1e30"} {
		body := `{"stored":[],"pending":[],"permanent":[],"clientReportId":"` + uuid.NewString() +
			`","kind":"read","readAt":"2000-01-01T00:00:00Z","mil":false,"vin":"00000000000000000","odometerKm":` + n + `}`
		r := e.postJSON("/v1/vehicles/"+vid+"/dtc-reports", body, "Authorization", "Bearer "+token, "Idempotency-Key", uuid.NewString())
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
			t.Errorf("%s: %d %s", n, r.status, r.body)
			continue
		}
		m := r.json(t)
		errs, _ := m["errors"].([]any)
		if len(errs) != 1 || errs[0].(map[string]any)["pointer"] != "/odometerKm" {
			t.Errorf("%s: errors = %v", n, errs)
			continue
		}
		// Only the problem's own text can echo a value (the instance and
		// requestId may contain "1e3" by chance).
		if detail := errs[0].(map[string]any)["detail"].(string) + " " + m["detail"].(string); strings.Contains(detail, n) {
			t.Errorf("%s: value echoed: %s", n, detail)
		}
	}
}

// TestNullableIntegerAsFloatDetail is #168: the nullable integers of a
// vehicle (odometerKm, modelYear, engineDisplacementCc) get the specific
// whole-number detail, not the generic "has the wrong type".
func TestNullableIntegerAsFloatDetail(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	token, _ := e.signIn("nullnum@test.orangebox.invalid")
	for _, field := range []string{"odometerKm", "modelYear", "engineDisplacementCc"} {
		for _, n := range []string{"2000.0", "2e3"} {
			body := `{"make":"Hyundai","model":"i20","fuelType":"gasoline","` + field + `":` + n + `}`
			r := e.postJSON("/v1/vehicles", body, "Authorization", "Bearer "+token)
			if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
				t.Errorf("%s=%s: %d %s", field, n, r.status, r.body)
				continue
			}
			errs, _ := r.json(t)["errors"].([]any)
			if len(errs) != 1 || errs[0].(map[string]any)["pointer"] != "/"+field ||
				errs[0].(map[string]any)["detail"] != "must be a whole number written without a decimal point or exponent, within range" {
				t.Errorf("%s=%s: errors = %v", field, n, errs)
			}
		}
	}
}
