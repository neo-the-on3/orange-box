package timeutil

import (
	"errors"
	"time"
)

// The instants the API accepts and stores: years 0001 to 9999 in UTC.
// RFC 3339 has four-digit years only, so a stored instant outside this
// range can't be written back out (encoding/json fails on it), and
// Postgres rejects instants before 4713 BC. A client timestamp can pass
// the date-time format with an offset and still land outside the range
// once converted to UTC, e.g. 9999-12-31T23:59:59-01:00.
var (
	MinInstant = time.Date(1, time.January, 1, 0, 0, 0, 0, time.UTC)
	MaxInstant = time.Date(9999, time.December, 31, 23, 59, 59, 999999999, time.UTC)
)

// ErrOutOfRange is the error of an instant outside MinInstant..MaxInstant.
// It never contains the value.
var ErrOutOfRange = errors.New("must fall within years 0001 to 9999 once converted to UTC")

// InRange reports whether t lies within MinInstant..MaxInstant.
func InRange(t time.Time) bool {
	return !t.Before(MinInstant) && !t.After(MaxInstant)
}

// CheckRange returns ErrOutOfRange unless InRange(t).
func CheckRange(t time.Time) error {
	if !InRange(t) {
		return ErrOutOfRange
	}
	return nil
}
