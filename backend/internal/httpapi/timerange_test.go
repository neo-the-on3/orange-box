package httpapi

import (
	"fmt"
	"net/http"
	"testing"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/timeutil"
)

// Timestamps the schema accepts (4-digit year, any offset) but whose UTC
// instant falls outside years 0001-9999. Stored, they can't be encoded as
// RFC 3339 again, so every later read of them failed with 500.
var outOfRangeInstants = []string{
	"9999-12-31T23:59:59-01:00",
	"9999-12-31T23:30:00-00:30",
	"0000-01-01T00:00:00+14:00",
	"0000-12-31T23:59:59Z",
}

func pointers(t *testing.T, r response) []string {
	t.Helper()
	var got []string
	errs, _ := r.json(t)["errors"].([]any)
	for _, fe := range errs {
		got = append(got, fe.(map[string]any)["pointer"].(string))
	}
	return got
}

func TestDtcReportReadAtRange(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("dtc-range")
	vehicle := m.newVehicle()
	for _, at := range outOfRangeInstants {
		r := m.postDtc(vehicle, readReport(uuid.New(), at))
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
			t.Errorf("readAt %s: %d %s", at, r.status, r.body)
			continue
		}
		if got := pointers(t, r); fmt.Sprint(got) != "[/readAt]" {
			t.Errorf("readAt %s: pointers %v", at, got)
		}
		if d := r.json(t)["errors"].([]any)[0].(map[string]any)["detail"]; d != timeutil.ErrOutOfRange.Error() {
			t.Errorf("readAt %s: detail %q", at, d)
		}
	}
	// The edges of the range are fine.
	for _, at := range []string{"9999-12-31T23:59:59.999999Z", "0001-01-01T00:00:00Z", "9999-12-31T23:59:59+01:00", "0001-01-01T00:00:00-01:00"} {
		if r := m.postDtc(vehicle, readReport(uuid.New(), at)); r.status != http.StatusCreated {
			t.Errorf("readAt %s: %d %s", at, r.status, r.body)
		}
	}
	if r := m.call(http.MethodGet, dtcPath(vehicle), ""); r.status != http.StatusOK || len(r.json(t)["items"].([]any)) != 4 {
		t.Fatalf("list: %d %s", r.status, r.body)
	}
}

func TestTripTimestampRange(t *testing.T) {
	t.Parallel()
	e := newDBTestEnv(t)
	m := e.member("trip-range")
	vehicle := m.vehicle()
	const ok = "2026-10-23T14:00:00Z"
	_, since := m.walk("/v1/trips")
	for _, at := range outOfRangeInstants {
		for _, c := range []struct {
			field, body string
		}{
			{"startedAt", tripJSON(uuid.New(), vehicle, "completed", at, ok)},
			{"snapshotAt", tripJSON(uuid.New(), vehicle, "completed", ok, at)},
			{"endedAt", tripWithEnd(uuid.New(), vehicle, at)},
		} {
			r := m.call(http.MethodPost, "/v1/trips/batch", batch(tripJSON(uuid.New(), vehicle, "completed", ok, ok), c.body), "Idempotency-Key", uuid.NewString())
			if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" {
				t.Errorf("%s %s: %d %s", c.field, at, r.status, r.body)
				continue
			}
			if got, want := pointers(t, r), "[/trips/1/"+c.field+"]"; fmt.Sprint(got) != want {
				t.Errorf("%s %s: pointers %v, want %s", c.field, at, got, want)
			}
		}
	}
	// Nothing was stored, and the history and sync views still work.
	for _, path := range []string{"/v1/trips", "/v1/trips?since=" + q(since)} {
		r := m.call(http.MethodGet, path, "")
		if r.status != http.StatusOK || len(r.json(t)["items"].([]any)) != 0 {
			t.Errorf("%s: %d %s", path, r.status, r.body)
		}
	}
	// A query instant outside the range is a bad parameter, not a 500.
	for _, at := range outOfRangeInstants {
		if r := m.call(http.MethodGet, "/v1/trips?from="+q(at), ""); r.status != http.StatusBadRequest {
			t.Errorf("from=%s: %d %s", at, r.status, r.body)
		}
	}
}

func tripWithEnd(clientID uuid.UUID, vehicle, endedAt string) string {
	return fmt.Sprintf(`{"clientTripId":%q,"vehicleId":%q,"state":"completed","startedAt":"2026-10-23T14:00:00Z","endedAt":%q,`+
		`"snapshotAt":"2026-10-23T14:00:00Z","distanceKm":1,"durationS":60,"maxRpm":3000}`, clientID, vehicle, endedAt)
}
