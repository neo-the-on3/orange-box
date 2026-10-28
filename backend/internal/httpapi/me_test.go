package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

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
	if r := m.call(http.MethodPatch, "/v1/me", patch); r.status != http.StatusPreconditionRequired || r.problemCode(t) != "precondition_required" {
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
	if r := m.call(http.MethodPost, "/v1/vehicles", i20); r.status != http.StatusCreated {
		t.Fatalf("vehicle: %d %s", r.status, r.body)
	}

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
	// The token is revoked at once, on every operation; a retry gets 401.
	for _, c := range []struct{ method, path string }{
		{http.MethodGet, "/v1/me"}, {http.MethodDelete, "/v1/me"}, {http.MethodGet, "/v1/vehicles"}, {http.MethodGet, "/v1/trips"},
	} {
		r := m.call(c.method, c.path, "")
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

// A revocation check that can't reach the database fails closed: 503 when
// the error is classified as unavailable, 500 otherwise, never a pass.
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
		{"other", nil, http.StatusInternalServerError, "internal_error"},
	} {
		e := newTestEnvWith(t, true, func(d *Deps) {
			d.Profiles = failingProfiles{err: errDown}
			d.ClassifyError = c.classify
		})
		m := e.member("closed")
		r := m.call(http.MethodGet, "/v1/vehicles", "")
		if r.status != c.status || r.problemCode(t) != c.code {
			t.Errorf("%s: %d %s", c.name, r.status, r.body)
		}
		if !strings.Contains(e.logs.String(), "database down") {
			t.Errorf("%s: error not logged", c.name)
		}
	}
}
