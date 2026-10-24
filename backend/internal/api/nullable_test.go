package api

import (
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"reflect"
	"strings"
	"testing"
)

// TestNullIfEmptyMarshalsNull is #169: a required-nullable response field
// such as nextCursor must come out as null, never "".
func TestNullIfEmptyMarshalsNull(t *testing.T) {
	page := VehiclePage{Items: []Vehicle{}, NextCursor: NullIfEmpty("")}
	b, err := json.Marshal(page)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(b), `{"items":[],"nextCursor":null}`; got != want {
		t.Errorf("last page = %s, want %s", got, want)
	}
	page.NextCursor = NullIfEmpty("abc")
	if b, _ = json.Marshal(page); string(b) != `{"items":[],"nextCursor":"abc"}` {
		t.Errorf("next page = %s", b)
	}
	sync := TripPage{Items: []Trip{}, NextCursor: NullIfEmpty(""), NextSince: NullIfEmpty("tok")}
	if b, _ = json.Marshal(sync); !strings.Contains(string(b), `"nextCursor":null`) || !strings.Contains(string(b), `"nextSince":"tok"`) {
		t.Errorf("trip page = %s", b)
	}
}

// TestRequiredNullableFieldsAreCovered guards #169: the zero
// nullable.Nullable marshals as the zero value ("" for a string), not null.
// Required (no omitempty) nullable fields must therefore always be set, with
// NullIfEmpty for the string tokens. Today every such field is a nextCursor
// or nextSince string; a new kind of required-nullable field in the spec
// fails this test so that it gets a helper (and a handler test) too.
func TestRequiredNullableFieldsAreCovered(t *testing.T) {
	f, err := parser.ParseFile(token.NewFileSet(), "api.gen.go", nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	found := 0
	ast.Inspect(f, func(n ast.Node) bool {
		ts, ok := n.(*ast.TypeSpec)
		if !ok {
			return true
		}
		st, ok := ts.Type.(*ast.StructType)
		if !ok {
			return true
		}
		for _, field := range st.Fields.List {
			idx, ok := field.Type.(*ast.IndexExpr)
			if !ok || field.Tag == nil {
				continue
			}
			sel, ok := idx.X.(*ast.SelectorExpr)
			if !ok || sel.Sel.Name != "Nullable" {
				continue
			}
			tag := reflect.StructTag(strings.Trim(field.Tag.Value, "`")).Get("json")
			if strings.Contains(tag, "omitempty") {
				continue
			}
			found++
			name := strings.Split(tag, ",")[0]
			elem, _ := idx.Index.(*ast.Ident)
			if (name != "nextCursor" && name != "nextSince") || elem == nil || elem.Name != "string" {
				t.Errorf("%s.%s is a required nullable field not covered by NullIfEmpty; add a helper for it (#169)", ts.Name.Name, name)
			}
		}
		return true
	})
	if found == 0 {
		t.Fatal("found no required nullable fields: has the generator output changed?")
	}
}

func TestNullableFrom(t *testing.T) {
	year := 2021
	v := Vehicle{ModelYear: NullableFrom(&year), Vin: NullableFrom[string](nil)}
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	if s := string(b); !strings.Contains(s, `"modelYear":2021`) || !strings.Contains(s, `"vin":null`) || strings.Contains(s, `"plateNumber"`) {
		t.Errorf("vehicle = %s (want modelYear 2021, vin null, plateNumber omitted)", s)
	}
}
