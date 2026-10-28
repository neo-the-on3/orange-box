package httpapi

import (
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
)

// Bodies over maxMultiErrorBody are validated fail-fast. Their first error
// can itself wrap a MultiError (a nullable field is a type list), and it
// must still be a 422 at the field, not a 400.
func TestLargeBodyFieldErrors(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("large")
	pad := strings.Repeat(" ", maxMultiErrorBody)
	for _, c := range []struct{ method, path, body, pointer string }{
		{http.MethodPatch, "/v1/me", `{"countryCode":"in"` + pad + `}`, "/countryCode"},
		{http.MethodPatch, "/v1/me", `{"regionCode":"IN-` + strings.Repeat("K", 9000) + `"}`, "/regionCode"},
		{http.MethodPost, "/v1/vehicles", strings.Replace(i20, `"KMHB341ABCD123456"`, `"kmhb"`+pad, 1), "/vin"},
	} {
		r := m.call(c.method, c.path, c.body, "If-Match", "*")
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
			t.Errorf("%s %s (%d bytes): %d %.300s", c.method, c.path, len(c.body), r.status, r.body)
			continue
		}
		if got := pointers(t, r); fmt.Sprint(got) != "["+c.pointer+"]" {
			t.Errorf("%s %s: pointers %v, want %s", c.method, c.path, got, c.pointer)
		}
	}
}

// Oversized values of pattern fields fail on maxLength, a 422 at the field,
// before the pattern runs on them.
func TestOversizedPatternFields(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("oversized")
	long := strings.Repeat("A", 7000)
	for _, c := range []struct{ method, path, body, pointer string }{
		{http.MethodPatch, "/v1/me", `{"countryCode":"` + long + `"}`, "/countryCode"},
		{http.MethodPatch, "/v1/me", `{"regionCode":"IN-` + long + `"}`, "/regionCode"},
		{http.MethodPost, "/v1/vehicles", strings.Replace(i20, `"KMHB341ABCD123456"`, `"`+long+`"`, 1), "/vin"},
	} {
		r := m.call(c.method, c.path, c.body, "If-Match", "*")
		if r.status != http.StatusUnprocessableEntity {
			t.Errorf("%s: %d %.300s", c.pointer, r.status, r.body)
			continue
		}
		length := false
		for _, fe := range r.json(t)["errors"].([]any) {
			fe := fe.(map[string]any)
			if fe["pointer"] != c.pointer {
				t.Errorf("%s: unexpected error %v", c.pointer, fe)
			}
			length = length || strings.Contains(fe["detail"].(string), "at most")
		}
		if !length {
			t.Errorf("%s: no length error in %s", c.pointer, r.body)
		}
	}
}

// Every string with a pattern also declares maxLength, so a huge value is
// rejected by length (cheap, and a clear 422) rather than by the pattern.
func TestPatternFieldsHaveMaxLength(t *testing.T) {
	t.Parallel()
	doc, err := api.GetSpec()
	if err != nil {
		t.Fatal(err)
	}
	seen := map[*openapi3.Schema]bool{}
	var walk func(where string, s *openapi3.SchemaRef)
	walk = func(where string, ref *openapi3.SchemaRef) {
		if ref == nil || ref.Value == nil || seen[ref.Value] {
			return
		}
		s := ref.Value
		seen[s] = true
		if s.Pattern != "" && s.MaxLength == nil {
			t.Errorf("%s: pattern %q without maxLength", where, s.Pattern)
		}
		for name, p := range s.Properties {
			walk(where+"."+name, p)
		}
		walk(where+"[]", s.Items)
		if s.AdditionalProperties.Schema != nil {
			walk(where+"{}", s.AdditionalProperties.Schema)
		}
		for i, sub := range append(append(append(openapi3.SchemaRefs{}, s.AllOf...), s.AnyOf...), s.OneOf...) {
			walk(fmt.Sprintf("%s/%d", where, i), sub)
		}
	}
	for name, s := range doc.Components.Schemas {
		walk(name, s)
	}
	for _, p := range doc.Components.Parameters {
		if p.Value != nil {
			walk("parameter "+p.Value.Name, p.Value.Schema)
		}
	}
}
