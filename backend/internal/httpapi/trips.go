package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"
	_ "time/tzdata" // GET /v1/trips/days: every IANA name and alias, whatever the image has

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
	"github.com/neo-the-on3/orange-box/backend/internal/synctoken"
)

// Trip operations (#110): POST /v1/trips/batch, GET /v1/trips,
// /v1/trips/deletions, /v1/trips/days and GET/DELETE /v1/trips/{tripId}.
// They need Postgres; without DATABASE_URL they answer 501.
//
// Timestamps are stored and compared in UTC truncated to milliseconds, as
// for telemetry (UtcInstant), so a snapshotAt resent with more digits is
// not "newer" than the stored one.

// maxDrivingDays is the widest GET /v1/trips/days range, in days.
const maxDrivingDays = 366

func tripsUnavailable() *problem.Problem {
	return problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
		"Trips need a database; this server runs without DATABASE_URL.")
}

func tripNotFound() *problem.Problem {
	return problem.New(http.StatusNotFound, problem.CodeNotFound, "No such trip.")
}

func syncWindowExpired() *problem.Problem {
	return problem.New(http.StatusBadRequest, problem.CodeSyncWindowExpired,
		"The sync token can no longer be used; do a full sync (GET /v1/trips without since).").
		WithTitle("Sync window expired")
}

func badCursor() *problem.Problem {
	return problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The cursor is invalid or has expired.")
}

func badRequest(detail string) *problem.Problem {
	return problem.New(http.StatusBadRequest, problem.CodeBadRequest, detail)
}

// tripRef parses the tripId path parameter: a server id, or a client trip
// id prefixed with "client:". Anything else is a trip that doesn't exist.
func tripRef(s string) (store.TripRef, bool) {
	if rest, ok := strings.CutPrefix(s, "client:"); ok {
		id, ok := vehicleID(rest)
		return store.TripRef{ClientID: &id}, ok
	}
	id, ok := vehicleID(s)
	return store.TripRef{ID: &id}, ok
}

// instant normalises an API timestamp: UTC, truncated to milliseconds.
func instant(t time.Time) time.Time { return t.UTC().Truncate(time.Millisecond) }

func pageSize(p *int) int {
	if p == nil {
		return defaultPageLimit
	}
	return min(max(*p, 1), maxPageLimit)
}

// makeLike allocates a slice of the element type of s (for the generated
// anonymous item structs).
func makeLike[E any](_ []E, n int) []E { return make([]E, n) }

// UploadTrips implements POST /v1/trips/batch. Idempotency-Key handling
// happens in the middleware before this runs.
func (s *Server) UploadTrips(ctx context.Context, req api.UploadTripsRequestObject) (api.UploadTripsResponseObject, error) {
	if s.trips == nil {
		return nil, tripsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	in := req.Body.Trips
	var out api.BatchResult
	out.Results = makeLike(out.Results, len(in))
	uploads := make([]store.TripUpload, 0, len(in))
	index := make([]int, 0, len(in))
	// No trip may be larger than maxTripBytes as JSON: the whole batch is
	// rejected, like any other invalid field.
	for i, t := range in {
		b, err := json.Marshal(t)
		if err != nil {
			return nil, err
		}
		if len(b) > maxTripBytes {
			return nil, problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, "1 field is invalid").
				WithTitle("Validation failed").
				WithErrors(problem.FieldError{Pointer: fmt.Sprintf("/trips/%d", i), Detail: fmt.Sprintf("must be at most %d bytes as JSON", maxTripBytes)})
		}
	}
	for i, t := range in {
		out.Results[i].ClientId = t.ClientTripId
		vehicle, ok := vehicleID(t.VehicleId)
		if !ok {
			out.Results[i].Status = api.BatchResultResultsStatusRejected
			out.Results[i].Problem = itemProblem(ctx, http.StatusNotFound, problem.CodeVehicleNotFound, "vehicleId is not one of your vehicles.")
			continue
		}
		u, err := fromAPITrip(t, vehicle)
		if err != nil {
			return nil, err
		}
		uploads = append(uploads, u)
		index = append(index, i)
	}
	results, err := s.trips.UpsertTrips(ctx, member, uploads)
	if err != nil {
		return nil, err
	}
	for j, r := range results {
		item := &out.Results[index[j]]
		switch r.Outcome {
		case store.TripCreated:
			item.Status = api.BatchResultResultsStatusCreated
		case store.TripUpdated:
			item.Status = api.BatchResultResultsStatusUpdated
		case store.TripUnchanged:
			item.Status = api.BatchResultResultsStatusUnchanged
		case store.TripDeleted:
			item.Status = api.BatchResultResultsStatusRejected
			item.Problem = itemProblem(ctx, http.StatusGone, problem.CodeTripDeleted, "This trip was deleted. Do not send it again.")
		case store.TripVehicleNotFound:
			item.Status = api.BatchResultResultsStatusRejected
			item.Problem = itemProblem(ctx, http.StatusNotFound, problem.CodeVehicleNotFound, "vehicleId is not one of your vehicles.")
		default:
			return nil, fmt.Errorf("upload trips: unexpected outcome %d", r.Outcome)
		}
		if item.Status != api.BatchResultResultsStatusRejected {
			item.Id = api.NullIfEmpty(r.ID.String())
		}
	}
	for i := range out.Results {
		if out.Results[i].Status == api.BatchResultResultsStatusRejected {
			out.Results[i].Id = api.NullIfEmpty("") // explicit null: not accepted
		}
	}
	return api.UploadTrips200JSONResponse{Body: out}, nil
}

// uploadTripsReplay rewrites a stored POST /v1/trips/batch response for
// replay: a deletion tombstone beats a stored replay (spec, Idempotency),
// so an accepted item whose trip has since been deleted is replayed as
// rejected with trip_deleted, like a new upload of it would be.
func (s *Server) uploadTripsReplay(ctx context.Context, member uuid.UUID, status int, stored []byte) ([]byte, error) {
	if s.trips == nil || status != http.StatusOK {
		return stored, nil
	}
	var res api.BatchResult
	if err := json.Unmarshal(stored, &res); err != nil {
		return nil, fmt.Errorf("stored trip batch response: %w", err)
	}
	var accepted []uuid.UUID
	for _, it := range res.Results {
		if it.Status != api.BatchResultResultsStatusRejected {
			accepted = append(accepted, it.ClientId)
		}
	}
	if len(accepted) == 0 {
		return stored, nil
	}
	deleted, err := s.trips.DeletedClientTrips(ctx, member, accepted)
	if err != nil || len(deleted) == 0 {
		return stored, err
	}
	for i := range res.Results {
		it := &res.Results[i]
		if it.Status != api.BatchResultResultsStatusRejected && deleted[it.ClientId] {
			it.Status = api.BatchResultResultsStatusRejected
			it.Id = api.NullIfEmpty("")
			it.Problem = itemProblem(ctx, http.StatusGone, problem.CodeTripDeleted, "This trip was deleted. Do not send it again.")
		}
	}
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(res); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// itemProblem is the problem of one rejected batch item.
func itemProblem(ctx context.Context, status int, code, detail string) *api.Problem {
	p := problem.New(status, code, detail)
	return &api.Problem{
		Type: p.Type, Title: p.Title, Status: p.Status, Code: &p.Code, Detail: &p.Detail,
		RequestId: reqctx.RequestID(ctx),
	}
}

func fromAPITrip(t api.TripUpload, vehicle uuid.UUID) (store.TripUpload, error) {
	f := store.TripFields{
		VehicleID:         vehicle,
		State:             string(t.State),
		StartedAt:         instant(t.StartedAt),
		SnapshotAt:        instant(t.SnapshotAt),
		DurationS:         widenInt(t.DurationS),
		DistanceKm:        t.DistanceKm,
		EngineDistanceKm:  fromNullable(t.EngineDistanceKm),
		MaxSpeedKmh:       t.MaxSpeedKmh,
		AvgSpeedKmh:       t.AvgSpeedKmh,
		MaxRpm:            widenInt(t.MaxRpm),
		HarshAccelCount:   widenInt(t.HarshAccelCount),
		HarshBrakeCount:   widenInt(t.HarshBrakeCount),
		HarshStartCount:   widenInt(t.HarshStartCount),
		HarshStopCount:    widenInt(t.HarshStopCount),
		FuelUsedL:         fromNullable(t.FuelUsedL),
		FuelEconomyKmPerL: fromNullable(t.FuelEconomyKmPerL),
		FuelCutTimeS:      t.FuelCutTimeS,
		IdleTimeS:         t.IdleTimeS,
		WarmUpTimeS:       t.WarmUpTimeS,
		AccelTimeS:        t.AccelTimeS,
		EcoSpeedTimeS:     t.EcoSpeedTimeS,
		OverSpeedTimeS:    t.OverSpeedTimeS,
		EcoScore:          fromNullable(t.EcoScore),
		SafetyScore:       fromNullable(t.SafetyScore),
		BatteryMaxV:       fromNullable(t.BatteryMaxV),
		BatteryAvgV:       fromNullable(t.BatteryAvgV),
		CoolantMaxC:       fromNullable(t.CoolantMaxC),
		CoolantAvgC:       fromNullable(t.CoolantAvgC),
		Simulated:         t.Simulated != nil && *t.Simulated,
	}
	if e := fromNullable(t.EndedAt); e != nil {
		v := instant(*e)
		f.EndedAt = &v
	}
	var err error
	if t.SpeedBands != nil {
		if f.SpeedBands, err = json.Marshal(*t.SpeedBands); err != nil {
			return store.TripUpload{}, err
		}
	}
	if t.Dtc != nil {
		if f.Dtc, err = json.Marshal(*t.Dtc); err != nil {
			return store.TripUpload{}, err
		}
	}
	if t.App != nil {
		if f.App, err = json.Marshal(*t.App); err != nil {
			return store.TripUpload{}, err
		}
	}
	return store.TripUpload{ClientTripID: t.ClientTripId, TripFields: f}, nil
}

func toAPITrip(t store.Trip) (api.Trip, error) {
	out := api.Trip{
		Id:                t.ID.String(),
		ClientTripId:      t.ClientTripID,
		VehicleId:         t.VehicleID.String(),
		State:             api.TripState(t.State),
		StartedAt:         instant(t.StartedAt),
		SnapshotAt:        instant(t.SnapshotAt),
		DurationS:         narrowInt64(t.DurationS),
		DistanceKm:        t.DistanceKm,
		EngineDistanceKm:  api.NullableFrom(t.EngineDistanceKm),
		MaxSpeedKmh:       t.MaxSpeedKmh,
		AvgSpeedKmh:       t.AvgSpeedKmh,
		MaxRpm:            narrowInt64(t.MaxRpm),
		HarshAccelCount:   narrowInt64(t.HarshAccelCount),
		HarshBrakeCount:   narrowInt64(t.HarshBrakeCount),
		HarshStartCount:   narrowInt64(t.HarshStartCount),
		HarshStopCount:    narrowInt64(t.HarshStopCount),
		FuelUsedL:         api.NullableFrom(t.FuelUsedL),
		FuelEconomyKmPerL: api.NullableFrom(t.FuelEconomyKmPerL),
		FuelCutTimeS:      t.FuelCutTimeS,
		IdleTimeS:         t.IdleTimeS,
		WarmUpTimeS:       t.WarmUpTimeS,
		AccelTimeS:        t.AccelTimeS,
		EcoSpeedTimeS:     t.EcoSpeedTimeS,
		OverSpeedTimeS:    t.OverSpeedTimeS,
		EcoScore:          api.NullableFrom(t.EcoScore),
		SafetyScore:       api.NullableFrom(t.SafetyScore),
		BatteryMaxV:       api.NullableFrom(t.BatteryMaxV),
		BatteryAvgV:       api.NullableFrom(t.BatteryAvgV),
		CoolantMaxC:       api.NullableFrom(t.CoolantMaxC),
		CoolantAvgC:       api.NullableFrom(t.CoolantAvgC),
		Simulated:         &t.Simulated,
		PointsAwarded:     api.NullableFrom(narrowInt64(t.PointsAwarded)),
		CreatedAt:         instant(t.CreatedAt),
		UpdatedAt:         instant(t.UpdatedAt),
	}
	var ended *time.Time
	if t.EndedAt != nil {
		e := instant(*t.EndedAt)
		ended = &e
	}
	out.EndedAt = api.NullableFrom(ended)
	if len(t.SpeedBands) > 0 {
		var bands []api.SpeedBand
		if err := json.Unmarshal(t.SpeedBands, &bands); err != nil {
			return api.Trip{}, fmt.Errorf("stored speedBands: %w", err)
		}
		out.SpeedBands = &bands
	}
	if len(t.Dtc) > 0 {
		var dtc api.DtcSet
		if err := json.Unmarshal(t.Dtc, &dtc); err != nil {
			return api.Trip{}, fmt.Errorf("stored dtc: %w", err)
		}
		out.Dtc = &dtc
	}
	if len(t.App) > 0 {
		if err := json.Unmarshal(t.App, &out.App); err != nil {
			return api.Trip{}, fmt.Errorf("stored app: %w", err)
		}
	}
	return out, nil
}

// pageOfTrips converts up to limit of rows, stopping early before the
// page's JSON would exceed tripPageBudget (but always with at least one
// trip). more reports whether rows has trips beyond the page, so the page
// needs a cursor after its last trip.
func pageOfTrips(rows []store.Trip, limit int) (items []api.Trip, more bool, err error) {
	n := min(len(rows), limit)
	items = make([]api.Trip, 0, n)
	size := 0
	for _, t := range rows[:n] {
		item, err := toAPITrip(t)
		if err != nil {
			return nil, false, err
		}
		b, err := json.Marshal(item)
		if err != nil {
			return nil, false, err
		}
		if len(items) > 0 && size+len(b)+1 > tripPageBudget {
			break
		}
		size += len(b) + 1
		items = append(items, item)
	}
	return items, len(items) < len(rows), nil
}

// GetTrip implements GET /v1/trips/{tripId}.
func (s *Server) GetTrip(ctx context.Context, req api.GetTripRequestObject) (api.GetTripResponseObject, error) {
	if s.trips == nil {
		return nil, tripsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	ref, ok := tripRef(req.TripId)
	if !ok {
		return nil, tripNotFound()
	}
	t, err := s.trips.GetTrip(ctx, member, ref)
	if errors.Is(err, store.ErrNotFound) {
		return nil, tripNotFound()
	}
	if err != nil {
		return nil, err
	}
	out, err := toAPITrip(t)
	if err != nil {
		return nil, err
	}
	return api.GetTrip200JSONResponse(out), nil
}

// DeleteTrip implements DELETE /v1/trips/{tripId}: the trip and its
// telemetry are erased and a tombstone is kept.
func (s *Server) DeleteTrip(ctx context.Context, req api.DeleteTripRequestObject) (api.DeleteTripResponseObject, error) {
	if s.trips == nil {
		return nil, tripsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	ref, ok := tripRef(req.TripId)
	if !ok {
		return nil, tripNotFound()
	}
	keys, err := s.trips.DeleteTrip(ctx, member, ref)
	if errors.Is(err, store.ErrNotFound) {
		return nil, tripNotFound()
	}
	if err != nil {
		return nil, err
	}
	if len(keys) > 0 {
		// Telemetry uploads (#111) will write to object storage; until that
		// lands there is nothing to remove, so keys here mean a gap.
		s.logger.WarnContext(ctx, "trip delete left telemetry objects in object storage", slog.Int("objects", len(keys)))
	}
	return api.DeleteTrip204Response{}, nil
}

func samePoint(a, b store.SyncPoint) bool {
	return a.Identity == b.Identity && a.IssuedAt.Equal(b.IssuedAt) && a.Watermark == b.Watermark
}

// ListTrips implements GET /v1/trips: the history view (startedAt desc)
// without since, or a sync walk in change order with since. The last page
// of a sync walk, or of an unfiltered history walk, carries nextSince; the
// cursor carries the walk's since and the token it will end with.
func (s *Server) ListTrips(ctx context.Context, req api.ListTripsRequestObject) (api.ListTripsResponseObject, error) {
	if s.trips == nil {
		return nil, tripsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	p := req.Params
	limit := pageSize(p.Limit)
	filtered := p.VehicleId != nil || p.From != nil || p.To != nil
	if p.Since != nil && filtered {
		return nil, badRequest("since cannot be combined with vehicleId, from or to.")
	}
	var since *store.SyncPoint
	if p.Since != nil {
		sp, err := s.sync.ParseToken(member, *p.Since)
		if err != nil {
			return nil, syncWindowExpired()
		}
		since = &sp
	}
	var cur *synctoken.Cursor
	if p.Cursor != nil {
		c, err := s.sync.ParseCursor(member, *p.Cursor)
		if err != nil {
			return nil, badCursor()
		}
		cur = &c
		switch {
		case c.Kind == synctoken.Changes && !filtered:
			if since != nil && !samePoint(*since, *c.Since) {
				return nil, badRequest("since must be the walk's original since when sent with a cursor.")
			}
			since = c.Since
		case c.Kind == synctoken.History && since == nil:
			if c.Filter != historyFilter(p) {
				return nil, badCursor()
			}
		default:
			return nil, badCursor()
		}
	}
	if since != nil {
		return s.listTripChanges(ctx, member, *since, cur, limit)
	}
	return s.listTripHistory(ctx, member, p, filtered, cur, limit)
}

func historyFilter(p api.ListTripsParams) [16]byte {
	var v, from, to string
	if p.VehicleId != nil {
		v = *p.VehicleId
	}
	if p.From != nil {
		from = p.From.UTC().Format(time.RFC3339Nano)
	}
	if p.To != nil {
		to = p.To.UTC().Format(time.RFC3339Nano)
	}
	return synctoken.FilterHash(v, from, to)
}

func (s *Server) listTripHistory(ctx context.Context, member uuid.UUID, p api.ListTripsParams, filtered bool, cur *synctoken.Cursor, limit int) (api.ListTripsResponseObject, error) {
	q := store.TripHistoryQuery{Snapshot: cur == nil && !filtered, Limit: limit + 1}
	if p.VehicleId != nil {
		id, ok := vehicleID(*p.VehicleId)
		if !ok { // not a server id, so no trips can match
			return api.ListTrips200JSONResponse{Items: []api.Trip{}, NextCursor: api.NullIfEmpty(""), NextSince: api.NullIfEmpty("")}, nil
		}
		q.VehicleID = &id
	}
	if p.From != nil {
		f := p.From.UTC()
		q.From = &f
	}
	if p.To != nil {
		t := p.To.UTC()
		q.To = &t
	}
	var next *store.SyncPoint
	if cur != nil {
		after := cur.History
		q.After, next = &after, cur.Next
	}
	page, err := s.trips.ListTripHistory(ctx, member, q)
	if err != nil {
		return nil, err
	}
	if cur == nil {
		next = page.Snapshot
	}
	items, more, err := pageOfTrips(page.Trips, limit)
	if err != nil {
		return nil, err
	}
	nextCursor, nextSince := "", ""
	if more {
		last := page.Trips[len(items)-1]
		nextCursor = s.sync.Cursor(member, synctoken.Cursor{
			Kind: synctoken.History, Filter: historyFilter(p), Next: next,
			History: store.HistoryPos{StartedAt: last.StartedAt, ID: last.ID},
		})
	} else if !filtered && next != nil {
		nextSince = s.sync.Token(member, *next)
	}
	return api.ListTrips200JSONResponse{Items: items, NextCursor: api.NullIfEmpty(nextCursor), NextSince: api.NullIfEmpty(nextSince)}, nil
}

func (s *Server) listTripChanges(ctx context.Context, member uuid.UUID, since store.SyncPoint, cur *synctoken.Cursor, limit int) (api.ListTripsResponseObject, error) {
	q := store.TripChangesQuery{Since: since, Snapshot: cur == nil, Limit: limit + 1}
	var next *store.SyncPoint
	if cur != nil {
		after := cur.Change
		q.After, next = &after, cur.Next
	}
	page, err := s.trips.ListTripChanges(ctx, member, q)
	if errors.Is(err, store.ErrSyncWindowExpired) {
		return nil, syncWindowExpired()
	}
	if err != nil {
		return nil, err
	}
	if cur == nil {
		next = page.Snapshot
	}
	if next == nil {
		return nil, errors.New("list trip changes: walk without a snapshot")
	}
	items, more, err := pageOfTrips(page.Trips, limit)
	if err != nil {
		return nil, err
	}
	nextCursor, nextSince := "", ""
	if more {
		nextCursor = s.sync.Cursor(member, synctoken.Cursor{Kind: synctoken.Changes, Since: &since, Next: next, Change: page.Trips[len(items)-1].Change})
	} else {
		nextSince = s.sync.Token(member, *next)
	}
	return api.ListTrips200JSONResponse{Items: items, NextCursor: api.NullIfEmpty(nextCursor), NextSince: api.NullIfEmpty(nextSince)}, nil
}

// ListTripDeletions implements GET /v1/trips/deletions.
func (s *Server) ListTripDeletions(ctx context.Context, req api.ListTripDeletionsRequestObject) (api.ListTripDeletionsResponseObject, error) {
	if s.trips == nil {
		return nil, tripsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	p := req.Params
	limit := pageSize(p.Limit)
	since, err := s.sync.ParseToken(member, p.Since)
	if err != nil {
		return nil, syncWindowExpired()
	}
	q := store.TripDeletionsQuery{Since: since, Snapshot: true, Limit: limit + 1}
	var next *store.SyncPoint
	if p.Cursor != nil {
		c, err := s.sync.ParseCursor(member, *p.Cursor)
		if err != nil || c.Kind != synctoken.Deletions {
			return nil, badCursor()
		}
		if !samePoint(since, *c.Since) {
			return nil, badRequest("since must be the walk's original since when sent with a cursor.")
		}
		after := c.Change
		q.Snapshot, q.After, next = false, &after, c.Next
	}
	page, err := s.trips.ListTripDeletions(ctx, member, q)
	if errors.Is(err, store.ErrSyncWindowExpired) {
		return nil, syncWindowExpired()
	}
	if err != nil {
		return nil, err
	}
	if q.Snapshot {
		next = page.Snapshot
	}
	if next == nil {
		return nil, errors.New("list trip deletions: walk without a snapshot")
	}
	rows := page.Deletions
	nextCursor, nextSince := "", ""
	if len(rows) > limit {
		rows = rows[:limit]
		nextCursor = s.sync.Cursor(member, synctoken.Cursor{Kind: synctoken.Deletions, Since: &since, Next: next, Change: rows[limit-1].Change})
	} else {
		nextSince = s.sync.Token(member, *next)
	}
	items := make([]api.TripDeletion, len(rows))
	for i, d := range rows {
		var vehicle *string
		if d.VehicleID != nil {
			v := d.VehicleID.String()
			vehicle = &v
		}
		items[i] = api.TripDeletion{
			Id: d.ID.String(), ClientTripId: d.ClientTripID, VehicleId: api.NullableFrom(vehicle), DeletedAt: instant(d.DeletedAt),
		}
	}
	return api.ListTripDeletions200JSONResponse{Items: items, NextCursor: api.NullIfEmpty(nextCursor), NextSince: api.NullIfEmpty(nextSince)}, nil
}

// ListDrivingDays implements GET /v1/trips/days: calendar days in tz
// (default UTC) from from to to, inclusive, with at least one trip.
func (s *Server) ListDrivingDays(ctx context.Context, req api.ListDrivingDaysRequestObject) (api.ListDrivingDaysResponseObject, error) {
	if s.trips == nil {
		return nil, tripsUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	p := req.Params
	tz := "UTC"
	if p.Tz != nil {
		tz = *p.Tz
	}
	loc, ok := timeZone(tz)
	if !ok {
		return nil, badRequest("tz must be an IANA time zone name, e.g. Asia/Kolkata.")
	}
	from, to := p.From.Time, p.To.Time
	if to.Before(from) {
		return nil, badRequest("to must not be before from.")
	}
	if days := int(to.Sub(from).Hours()/24) + 1; days > maxDrivingDays {
		return nil, badRequest(fmt.Sprintf("The range from from to to may span at most %d days.", maxDrivingDays))
	}
	var resp api.ListDrivingDays200JSONResponse
	resp.Items = makeLike(resp.Items, 0)
	var vehicle *uuid.UUID
	if p.VehicleId != nil {
		id, ok := vehicleID(*p.VehicleId)
		if !ok {
			return resp, nil
		}
		vehicle = &id
	}
	// Day boundaries are computed here, with Go's time zone database
	// (embedded in the binary), and Postgres only counts trips between
	// them: Postgres's own database may lack backward-compatible names
	// such as Asia/Calcutta, which Android reports.
	n := int(to.Sub(from).Hours()/24) + 1
	bounds := make([]time.Time, n+1)
	for i := range bounds {
		bounds[i] = localMidnight(from.AddDate(0, 0, i), loc)
	}
	counts, err := s.trips.DrivingDays(ctx, member, bounds, vehicle)
	if err != nil {
		return nil, err
	}
	for i, c := range counts {
		if c == 0 {
			continue
		}
		d := from.AddDate(0, 0, i)
		resp.Items = append(resp.Items, makeLike(resp.Items, 1)[0])
		item := &resp.Items[len(resp.Items)-1]
		item.Date = openapi_types.Date{Time: time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, time.UTC)}
		item.TripCount = c
	}
	return resp, nil
}

// timeZone loads an IANA zone name, aliases included. Names that aren't
// real zones ("", Local, Factory, posixrules) are rejected.
func timeZone(name string) (*time.Location, bool) {
	switch name {
	case "", "Local", "Factory", "posixrules":
		return nil, false
	}
	loc, err := time.LoadLocation(name)
	return loc, err == nil
}

// localMidnight is the first instant of day's calendar date in loc. Where
// a DST change skips midnight (e.g. America/Santiago), the day starts at
// the first local time that exists.
func localMidnight(day time.Time, loc *time.Location) time.Time {
	t := time.Date(day.Year(), day.Month(), day.Day(), 0, 0, 0, 0, loc)
	if y, m, d := t.Date(); y != day.Year() || m != day.Month() || d != day.Day() {
		// time.Date normalised a skipped midnight into the previous day;
		// step forward to the day itself.
		for t.Day() != day.Day() {
			t = t.Add(15 * time.Minute)
		}
	}
	return t
}
