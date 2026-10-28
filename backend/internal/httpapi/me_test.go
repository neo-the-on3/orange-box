package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func TestMeProfile(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("me")

	r := m.call(http.MethodGet, "/v1/me", "")
	if r.status != http.StatusOK || r.header.Get("ETag") != `"v1"` {
		t.Fatalf("GET: %d %v %s", r.status, r.header, r.body)
	}
	p := r.json(t)
	name, _ := p["displayName"].(string)
	if p["id"] != m.id || p["email"] != "me@test.orangebox.invalid" || p["authProvider"] != "email_otp" ||
		p["rankingOptIn"] != false || p["countryCode"] != nil || p["regionCode"] != nil ||
		!strings.HasPrefix(name, "Driver ") || len(name) != len("Driver ")+6 || strings.Contains(name, "me") {
		t.Fatalf("new member's profile: %s", r.body)
	}
	if again := m.call(http.MethodGet, "/v1/me", "").json(t)["displayName"]; again != name {
		t.Errorf("default displayName not stable: %v vs %v", again, name)
	}

	// If-Match is required (428) and must be current (412).
	patch := `{"displayName":"Kiran","countryCode":"IN","regionCode":"IN-KA","rankingOptIn":true}`
	if r := m.call(http.MethodPatch, "/v1/me", patch); r.status != http.StatusPreconditionRequired || r.problemCode(t) != "precondition_required" ||
		!strings.Contains(string(r.body), "profile's ETag") {
		t.Fatalf("no If-Match: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodPatch, "/v1/me", patch, "If-Match", `"v9"`); r.status != http.StatusPreconditionFailed || r.problemCode(t) != "etag_mismatch" {
		t.Fatalf("stale If-Match: %d %s", r.status, r.body)
	}
	r = m.call(http.MethodPatch, "/v1/me", patch, "If-Match", `"v1"`)
	if r.status != http.StatusOK || r.header.Get("ETag") != `"v2"` {
		t.Fatalf("PATCH: %d %v %s", r.status, r.header, r.body)
	}
	p = r.json(t)
	if p["displayName"] != "Kiran" || p["countryCode"] != "IN" || p["regionCode"] != "IN-KA" || p["rankingOptIn"] != true || p["updatedAt"] == p["createdAt"] {
		t.Fatalf("patched: %s", r.body)
	}
	// Merge patch: absent keeps, null clears.
	r = m.call(http.MethodPatch, "/v1/me", `{"regionCode":null}`, "If-Match", "*")
	if p = r.json(t); r.status != http.StatusOK || p["regionCode"] != nil || p["countryCode"] != "IN" || p["displayName"] != "Kiran" || r.header.Get("ETag") != `"v3"` {
		t.Fatalf("clear regionCode: %d %s", r.status, r.body)
	}
	// A patch that changes nothing keeps the ETag.
	if r := m.call(http.MethodPatch, "/v1/me", `{"countryCode":"IN"}`, "If-Match", `"v3"`); r.status != http.StatusOK || r.header.Get("ETag") != `"v3"` {
		t.Fatalf("no-op: %d %v", r.status, r.header)
	}
	if got := m.call(http.MethodGet, "/v1/me", ""); got.header.Get("ETag") != `"v3"` || got.json(t)["displayName"] != "Kiran" {
		t.Fatalf("GET after PATCH: %v %s", got.header, got.body)
	}

	for _, c := range []struct{ name, body, pointer string }{
		{"empty", `{}`, ""},
		{"read-only email", `{"email":"x@test.orangebox.invalid"}`, ""},
		{"read-only id", `{"id":"x"}`, ""},
		{"empty name", `{"displayName":""}`, "/displayName"},
		{"long name", `{"displayName":"` + strings.Repeat("é", 41) + `"}`, "/displayName"},
		{"null name", `{"displayName":null}`, "/displayName"},
		{"lower-case country", `{"countryCode":"in"}`, "/countryCode"},
	} {
		r := m.call(http.MethodPatch, "/v1/me", c.body, "If-Match", "*")
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
			t.Errorf("%s: %d %s", c.name, r.status, r.body)
			continue
		}
		if c.pointer != "" && !strings.Contains(string(r.body), `"pointer":"`+c.pointer+`"`) {
			t.Errorf("%s: no pointer %s in %s", c.name, c.pointer, r.body)
		}
	}
	// A 40-character name (in characters, not bytes) is fine.
	if r := m.call(http.MethodPatch, "/v1/me", `{"displayName":"`+strings.Repeat("é", 40)+`"}`, "If-Match", "*"); r.status != http.StatusOK {
		t.Errorf("40 characters: %d %s", r.status, r.body)
	}
	// Each member sees only their own profile.
	other := e.member("other")
	if p := other.call(http.MethodGet, "/v1/me", "").json(t); p["id"] != other.id || p["displayName"] == "Kiran" {
		t.Errorf("other member: %v", p)
	}
}

func TestMeDelete(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m, bystander := e.member("leaving"), e.member("staying")
	v := m.call(http.MethodPost, "/v1/vehicles", i20)
	if v.status != http.StatusCreated {
		t.Fatalf("vehicle: %d %s", v.status, v.body)
	}
	vehicle := v.json(t)["id"].(string)

	before := time.Now().Add(-time.Second)
	r := m.call(http.MethodDelete, "/v1/me", "")
	if r.status != http.StatusAccepted {
		t.Fatalf("DELETE: %d %s", r.status, r.body)
	}
	d := r.json(t)
	requested, err1 := time.Parse(time.RFC3339Nano, d["requestedAt"].(string))
	completeBy, err2 := time.Parse(time.RFC3339Nano, d["completeBy"].(string))
	if d["status"] != "scheduled" || err1 != nil || err2 != nil || requested.Before(before) || requested.After(time.Now().Add(time.Second)) ||
		!completeBy.Equal(requested.Add(30*24*time.Hour)) || !strings.HasSuffix(d["requestedAt"].(string), "Z") {
		t.Fatalf("deletion: %s", r.body)
	}
	// The token is revoked at once, on every authenticated operation in the
	// spec (so new ones are covered as they land); a retry gets 401.
	ops := authenticatedOperations(t, vehicle)
	if len(ops) < 10 {
		t.Fatalf("only %d authenticated operations found", len(ops))
	}
	for _, c := range ops {
		r := m.call(c.method, c.path, "{}", "If-Match", "*", "Idempotency-Key", uuid.NewString())
		if r.status != http.StatusUnauthorized || r.problemCode(t) != "invalid_token" || !strings.Contains(r.header.Get("WWW-Authenticate"), `error="invalid_token"`) {
			t.Errorf("%s %s after delete: %d %v %s", c.method, c.path, r.status, r.header, r.body)
		}
	}
	// Other members are unaffected.
	if r := bystander.call(http.MethodGet, "/v1/me", ""); r.status != http.StatusOK {
		t.Errorf("bystander: %d", r.status)
	}
	// The address signs up again as a new, empty member.
	again := e.member("leaving")
	if again.id == m.id {
		t.Fatal("same member id after deletion")
	}
	if p := again.call(http.MethodGet, "/v1/me", "").json(t); p["id"] != again.id || p["email"] != "leaving@test.orangebox.invalid" {
		t.Errorf("new member: %v", p)
	}
	if items := again.call(http.MethodGet, "/v1/vehicles", "").json(t)["items"].([]any); len(items) != 0 {
		t.Errorf("the new member sees %d vehicles", len(items))
	}
}

// Without a database the profile operations answer 501, and nothing checks
// revocation (in-memory members can't be deleted).
func TestMeWithoutDatabase(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	m := e.member("nodb")
	for _, c := range []struct{ method, body string }{
		{http.MethodGet, ""}, {http.MethodPatch, `{"displayName":"x"}`}, {http.MethodDelete, ""},
	} {
		r := m.call(c.method, "/v1/me", c.body, "If-Match", "*")
		if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
			t.Errorf("%s /v1/me: %d %s", c.method, r.status, r.body)
		}
	}
}

// failingProfiles fails the revocation check.
type failingProfiles struct {
	store.Profiles
	err error
}

func (f failingProfiles) TokenRevoked(context.Context, uuid.UUID, time.Time) (bool, error) {
	return false, f.err
}

// A revocation check that fails, for whatever reason, fails closed with 503
// service_unavailable and Retry-After: 5; it never lets the request through.
func TestRevocationCheckFailsClosed(t *testing.T) {
	t.Parallel()
	errDown := errors.New("database down")
	for _, c := range []struct {
		name     string
		classify func(error) store.Failure
		status   int
		code     string
	}{
		{"unavailable", func(error) store.Failure { return store.FailureUnavailable }, http.StatusServiceUnavailable, "service_unavailable"},
		// An unclassified error (e.g. a missing table) is 503 too, so clients
		// back off rather than treat it as a client bug.
		{"other", nil, http.StatusServiceUnavailable, "service_unavailable"},
	} {
		e := newTestEnvWith(t, true, func(d *Deps) {
			d.Profiles = failingProfiles{err: errDown}
			d.ClassifyError = c.classify
		})
		m := e.member("closed")
		r := m.call(http.MethodGet, "/v1/vehicles", "")
		if r.status != c.status || r.problemCode(t) != c.code || r.header.Get("Retry-After") != "5" {
			t.Errorf("%s: %d %v %s", c.name, r.status, r.header, r.body)
		}
		if !strings.Contains(e.logs.String(), "database down") {
			t.Errorf("%s: error not logged", c.name)
		}
	}
}

// Two If-Match header lines are one list (RFC 9110 §5.3): either tag may
// match. The same holds for If-None-Match and on every operation.
func TestIfMatchHeaderLinesCombine(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("lines")
	send := func(method, path, ct, body, header string, values ...string) response {
		t.Helper()
		req, err := http.NewRequest(method, e.srv.URL+path, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+m.token)
		if body != "" {
			req.Header.Set("Content-Type", ct)
		}
		for _, v := range values {
			req.Header.Add(header, v)
		}
		res, err := e.srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var buf strings.Builder
		_, _ = io.Copy(&buf, res.Body)
		return response{status: res.StatusCode, header: res.Header, body: []byte(buf.String())}
	}
	const mp = "application/merge-patch+json"
	if r := send(http.MethodPatch, "/v1/me", mp, `{"displayName":"Two"}`, "If-Match", `"v9"`, `"v1"`); r.status != http.StatusOK || r.header.Get("ETag") != `"v2"` {
		t.Fatalf("two If-Match lines, second current: %d %s", r.status, r.body)
	}
	if r := send(http.MethodPatch, "/v1/me", mp, `{"displayName":"Three"}`, "If-Match", `"v1"`, `"v9"`); r.status != http.StatusPreconditionFailed {
		t.Fatalf("two stale If-Match lines: %d %s", r.status, r.body)
	}
	v := m.call(http.MethodPost, "/v1/vehicles", i20)
	loc, etag := v.header.Get("Location"), v.header.Get("ETag")
	if r := send(http.MethodGet, loc, "", "", "If-None-Match", `"v7"`, etag); r.status != http.StatusNotModified {
		t.Fatalf("two If-None-Match lines: %d %s", r.status, r.body)
	}
	if r := send(http.MethodPatch, loc, mp, `{"nickname":"x"}`, "If-Match", `"v7"`, etag); r.status != http.StatusOK {
		t.Fatalf("vehicle, two If-Match lines: %d %s", r.status, r.body)
	}
}

// displayName is shown to other members (leaderboards): it is trimmed, and
// blank names, control and format characters (bidi overrides, zero-width
// spaces) and line separators are 422. U+200D joins emoji sequences.
func TestMeDisplayNameContent(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("names")
	// Values are JSON string contents: escapes reach the server as sent.
	for _, c := range []struct{ name, value string }{
		{"spaces", `   `},
		{"tab and newline", `\t\n`},
		{"bell", `Kiran\u0007`},
		{"newline inside", `Ki\nran`},
		{"DEL", `Kiran\u007f`},
		{"C1 control", `Ki\u0085ran`},
		{"C1 control at the end", `Kiran\u0080`},
		{"right-to-left override", `\u202enariK`},
		{"isolate", `\u2066Kiran\u2069`},
		{"zero-width space", `Ki\u200bran`},
		{"BOM", `\ufeffKiran`},
		{"soft hyphen", `Ki\u00adran`},
		{"line separator", `Ki\u2028ran`},
		{"lone joiner", `\u200dKiran`},
		{"trailing joiner", `Kiran\u200d`},
		{"ideographic space only", `\u3000`},
		{"braille blank only", `\u2800`},
		{"braille blank inside", `Ki\u2800ran`},
		{"Hangul filler", `\u3164`},
		{"Hangul choseong filler", `\u115f`},
		{"Hangul jungseong filler", `\u1160`},
		{"halfwidth Hangul filler", `\uffa0`},
		{"combining grapheme joiner", `Ki\u034fran`},
		{"leading non-joiner", `\u200cKiran`},
		{"trailing non-joiner", `Kiran\u200c`},
		{"non-joiner next to a space", `Ki \u200cran`},
		{"doubled non-joiner", `Ki\u200c\u200cran`},
		{"non-joiner between digits", `12\u200c34`},
		{"tag characters without a flag", `Kiran\udb40\udc67\udb40\udc62\udb40\udc7f`},
		{"flag tags without cancel tag", `\ud83c\udff4\udb40\udc67\udb40\udc62`},
		{"flag with upper-case tag", `\ud83c\udff4\udb40\udc47\udb40\udc7f`},
		{"lone cancel tag", `Kiran\udb40\udc7f`},
	} {
		r := m.call(http.MethodPatch, "/v1/me", `{"displayName":"`+c.value+`"}`, "If-Match", "*")
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" || !strings.Contains(string(r.body), `"pointer":"/displayName"`) {
			t.Errorf("%s: %d %s", c.name, r.status, r.body)
		}
	}
	for _, c := range []struct{ name, value, want string }{
		{"trimmed", `  Kiran B\t`, "Kiran B"},
		{"inner spaces kept", `Kiran  B`, "Kiran  B"},
		{"emoji ZWJ sequence", `\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67`, "\U0001F468\u200d\U0001F469\u200d\U0001F467"},
		{"accents and scripts", `Kíran ಕಿರಣ್`, "Kíran ಕಿರಣ್"},
		{"Persian non-joiner", `می\u200cخواهم`, "می\u200cخواهم"},
		{"non-joiner after virama", `क्\u200cष`, "क्\u200cष"},
		{"England flag", `\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc65\udb40\udc6e\udb40\udc67\udb40\udc7f`,
			"\U0001F3F4\U000E0067\U000E0062\U000E0065\U000E006E\U000E0067\U000E007F"},
		{"flag next to a name", `Kiran \ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc73\udb40\udc63\udb40\udc74\udb40\udc7f`,
			"Kiran \U0001F3F4\U000E0067\U000E0062\U000E0073\U000E0063\U000E0074\U000E007F"},
		{"black flag", `\ud83c\udff4`, "\U0001F3F4"},
		{"emoji variation selector", `Kiran \u2764\ufe0f`, "Kiran \u2764\ufe0f"},
	} {
		r := m.call(http.MethodPatch, "/v1/me", `{"displayName":"`+c.value+`"}`, "If-Match", "*")
		if r.status != http.StatusOK || r.json(t)["displayName"] != c.want {
			t.Errorf("%s: %d %s", c.name, r.status, r.body)
		}
	}
	// No-op detection works on the trimmed value.
	etag := m.call(http.MethodPatch, "/v1/me", `{"displayName":"Kíran ಕಿರಣ್"}`, "If-Match", "*").header.Get("ETag")
	if r := m.call(http.MethodPatch, "/v1/me", `{"displayName":" Kíran ಕಿರಣ್ "}`, "If-Match", etag); r.header.Get("ETag") != etag {
		t.Errorf("trimmed duplicate bumped the ETag: %v", r.header)
	}
}

// regionCode is an ISO 3166-2 code whose country part is countryCode, in
// the profile that results from the patch.
func TestMeRegionCode(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("regions")
	patch := func(body string) response {
		t.Helper()
		return m.call(http.MethodPatch, "/v1/me", body, "If-Match", "*")
	}
	reject := func(name, body, pointer string) {
		t.Helper()
		r := patch(body)
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" || !strings.Contains(string(r.body), `"pointer":"`+pointer+`"`) {
			t.Errorf("%s: %d %s", name, r.status, r.body)
		}
	}
	// Format (schema).
	for _, v := range []string{``, `KA`, `in-ka`, `IN-`, `IN-KARN`, `IN_KA`, `IND-KA`, strings.Repeat("A", 300)} {
		reject("format "+v[:min(len(v), 10)], `{"countryCode":"IN","regionCode":"`+v+`"}`, "/regionCode")
	}
	// Consistency with countryCode.
	reject("region without country", `{"regionCode":"IN-KA"}`, "/regionCode")
	reject("other country's region", `{"countryCode":"IN","regionCode":"US-CA"}`, "/regionCode")
	if r := patch(`{"countryCode":"IN","regionCode":"IN-KA"}`); r.status != http.StatusOK || r.json(t)["regionCode"] != "IN-KA" {
		t.Fatalf("IN-KA: %d %s", r.status, r.body)
	}
	if r := patch(`{"regionCode":"IN-MH"}`); r.status != http.StatusOK || r.json(t)["regionCode"] != "IN-MH" {
		t.Fatalf("region of the stored country: %d %s", r.status, r.body)
	}
	reject("country changed under the region", `{"countryCode":"US"}`, "/countryCode")
	reject("country cleared under the region", `{"countryCode":null}`, "/countryCode")
	if r := patch(`{"countryCode":"US","regionCode":"US-CA"}`); r.status != http.StatusOK || r.json(t)["regionCode"] != "US-CA" {
		t.Fatalf("both changed: %d %s", r.status, r.body)
	}
	if r := patch(`{"countryCode":"GB","regionCode":null}`); r.status != http.StatusOK || r.json(t)["regionCode"] != nil {
		t.Fatalf("country changed, region cleared: %d %s", r.status, r.body)
	}
	if r := patch(`{"countryCode":null}`); r.status != http.StatusOK || r.json(t)["countryCode"] != nil {
		t.Fatalf("clear country: %d %s", r.status, r.body)
	}
	// A rejected patch stores nothing.
	if p := m.call(http.MethodGet, "/v1/me", "").json(t); p["countryCode"] != nil || p["regionCode"] != nil {
		t.Errorf("after rejections: %v", p)
	}
	// The stale If-Match check comes first.
	if r := m.call(http.MethodPatch, "/v1/me", `{"regionCode":"IN-KA"}`, "If-Match", `"v1"`); r.status != http.StatusPreconditionFailed {
		t.Errorf("stale and inconsistent: %d", r.status)
	}
}

// Every /v1/me operation can answer 503 (the revocation check and the
// store), so the spec has to say so.
func TestMeOperationsDeclare503(t *testing.T) {
	t.Parallel()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	item := doc.Paths.Find("/v1/me")
	if item == nil {
		t.Fatal("no /v1/me path")
	}
	for method, op := range map[string]*openapi3.Operation{"GET": item.Get, "PATCH": item.Patch, "DELETE": item.Delete} {
		if op == nil || op.Responses.Status(http.StatusServiceUnavailable) == nil {
			t.Errorf("%s /v1/me doesn't declare 503", method)
		}
	}
}

type operation struct{ method, path string }

// authenticatedOperations lists every operation of the spec that needs a
// bearer token, with {vehicleId} set to vehicle and any other path
// parameter to a fresh UUID.
func authenticatedOperations(t *testing.T, vehicle string) []operation {
	t.Helper()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	param := regexp.MustCompile(`\{[^}]+\}`)
	var ops []operation
	for _, path := range doc.Paths.InMatchingOrder() {
		for method, op := range doc.Paths.Value(path).Operations() {
			sec := doc.Security
			if op.Security != nil {
				sec = *op.Security
			}
			if len(sec) == 0 {
				continue
			}
			concrete := param.ReplaceAllStringFunc(path, func(p string) string {
				if p == "{vehicleId}" {
					return vehicle
				}
				return uuid.NewString()
			})
			ops = append(ops, operation{method, concrete})
		}
	}
	return ops
}
