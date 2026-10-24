package httpapi

import (
	"net/http"
	"strconv"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
)

func batchSchema(t *testing.T, path string) *openapi3.Schema {
	t.Helper()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	op := doc.Paths.Find(path).Post
	return requestBodySchema(op, "application/json; charset=utf-8")
}

func TestPrescan(t *testing.T) {
	t.Parallel()
	trips := batchSchema(t, "/v1/trips/batch")
	telemetry := batchSchema(t, "/v1/telemetry/batches")
	token := batchSchema(t, "/v1/auth/token")
	items := func(n int, item string) string {
		return strings.TrimSuffix(strings.Repeat(item+",", n), ",")
	}
	for _, tc := range []struct {
		name    string
		schema  *openapi3.Schema
		body    string
		status  int    // 0: passes
		pointer string // for 422
	}{
		{"100 trips ok", trips, `{"trips":[` + items(100, `{}`) + `]}`, 0, ""},
		{"101 trips", trips, `{"trips":[` + items(101, `{}`) + `]}`, http.StatusUnprocessableEntity, "/trips"},
		{"whitespace", trips, " {\n \"trips\" : [ " + items(101, " { } ") + " ] } ", http.StatusUnprocessableEntity, "/trips"},
		{"escaped key", trips, `{"tr\u0069ps":[` + items(101, `{}`) + `]}`, http.StatusUnprocessableEntity, "/trips"},
		{"strings with brackets", trips, `{"x":"[\"{,","trips":[` + items(101, `"],}"`) + `]}`, http.StatusUnprocessableEntity, "/trips"},
		{"5000 samples ok", telemetry, `{"samples":[` + items(5000, `{"t":"x"}`) + `]}`, 0, ""},
		{"5001 samples", telemetry, `{"samples":[` + items(5001, `{"t":"x"}`) + `]}`, http.StatusUnprocessableEntity, "/samples"},
		{"501 events", telemetry, `{"samples":[],"events":[` + items(501, `1`) + `]}`, http.StatusUnprocessableEntity, "/events"},
		{"unknown property unbounded", trips, `{"other":[` + items(1000, `0`) + `]}`, 0, ""},
		{"no schema", nil, `[` + items(1000, `0`) + `]`, 0, ""},
		{"too many values", nil, `[` + items(MaxJSONValues, `0`) + `]`, http.StatusRequestEntityTooLarge, ""},
		{"too deep", nil, strings.Repeat("[", MaxJSONDepth+1) + strings.Repeat("]", MaxJSONDepth+1), http.StatusBadRequest, ""},
		{"max depth ok", nil, strings.Repeat("[", MaxJSONDepth) + strings.Repeat("]", MaxJSONDepth), 0, ""},
		{"malformed left to validator", trips, `{"trips":[{},{}`, 0, ""},
		{"truncated string", trips, `{"trips":["abc`, 0, ""},
		{"garbage", trips, `}{`, 0, ""},
		// Member names: exact case only, no duplicates (second #163 re-test).
		{"case variant beside the real key", trips, `{"trips":[{}],"TRIPS":[{"state":"x"}]}`, http.StatusUnprocessableEntity, "/TRIPS"},
		{"case variant alone", trips, `{"Trips":[{}]}`, http.StatusUnprocessableEntity, "/Trips"},
		{"unicode fold variant", trips, `{"tripſ":[{}]}`, http.StatusUnprocessableEntity, "/tripſ"},
		{"escaped case variant", trips, `{"\u0054rips":[{}]}`, http.StatusUnprocessableEntity, "/Trips"},
		{"nested case variant", trips, `{"trips":[{"durationS":1},{"DurationS":-5}]}`, http.StatusUnprocessableEntity, "/trips/1/DurationS"},
		{"case variant through allOf/ref", trips, `{"trips":[{"dtc":{"Stored":["P0300"]}}]}`, http.StatusUnprocessableEntity, "/trips/0/dtc/Stored"},
		{"case variant in a oneOf branch", token, `{"grantType":"email_otp","Email":"a@b.co","code":"123456"}`, http.StatusUnprocessableEntity, "/Email"},
		{"unknown name is left to the schema", trips, `{"trips":[{}],"tripsX":1}`, 0, ""},
		{"map keys differing by case are fine", telemetry, `{"samples":[{"t":"x","pids":{"0x0b":1,"0x0B":2}}]}`, 0, ""},
		{"duplicate", trips, `{"trips":[{}],"trips":[{"state":"x"}]}`, http.StatusUnprocessableEntity, "/trips"},
		{"escaped duplicate", trips, `{"trips":[{}],"tr\u0069ps":[{}]}`, http.StatusUnprocessableEntity, "/trips"},
		{"nested duplicate in a map", telemetry, `{"samples":[{"t":"x","pids":{"0x0B":1,"0x0B":2}}]}`, http.StatusUnprocessableEntity, "/samples/0/pids/0x0B"},
		{"duplicate without a schema", nil, `[{"a":{"b":1,"b":2}}]`, http.StatusUnprocessableEntity, "/0/a/b"},
		{"duplicate in a large object", nil, `{` + manyKeys(40) + `,"k7":0}`, http.StatusUnprocessableEntity, "/k7"},
		{"large object without duplicates", nil, `{` + manyKeys(40) + `}`, 0, ""},
		{"same name in sibling objects", nil, `[{"a":1},{"a":2}]`, 0, ""},
	} {
		_, p := prescan([]byte(tc.body), tc.schema, MaxJSONValues)
		switch {
		case tc.status == 0 && p != nil:
			t.Errorf("%s: got %d %s", tc.name, p.Status, p.Detail)
		case tc.status != 0 && (p == nil || p.Status != tc.status):
			t.Errorf("%s: got %+v, want %d", tc.name, p, tc.status)
		case tc.pointer != "" && (len(p.Errors) != 1 || p.Errors[0].Pointer != tc.pointer):
			t.Errorf("%s: errors %+v, want pointer %s", tc.name, p.Errors, tc.pointer)
		}
	}
}

func manyKeys(n int) string {
	var b strings.Builder
	for i := range n {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(`"k` + strconv.Itoa(i) + `":0`)
	}
	return b.String()
}

// Member names count toward the value budget, and the budget is a parameter
// (uploads and other bodies differ).
func TestPrescanBudget(t *testing.T) {
	t.Parallel()
	body := []byte(`{` + manyKeys(10) + `}`) // 1 object + 10 names + 10 values
	if n, p := prescan(body, nil, 21); p != nil || n != 21 {
		t.Errorf("at budget: %d %+v", n, p)
	}
	if _, p := prescan(body, nil, 20); p == nil || p.Status != http.StatusRequestEntityTooLarge {
		t.Errorf("over budget: %+v", p)
	}
}

func TestPrescanNestedPointer(t *testing.T) {
	t.Parallel()
	s := &openapi3.Schema{Properties: openapi3.Schemas{
		"a/b": {Value: &openapi3.Schema{Items: &openapi3.SchemaRef{Value: &openapi3.Schema{
			AllOf: openapi3.SchemaRefs{{Value: &openapi3.Schema{Properties: openapi3.Schemas{
				"list": {Value: &openapi3.Schema{MaxItems: openapi3.Ptr(uint64(2))}},
			}}}},
		}}}},
	}}
	_, p := prescan([]byte(`{"a/b":[{"list":[1,2]},{"list":[1,2,3]}]}`), s, MaxJSONValues)
	if p == nil || len(p.Errors) != 1 || p.Errors[0].Pointer != "/a~1b/1/list" || p.Errors[0].Detail != "must have at most 2 items" {
		t.Fatalf("got %+v", p)
	}
}

func BenchmarkPrescan(b *testing.B) {
	doc, _ := api.GetSpec()
	schema := requestBodySchema(doc.Paths.Find("/v1/telemetry/batches").Post, "application/json")
	body := []byte(`{"clientBatchId":"x","samples":[` + strings.TrimSuffix(strings.Repeat(
		`{"t":"2026-10-23T14:05:00.123Z","lat":12.97,"lon":77.59,"speedKmh":42.5,"rpm":1800,"accel":{"x":0.1,"y":0.2,"z":9.8},"pids":{"0x0B":1.5}},`, 5000), ",") + `]}`)
	b.SetBytes(int64(len(body)))
	b.ReportAllocs()
	for b.Loop() {
		if _, p := prescan(body, schema, MaxJSONValues); p != nil {
			b.Fatal(p.Detail)
		}
	}
}
