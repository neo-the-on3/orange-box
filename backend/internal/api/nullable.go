package api

import "github.com/oapi-codegen/nullable"

// NullIfEmpty returns s as a nullable value, or an explicit null when s is
// "". Use it for every required-nullable response field (nextCursor and
// nextSince, `type: [string, 'null']` without omitempty in the generated
// code): their zero nullable.Nullable marshals as "" instead of null, which
// would tell the app there is another page or a sync token (#169).
func NullIfEmpty(s string) nullable.Nullable[string] {
	if s == "" {
		return nullable.NewNullNullable[string]()
	}
	return nullable.NewNullableWithValue(s)
}

// NullableFrom returns *p as a nullable value, or an explicit null when p is
// nil. It maps a nullable database column to a nullable response field.
func NullableFrom[T any](p *T) nullable.Nullable[T] {
	if p == nil {
		return nullable.NewNullNullable[T]()
	}
	return nullable.NewNullableWithValue(*p)
}
