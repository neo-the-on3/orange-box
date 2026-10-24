package httpapi

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

// TestLocalMidnight checks day starts where midnight is skipped, happens
// twice, or the whole day is skipped.
func TestLocalMidnight(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		zone, day, want string
	}{
		{"UTC", "2026-10-23", "2026-10-23T00:00:00Z"},
		{"Asia/Kolkata", "2026-10-23", "2026-10-22T18:30:00Z"},
		{"Asia/Calcutta", "2026-10-23", "2026-10-22T18:30:00Z"},
		// DST starts at midnight: the day starts at 01:00 (-03).
		{"America/Santiago", "2026-09-06", "2026-09-06T04:00:00Z"},
		// DST starts at 24:00 on Thursday: Friday starts at 01:00 (+03).
		{"Asia/Amman", "2021-02-26", "2021-02-25T22:00:00Z"},
		// DST ends at 01:00 (+03) back to 00:00 (+02): midnight happens
		// twice and the day starts at the first one.
		{"Asia/Amman", "2021-10-29", "2021-10-28T21:00:00Z"},
		{"Asia/Amman", "2021-10-30", "2021-10-29T22:00:00Z"},
		// 2011-12-30 doesn't exist in Apia (-10 to +14): its range is empty.
		{"Pacific/Apia", "2011-12-29", "2011-12-29T10:00:00Z"},
		{"Pacific/Apia", "2011-12-30", "2011-12-30T10:00:00Z"},
		{"Pacific/Apia", "2011-12-31", "2011-12-30T10:00:00Z"},
		{"Pacific/Apia", "2012-01-01", "2011-12-31T10:00:00Z"},
	} {
		loc, ok := timeZone(c.zone)
		if !ok {
			t.Fatalf("zone %s", c.zone)
		}
		day, _ := time.Parse(time.DateOnly, c.day)
		if got := localMidnight(day, loc).UTC().Format(time.RFC3339); got != c.want {
			t.Errorf("%s %s: %s, want %s", c.zone, c.day, got, c.want)
		}
	}
}

// TestLocalMidnightSweep checks, every day from 1900 to 2040 in the zones
// with skipped days or midnight transitions, that a day's bound is the
// first instant on or after its date and bounds never decrease.
func TestLocalMidnightSweep(t *testing.T) {
	t.Parallel()
	zones := []string{
		"Pacific/Apia", "Pacific/Fakaofo", "Pacific/Kanton", "Pacific/Kiritimati", "Pacific/Kwajalein",
		"Asia/Amman", "Asia/Gaza", "Asia/Hebron", "Antarctica/Casey", "America/Santiago", "America/Havana",
		"Asia/Tehran", "Asia/Kathmandu", "Australia/Lord_Howe", "Europe/London", "Asia/Kolkata",
	}
	first := time.Date(1900, 1, 1, 0, 0, 0, 0, time.UTC)
	last := time.Date(2040, 12, 31, 0, 0, 0, 0, time.UTC)
	for _, z := range zones {
		loc, ok := timeZone(z)
		if !ok {
			t.Fatalf("zone %s", z)
		}
		date := func(x time.Time) string { return x.In(loc).Format(time.DateOnly) }
		var prev time.Time
		empty := 0
		for d := first; !d.After(last); d = d.AddDate(0, 0, 1) {
			b := localMidnight(d, loc)
			day := d.Format(time.DateOnly)
			if date(b) < day || date(b.Add(-time.Nanosecond)) >= day {
				t.Fatalf("%s %s: bound %v (local %s, an instant earlier %s)", z, day, b.UTC(), b.In(loc), b.Add(-time.Nanosecond).In(loc))
			}
			if b.Before(prev) {
				t.Fatalf("%s %s: bound %v before the previous day's %v", z, day, b.UTC(), prev.UTC())
			}
			if b.Equal(prev) {
				empty++
			}
			prev = b
		}
		if empty > 0 {
			t.Logf("%s: %d skipped day(s)", z, empty)
		}
	}
}

// TestDrivingDaysTransitions: no 500s around skipped days and midnight
// DST transitions, and trips land on the right local day.
func TestDrivingDaysTransitions(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("days-transitions")
	v := m.vehicle()
	for _, start := range []string{
		"2011-12-30T09:30:00Z", // 23:30 on Dec 29 in Apia (-10)
		"2011-12-30T10:30:00Z", // 00:30 on Dec 31 (+14)
		"2021-10-28T21:30:00Z", // 00:30 on Oct 29 in Amman, the first of two
		"2021-10-28T22:30:00Z", // 00:30 on Oct 29 again
		"2021-02-25T22:30:00Z", // 01:30 on Feb 26 in Amman (midnight skipped)
	} {
		m.upload(tripJSON(uuid.New(), v, "completed", start, start))
	}
	days := func(query string) string {
		t.Helper()
		r := m.call(http.MethodGet, "/v1/trips/days?"+query, "")
		if r.status != http.StatusOK {
			t.Fatalf("days?%s: %d %s", query, r.status, r.body)
		}
		var s []string
		for _, x := range r.json(t)["items"].([]any) {
			d := x.(map[string]any)
			s = append(s, fmt.Sprintf("%s=%v", d["date"], d["tripCount"]))
		}
		return strings.Join(s, ",")
	}
	for query, want := range map[string]string{
		"from=2011-12-30&to=2011-12-30&tz=Pacific/Apia":     "",
		"from=2011-12-29&to=2011-12-31&tz=Pacific/Apia":     "2011-12-29=1,2011-12-31=1",
		"from=2011-12-31&to=2011-12-31&tz=Pacific/Apia":     "2011-12-31=1",
		"from=2011-12-30&to=2011-12-30&tz=Pacific/Fakaofo":  "",
		"from=2021-10-28&to=2021-10-30&tz=Asia/Amman":       "2021-10-29=2",
		"from=2021-10-29&to=2021-10-29&tz=Asia/Amman":       "2021-10-29=2",
		"from=2021-02-25&to=2021-02-27&tz=Asia/Amman":       "2021-02-26=1",
		"from=2026-09-06&to=2026-09-06&tz=America/Santiago": "",
	} {
		if got := days(query); got != want {
			t.Errorf("days?%s = %q, want %q", query, got, want)
		}
	}
}
