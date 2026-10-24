package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"
	"unsafe"

	"github.com/getkin/kin-openapi/openapi3"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
)

// Structural limits checked by prescan before the body is decoded.
const (
	// MaxJSONValues caps the JSON values (objects, arrays, scalars and object
	// member names) in one upload body. A full telemetry batch (5000 samples
	// of ~20 fields, i.e. ~40 names and values each, plus 500 events) is
	// about 220k; the cap leaves headroom for extra PIDs while bounding the
	// decode and validation cost of a body made of tiny values (8 MiB of
	// `{},` is 2.8M objects; 8 MiB of `"k1":0,` is 1.2M names and values).
	MaxJSONValues = 500_000
	// MaxBodyValues is the same cap for every other (non-upload) body. The
	// largest of them (a vehicle or profile) has well under 100.
	MaxBodyValues = 4096
	// MaxJSONDepth caps nesting. The spec's deepest body nests about 6 levels.
	MaxJSONDepth = 64
)

var (
	errTooManyValues = errors.New("too many JSON values")
	errTooDeep       = errors.New("JSON nested too deeply")
	errSyntax        = errors.New("JSON syntax error") // left to the validator to report
	errNUL           = errors.New("string contains U+0000")
)

// maxItemsError reports an array longer than its schema's maxItems.
type maxItemsError struct {
	pointer string
	max     uint64
}

func (e *maxItemsError) Error() string {
	return fmt.Sprintf("%s: more than %d items", e.pointer, e.max)
}

// keyError reports an object member name that would be decoded differently
// from how it was validated: a duplicate, or a case variant of a declared
// property.
type keyError struct {
	pointer string
	detail  string
}

func (e *keyError) Error() string { return e.pointer + ": " + e.detail }

// prescan walks a JSON body once, without decoding it, and rejects it early
// when it breaks a structural limit that would otherwise only be noticed
// after a full decode and schema validation:
//   - an array longer than its schema's maxItems (422 with a pointer, after
//     reading at most maxItems+1 elements of it);
//   - more than MaxJSONValues values in total (413), which also bounds arrays
//     the schema leaves unbounded (e.g. a trip's dtc.stored);
//   - nesting deeper than MaxJSONDepth (400);
//   - a duplicate member name in any object, or a name that differs from a
//     property declared by the object's schema only by case (422 with a
//     pointer). kin-openapi treats "TRIPS" as an unknown property, while
//     encoding/json would decode it into Trips (case-insensitive, last
//     duplicate wins), so the handler could see unvalidated data;
//   - U+0000 in any string, value or member name, raw or escaped as \u0000
//     (422 with a pointer: the value's, or for a name the object's).
//     Postgres text can't store it, so it would otherwise fail the write
//     with a 500, and nothing the API stores needs it.
//
// maxValues counts objects, arrays, scalars and member names. prescan
// returns how many it saw (up to the first error).
//
// kin-openapi decodes the whole body into map[string]any and, for arrays,
// validates every item before (and regardless of) maxItems, so without this
// a ~1 KB gzip of `{"trips":[{},{},…]}` costs gigabytes. prescan allocates
// little: a name set for objects with more than 16 members, and errors. Malformed
// JSON is passed through for the validator to report as 400.
func prescan(body []byte, schema *openapi3.Schema, maxValues int) (int, *problem.Problem) {
	s := &scanner{b: body, maxValues: maxValues}
	var roots []*openapi3.Schema
	if schema != nil {
		roots = expandAllOf(roots, schema, 0)
	}
	s.ws()
	err := s.value(roots, 0)
	var (
		mi *maxItemsError
		ke *keyError
	)
	invalid := func(f problem.FieldError) *problem.Problem {
		return problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, "1 field is invalid").
			WithTitle("Validation failed").WithErrors(f)
	}
	switch {
	case err == nil, errors.Is(err, errSyntax):
		return s.values, nil
	case errors.As(err, &mi):
		return s.values, invalid(problem.FieldError{Pointer: mi.pointer, Detail: fmt.Sprintf("must have at most %d items", mi.max)})
	case errors.As(err, &ke):
		return s.values, invalid(problem.FieldError{Pointer: ke.pointer, Detail: ke.detail})
	case errors.Is(err, errTooManyValues):
		return s.values, problem.New(http.StatusRequestEntityTooLarge, problem.CodePayloadTooLarge,
			fmt.Sprintf("The request body has more than %d JSON values and member names.", maxValues))
	case errors.Is(err, errTooDeep):
		return s.values, problem.New(http.StatusBadRequest, problem.CodeBadRequest,
			fmt.Sprintf("The request body is nested more than %d levels deep.", MaxJSONDepth))
	default:
		return s.values, nil
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
	b         []byte
	i         int
	values    int
	maxValues int
	path      []pathElem
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
	if s.values++; s.values > s.maxValues {
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
		if errors.Is(err, errNUL) {
			return &keyError{pointer: s.pointer(), detail: "must not contain U+0000"}
		}
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
	// Names seen so far: linear search for small objects, a map beyond.
	var (
		small  [16]string
		nSmall int
		seen   map[string]struct{}
	)
	for {
		s.ws()
		if s.i >= len(s.b) || s.b[s.i] != '"' {
			return errSyntax
		}
		key, err := s.str()
		if errors.Is(err, errNUL) {
			// The name itself is not echoed: point at its object.
			return &keyError{pointer: s.pointer(), detail: "has a member name that contains U+0000"}
		}
		if err != nil {
			return err
		}
		if s.values++; s.values > s.maxValues {
			return errTooManyValues
		}
		s.ws()
		if s.i >= len(s.b) || s.b[s.i] != ':' {
			return errSyntax
		}
		s.i++
		s.ws()
		s.path = append(s.path, pathElem{key: key, isKey: true})

		name := keyName(key)
		dup := false
		if seen != nil {
			_, dup = seen[name]
			seen[name] = struct{}{}
		} else {
			for _, k := range small[:nSmall] {
				if k == name {
					dup = true
					break
				}
			}
			if nSmall < len(small) {
				small[nSmall] = name
				nSmall++
			} else {
				seen = make(map[string]struct{}, 2*len(small))
				for _, k := range small {
					seen[k] = struct{}{}
				}
				seen[name] = struct{}{}
			}
		}
		if dup {
			return &keyError{pointer: s.pointer(), detail: "is a duplicate member name"}
		}
		var child []*openapi3.Schema
		if len(schemas) > 0 {
			if canonical, ok := caseVariant(schemas, name); ok {
				return &keyError{pointer: s.pointer(),
					detail: fmt.Sprintf("must be spelled %q: member names are case-sensitive", canonical)}
			}
			child = propertySchemas(schemas, name)
		}
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
// It returns errNUL for a string holding U+0000, raw or as \u0000 (no other
// escape decodes to it: a surrogate pair is never U+0000).
func (s *scanner) str() ([]byte, error) {
	start := s.i + 1
	for j := start; j < len(s.b); j++ {
		switch s.b[j] {
		case 0:
			return nil, errNUL
		case '\\':
			j++ // skip the escaped byte; \uXXXX's hex digits are plain bytes
			if j+4 < len(s.b) && s.b[j] == 'u' && string(s.b[j+1:j+5]) == "0000" {
				return nil, errNUL
			}
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
	return truncatePointer(pointer(tokens))
}

// maxPointerLen caps the pointers prescan reports: member names come from
// the client and can be long.
const maxPointerLen = 256

// truncatePointer shortens p to at most maxPointerLen bytes on a UTF-8
// boundary, marking the cut with "…".
func truncatePointer(p string) string {
	if len(p) <= maxPointerLen {
		return p
	}
	cut := maxPointerLen - len("…")
	for cut > 0 && !utf8.RuneStart(p[cut]) {
		cut--
	}
	return p[:cut] + "…"
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

// keyName returns the member name for a raw JSON string. Names without
// escapes alias the body (which isn't modified while it is scanned), so
// they cost no allocation.
func keyName(raw []byte) string {
	for _, c := range raw {
		if c == '\\' {
			return unescapeKey(raw)
		}
	}
	if len(raw) == 0 {
		return ""
	}
	// The string aliases the body, which nothing writes to while the scan
	// runs, and never outlives the scan (errors copy what they report).
	return unsafe.String(&raw[0], len(raw)) //nolint:gosec // G103 audited: read-only alias, see above
}

// propertySchemas returns the schemas that apply to property key of an
// object matching schemas: declared properties first, else
// additionalProperties schemas.
func propertySchemas(schemas []*openapi3.Schema, key string) []*openapi3.Schema {
	var out []*openapi3.Schema
	for _, sc := range schemas {
		if ref, ok := sc.Properties[key]; ok && ref != nil && ref.Value != nil {
			out = expandAllOf(out, ref.Value, 0)
		} else if ap := sc.AdditionalProperties.Schema; ap != nil && ap.Value != nil {
			out = expandAllOf(out, ap.Value, 0)
		}
	}
	return out
}

// caseVariant reports whether name is not a property declared by schemas
// (including their oneOf/anyOf branches, which decoders may also match) but
// equals one when compared case-insensitively, and returns that property.
// The comparison uses Unicode case folding, as encoding/json v1 does.
func caseVariant(schemas []*openapi3.Schema, name string) (string, bool) {
	if declared(schemas, name, 0) {
		return "", false
	}
	return foldMatch(schemas, name, 0)
}

func declared(schemas []*openapi3.Schema, name string, depth int) bool {
	for _, sc := range schemas {
		if _, ok := sc.Properties[name]; ok {
			return true
		}
		if depth < 8 && (declared(branchSchemas(sc.OneOf), name, depth+1) || declared(branchSchemas(sc.AnyOf), name, depth+1) ||
			declared(branchSchemas(sc.AllOf), name, depth+1)) {
			return true
		}
	}
	return false
}

func foldMatch(schemas []*openapi3.Schema, name string, depth int) (string, bool) {
	for _, sc := range schemas {
		for prop := range sc.Properties {
			if strings.EqualFold(prop, name) {
				return prop, true
			}
		}
		if depth < 8 {
			for _, refs := range []openapi3.SchemaRefs{sc.OneOf, sc.AnyOf, sc.AllOf} {
				if len(refs) == 0 {
					continue
				}
				if prop, ok := foldMatch(branchSchemas(refs), name, depth+1); ok {
					return prop, true
				}
			}
		}
	}
	return "", false
}

// branchSchemas resolves a list of schema refs, skipping unresolved ones.
func branchSchemas(refs openapi3.SchemaRefs) []*openapi3.Schema {
	if len(refs) == 0 {
		return nil
	}
	out := make([]*openapi3.Schema, 0, len(refs))
	for _, ref := range refs {
		if ref != nil && ref.Value != nil {
			out = append(out, ref.Value)
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
