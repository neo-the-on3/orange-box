package httpapi

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/oapi-codegen/nullable"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Vehicle operations (#108): GET/POST /v1/vehicles and GET/PATCH/DELETE
// /v1/vehicles/{vehicleId}. They need Postgres; without DATABASE_URL (only
// allowed in local, ci and sandbox) they answer 501.

const (
	defaultPageLimit = 50
	maxPageLimit     = 100
)

func vehiclesUnavailable() *problem.Problem {
	return problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
		"Vehicles need a database; this server runs without DATABASE_URL.")
}

func vehicleNotFound() *problem.Problem {
	return problem.New(http.StatusNotFound, problem.CodeNotFound, "No such vehicle.")
}

func vehicleDeleted() *problem.Problem {
	return problem.New(http.StatusGone, problem.CodeVehicleDeleted,
		"A vehicle with this clientVehicleId was deleted. Do not retry.").WithTitle("Vehicle deleted")
}

func preconditionRequired() *problem.Problem {
	return problem.New(http.StatusPreconditionRequired, problem.CodePreconditionReq,
		"If-Match with the vehicle's ETag is required.")
}

func etagMismatch() *problem.Problem {
	return problem.New(http.StatusPreconditionFailed, problem.CodeEtagMismatch,
		"The vehicle has changed since If-Match's ETag; fetch it again.")
}

// callerID returns the authenticated member as a UUID. The token's subject
// is always one (the server issues it), so anything else is a server bug.
func callerID(ctx context.Context) (uuid.UUID, error) {
	id, ok := reqctx.MemberID(ctx)
	if !ok {
		return uuid.UUID{}, errors.New("no authenticated member")
	}
	u, err := uuid.Parse(id)
	if err != nil {
		return uuid.UUID{}, fmt.Errorf("member id is not a UUID: %w", err)
	}
	return u, nil
}

// vehicleID parses the vehicleId path parameter. Server ids are UUIDs, so
// anything else is a vehicle that doesn't exist (404), never a 500 (#180).
func vehicleID(s string) (uuid.UUID, bool) {
	id, err := uuid.Parse(s)
	return id, err == nil && len(s) == 36
}

func vehicleLocation(id uuid.UUID) string { return "/v1/vehicles/" + id.String() }

// requireIfMatch returns the store check for a mutating request: 428 when
// If-Match is missing (checked before anything else), 412 when it does not
// match the version found.
func requireIfMatch(header *string) (func(store.Vehicle) error, *problem.Problem) {
	if header == nil || strings.TrimSpace(*header) == "" {
		return nil, preconditionRequired()
	}
	h := *header
	return func(v store.Vehicle) error {
		if !ifMatch(h, vehicleETag(v.Version)) {
			return etagMismatch()
		}
		return nil
	}, nil
}

// CreateVehicle implements POST /v1/vehicles. Idempotency-Key handling
// (replay, 409, 422) happens in the middleware before this runs.
func (s *Server) CreateVehicle(ctx context.Context, req api.CreateVehicleRequestObject) (api.CreateVehicleResponseObject, error) {
	if s.vehicles == nil {
		return nil, vehiclesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	b := req.Body
	f := store.VehicleFields{
		Nickname:             b.Nickname,
		VariantID:            fromNullable(b.VariantId),
		Make:                 fromNullable(b.Make),
		Model:                fromNullable(b.Model),
		FuelType:             string(b.FuelType),
		EngineDisplacementCc: fromNullable(b.EngineDisplacementCc),
		ModelYear:            fromNullable(b.ModelYear),
		PlateNumber:          fromNullable(b.PlateNumber),
		Vin:                  fromNullable(b.Vin),
		OdometerKm:           widenInt(fromNullable(b.OdometerKm)),
		IdleStopGo:           b.IdleStopGo,
		Archived:             b.Archived != nil && *b.Archived,
	}
	if b.ObdAdapter != nil {
		f.ObdElmVersion, f.ObdProtocol = b.ObdAdapter.ElmVersion, b.ObdAdapter.ObdProtocol
	}
	v, outcome, err := s.vehicles.CreateVehicle(ctx, member, b.ClientVehicleId, f)
	if err != nil {
		return nil, err
	}
	etag, loc := vehicleETag(v.Version), vehicleLocation(v.ID)
	switch outcome {
	case store.Created:
		return api.CreateVehicle201JSONResponse{Body: toAPIVehicle(v), Headers: api.CreateVehicle201ResponseHeaders{ETag: &etag, Location: &loc}}, nil
	case store.Existing:
		return api.CreateVehicle200JSONResponse{Body: toAPIVehicle(v), Headers: api.CreateVehicle200ResponseHeaders{ETag: &etag, Location: &loc}}, nil
	case store.Deleted:
		return nil, vehicleDeleted()
	default:
		return nil, fmt.Errorf("create vehicle: unexpected outcome %d", outcome)
	}
}

// createVehicleReplayGuard makes a deletion tombstone beat a stored
// Idempotency-Key replay of POST /v1/vehicles: a retry of the original
// create after the vehicle was deleted gets 410, not the stored 201.
func (s *Server) createVehicleReplayGuard(ctx context.Context, member uuid.UUID, body []byte) (*problem.Problem, error) {
	if s.vehicles == nil {
		return nil, nil
	}
	var b struct {
		ClientVehicleID *uuid.UUID `json:"clientVehicleId"`
	}
	if err := unmarshalExact(body, &b); err != nil || b.ClientVehicleID == nil {
		return nil, nil //nolint:nilerr // the body was validated when first sent; no client id means no tombstone
	}
	deleted, err := s.vehicles.ClientVehicleDeleted(ctx, member, *b.ClientVehicleID)
	if err != nil || !deleted {
		return nil, err
	}
	return vehicleDeleted(), nil
}

// ListVehicles implements GET /v1/vehicles.
func (s *Server) ListVehicles(ctx context.Context, req api.ListVehiclesRequestObject) (api.ListVehiclesResponseObject, error) {
	if s.vehicles == nil {
		return nil, vehiclesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	limit := defaultPageLimit
	if req.Params.Limit != nil {
		limit = min(max(*req.Params.Limit, 1), maxPageLimit)
	}
	var after *uuid.UUID
	if req.Params.Cursor != nil {
		id, ok := decodeVehicleCursor(*req.Params.Cursor)
		if !ok {
			return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The cursor is invalid or has expired.")
		}
		after = &id
	}
	includeArchived := req.Params.IncludeArchived != nil && *req.Params.IncludeArchived
	rows, err := s.vehicles.ListVehicles(ctx, member, includeArchived, after, limit+1)
	if err != nil {
		return nil, err
	}
	next := ""
	if len(rows) > limit {
		rows = rows[:limit]
		next = encodeVehicleCursor(rows[limit-1].ID)
	}
	items := make([]api.Vehicle, len(rows))
	for i, v := range rows {
		items[i] = toAPIVehicle(v)
	}
	return api.ListVehicles200JSONResponse{Body: api.VehiclePage{Items: items, NextCursor: api.NullIfEmpty(next)}}, nil
}

// vehicleCursorPrefix versions the cursor format.
const vehicleCursorPrefix = "v1:"

func encodeVehicleCursor(after uuid.UUID) string {
	return base64.RawURLEncoding.EncodeToString([]byte(vehicleCursorPrefix + after.String()))
}

func decodeVehicleCursor(c string) (uuid.UUID, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return uuid.UUID{}, false
	}
	s, ok := strings.CutPrefix(string(raw), vehicleCursorPrefix)
	if !ok {
		return uuid.UUID{}, false
	}
	return vehicleID(s)
}

// GetVehicle implements GET /v1/vehicles/{vehicleId}.
func (s *Server) GetVehicle(ctx context.Context, req api.GetVehicleRequestObject) (api.GetVehicleResponseObject, error) {
	if s.vehicles == nil {
		return nil, vehiclesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	id, ok := vehicleID(req.VehicleId)
	if !ok {
		return nil, vehicleNotFound()
	}
	v, err := s.vehicles.GetVehicle(ctx, member, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, vehicleNotFound()
	}
	if err != nil {
		return nil, err
	}
	etag := vehicleETag(v.Version)
	if req.Params.IfNoneMatch != nil && ifNoneMatch(*req.Params.IfNoneMatch, etag) {
		return api.GetVehicle304Response{Headers: api.GetVehicle304ResponseHeaders{ETag: &etag}}, nil
	}
	return api.GetVehicle200JSONResponse{Body: toAPIVehicle(v), Headers: api.GetVehicle200ResponseHeaders{ETag: &etag}}, nil
}

// UpdateVehicle implements PATCH /v1/vehicles/{vehicleId} (JSON merge
// patch): an absent field is kept, null clears a nullable field, and
// obdAdapter is merged member by member.
func (s *Server) UpdateVehicle(ctx context.Context, req api.UpdateVehicleRequestObject) (api.UpdateVehicleResponseObject, error) {
	if s.vehicles == nil {
		return nil, vehiclesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	check, p := requireIfMatch(req.Params.IfMatch)
	if p != nil {
		return nil, p
	}
	id, ok := vehicleID(req.VehicleId)
	if !ok {
		return nil, vehicleNotFound()
	}
	patch := *req.Body
	v, err := s.vehicles.UpdateVehicle(ctx, member, id, check, func(f store.VehicleFields) store.VehicleFields {
		return applyVehiclePatch(f, patch)
	})
	if errors.Is(err, store.ErrNotFound) {
		return nil, vehicleNotFound()
	}
	if err != nil {
		return nil, err
	}
	etag := vehicleETag(v.Version)
	return api.UpdateVehicle200JSONResponse{Body: toAPIVehicle(v), Headers: api.UpdateVehicle200ResponseHeaders{ETag: &etag}}, nil
}

func applyVehiclePatch(f store.VehicleFields, p api.VehiclePatch) store.VehicleFields {
	setIf(&f.Nickname, p.Nickname)
	mergeNullable(&f.VariantID, p.VariantId)
	mergeNullable(&f.Make, p.Make)
	mergeNullable(&f.Model, p.Model)
	if p.FuelType != nil {
		f.FuelType = string(*p.FuelType)
	}
	mergeNullable(&f.EngineDisplacementCc, p.EngineDisplacementCc)
	mergeNullable(&f.ModelYear, p.ModelYear)
	mergeNullable(&f.PlateNumber, p.PlateNumber)
	mergeNullable(&f.Vin, p.Vin)
	if p.OdometerKm.IsSpecified() {
		f.OdometerKm = widenInt(fromNullable(p.OdometerKm))
	}
	setIf(&f.IdleStopGo, p.IdleStopGo)
	if p.ObdAdapter != nil {
		setIf(&f.ObdElmVersion, p.ObdAdapter.ElmVersion)
		setIf(&f.ObdProtocol, p.ObdAdapter.ObdProtocol)
	}
	if p.Archived != nil {
		f.Archived = *p.Archived
	}
	return f
}

// DeleteVehicle implements DELETE /v1/vehicles/{vehicleId}.
func (s *Server) DeleteVehicle(ctx context.Context, req api.DeleteVehicleRequestObject) (api.DeleteVehicleResponseObject, error) {
	if s.vehicles == nil {
		return nil, vehiclesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	check, p := requireIfMatch(req.Params.IfMatch)
	if p != nil {
		return nil, p
	}
	id, ok := vehicleID(req.VehicleId)
	if !ok {
		return nil, vehicleNotFound()
	}
	cascade := req.Params.Cascade != nil && *req.Params.Cascade
	res, err := s.vehicles.DeleteVehicle(ctx, member, id, check, cascade)
	if errors.Is(err, store.ErrNotFound) {
		return nil, vehicleNotFound()
	}
	if err != nil {
		return nil, err
	}
	if len(res.ObjectKeys) > 0 {
		// Telemetry uploads (#111) will write to object storage; until that
		// lands there is nothing to remove, so keys here mean a gap.
		s.logger.WarnContext(ctx, "cascade delete left telemetry objects in object storage",
			slog.Int("objects", len(res.ObjectKeys)))
	}
	return api.DeleteVehicle204Response{}, nil
}

func toAPIVehicle(v store.Vehicle) api.Vehicle {
	id := v.ID.String()
	created, updated := v.CreatedAt.UTC(), v.UpdatedAt.UTC()
	out := api.Vehicle{
		Id:                   &id,
		ClientVehicleId:      api.NullableFrom(v.ClientVehicleID),
		Nickname:             v.Nickname,
		VariantId:            api.NullableFrom(v.VariantID),
		Make:                 api.NullableFrom(v.Make),
		Model:                api.NullableFrom(v.Model),
		FuelType:             api.FuelType(v.FuelType),
		EngineDisplacementCc: api.NullableFrom(v.EngineDisplacementCc),
		ModelYear:            api.NullableFrom(v.ModelYear),
		PlateNumber:          api.NullableFrom(v.PlateNumber),
		Vin:                  api.NullableFrom(v.Vin),
		OdometerKm:           api.NullableFrom(narrowInt64(v.OdometerKm)),
		IdleStopGo:           v.IdleStopGo,
		Archived:             v.Archived,
		CreatedAt:            &created,
		UpdatedAt:            &updated,
	}
	if v.ObdElmVersion != nil || v.ObdProtocol != nil {
		out.ObdAdapter = &api.ObdAdapterInfo{ElmVersion: v.ObdElmVersion, ObdProtocol: v.ObdProtocol}
	}
	return out
}

// fromNullable maps a request's nullable field to a column value: null and
// absent are both NULL (use IsSpecified to tell them apart first).
func fromNullable[T any](n nullable.Nullable[T]) *T {
	if !n.IsSpecified() || n.IsNull() {
		return nil
	}
	v := n.MustGet()
	return &v
}

// mergeNullable applies a merge-patch member: absent keeps, null clears.
func mergeNullable[T any](dst **T, n nullable.Nullable[T]) {
	if n.IsSpecified() {
		*dst = fromNullable(n)
	}
}

func setIf[T any](dst **T, v *T) {
	if v != nil {
		*dst = v
	}
}

func widenInt(p *int) *int64 {
	if p == nil {
		return nil
	}
	v := int64(*p)
	return &v
}

func narrowInt64(p *int64) *int {
	if p == nil {
		return nil
	}
	v := int(*p)
	return &v
}
