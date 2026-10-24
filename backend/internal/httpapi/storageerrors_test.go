package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// failingVehicles fails every GetVehicle with err.
type failingVehicles struct {
	store.Vehicles
	err error
}

func (f failingVehicles) GetVehicle(context.Context, uuid.UUID, uuid.UUID) (store.Vehicle, error) {
	return store.Vehicle{}, f.err
}

// TestStorageErrorsAre503 is #178: lock and statement timeouts and
// deadlines are 503 server_busy, an unreachable database 503
// service_unavailable, both with Retry-After; anything else stays 500.
func TestStorageErrorsAre503(t *testing.T) {
	t.Parallel()
	for name, c := range map[string]struct {
		err        error
		status     int
		code       string
		retryAfter string
	}{
		"lock_timeout":       {fmt.Errorf("get: %w", &pgconn.PgError{Code: "55P03"}), http.StatusServiceUnavailable, "server_busy", "1"},
		"statement_timeout":  {&pgconn.PgError{Code: "57014"}, http.StatusServiceUnavailable, "server_busy", "1"},
		"idle in tx timeout": {fmt.Errorf("upsert: %w", &pgconn.PgError{Code: "25P03"}), http.StatusServiceUnavailable, "server_busy", "1"},
		"idle session":       {&pgconn.PgError{Code: "57P05"}, http.StatusServiceUnavailable, "server_busy", "1"},
		"deadline":           {fmt.Errorf("acquire: %w", context.DeadlineExceeded), http.StatusServiceUnavailable, "server_busy", "1"},
		"unreachable":        {&pgconn.PgError{Code: "08006"}, http.StatusServiceUnavailable, "service_unavailable", "5"},
		"other":              {errors.New("boom"), http.StatusInternalServerError, "internal_error", ""},
	} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			e := newTestEnvWith(t, true, func(d *Deps) {
				d.Vehicles = failingVehicles{err: c.err}
				d.ClassifyError = db.Classify
			})
			m := e.member("busy")
			r := m.call(http.MethodGet, "/v1/vehicles/"+uuid.NewString(), "")
			if r.status != c.status || r.problemCode(t) != c.code || r.header.Get("Retry-After") != c.retryAfter {
				t.Fatalf("%d %s Retry-After=%q, want %d %s %q", r.status, r.body, r.header.Get("Retry-After"), c.status, c.code, c.retryAfter)
			}
		})
	}
}
