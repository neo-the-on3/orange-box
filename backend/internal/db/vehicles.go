package db

import (
	"context"
	"errors"
	"fmt"
	"math"
	"reflect"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Vehicles is the Postgres store.Vehicles.
type Vehicles struct {
	pool *pgxpool.Pool
	q    *dbgen.Queries
}

var _ store.Vehicles = (*Vehicles)(nil)

// NewVehicles returns a vehicle store on pool.
func NewVehicles(pool *pgxpool.Pool) *Vehicles {
	return &Vehicles{pool: pool, q: dbgen.New(pool)}
}

// maxAttempts bounds CreateVehicle's retry when it races a tombstone purge.
const maxAttempts = 5

// CreateVehicle implements store.Vehicles. Concurrent creates with the same
// clientVehicleId store one vehicle: the unique key on (member_id,
// client_vehicle_id) makes the others wait for it and then read it.
func (s *Vehicles) CreateVehicle(ctx context.Context, member uuid.UUID, clientID *uuid.UUID, f store.VehicleFields) (store.Vehicle, store.CreateOutcome, error) {
	p, err := insertParams(f)
	if err != nil {
		return store.Vehicle{}, 0, err
	}
	p.MemberID, p.ClientVehicleID = member, clientID
	// Retried only when the row that blocked the insert vanished before we
	// read it (its tombstone was purged in between).
	for range maxAttempts {
		if p.ID, err = uuid.NewV7(); err != nil {
			return store.Vehicle{}, 0, err
		}
		row, err := s.q.InsertVehicle(ctx, p)
		if err == nil {
			return toVehicle(row), store.Created, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return store.Vehicle{}, 0, err
		}
		if clientID == nil {
			return store.Vehicle{}, 0, errors.New("vehicles: insert without a client id conflicted")
		}
		row, err = s.q.GetVehicleByClientID(ctx, dbgen.GetVehicleByClientIDParams{MemberID: member, ClientVehicleID: *clientID})
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			continue
		case err != nil:
			return store.Vehicle{}, 0, err
		case row.DeletedAt != nil:
			return store.Vehicle{}, store.Deleted, nil
		default:
			return toVehicle(row), store.Existing, nil
		}
	}
	return store.Vehicle{}, 0, errors.New("vehicles: create kept racing a purge; retry")
}

// GetVehicle implements store.Vehicles.
func (s *Vehicles) GetVehicle(ctx context.Context, member, id uuid.UUID) (store.Vehicle, error) {
	row, err := s.q.GetVehicle(ctx, dbgen.GetVehicleParams{MemberID: member, ID: id})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.Vehicle{}, store.ErrNotFound
	}
	if err != nil {
		return store.Vehicle{}, err
	}
	return toVehicle(row), nil
}

// ListVehicles implements store.Vehicles.
func (s *Vehicles) ListVehicles(ctx context.Context, member uuid.UUID, includeArchived bool, after *uuid.UUID, limit int) ([]store.Vehicle, error) {
	if limit < 1 || limit > math.MaxInt32 {
		return nil, fmt.Errorf("vehicles: limit %d out of range", limit)
	}
	rows, err := s.q.ListVehicles(ctx, dbgen.ListVehiclesParams{
		MemberID: member, IncludeArchived: includeArchived, AfterID: after, MaxRows: int32(limit),
	})
	if err != nil {
		return nil, err
	}
	out := make([]store.Vehicle, len(rows))
	for i, r := range rows {
		out[i] = toVehicle(r)
	}
	return out, nil
}

// UpdateVehicle implements store.Vehicles. The row is locked for the
// whole read-check-write, as in DeleteVehicle, so concurrent patches (and a
// delete) take turns: each check sees the version it writes over, an
// If-Match: * patch never fails for losing a race, and none is lost.
func (s *Vehicles) UpdateVehicle(ctx context.Context, member, id uuid.UUID, check func(store.Vehicle) error, patch func(store.VehicleFields) store.VehicleFields) (store.Vehicle, error) {
	var out store.Vehicle
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		row, err := q.GetVehicleForUpdate(ctx, dbgen.GetVehicleForUpdateParams{MemberID: member, ID: id})
		if errors.Is(err, pgx.ErrNoRows) {
			return store.ErrNotFound
		}
		if err != nil {
			return err
		}
		cur := toVehicle(row)
		if err := check(cur); err != nil {
			return err
		}
		next := patch(cur.VehicleFields)
		if reflect.DeepEqual(next, cur.VehicleFields) { // compares the values pointed to
			out = cur // nothing changed: same version, same ETag
			return nil
		}
		p, err := updateParams(next)
		if err != nil {
			return err
		}
		p.MemberID, p.ID, p.ExpectedVersion = member, id, cur.Version
		updated, err := q.UpdateVehicle(ctx, p)
		if err != nil {
			// No row can't happen under the lock; report it rather than retry.
			return fmt.Errorf("update vehicle: %w", err)
		}
		out = toVehicle(updated)
		return nil
	})
	if err != nil {
		return store.Vehicle{}, err
	}
	return out, nil
}

// DeleteVehicle implements store.Vehicles.
func (s *Vehicles) DeleteVehicle(ctx context.Context, member, id uuid.UUID, check func(store.Vehicle) error, cascade bool) (store.DeleteResult, error) {
	var res store.DeleteResult
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		row, err := q.GetVehicleForUpdate(ctx, dbgen.GetVehicleForUpdateParams{MemberID: member, ID: id})
		if errors.Is(err, pgx.ErrNoRows) {
			return store.ErrNotFound
		}
		if err != nil {
			return err
		}
		if err := check(toVehicle(row)); err != nil {
			return err
		}
		if row.ClientVehicleID != nil {
			if _, err := q.TombstoneVehicle(ctx, dbgen.TombstoneVehicleParams{MemberID: member, ID: id, ExpectedVersion: row.Version}); err != nil {
				return fmt.Errorf("tombstone vehicle: %w", err)
			}
		} else {
			n, err := q.DeleteVehicle(ctx, dbgen.DeleteVehicleParams{MemberID: member, ID: id, ExpectedVersion: row.Version})
			if err != nil {
				return err
			}
			if n != 1 {
				return fmt.Errorf("delete vehicle: %d rows", n)
			}
		}
		if !cascade {
			return nil
		}
		trips, err := q.TombstoneVehicleTrips(ctx, dbgen.TombstoneVehicleTripsParams{MemberID: member, VehicleID: id})
		if err != nil {
			return fmt.Errorf("tombstone trips: %w", err)
		}
		clientTrips := make([]uuid.UUID, len(trips))
		for i, t := range trips {
			clientTrips[i] = t.ClientTripID
		}
		keys, err := q.DeleteTelemetry(ctx, dbgen.DeleteTelemetryParams{MemberID: member, ClientTripIds: clientTrips, VehicleID: &id})
		if err != nil {
			return fmt.Errorf("delete telemetry: %w", err)
		}
		if _, err := q.DeleteVehicleDtcReports(ctx, dbgen.DeleteVehicleDtcReportsParams{MemberID: member, VehicleID: id}); err != nil {
			return fmt.Errorf("delete dtc reports: %w", err)
		}
		res = store.DeleteResult{TripsDeleted: len(trips), ObjectKeys: keys}
		return nil
	})
	return res, err
}

// ClientVehicleDeleted implements store.Vehicles.
func (s *Vehicles) ClientVehicleDeleted(ctx context.Context, member, clientID uuid.UUID) (bool, error) {
	row, err := s.q.GetVehicleByClientID(ctx, dbgen.GetVehicleByClientIDParams{MemberID: member, ClientVehicleID: clientID})
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return row.DeletedAt != nil, nil
}

func toVehicle(r dbgen.Vehicle) store.Vehicle {
	return store.Vehicle{
		ID:              r.ID,
		ClientVehicleID: r.ClientVehicleID,
		VehicleFields: store.VehicleFields{
			Nickname:             r.Nickname,
			VariantID:            r.VariantID,
			Make:                 deref(r.Make),
			Model:                deref(r.Model),
			FuelType:             deref(r.FuelType),
			EngineDisplacementCc: widen(r.EngineDisplacementCc),
			ModelYear:            widen(r.ModelYear),
			PlateNumber:          r.PlateNumber,
			Vin:                  r.Vin,
			OdometerKm:           r.OdometerKm,
			IdleStopGo:           r.IdleStopGo,
			ObdElmVersion:        r.ObdElmVersion,
			ObdProtocol:          widen(r.ObdProtocol),
			Archived:             r.Archived,
		},
		Version:   r.Version,
		CreatedAt: r.CreatedAt.UTC(),
		UpdatedAt: r.UpdatedAt.UTC(),
	}
}

// narrowed holds the column-sized integers of VehicleFields.
type narrowed struct {
	displacement *int32
	modelYear    *int16
	obdProtocol  *int16
}

func narrow(f store.VehicleFields) (narrowed, error) {
	var n narrowed
	var err error
	if n.displacement, err = narrowInt[int32](f.EngineDisplacementCc, "engineDisplacementCc", math.MinInt32, math.MaxInt32); err != nil {
		return n, err
	}
	if n.modelYear, err = narrowInt[int16](f.ModelYear, "modelYear", math.MinInt16, math.MaxInt16); err != nil {
		return n, err
	}
	if n.obdProtocol, err = narrowInt[int16](f.ObdProtocol, "obdProtocol", math.MinInt16, math.MaxInt16); err != nil {
		return n, err
	}
	return n, nil
}

func insertParams(f store.VehicleFields) (dbgen.InsertVehicleParams, error) {
	n, err := narrow(f)
	if err != nil {
		return dbgen.InsertVehicleParams{}, err
	}
	return dbgen.InsertVehicleParams{
		Nickname: f.Nickname, VariantID: f.VariantID, Make: f.Make, Model: f.Model, FuelType: f.FuelType,
		EngineDisplacementCc: n.displacement, ModelYear: n.modelYear, PlateNumber: f.PlateNumber, Vin: f.Vin,
		OdometerKm: f.OdometerKm, IdleStopGo: f.IdleStopGo, ObdElmVersion: f.ObdElmVersion, ObdProtocol: n.obdProtocol,
		Archived: f.Archived,
	}, nil
}

func updateParams(f store.VehicleFields) (dbgen.UpdateVehicleParams, error) {
	n, err := narrow(f)
	if err != nil {
		return dbgen.UpdateVehicleParams{}, err
	}
	return dbgen.UpdateVehicleParams{
		Nickname: f.Nickname, VariantID: f.VariantID, Make: f.Make, Model: f.Model, FuelType: f.FuelType,
		EngineDisplacementCc: n.displacement, ModelYear: n.modelYear, PlateNumber: f.PlateNumber, Vin: f.Vin,
		OdometerKm: f.OdometerKm, IdleStopGo: f.IdleStopGo, ObdElmVersion: f.ObdElmVersion, ObdProtocol: n.obdProtocol,
		Archived: f.Archived,
	}, nil
}

// narrowInt converts an API int to a column type whose range is [lo, hi].
// The spec's minimum and maximum keep values in range, so out of range is a
// programming error rather than a client one.
func narrowInt[T int16 | int32](p *int, name string, lo, hi int) (*T, error) {
	if p == nil {
		return nil, nil
	}
	if *p < lo || *p > hi {
		return nil, fmt.Errorf("vehicles: %s %d does not fit the column", name, *p)
	}
	t := T(*p)
	return &t, nil
}

func widen[T int16 | int32](p *T) *int {
	if p == nil {
		return nil
	}
	v := int(*p)
	return &v
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
