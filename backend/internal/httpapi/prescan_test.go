package httpapi

import (
	"net/http"
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
	} {
		p := prescan([]byte(tc.body), tc.schema)
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

func TestPrescanNestedPointer(t *testing.T) {
	t.Parallel()
	s := &openapi3.Schema{Properties: openapi3.Schemas{
		"a/b": {Value: &openapi3.Schema{Items: &openapi3.SchemaRef{Value: &openapi3.Schema{
			AllOf: openapi3.SchemaRefs{{Value: &openapi3.Schema{Properties: openapi3.Schemas{
				"list": {Value: &openapi3.Schema{MaxItems: openapi3.Ptr(uint64(2))}},
			}}}},
		}}}},
	}}
	p := prescan([]byte(`{"a/b":[{"list":[1,2]},{"list":[1,2,3]}]}`), s)
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
		if p := prescan(body, schema); p != nil {
			b.Fatal(p.Detail)
		}
	}
}
