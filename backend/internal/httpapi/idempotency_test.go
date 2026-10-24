package httpapi

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// memIdempotency is an in-memory store.Idempotency for middleware tests,
// with the same semantics as the Postgres queries.
type memIdempotency struct {
	mu   sync.Mutex
	keys map[store.IdempotencyScope]*memKey
}

type memKey struct {
	rec         store.IdempotencyRecord
	token       uuid.UUID
	lockedUntil time.Time
}

func newMemIdempotency() *memIdempotency {
	return &memIdempotency{keys: map[store.IdempotencyScope]*memKey{}}
}

func (s *memIdempotency) Claim(_ context.Context, sc store.IdempotencyScope, fp []byte, token uuid.UUID, lockedUntil, _ time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.keys[sc]; ok {
		return false, nil
	}
	s.keys[sc] = &memKey{rec: store.IdempotencyRecord{Fingerprint: fp}, token: token, lockedUntil: lockedUntil}
	return true, nil
}

func (s *memIdempotency) Get(_ context.Context, sc store.IdempotencyScope) (store.IdempotencyRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k, ok := s.keys[sc]
	if !ok {
		return store.IdempotencyRecord{}, store.ErrNotFound
	}
	return k.rec, nil
}

func (s *memIdempotency) Reclaim(_ context.Context, sc store.IdempotencyScope, fp []byte, token uuid.UUID, lockedUntil time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k, ok := s.keys[sc]
	if !ok || k.rec.Completed || !bytes.Equal(k.rec.Fingerprint, fp) || !k.lockedUntil.Before(time.Now()) {
		return false, nil
	}
	k.token, k.lockedUntil = token, lockedUntil
	return true, nil
}

func (s *memIdempotency) Complete(_ context.Context, sc store.IdempotencyScope, token uuid.UUID, status int, headers map[string]string, body []byte) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k, ok := s.keys[sc]
	if !ok || k.rec.Completed || k.token != token {
		return false, nil
	}
	k.rec.Completed, k.rec.Status, k.rec.Headers, k.rec.Body = true, status, headers, body
	return true, nil
}

func (s *memIdempotency) Release(_ context.Context, sc store.IdempotencyScope, token uuid.UUID) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k, ok := s.keys[sc]
	if !ok || k.rec.Completed || k.token != token {
		return false, nil
	}
	delete(s.keys, sc)
	return true, nil
}

// expireLeases makes every in-progress key reclaimable.
func (s *memIdempotency) expireLeases() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, k := range s.keys {
		k.lockedUntil = time.Now().Add(-time.Second)
	}
}

// scriptedVehicles answers CreateVehicle from a script; nothing else is used.
type scriptedVehicles struct {
	store.Vehicles
	mu    sync.Mutex
	calls int
	next  func(call int) (store.Vehicle, store.CreateOutcome, error)
}

func (s *scriptedVehicles) CreateVehicle(context.Context, uuid.UUID, *uuid.UUID, store.VehicleFields) (store.Vehicle, store.CreateOutcome, error) {
	s.mu.Lock()
	s.calls++
	call := s.calls
	s.mu.Unlock()
	return s.next(call)
}

func (s *scriptedVehicles) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.calls
}

func newIdemEnv(t *testing.T, v *scriptedVehicles) (*member, *memIdempotency) {
	t.Helper()
	idem := newMemIdempotency()
	e := newTestEnvWith(t, true, func(d *Deps) {
		d.Vehicles = v
		d.Idempotency = idem
	})
	return e.member("idem-mw"), idem
}

func okVehicle(int) (store.Vehicle, store.CreateOutcome, error) {
	now := time.Now().UTC()
	return store.Vehicle{ID: uuid.New(), VehicleFields: store.VehicleFields{Make: "a", Model: "b", FuelType: "lpg"}, Version: 1, CreatedAt: now, UpdatedAt: now}, store.Created, nil
}

const tinyVehicle = `{"make":"a","model":"b","fuelType":"lpg"}`

// A retry while the first request is still running gets 409, and the
// handler runs once.
func TestIdempotencyInFlight(t *testing.T) {
	t.Parallel()
	release := make(chan struct{})
	started := make(chan struct{})
	v := &scriptedVehicles{next: func(call int) (store.Vehicle, store.CreateOutcome, error) {
		if call == 1 {
			close(started)
			<-release
		}
		return okVehicle(call)
	}}
	m, _ := newIdemEnv(t, v)
	key := uuid.NewString()
	done := make(chan response)
	go func() { done <- m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key) }()
	<-started
	r := m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key)
	if r.status != http.StatusConflict || r.problemCode(t) != "idempotency_in_flight" || r.header.Get("Retry-After") == "" {
		t.Fatalf("in flight: %d %v %s", r.status, r.header, r.body)
	}
	close(release)
	first := <-done
	if first.status != http.StatusCreated {
		t.Fatalf("first: %d %s", first.status, first.body)
	}
	if again := m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key); again.status != http.StatusCreated || !bytes.Equal(again.body, first.body) {
		t.Fatalf("replay: %d %s", again.status, again.body)
	}
	if n := v.count(); n != 1 {
		t.Fatalf("handler ran %d times", n)
	}
}

// A 5xx is not stored: the key is released and a retry runs again.
func TestIdempotencyServerErrorReleases(t *testing.T) {
	t.Parallel()
	v := &scriptedVehicles{next: func(call int) (store.Vehicle, store.CreateOutcome, error) {
		if call == 1 {
			return store.Vehicle{}, 0, errors.New("database down")
		}
		return okVehicle(call)
	}}
	m, _ := newIdemEnv(t, v)
	key := uuid.NewString()
	if r := m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key); r.status != http.StatusInternalServerError {
		t.Fatalf("first: %d", r.status)
	}
	if r := m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key); r.status != http.StatusCreated {
		t.Fatalf("retry: %d %s", r.status, r.body)
	}
	if n := v.count(); n != 2 {
		t.Fatalf("handler ran %d times, want 2", n)
	}
}

// After a request's lease runs out, a retry takes the key over; the stale
// request finishing later can't overwrite the retry's stored response.
func TestIdempotencyTakeoverAfterLease(t *testing.T) {
	t.Parallel()
	release := make(chan struct{})
	started := make(chan struct{})
	v := &scriptedVehicles{next: func(call int) (store.Vehicle, store.CreateOutcome, error) {
		if call == 1 {
			close(started)
			<-release
		}
		return okVehicle(call) // a new vehicle id each call, so the answers differ
	}}
	m, idem := newIdemEnv(t, v)
	key := uuid.NewString()
	done := make(chan response)
	go func() { done <- m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key) }()
	<-started
	idem.expireLeases()
	retry := m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key)
	if retry.status != http.StatusCreated {
		t.Fatalf("takeover: %d %s", retry.status, retry.body)
	}
	close(release)
	if old := <-done; old.status != http.StatusCreated || bytes.Equal(old.body, retry.body) {
		t.Fatalf("stale request: %d %s", old.status, old.body)
	}
	// The stored response is the retry's, not the stale request's.
	if r := m.call(http.MethodPost, "/v1/vehicles", tinyVehicle, "Idempotency-Key", key); !bytes.Equal(r.body, retry.body) {
		t.Fatalf("stored response is not the new owner's: %s vs %s", r.body, retry.body)
	}
	if !strings.Contains(m.e.logs.String(), "idempotency claim lost") {
		t.Error("lost claim not logged")
	}
}

func TestFingerprint(t *testing.T) {
	t.Parallel()
	a := fingerprint("/v1/vehicles/{vehicleId}/dtc-reports", map[string]string{"vehicleId": "x"}, []byte("{}"))
	for _, other := range [][]byte{
		fingerprint("/v1/vehicles/{vehicleId}/dtc-reports", map[string]string{"vehicleId": "y"}, []byte("{}")),
		fingerprint("/v1/vehicles/{vehicleId}/dtc-reports", map[string]string{"vehicleId": "x"}, []byte("{ }")),
		fingerprint("/v1/vehicles/{vehicleId}/dtc-reports", map[string]string{"vehicleId": "x{"}, []byte("}")),
	} {
		if bytes.Equal(a, other) {
			t.Error("fingerprints collide")
		}
	}
	if !bytes.Equal(a, fingerprint("/v1/vehicles/{vehicleId}/dtc-reports", map[string]string{"vehicleId": "x"}, []byte("{}"))) {
		t.Error("fingerprint not deterministic")
	}
}
