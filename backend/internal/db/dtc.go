package db

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// DtcReports is the Postgres store.DtcReports.
type DtcReports struct {
	pool *pgxpool.Pool
	q    *dbgen.Queries
}

var _ store.DtcReports = (*DtcReports)(nil)

// NewDtcReports returns a DTC report store on pool.
func NewDtcReports(pool *pgxpool.Pool) *DtcReports {
	return &DtcReports{pool: pool, q: dbgen.New(pool)}
}

// CreateDtcReport implements store.DtcReports. In one transaction it
// share-locks the live vehicle (LockLiveVehiclesForShare, which a vehicle
// delete waits for with FOR UPDATE) and inserts the report. Concurrent
// uploads of one clientReportId store one report: the unique key on
// (member_id, client_report_id) makes the others wait for it and then read
// it.
func (s *DtcReports) CreateDtcReport(ctx context.Context, member, vehicle uuid.UUID, f store.DtcReportFields) (store.DtcReport, store.DtcOutcome, error) {
	var (
		out     store.DtcReport
		outcome store.DtcOutcome
	)
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		live, err := q.LockLiveVehiclesForShare(ctx, dbgen.LockLiveVehiclesForShareParams{MemberID: member, Ids: []uuid.UUID{vehicle}})
		if err != nil {
			return err
		}
		if len(live) != 1 {
			return store.ErrNotFound
		}
		p := dbgen.InsertDtcReportParams{
			MemberID: member, VehicleID: vehicle, ClientReportID: f.ClientReportID, Kind: f.Kind,
			// Postgres keeps microseconds. pgx already drops the rest;
			// truncating here keeps that independent of the driver.
			ReadAt: f.ReadAt.UTC().Truncate(time.Microsecond),
			Mil:    f.Mil, Stored: f.Stored, Pending: f.Pending, Permanent: f.Permanent,
			Vin: f.Vin, ClientTripID: f.ClientTripID, OdometerKm: f.OdometerKm, DistanceSinceMilKm: f.DistanceSinceMilKm,
		}
		// Retried only when the report that blocked the insert vanished
		// before we read it (a cascade delete of its vehicle in between).
		for range maxAttempts {
			if p.ID, err = uuid.NewV7(); err != nil {
				return err
			}
			row, err := q.InsertDtcReport(ctx, p)
			if err == nil {
				out, outcome = toDtcReport(row), store.DtcCreated
				return nil
			}
			if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			row, err = q.GetDtcReportByClientID(ctx, dbgen.GetDtcReportByClientIDParams{MemberID: member, ClientReportID: f.ClientReportID})
			switch {
			case errors.Is(err, pgx.ErrNoRows):
				continue
			case err != nil:
				return err
			default:
				out, outcome = toDtcReport(row), store.DtcExisting
				return nil
			}
		}
		return errors.New("dtc reports: create kept racing a delete; retry")
	})
	if err != nil {
		return store.DtcReport{}, 0, err
	}
	return out, outcome, nil
}

// ListDtcReports implements store.DtcReports.
func (s *DtcReports) ListDtcReports(ctx context.Context, member, vehicle uuid.UUID, after *store.DtcReportPos, limit int) ([]store.DtcReport, error) {
	maxRows, err := pageLimit(limit)
	if err != nil {
		return nil, err
	}
	ok, err := s.q.VehicleExists(ctx, dbgen.VehicleExistsParams{MemberID: member, ID: vehicle})
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, store.ErrNotFound
	}
	p := dbgen.ListDtcReportsParams{MemberID: member, VehicleID: vehicle, MaxRows: maxRows}
	if after != nil {
		at := after.ReadAt
		p.CursorReadAt, p.CursorID = &at, after.ID
	}
	rows, err := s.q.ListDtcReports(ctx, p)
	if err != nil {
		return nil, err
	}
	out := make([]store.DtcReport, len(rows))
	for i, r := range rows {
		out[i] = toDtcReport(r)
	}
	return out, nil
}

func toDtcReport(r dbgen.DtcReport) store.DtcReport {
	return store.DtcReport{
		ID:        r.ID,
		VehicleID: r.VehicleID,
		DtcReportFields: store.DtcReportFields{
			ClientReportID: r.ClientReportID, Kind: r.Kind, ReadAt: r.ReadAt.UTC(), Mil: r.Mil,
			Stored: r.Stored, Pending: r.Pending, Permanent: r.Permanent,
			Vin: r.Vin, ClientTripID: r.ClientTripID, OdometerKm: r.OdometerKm, DistanceSinceMilKm: r.DistanceSinceMilKm,
		},
		CreatedAt: r.CreatedAt.UTC(),
	}
}
