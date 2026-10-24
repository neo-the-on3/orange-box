package timeutil

import (
	"encoding/json"
	"testing"
	"time"
)

func TestParseUtcInstant(t *testing.T) {
	t.Parallel()
	ok := []struct{ in, want string }{
		{"2026-10-23T14:05:00Z", "2026-10-23T14:05:00.000Z"},
		{"2026-10-23T14:05:00.1Z", "2026-10-23T14:05:00.100Z"},
		{"2026-10-23T14:05:00.123Z", "2026-10-23T14:05:00.123Z"},
		{"2026-10-23T14:05:00.123999Z", "2026-10-23T14:05:00.123Z"},    // truncated, not rounded
		{"2026-10-23T14:05:00.999999999Z", "2026-10-23T14:05:00.999Z"}, // never rolls over
	}
	for _, tc := range ok {
		got, err := ParseUtcInstant(tc.in)
		if err != nil {
			t.Errorf("ParseUtcInstant(%q): %v", tc.in, err)
			continue
		}
		if got.String() != tc.want {
			t.Errorf("ParseUtcInstant(%q) = %s, want %s", tc.in, got, tc.want)
		}
	}
	bad := []string{
		"", "2026-10-23", "2026-10-23T14:05:00", "2026-10-23T14:05:00z",
		"2026-10-23T14:05:00+00:00", "2026-10-23T14:05:00+05:30",
		"2026-10-23T14:05:00.Z", "2026-10-23T14:05:00.1234567890Z",
		"2026-13-23T14:05:00Z", "2026-10-23 14:05:00Z",
	}
	for _, in := range bad {
		if _, err := ParseUtcInstant(in); err == nil {
			t.Errorf("ParseUtcInstant(%q) succeeded, want error", in)
		}
	}
}

func TestUtcInstantJSON(t *testing.T) {
	t.Parallel()
	var v struct {
		T UtcInstant `json:"t"`
	}
	if err := json.Unmarshal([]byte(`{"t":"2026-10-23T14:05:00.123456789Z"}`), &v); err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	if string(out) != `{"t":"2026-10-23T14:05:00.123Z"}` {
		t.Errorf("round trip = %s", out)
	}
	if err := json.Unmarshal([]byte(`{"t":"2026-10-23T14:05:00+00:00"}`), &v); err == nil {
		t.Error("offset accepted, want error")
	}
	if err := json.Unmarshal([]byte(`{"t":123}`), &v); err == nil {
		t.Error("number accepted, want error")
	}
	if got := NewUtcInstant(time.Date(2026, 1, 2, 3, 4, 5, 6_789_000, time.FixedZone("IST", 19800))).String(); got != "2026-01-01T21:34:05.006Z" {
		t.Errorf("NewUtcInstant = %s", got)
	}
}
