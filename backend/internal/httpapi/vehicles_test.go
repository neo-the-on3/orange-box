package httpapi

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

// newDBTestEnv is a test server on a fresh Postgres database (skipped
// without DATABASE_URL).
func newDBTestEnv(t *testing.T) *testEnv {
	t.Helper()
	pool := dbtest.New(t)
	return newTestEnvWith(t, true, func(d *Deps) {
		d.Members = db.NewMembers(pool)
		d.Vehicles = db.NewVehicles(pool)
		d.Idempotency = db.NewIdempotency(pool)
	})
}

// member is a signed-in client of a test server.
type member struct {
	e     *testEnv
	token string
	id    string
}

func (e *testEnv) member(name string) *member {
	e.t.Helper()
	token, id := e.signIn(name + "@test.orangebox.invalid")
	return &member{e: e, token: token, id: id}
}

// call sends a request as the member and checks the response against the
// spec (status, headers, body schema).
func (m *member) call(method, path, body string, headers ...string) response {
	m.e.t.Helper()
	h := []string{"Authorization", "Bearer " + m.token}
	if body != "" {
		ct := "application/json"
		if method == http.MethodPatch {
			ct = "application/merge-patch+json"
		}
		h = append(h, "Content-Type", ct)
	}
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	r := m.e.do(method, path, rd, append(h, headers...)...)
	checkResponseSchema(m.e.t, method, path, body, h, headers, r)
	return r
}

func checkResponseSchema(t *testing.T, method, path, body string, h1, h2 []string, r response) {
	t.Helper()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = nil
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	for _, h := range [][]string{h1, h2} {
		for i := 0; i+1 < len(h); i += 2 {
			req.Header.Set(h[i], h[i+1])
		}
	}
	route, params, err := router.FindRoute(req)
	if err != nil {
		t.Fatalf("%s %s: no route: %v", method, path, err)
	}
	in := &openapi3filter.ResponseValidationInput{
		RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route},
		Status:                 r.status,
		Header:                 r.header,
		Body:                   io.NopCloser(bytes.NewReader(r.body)),
		Options:                &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
	}
	if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
		t.Errorf("%s %s → %d does not match the spec: %v\nbody: %s", method, path, r.status, err, r.body)
	}
}

const i20 = `{"make":"Hyundai","model":"i20","fuelType":"gasoline","modelYear":2021,"vin":"KMHB341ABCD123456","nickname":"Blue","obdAdapter":{"elmVersion":"1.5","obdProtocol":6}}`

func withClientID(body string, id uuid.UUID) string {
	return strings.Replace(body, "{", `{"clientVehicleId":"`+id.String()+`",`, 1)
}

func TestVehicleCRUD(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("crud")
	clientID := uuid.New()

	r := m.call(http.MethodPost, "/v1/vehicles", withClientID(i20, clientID))
	if r.status != http.StatusCreated {
		t.Fatalf("create: %d %s", r.status, r.body)
	}
	v := r.json(t)
	id, _ := v["id"].(string)
	etag := r.header.Get("ETag")
	if r.header.Get("Location") != "/v1/vehicles/"+id || etag == "" || v["make"] != "Hyundai" || v["modelYear"] != float64(2021) ||
		v["clientVehicleId"] != clientID.String() || v["archived"] != false || v["plateNumber"] != nil {
		t.Fatalf("create: %v %v", r.header, v)
	}
	if _, present := v["plateNumber"]; !present {
		t.Error("NULL nullable field plateNumber is omitted instead of null")
	}
	if _, present := v["idleStopGo"]; present {
		t.Error("unset non-nullable idleStopGo is present")
	}
	if obd, _ := v["obdAdapter"].(map[string]any); obd["elmVersion"] != "1.5" || obd["obdProtocol"] != float64(6) {
		t.Errorf("obdAdapter = %v", v["obdAdapter"])
	}

	// Same clientVehicleId again, any body: 200 with the vehicle unchanged.
	r = m.call(http.MethodPost, "/v1/vehicles", withClientID(`{"make":"Kia","model":"Rio","fuelType":"diesel"}`, clientID))
	if r.status != http.StatusOK || r.json(t)["make"] != "Hyundai" || r.header.Get("ETag") != etag || r.header.Get("Location") != "/v1/vehicles/"+id {
		t.Fatalf("duplicate create: %d %v %s", r.status, r.header, r.body)
	}

	r = m.call(http.MethodGet, "/v1/vehicles/"+id, "")
	if r.status != http.StatusOK || r.header.Get("ETag") != etag || !bytes.Equal(bytes.TrimSpace(r.body), bytes.TrimSpace(mustBody(t, m, id))) {
		t.Fatalf("get: %d %v %s", r.status, r.header, r.body)
	}
	if r := m.call(http.MethodGet, "/v1/vehicles/"+id, "", "If-None-Match", etag); r.status != http.StatusNotModified || r.header.Get("ETag") != etag || len(r.body) != 0 {
		t.Fatalf("If-None-Match: %d %v %q", r.status, r.header, r.body)
	}

	// PATCH: If-Match required (428) and checked (412).
	patch := `{"vin":null,"odometerKm":12345,"obdAdapter":{"obdProtocol":7}}`
	if r := m.call(http.MethodPatch, "/v1/vehicles/"+id, patch); r.status != http.StatusPreconditionRequired || r.problemCode(t) != "precondition_required" {
		t.Fatalf("PATCH without If-Match: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodPatch, "/v1/vehicles/"+id, patch, "If-Match", `"v99"`); r.status != http.StatusPreconditionFailed || r.problemCode(t) != "etag_mismatch" {
		t.Fatalf("PATCH stale If-Match: %d %s", r.status, r.body)
	}
	r = m.call(http.MethodPatch, "/v1/vehicles/"+id, patch, "If-Match", etag)
	if r.status != http.StatusOK {
		t.Fatalf("PATCH: %d %s", r.status, r.body)
	}
	v = r.json(t)
	newTag := r.header.Get("ETag")
	if newTag == etag || v["vin"] != nil || v["odometerKm"] != float64(12345) || v["modelYear"] != float64(2021) || v["nickname"] != "Blue" {
		t.Fatalf("PATCH: null must clear vin, absent must keep modelYear/nickname: %v %v", newTag, v)
	}
	if obd, _ := v["obdAdapter"].(map[string]any); obd["elmVersion"] != "1.5" || obd["obdProtocol"] != float64(7) {
		t.Errorf("obdAdapter merge: %v", v["obdAdapter"])
	}
	if v["updatedAt"] == v["createdAt"] {
		t.Error("updatedAt not changed")
	}
	// The old ETag is now stale; a no-op patch keeps the ETag.
	if r := m.call(http.MethodPatch, "/v1/vehicles/"+id, `{"nickname":"Blue"}`, "If-Match", etag); r.status != http.StatusPreconditionFailed {
		t.Fatalf("PATCH with the old ETag: %d", r.status)
	}
	if r := m.call(http.MethodPatch, "/v1/vehicles/"+id, `{"nickname":"Blue"}`, "If-Match", newTag); r.status != http.StatusOK || r.header.Get("ETag") != newTag {
		t.Fatalf("no-op PATCH: %d %v", r.status, r.header)
	}
	// Non-nullable fields can't be nulled (422), minProperties 1.
	for _, bad := range []string{`{"make":null}`, `{"archived":null}`, `{}`, `{"modelYear":1900}`} {
		if r := m.call(http.MethodPatch, "/v1/vehicles/"+id, bad, "If-Match", newTag); r.status != http.StatusUnprocessableEntity {
			t.Errorf("PATCH %s: %d %s", bad, r.status, r.body)
		}
	}

	// DELETE: If-Match required and checked; then 404 and 410.
	if r := m.call(http.MethodDelete, "/v1/vehicles/"+id, ""); r.status != http.StatusPreconditionRequired {
		t.Fatalf("DELETE without If-Match: %d", r.status)
	}
	if r := m.call(http.MethodDelete, "/v1/vehicles/"+id, "", "If-Match", etag); r.status != http.StatusPreconditionFailed {
		t.Fatalf("DELETE stale: %d", r.status)
	}
	if r := m.call(http.MethodDelete, "/v1/vehicles/"+id, "", "If-Match", newTag); r.status != http.StatusNoContent {
		t.Fatalf("DELETE: %d %s", r.status, r.body)
	}
	for _, method := range []string{http.MethodGet, http.MethodDelete} {
		if r := m.call(method, "/v1/vehicles/"+id, "", "If-Match", "*"); r.status != http.StatusNotFound || r.problemCode(t) != "not_found" {
			t.Errorf("%s after delete: %d", method, r.status)
		}
	}
	if r := m.call(http.MethodPatch, "/v1/vehicles/"+id, `{"nickname":"x"}`, "If-Match", "*"); r.status != http.StatusNotFound {
		t.Errorf("PATCH after delete: %d", r.status)
	}
	r = m.call(http.MethodPost, "/v1/vehicles", withClientID(i20, clientID))
	if r.status != http.StatusGone || r.problemCode(t) != "vehicle_deleted" {
		t.Fatalf("re-create deleted: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodGet, "/v1/vehicles?includeArchived=true", ""); len(r.json(t)["items"].([]any)) != 0 {
		t.Errorf("tombstone listed: %s", r.body)
	}
}

func mustBody(t *testing.T, m *member, id string) []byte {
	t.Helper()
	r := m.e.do(http.MethodGet, "/v1/vehicles/"+id, nil, "Authorization", "Bearer "+m.token)
	if r.status != http.StatusOK {
		t.Fatalf("get: %d", r.status)
	}
	return r.body
}

// Vehicles are scoped to their member, and non-UUID ids are 404 (#180).
func TestVehicleScopeAndIDs(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	alice, bob := e.member("alice"), e.member("bob")
	clientID := uuid.New()
	r := alice.call(http.MethodPost, "/v1/vehicles", withClientID(i20, clientID))
	if r.status != http.StatusCreated {
		t.Fatalf("create: %d %s", r.status, r.body)
	}
	id, etag := r.json(t)["id"].(string), r.header.Get("ETag")
	for _, c := range []struct{ method, body string }{
		{http.MethodGet, ""}, {http.MethodPatch, `{"nickname":"mine"}`}, {http.MethodDelete, ""},
	} {
		if r := bob.call(c.method, "/v1/vehicles/"+id, c.body, "If-Match", etag); r.status != http.StatusNotFound {
			t.Errorf("bob %s alice's vehicle: %d", c.method, r.status)
		}
		for _, bad := range []string{"not-a-uuid", "123", "client:" + id, "%7B" + id + "%7D"} {
			if r := alice.call(c.method, "/v1/vehicles/"+bad, c.body, "If-Match", etag); r.status != http.StatusNotFound {
				t.Errorf("%s /v1/vehicles/%s: %d %s", c.method, bad, r.status, r.body)
			}
		}
	}
	if r := bob.call(http.MethodGet, "/v1/vehicles", ""); len(r.json(t)["items"].([]any)) != 0 {
		t.Errorf("bob lists alice's vehicle: %s", r.body)
	}
	// Bob's identical clientVehicleId is his own vehicle.
	if r := bob.call(http.MethodPost, "/v1/vehicles", withClientID(i20, clientID)); r.status != http.StatusCreated || r.json(t)["id"] == id {
		t.Errorf("bob create with alice's client id: %d %s", r.status, r.body)
	}
	if r := alice.call(http.MethodGet, "/v1/vehicles/"+id, ""); r.status != http.StatusOK {
		t.Errorf("alice's vehicle changed: %d", r.status)
	}
}

func TestVehicleList(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("list")
	var ids []string
	for i := range 5 {
		body := `{"make":"M","model":"` + string(rune('a'+i)) + `","fuelType":"electric"` + map[bool]string{true: `,"archived":true`, false: ``}[i == 2] + `}`
		r := m.call(http.MethodPost, "/v1/vehicles", body)
		if r.status != http.StatusCreated {
			t.Fatalf("create: %d %s", r.status, r.body)
		}
		ids = append(ids, r.json(t)["id"].(string))
	}
	walk := func(query string) []string {
		t.Helper()
		var got []string
		path := "/v1/vehicles?limit=2" + query
		for range 10 {
			r := m.call(http.MethodGet, path, "")
			if r.status != http.StatusOK {
				t.Fatalf("list %s: %d %s", path, r.status, r.body)
			}
			page := r.json(t)
			for _, it := range page["items"].([]any) {
				got = append(got, it.(map[string]any)["id"].(string))
			}
			next, present := page["nextCursor"]
			if !present {
				t.Fatal("nextCursor missing")
			}
			if next == nil {
				return got
			}
			path = "/v1/vehicles?limit=2" + query + "&cursor=" + next.(string)
		}
		t.Fatal("walk did not end")
		return nil
	}
	if got := walk("&includeArchived=true"); strings.Join(got, ",") != strings.Join(ids, ",") {
		t.Errorf("with archived: %v, want %v", got, ids)
	}
	want := []string{ids[0], ids[1], ids[3], ids[4]}
	if got := walk(""); strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("without archived: %v, want %v", got, want)
	}
	if r := m.call(http.MethodGet, "/v1/vehicles", ""); !strings.Contains(string(r.body), `"nextCursor":null`) || len(r.json(t)["items"].([]any)) != 4 {
		t.Errorf("default page: %s", r.body)
	}
	for _, bad := range []string{"nope", "dj06bm9wZQ", "%3D%3D"} {
		if r := m.call(http.MethodGet, "/v1/vehicles?cursor="+bad, ""); r.status != http.StatusBadRequest {
			t.Errorf("cursor %q: %d", bad, r.status)
		}
	}
	for _, bad := range []string{"0", "101", "x"} {
		if r := m.call(http.MethodGet, "/v1/vehicles?limit="+bad, ""); r.status != http.StatusBadRequest {
			t.Errorf("limit %q: %d", bad, r.status)
		}
	}
}

func TestVehicleIdempotencyKey(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("idem")
	key := uuid.NewString()
	body := withClientID(i20, uuid.New())

	first := m.call(http.MethodPost, "/v1/vehicles", body, "Idempotency-Key", key)
	if first.status != http.StatusCreated {
		t.Fatalf("create: %d %s", first.status, first.body)
	}
	// Retry with the same key and body: the stored 201, byte for byte.
	again := m.call(http.MethodPost, "/v1/vehicles", body, "Idempotency-Key", key)
	if again.status != http.StatusCreated || !bytes.Equal(again.body, first.body) ||
		again.header.Get("ETag") != first.header.Get("ETag") || again.header.Get("Location") != first.header.Get("Location") {
		t.Fatalf("replay: %d %v %s", again.status, again.header, again.body)
	}
	// Without the key the clientVehicleId de-duplication answers 200.
	if r := m.call(http.MethodPost, "/v1/vehicles", body); r.status != http.StatusOK {
		t.Fatalf("no key: %d", r.status)
	}
	// Same key, other body: 422.
	if r := m.call(http.MethodPost, "/v1/vehicles", withClientID(i20, uuid.New()), "Idempotency-Key", key); r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "idempotency_key_reused" {
		t.Fatalf("reused key: %d %s", r.status, r.body)
	}
	// Same key from another member is a different key.
	other := e.member("idem-other")
	if r := other.call(http.MethodPost, "/v1/vehicles", body, "Idempotency-Key", key); r.status != http.StatusCreated {
		t.Fatalf("other member, same key: %d %s", r.status, r.body)
	}
	// A key without a clientVehicleId still makes the create happen once.
	k2 := uuid.NewString()
	a := m.call(http.MethodPost, "/v1/vehicles", i20, "Idempotency-Key", k2)
	b := m.call(http.MethodPost, "/v1/vehicles", i20, "Idempotency-Key", k2)
	if a.status != http.StatusCreated || !bytes.Equal(a.body, b.body) {
		t.Fatalf("no client id: %d %s / %d %s", a.status, a.body, b.status, b.body)
	}
	if r := m.call(http.MethodGet, "/v1/vehicles", ""); len(r.json(t)["items"].([]any)) != 2 {
		t.Errorf("vehicles: %s", r.body)
	}

	// The tombstone beats the stored replay: after the delete, a retry of
	// the original create gets 410, not the stored 201.
	if r := m.call(http.MethodDelete, first.header.Get("Location"), "", "If-Match", first.header.Get("ETag")); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d %s", r.status, r.body)
	}
	if r := m.call(http.MethodPost, "/v1/vehicles", body, "Idempotency-Key", key); r.status != http.StatusGone || r.problemCode(t) != "vehicle_deleted" {
		t.Fatalf("replay after delete: %d %s", r.status, r.body)
	}
	// A malformed key is a 400 from the validator.
	if r := m.call(http.MethodPost, "/v1/vehicles", body, "Idempotency-Key", "nope"); r.status != http.StatusBadRequest {
		t.Fatalf("bad key: %d", r.status)
	}
}

// Concurrent creates with the same clientVehicleId (each with its own
// Idempotency-Key, or none) create one vehicle: one 201, the rest 200.
func TestVehicleConcurrentCreate(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("race")
	body := withClientID(i20, uuid.New())
	const n = 10
	var wg sync.WaitGroup
	results := make([]response, n)
	for i := range n {
		wg.Go(func() {
			var h []string
			if i%2 == 0 {
				h = []string{"Idempotency-Key", uuid.NewString()}
			}
			results[i] = m.e.do(http.MethodPost, "/v1/vehicles", strings.NewReader(body),
				append([]string{"Authorization", "Bearer " + m.token, "Content-Type", "application/json"}, h...)...)
		})
	}
	wg.Wait()
	created, id := 0, ""
	for _, r := range results {
		switch r.status {
		case http.StatusCreated:
			created++
		case http.StatusOK:
		default:
			t.Fatalf("status %d %s", r.status, r.body)
		}
		got := r.json(t)["id"].(string)
		if id == "" {
			id = got
		} else if got != id {
			t.Fatalf("two vehicles: %s and %s", id, got)
		}
	}
	if created != 1 {
		t.Fatalf("%d creates, want 1", created)
	}
}

// A DELETE racing a PATCH under the same ETag: exactly one wins (200 + 412,
// or 204 + 404).
func TestVehicleDeleteRacesPatch(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("delpatch")
	for round := range 10 {
		r := m.call(http.MethodPost, "/v1/vehicles", withClientID(i20, uuid.New()))
		loc, etag := r.header.Get("Location"), r.header.Get("ETag")
		var wg sync.WaitGroup
		var patch, del response
		auth := []string{"Authorization", "Bearer " + m.token, "If-Match", etag}
		wg.Go(func() {
			patch = m.e.do(http.MethodPatch, loc, strings.NewReader(`{"nickname":"race"}`), append(auth, "Content-Type", "application/merge-patch+json")...)
		})
		wg.Go(func() { del = m.e.do(http.MethodDelete, loc+"?cascade=true", nil, auth...) })
		wg.Wait()
		ok := (patch.status == http.StatusOK && del.status == http.StatusPreconditionFailed) ||
			(patch.status == http.StatusNotFound && del.status == http.StatusNoContent)
		if !ok {
			t.Fatalf("round %d: PATCH %d %s, DELETE %d %s", round, patch.status, patch.body, del.status, del.body)
		}
	}
}

// Without a database the vehicle operations answer 501 (local, ci and
// sandbox only; elsewhere DATABASE_URL is required).
func TestVehiclesWithoutDatabase(t *testing.T) {
	t.Parallel()
	e := newTestEnv(t, true)
	m := e.member("nodb")
	id := uuid.NewString()
	for _, c := range []struct{ method, path, body string }{
		{http.MethodGet, "/v1/vehicles", ""},
		{http.MethodPost, "/v1/vehicles", i20},
		{http.MethodGet, "/v1/vehicles/" + id, ""},
		{http.MethodPatch, "/v1/vehicles/" + id, `{"nickname":"x"}`},
		{http.MethodDelete, "/v1/vehicles/" + id, ""},
	} {
		r := m.call(c.method, c.path, c.body, "If-Match", `"v1"`, "Idempotency-Key", uuid.NewString())
		if r.status != http.StatusNotImplemented || r.problemCode(t) != "not_implemented" {
			t.Errorf("%s %s: %d %s", c.method, c.path, r.status, r.body)
		}
	}
}
