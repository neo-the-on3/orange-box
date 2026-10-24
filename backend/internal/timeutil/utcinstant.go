// Package timeutil holds time types shared by the API and storage layers.
package timeutil

import (
	"errors"
	"regexp"
	"time"
)

// UtcInstantLayout is the wire format the server emits: RFC 3339 in UTC with
// exactly three fractional digits and a Z suffix.
const UtcInstantLayout = "2006-01-02T15:04:05.000Z"

// utcInstantPattern mirrors the UtcInstant schema in openapi-v2.yaml: a Z
// suffix (upper case only) and 0 to 9 fractional-second digits.
var utcInstantPattern = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z$`)

// ErrInvalidUtcInstant is returned for a value that is not a UtcInstant.
// It never contains the offending value.
var ErrInvalidUtcInstant = errors.New("must be an RFC 3339 UTC timestamp with a Z suffix and 0 to 9 fractional digits")

// UtcInstant is a telemetry timestamp. Parsing accepts 0 to 9 fractional
// digits (Android's Instant.toString()) and truncates, never rounds, to
// milliseconds. Marshalling always emits exactly three fractional digits.
type UtcInstant struct {
	time.Time
}

// NewUtcInstant returns t in UTC, truncated to milliseconds.
func NewUtcInstant(t time.Time) UtcInstant {
	return UtcInstant{Time: t.UTC().Truncate(time.Millisecond)}
}

// ParseUtcInstant parses s strictly (see UtcInstant).
func ParseUtcInstant(s string) (UtcInstant, error) {
	if !utcInstantPattern.MatchString(s) {
		return UtcInstant{}, ErrInvalidUtcInstant
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return UtcInstant{}, ErrInvalidUtcInstant
	}
	return NewUtcInstant(t), nil
}

// String returns the wire format.
func (u UtcInstant) String() string {
	return u.UTC().Truncate(time.Millisecond).Format(UtcInstantLayout)
}

// MarshalText implements encoding.TextMarshaler (and so JSON).
func (u UtcInstant) MarshalText() ([]byte, error) {
	return []byte(u.String()), nil
}

// UnmarshalText implements encoding.TextUnmarshaler (and so JSON).
func (u *UtcInstant) UnmarshalText(b []byte) error {
	v, err := ParseUtcInstant(string(b))
	if err != nil {
		return err
	}
	*u = v
	return nil
}

// MarshalJSON emits a JSON string in the wire format.
func (u UtcInstant) MarshalJSON() ([]byte, error) {
	b := make([]byte, 0, len(UtcInstantLayout)+2)
	b = append(b, '"')
	b = append(b, u.String()...)
	return append(b, '"'), nil
}

// UnmarshalJSON parses a JSON string strictly.
func (u *UtcInstant) UnmarshalJSON(b []byte) error {
	if len(b) < 2 || b[0] != '"' || b[len(b)-1] != '"' {
		return ErrInvalidUtcInstant
	}
	return u.UnmarshalText(b[1 : len(b)-1])
}
