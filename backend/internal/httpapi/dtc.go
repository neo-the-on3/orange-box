package httpapi

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// DTC report operations (#112): POST and GET
// /v1/vehicles/{vehicleId}/dtc-reports. They need Postgres; without
// DATABASE_URL (only allowed in local, ci and sandbox) they answer 501.

func dtcReportsUnavailable() *problem.Problem {
	return problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
		"DTC reports need a database; this server runs without DATABASE_URL.")
}

// CreateDtcReport implements POST /v1/vehicles/{vehicleId}/dtc-reports.
// Idempotency-Key handling (replay, 409, 422) happens in the middleware
// before this runs; a new key with a known clientReportId answers 200 with
// the stored report.
func (s *Server) CreateDtcReport(ctx context.Context, req api.CreateDtcReportRequestObject) (api.CreateDtcReportResponseObject, error) {
	if s.dtcReports == nil {
		return nil, dtcReportsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	vehicle, ok := vehicleID(req.VehicleId)
	if !ok {
		return nil, vehicleNotFound()
	}
	b := req.Body
	if p := checkClearedReport(b); p != nil {
		return nil, p
	}
	f := store.DtcReportFields{
		ClientReportID:     b.ClientReportId,
		Kind:               string(b.Kind),
		ReadAt:             b.ReadAt,
		Mil:                fromNullable(b.Mil),
		Vin:                fromNullable(b.Vin),
		ClientTripID:       fromNullable(b.ClientTripId),
		OdometerKm:         widenInt(fromNullable(b.OdometerKm)),
		DistanceSinceMilKm: widenInt(fromNullable(b.DistanceSinceMilKm)),
	}
	f.Stored, f.Pending, f.Permanent = codeList(b.Stored), codeList(b.Pending), codeList(b.Permanent)
	r, outcome, err := s.dtcReports.CreateDtcReport(ctx, member, vehicle, f)
	if errors.Is(err, store.ErrNotFound) {
		return nil, vehicleNotFound()
	}
	if err != nil {
		return nil, err
	}
	// No Location: the spec has no URL for a single report (see #112).
	switch outcome {
	case store.DtcCreated:
		return api.CreateDtcReport201JSONResponse{Body: toAPIDtcReport(r)}, nil
	case store.DtcExisting:
		return api.CreateDtcReport200JSONResponse{Body: toAPIDtcReport(r)}, nil
	default:
		return nil, fmt.Errorf("create dtc report: unexpected outcome %d", outcome)
	}
}

// checkClearedReport enforces the contents DtcReportCreate defines for
// kind=cleared (a successful mode 04 clear): no permanent list, since mode
// 04 can't clear permanent codes, and mil null, since the MIL state is
// unknown until the next read (never false for unknown). stored and pending
// are the codes cleared, i.e. the last read's lists, and may be left out
// when that read didn't read the mode.
func checkClearedReport(b *api.DtcReportCreate) *problem.Problem {
	if b.Kind != api.DtcReportCreateKindCleared {
		return nil
	}
	var fields []problem.FieldError
	if b.Mil.IsSpecified() && !b.Mil.IsNull() {
		fields = append(fields, problem.FieldError{Pointer: "/mil", Detail: "must be null in a cleared report: the MIL state is unknown until the next read"})
	}
	if b.Permanent != nil {
		fields = append(fields, problem.FieldError{Pointer: "/permanent", Detail: "must be left out of a cleared report: mode 04 can't clear permanent codes"})
	}
	if len(fields) == 0 {
		return nil
	}
	detail := "1 field is invalid"
	if len(fields) > 1 {
		detail = fmt.Sprintf("%d fields are invalid", len(fields))
	}
	return problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, detail).
		WithTitle("Validation failed").WithErrors(fields...)
}

// codeList keeps an absent list absent (nil) and an empty one empty.
func codeList(p *[]api.DtcCode) []string {
	if p == nil {
		return nil
	}
	return append([]string{}, *p...)
}

// ListDtcReports implements GET /v1/vehicles/{vehicleId}/dtc-reports,
// newest readAt first.
func (s *Server) ListDtcReports(ctx context.Context, req api.ListDtcReportsRequestObject) (api.ListDtcReportsResponseObject, error) {
	if s.dtcReports == nil {
		return nil, dtcReportsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	vehicle, ok := vehicleID(req.VehicleId)
	if !ok {
		return nil, vehicleNotFound()
	}
	limit := defaultPageLimit
	if req.Params.Limit != nil {
		limit = min(max(*req.Params.Limit, 1), maxPageLimit)
	}
	var after *store.DtcReportPos
	if req.Params.Cursor != nil {
		pos, ok := decodeDtcCursor(*req.Params.Cursor)
		if !ok {
			return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The cursor is invalid or has expired.")
		}
		after = &pos
	}
	rows, err := s.dtcReports.ListDtcReports(ctx, member, vehicle, after, limit+1)
	if errors.Is(err, store.ErrNotFound) {
		return nil, vehicleNotFound()
	}
	if err != nil {
		return nil, err
	}
	next := ""
	if len(rows) > limit {
		rows = rows[:limit]
		last := rows[limit-1]
		next = encodeDtcCursor(store.DtcReportPos{ReadAt: last.ReadAt, ID: last.ID})
	}
	items := make([]api.DtcReport, len(rows))
	for i, r := range rows {
		items[i] = toAPIDtcReport(r)
	}
	return api.ListDtcReports200JSONResponse{Items: items, NextCursor: api.NullIfEmpty(next)}, nil
}

// dtcCursorPrefix versions the cursor format: "d1:<readAt unix µs>:<id>".
const dtcCursorPrefix = "d1:"

func encodeDtcCursor(p store.DtcReportPos) string {
	return base64.RawURLEncoding.EncodeToString(fmt.Appendf(nil, "%s%d:%s", dtcCursorPrefix, p.ReadAt.UnixMicro(), p.ID))
}

func decodeDtcCursor(c string) (store.DtcReportPos, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return store.DtcReportPos{}, false
	}
	rest, ok := strings.CutPrefix(string(raw), dtcCursorPrefix)
	if !ok {
		return store.DtcReportPos{}, false
	}
	at, id, ok := strings.Cut(rest, ":")
	if !ok {
		return store.DtcReportPos{}, false
	}
	micros, err := strconv.ParseInt(at, 10, 64)
	if err != nil {
		return store.DtcReportPos{}, false
	}
	u, ok := vehicleID(id) // a canonical UUID
	if !ok {
		return store.DtcReportPos{}, false
	}
	return store.DtcReportPos{ReadAt: time.UnixMicro(micros).UTC(), ID: u}, true
}

func toAPIDtcReport(r store.DtcReport) api.DtcReport {
	out := api.DtcReport{
		Id:                 r.ID.String(),
		VehicleId:          r.VehicleID.String(),
		ClientReportId:     r.ClientReportID,
		Kind:               api.DtcReportKind(r.Kind),
		ReadAt:             r.ReadAt.UTC(),
		Mil:                api.NullableFrom(r.Mil),
		Vin:                api.NullableFrom(r.Vin),
		ClientTripId:       api.NullableFrom(r.ClientTripID),
		OdometerKm:         api.NullableFrom(narrowInt64(r.OdometerKm)),
		DistanceSinceMilKm: api.NullableFrom(narrowInt64(r.DistanceSinceMilKm)),
		CreatedAt:          r.CreatedAt.UTC(),
	}
	out.Stored, out.Pending, out.Permanent = codePtr(r.Stored), codePtr(r.Pending), codePtr(r.Permanent)
	return out
}

func codePtr(codes []string) *[]api.DtcCode {
	if codes == nil {
		return nil
	}
	out := append([]api.DtcCode{}, codes...)
	return &out
}
