package httpapi

import (
	"reflect"
	"testing"

	"github.com/oapi-codegen/nullable"
)

func TestTypeDetailUnwraps(t *testing.T) {
	t.Parallel()
	const whole = "must be a whole number written without a decimal point or exponent, within range"
	for _, c := range []struct {
		t    reflect.Type
		want string
	}{
		{reflect.TypeFor[int](), whole},
		{reflect.TypeFor[*int](), whole},
		{reflect.TypeFor[nullable.Nullable[int]](), whole},
		{reflect.TypeFor[nullable.Nullable[float64]](), "must be a number within range"},
		{reflect.TypeFor[nullable.Nullable[string]](), "has the wrong type"},
		{reflect.TypeFor[map[bool]int](), "has the wrong type"},
		{nil, "has the wrong type"},
	} {
		if got := typeDetail(c.t); got != c.want {
			t.Errorf("typeDetail(%v) = %q, want %q", c.t, got, c.want)
		}
	}
}
