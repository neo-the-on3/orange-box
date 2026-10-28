package timeutil

import (
	"errors"
	"testing"
	"time"
)

func TestInRange(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		in   string
		want bool
	}{
		{"2026-10-23T14:05:00Z", true},
		{"0001-01-01T00:00:00Z", true},
		{"0001-01-01T00:00:00-01:00", true},
		{"9999-12-31T23:59:59.999999999Z", true},
		{"9999-12-31T23:59:59+01:00", true},
		{"0000-12-31T23:59:59.999999999Z", false},
		{"0000-01-01T00:00:00+14:00", false},
		{"9999-12-31T23:59:59-01:00", false},
		{"9999-12-31T23:30:00-00:30", false},
	} {
		at, err := time.Parse(time.RFC3339Nano, c.in)
		if err != nil {
			t.Fatal(err)
		}
		if got := InRange(at); got != c.want {
			t.Errorf("InRange(%s) = %v, want %v", c.in, got, c.want)
		}
		if err := CheckRange(at); (err == nil) != c.want || (err != nil && !errors.Is(err, ErrOutOfRange)) {
			t.Errorf("CheckRange(%s) = %v", c.in, err)
		}
	}
	if InRange(time.UnixMicro(-210866803200000001)) || InRange(time.UnixMicro(1<<62)) {
		t.Error("far out of range instants are in range")
	}
}
