package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/getkin/kin-openapi/openapi3"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
)

// Structural limits checked by prescan before the body is decoded.
const (
	// MaxJSONValues caps the number of JSON values (objects, arrays and
	// scalars) in one body. A full telemetry batch (5000 samples of ~20
	// fields, plus 500 events) is about 110k values; the cap leaves headroom
	// for extra PIDs while bounding the decode and validation cost of a body
	// made of tiny values (e.g. 8 MiB of `{},` is 2.8M objects).
	MaxJSONValues = 500_000
	// MaxJSONDepth caps nesting. The spec's deepest body nests about 6 levels.
	MaxJSONDepth = 64
)

var (
	errTooManyValues = errors.New("too many JSON values")
	errTooDeep       = errors.New("JSON nested too deeply")
	errSyntax        = errors.New("JSON syntax error") // left to the validator to report
)

// maxItemsError reports an array longer than its schema's maxItems.
type maxItemsError struct {
	pointer string
	max     uint64
}

func (e *maxItemsError) Error() string {
	return fmt.Sprintf("%s: more than %d items", e.pointer, e.max)
}

// prescan walks a JSON body once, without decoding it, and rejects it early
// when it breaks a structural limit that would otherwise only be noticed
// after a full decode and schema validation:
//   - an array longer than its schema's maxItems (422 with a pointer, after
//     reading at most maxItems+1 elements of it);
//   - more than MaxJSONValues values in total (413), which also bounds arrays
//     the schema leaves unbounded (e.g. a trip's dtc.stored);
//   - nesting deeper than MaxJSONDepth (400).
//
// kin-openapi decodes the whole body into map[string]any and, for arrays,
// validates every item before (and regardless of) maxItems, so without this
// a ~1 KB gzip of `{"trips":[{},{},…]}` costs gigabytes. prescan allocates
// nothing for well-formed input except when building an error. Malformed
// JSON is passed through for the validator to report as 400.
func prescan(body []byte, schema *openapi3.Schema) *problem.Problem {
	s := &scanner{b: body}
	var roots []*openapi3.Schema
	if schema != nil {
		roots = expandAllOf(roots, schema, 0)
	}
	s.ws()
	err := s.value(roots, 0)
	var mi *maxItemsError
	switch {
	case err == nil, errors.Is(err, errSyntax):
		return nil
	case errors.As(err, &mi):
		return problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, "1 field is invalid").
			WithTitle("Validation failed").
			WithErrors(problem.FieldError{Pointer: mi.pointer, Detail: fmt.Sprintf("must have at most %d items", mi.max)})
	case errors.Is(err, errTooManyValues):
		return problem.New(http.StatusRequestEntityTooLarge, problem.CodePayloadTooLarge,
			fmt.Sprintf("The request body has more than %d JSON values.", MaxJSONValues))
	case errors.Is(err, errTooDeep):
		return problem.New(http.StatusBadRequest, problem.CodeBadRequest,
			fmt.Sprintf("The request body is nested more than %d levels deep.", MaxJSONDepth))
	default:
		return nil
	}
}

// pathElem is one step of the current location: an object key (raw JSON
// string contents) or an array index.
type pathElem struct {
	key   []byte
	index int
	isKey bool
}

type scanner struct {
	b      []byte
	i      int
	values int
	path   []pathElem
}

func (s *scanner) ws() {
	for s.i < len(s.b) {
		switch s.b[s.i] {
		case ' ', '\t', '\n', '\r':
			s.i++
		default:
			return
		}
	}
}

// value scans one JSON value at s.i; schemas are the (allOf-expanded)
// schemas that apply to it, possibly none.
func (s *scanner) value(schemas []*openapi3.Schema, depth int) error {
	if s.values++; s.values > MaxJSONValues {
		return errTooManyValues
	}
	if s.i >= len(s.b) {
		return errSyntax
	}
	switch s.b[s.i] {
	case '{':
		if depth >= MaxJSONDepth {
			return errTooDeep
		}
		return s.object(schemas, depth+1)
	case '[':
		if depth >= MaxJSONDepth {
			return errTooDeep
		}
		return s.array(schemas, depth+1)
	case '"':
		_, err := s.str()
		return err
	default:
		return s.scalar()
	}
}

func (s *scanner) object(schemas []*openapi3.Schema, depth int) error {
	s.i++ // {
	s.ws()
	if s.i < len(s.b) && s.b[s.i] == '}' {
		s.i++
		return nil
	}
	for {
		s.ws()
		if s.i >= len(s.b) || s.b[s.i] != '"' {
			return errSyntax
		}
		key, err := s.str()
		if err != nil {
			return err
		}
		s.ws()
		if s.i >= len(s.b) || s.b[s.i] != ':' {
			return errSyntax
		}
		s.i++
		s.ws()
		var child []*openapi3.Schema
		if len(schemas) > 0 {
			child = propertySchemas(schemas, key)
		}
		s.path = append(s.path, pathElem{key: key, isKey: true})
		if err := s.value(child, depth); err != nil {
			return err
		}
		s.path = s.path[:len(s.path)-1]
		s.ws()
		if s.i >= len(s.b) {
			return errSyntax
		}
		switch s.b[s.i] {
		case ',':
			s.i++
		case '}':
			s.i++
			return nil
		default:
			return errSyntax
		}
	}
}

func (s *scanner) array(schemas []*openapi3.Schema, depth int) error {
	s.i++ // [
	var (
		child  []*openapi3.Schema
		max    uint64
		hasMax bool
	)
	for _, sc := range schemas {
		if sc.Items != nil && sc.Items.Value != nil {
			child = expandAllOf(child, sc.Items.Value, 0)
		}
		if sc.MaxItems != nil && (!hasMax || *sc.MaxItems < max) {
			max, hasMax = *sc.MaxItems, true
		}
	}
	s.ws()
	if s.i < len(s.b) && s.b[s.i] == ']' {
		s.i++
		return nil
	}
	for n := 0; ; n++ {
		if hasMax && uint64(n) >= max {
			return &maxItemsError{pointer: s.pointer(), max: max}
		}
		s.ws()
		s.path = append(s.path, pathElem{index: n})
		if err := s.value(child, depth); err != nil {
			return err
		}
		s.path = s.path[:len(s.path)-1]
		s.ws()
		if s.i >= len(s.b) {
			return errSyntax
		}
		switch s.b[s.i] {
		case ',':
			s.i++
		case ']':
			s.i++
			return nil
		default:
			return errSyntax
		}
	}
}

// str skips a string at s.i and returns its raw contents (escapes intact).
func (s *scanner) str() ([]byte, error) {
	start := s.i + 1
	for j := start; j < len(s.b); j++ {
		switch s.b[j] {
		case '\\':
			j++ // skip the escaped byte; \uXXXX's hex digits are plain bytes
		case '"':
			s.i = j + 1
			return s.b[start:j], nil
		}
	}
	return nil, errSyntax
}

// scalar skips a number, true, false or null.
func (s *scanner) scalar() error {
	start := s.i
	for s.i < len(s.b) {
		switch s.b[s.i] {
		case ',', ']', '}', ' ', '\t', '\n', '\r':
			if s.i == start {
				return errSyntax
			}
			return nil
		case '{', '[', '"', ':':
			return errSyntax
		}
		s.i++
	}
	if s.i == start {
		return errSyntax
	}
	return nil
}

// pointer renders the current location as a JSON pointer.
func (s *scanner) pointer() string {
	tokens := make([]string, len(s.path))
	for i, p := range s.path {
		if p.isKey {
			tokens[i] = unescapeKey(p.key)
		} else {
			tokens[i] = strconv.Itoa(p.index)
		}
	}
	return pointer(tokens)
}

func unescapeKey(raw []byte) string {
	for _, c := range raw {
		if c == '\\' {
			var out string
			if err := json.Unmarshal(append(append([]byte{'"'}, raw...), '"'), &out); err == nil {
				return out
			}
			break
		}
	}
	return string(raw)
}

// propertySchemas returns the schemas that apply to property key of an
// object matching schemas: declared properties first, else
// additionalProperties schemas.
func propertySchemas(schemas []*openapi3.Schema, rawKey []byte) []*openapi3.Schema {
	escaped := false
	for _, c := range rawKey {
		if c == '\\' {
			escaped = true
			break
		}
	}
	var key string
	if escaped {
		key = unescapeKey(rawKey)
	}
	var out []*openapi3.Schema
	for _, sc := range schemas {
		var ref *openapi3.SchemaRef
		var ok bool
		if escaped {
			ref, ok = sc.Properties[key]
		} else {
			ref, ok = sc.Properties[string(rawKey)] // no allocation for a map index
		}
		if ok && ref != nil && ref.Value != nil {
			out = expandAllOf(out, ref.Value, 0)
		} else if ap := sc.AdditionalProperties.Schema; ap != nil && ap.Value != nil {
			out = expandAllOf(out, ap.Value, 0)
		}
	}
	return out
}

// expandAllOf appends sc and, recursively, its allOf members. oneOf/anyOf
// branches are ambiguous without validation and are not followed.
func expandAllOf(out []*openapi3.Schema, sc *openapi3.Schema, depth int) []*openapi3.Schema {
	out = append(out, sc)
	if depth > 8 {
		return out
	}
	for _, ref := range sc.AllOf {
		if ref != nil && ref.Value != nil {
			out = expandAllOf(out, ref.Value, depth+1)
		}
	}
	return out
}
