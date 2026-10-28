package httpapi

import (
	"fmt"
	"net/http"
	"testing"

	"github.com/google/uuid"
)

// A batch for a trip that exists must name the trip's vehicle (422 at
// /vehicleId otherwise), and a cascade delete of a vehicle never removes
// telemetry of another vehicle's trip, in either upload order.
func TestTelemetryVehicleMatchesTrip(t *testing.T) {
	t.Parallel()
	e, _, _ := newTelemetryEnv(t)
	m := e.member("tel-match")
	v1, v2 := m.vehicle(), m.vehicle()
	sample := []string{sampleJSON("2026-10-23T14:00:00.000Z", 1)}
	samplesOf := func(trip uuid.UUID) int {
		t.Helper()
		r := m.call(http.MethodGet, "/v1/trips/client:"+trip.String()+"/samples", "")
		if r.status != http.StatusOK {
			t.Fatalf("samples of %s: %d %s", trip, r.status, r.body)
		}
		return len(r.json(t)["items"].([]any))
	}

	// The trip exists: the batch must name its vehicle.
	trip := uuid.New()
	m.upload(tripJSON(trip, v2, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))
	r := m.sendTelemetry(telemetryJSON(uuid.New(), trip, v1, sample, nil), "")
	if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "validation_failed" || fmt.Sprint(pointers(t, r)) != "[/vehicleId]" {
		t.Errorf("other vehicle: %d %s", r.status, r.body)
	}
	ackOf(t, m.sendTelemetry(telemetryJSON(uuid.New(), trip, v2, sample, nil), ""))

	// Telemetry first, then the trip names another vehicle: deleting the
	// batch's vehicle leaves the trip's telemetry alone.
	early := uuid.New()
	ackOf(t, m.sendTelemetry(telemetryJSON(uuid.New(), early, v1, sample,
		[]string{`{"t":"2026-10-23T14:00:00Z","type":"harsh_brake"}`}), ""))
	m.upload(tripJSON(early, v2, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))
	// And a batch of v1 whose trip hasn't arrived goes with v1.
	orphan := uuid.New()
	ackOf(t, m.sendTelemetry(telemetryJSON(uuid.New(), orphan, v1, sample, nil), ""))

	if r := m.call(http.MethodDelete, "/v1/vehicles/"+v1+"?cascade=true", "", "If-Match", "*"); r.status != http.StatusNoContent {
		t.Fatalf("cascade delete: %d %s", r.status, r.body)
	}
	if n := samplesOf(trip); n != 1 {
		t.Errorf("trip of v2: %d samples after deleting v1", n)
	}
	if n := samplesOf(early); n != 1 {
		t.Errorf("early telemetry of v2's trip: %d samples after deleting v1", n)
	}
	if r := m.call(http.MethodGet, "/v1/trips/client:"+early.String()+"/events", ""); len(r.json(t)["items"].([]any)) != 1 {
		t.Errorf("events of v2's trip after deleting v1: %s", r.body)
	}
	// The v1 batch without a trip went with v1: uploading its trip now
	// (for v2) shows no samples.
	m.upload(tripJSON(orphan, v2, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))
	if n := samplesOf(orphan); n != 0 {
		t.Errorf("v1's tripless batch survived the cascade: %d samples", n)
	}
}

// A clientBatchId names fixed content: resending it with the same content
// is a duplicate (any key, any encoding), with different content 422
// client_batch_id_reused, and nothing of the second body is stored.
func TestTelemetryBatchIDReused(t *testing.T) {
	t.Parallel()
	e, objects, _ := newTelemetryEnv(t)
	m := e.member("tel-reuse")
	vehicle := m.vehicle()
	trip, batch := uuid.New(), uuid.New()
	samples := []string{sampleJSON("2026-10-23T14:00:00.000Z", 1), sampleJSON("2026-10-23T14:00:01.000Z", 2)}
	body := telemetryJSON(batch, trip, vehicle, samples, nil)
	if got := ackOf(t, m.sendTelemetry(body, "")); got != "accepted a=2/0 d=0/0" {
		t.Fatalf("first: %s", got)
	}
	if got := ackOf(t, m.sendTelemetry(body, "")); got != "duplicate a=2/0 d=0/0" {
		t.Errorf("same content, new key: %s", got)
	}
	if got := ackOf(t, m.sendTelemetryGzip(body)); got != "duplicate a=2/0 d=0/0" {
		t.Errorf("same content gzipped: %s", got)
	}
	// Same samples in another order and with sub-millisecond digits
	// normalise to the same content.
	same := telemetryJSON(batch, trip, vehicle, []string{sampleJSON("2026-10-23T14:00:01.0004Z", 2), sampleJSON("2026-10-23T14:00:00.000Z", 1)}, nil)
	if got := ackOf(t, m.sendTelemetry(same, "")); got != "duplicate a=2/0 d=0/0" {
		t.Errorf("same normalised content: %s", got)
	}
	for name, other := range map[string]string{
		"another sample value": telemetryJSON(batch, trip, vehicle, []string{sampleJSON("2026-10-23T14:00:00.000Z", 9), samples[1]}, nil),
		"an extra event":       telemetryJSON(batch, trip, vehicle, samples, []string{`{"t":"2026-10-23T14:00:00Z","type":"idling"}`}),
		"another trip":         telemetryJSON(batch, uuid.New(), vehicle, samples, nil),
	} {
		r := m.sendTelemetry(other, "")
		if r.status != http.StatusUnprocessableEntity || r.problemCode(t) != "client_batch_id_reused" {
			t.Errorf("%s: %d %s", name, r.status, r.body)
		}
	}
	if n := len(objects.Keys()); n != 1 {
		t.Errorf("%d objects, want 1", n)
	}
	m.upload(tripJSON(trip, vehicle, "completed", "2026-10-23T14:00:00Z", "2026-10-23T14:30:00Z"))
	r := m.call(http.MethodGet, "/v1/trips/client:"+trip.String()+"/events", "")
	if r.status != http.StatusOK || len(r.json(t)["items"].([]any)) != 0 {
		t.Errorf("events: %d %s", r.status, r.body)
	}
}
