package httpapi

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/oapi-codegen/nullable"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/objstore"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
	"github.com/neo-the-on3/orange-box/backend/internal/timeutil"
)

// Telemetry operations (#111): POST /v1/telemetry/batches and
// GET /v1/trips/{tripId}/samples and /events. They need Postgres and an
// object store; without either they answer 501.
//
// A batch's samples are de-duplicated within the batch by t (truncated to
// milliseconds), sorted, gzipped and written to object storage as one
// object under members/<member>/trips/<clientTripId>/<batch id>.json.gz;
// Postgres keeps the batch's metadata (telemetry_batches) and its events
// (trip_events, de-duplicated on insert). The object is written before
// the metadata row commits, so a 202 means both are durable. Samples
// repeated across batches are de-duplicated on read-back: for each t the
// sample of the earliest stored batch wins.

const (
	// defaultSampleLimit and maxSampleLimit bound GET /v1/trips/{tripId}/samples.
	defaultSampleLimit = 1000
	maxSampleLimit     = 5000
	// sampleBatchChunk is how many batch rows read-back fetches at a time.
	sampleBatchChunk = 32
	// objectCleanupTimeout bounds deleting objects after a request is done.
	objectCleanupTimeout = 10 * time.Second
	// maxObjectBytes bounds an inflated sample object on read-back (a full
	// batch is about 1.5 MB of JSON).
	maxObjectBytes = 16 << 20
)

// sampleObjectVersion is the format version of a stored sample object.
const sampleObjectVersion = 1

// sampleObject is the JSON (gzipped) stored per batch.
type sampleObject struct {
	V             int          `json:"v"`
	ClientBatchID uuid.UUID    `json:"clientBatchId"`
	ClientTripID  uuid.UUID    `json:"clientTripId"`
	Samples       []api.Sample `json:"samples"`
}

func telemetryUnavailable() *problem.Problem {
	return problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
		"Telemetry needs a database and object storage; this server runs without them.")
}

// sampleObjectKey names a batch's object. The per-member prefix lets
// account erasure delete a member's objects by prefix (#179).
func sampleObjectKey(member, clientTrip, batch uuid.UUID) string {
	return "members/" + member.String() + "/trips/" + clientTrip.String() + "/" + batch.String() + ".json.gz"
}

// normaliseSamples truncates t to milliseconds, drops in-batch duplicates
// by t (the first one sent wins) and sorts by t.
func normaliseSamples(in []api.Sample) (out []api.Sample, dropped int) {
	out = make([]api.Sample, len(in))
	for i, s := range in {
		s.T = timeutil.NewUtcInstant(s.T.Time)
		out[i] = s
	}
	slices.SortStableFunc(out, func(a, b api.Sample) int { return a.T.Compare(b.T.Time) })
	out = slices.CompactFunc(out, func(a, b api.Sample) bool { return a.T.Equal(b.T.Time) })
	return out, len(in) - len(out)
}

// eventKey is the fallback de-duplication key (clientTripId is implied).
type eventKey struct {
	t      int64
	typ    string
	active bool
	dtc    string
	hasDtc bool
}

// normaliseEvents converts the batch's events, dropping in-batch
// duplicates: an event matching an earlier one on clientEventId or on the
// fallback key (t, type, active, dtc) is dropped, as Postgres would.
func normaliseEvents(in []api.Event) (out []store.TelemetryEvent, dropped int) {
	ids := map[uuid.UUID]bool{}
	keys := map[eventKey]bool{}
	for _, e := range in {
		se := store.TelemetryEvent{
			ClientEventID: e.ClientEventId, T: timeutil.NewUtcInstant(e.T.Time).Time, Type: string(e.Type),
			Active: e.Active == nil || *e.Active,
			Lat:    fromNullable(e.Lat), Lon: fromNullable(e.Lon), Dtc: fromNullable(e.Dtc), Value: fromNullable(e.Value),
		}
		k := eventKey{t: se.T.UnixMilli(), typ: se.Type, active: se.Active}
		if se.Dtc != nil {
			k.dtc, k.hasDtc = *se.Dtc, true
		}
		dup := keys[k]
		if se.ClientEventID != nil && ids[*se.ClientEventID] {
			dup = true
		}
		if dup {
			dropped++
			continue
		}
		keys[k] = true
		if se.ClientEventID != nil {
			ids[*se.ClientEventID] = true
		}
		out = append(out, se)
	}
	return out, dropped
}

func encodeSamples(o sampleObject) ([]byte, error) {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if err := json.NewEncoder(zw).Encode(o); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func decodeSamples(b []byte) (sampleObject, error) {
	var o sampleObject
	zr, err := gzip.NewReader(bytes.NewReader(b))
	if err != nil {
		return o, err
	}
	raw, err := io.ReadAll(io.LimitReader(zr, maxObjectBytes+1))
	if err != nil {
		return o, err
	}
	if len(raw) > maxObjectBytes {
		return o, errors.New("sample object too large")
	}
	if err := json.Unmarshal(raw, &o); err != nil {
		return o, err
	}
	if o.V != sampleObjectVersion {
		return o, fmt.Errorf("sample object version %d", o.V)
	}
	return o, nil
}

func telemetryAck(clientBatch uuid.UUID, status api.TelemetryBatchAckStatus, c store.BatchCounts) api.UploadTelemetryBatch202JSONResponse {
	return api.UploadTelemetryBatch202JSONResponse{Body: api.TelemetryBatchAck{
		ClientBatchId: clientBatch, Status: status,
		AcceptedSamples: c.AcceptedSamples, AcceptedEvents: c.AcceptedEvents,
		DroppedSamples: &c.DroppedSamples, DroppedEvents: &c.DroppedEvents,
	}}
}

func telemetryVehicleNotFound() *problem.Problem {
	return problem.New(http.StatusNotFound, problem.CodeVehicleNotFound, "vehicleId is not one of your vehicles.")
}

// UploadTelemetryBatch implements POST /v1/telemetry/batches.
// Idempotency-Key handling happens in the middleware before this runs.
func (s *Server) UploadTelemetryBatch(ctx context.Context, req api.UploadTelemetryBatchRequestObject) (api.UploadTelemetryBatchResponseObject, error) {
	if s.telemetry == nil || s.objects == nil {
		return nil, telemetryUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	in := req.Body
	// A vehicleId that isn't a server id is no vehicle of the member's;
	// uuid.Nil matches none (a tombstone still wins).
	vehicle, _ := vehicleID(in.VehicleId)
	var samples []api.Sample
	var events []store.TelemetryEvent
	var counts store.BatchCounts
	if in.Samples != nil {
		samples, counts.DroppedSamples = normaliseSamples(*in.Samples)
	}
	if in.Events != nil {
		events, counts.DroppedEvents = normaliseEvents(*in.Events)
	}
	counts.AcceptedSamples, counts.AcceptedEvents = len(samples), len(events)

	// Cheap pre-check, so retries and late batches don't write objects.
	outcome, stored, err := s.telemetry.CheckBatch(ctx, member, in.ClientBatchId, in.ClientTripId, vehicle)
	if err != nil {
		return nil, err
	}
	if outcome == store.BatchAccepted {
		outcome, stored, err = s.storeBatch(ctx, member, in, vehicle, samples, events, counts)
		if err != nil {
			return nil, err
		}
	}
	switch outcome {
	case store.BatchAccepted:
		return telemetryAck(in.ClientBatchId, api.TelemetryBatchAckStatusAccepted, stored), nil
	case store.BatchDuplicate:
		return telemetryAck(in.ClientBatchId, api.TelemetryBatchAckStatusDuplicate, stored), nil
	case store.BatchTripDeleted:
		return telemetryAck(in.ClientBatchId, api.TelemetryBatchAckStatusTripDeleted, store.BatchCounts{}), nil
	case store.BatchVehicleNotFound:
		return nil, telemetryVehicleNotFound()
	default:
		return nil, fmt.Errorf("upload telemetry: unexpected outcome %d", outcome)
	}
}

// storeBatch writes the samples object, then the batch row and events.
// The object is removed again unless the batch was stored.
func (s *Server) storeBatch(ctx context.Context, member uuid.UUID, in *api.TelemetryBatch, vehicle uuid.UUID,
	samples []api.Sample, events []store.TelemetryEvent, counts store.BatchCounts) (store.BatchOutcome, store.BatchCounts, error) {
	id, err := uuid.NewV7()
	if err != nil {
		return 0, store.BatchCounts{}, err
	}
	b := store.TelemetryBatch{
		ID: id, ClientBatchID: in.ClientBatchId, ClientTripID: in.ClientTripId, VehicleID: vehicle,
		Counts: counts, Events: events,
	}
	if info := reqctx.InfoFrom(ctx); info != nil {
		b.BodyBytes = info.BodyBytes
	}
	if len(samples) > 0 {
		obj, err := encodeSamples(sampleObject{V: sampleObjectVersion, ClientBatchID: in.ClientBatchId, ClientTripID: in.ClientTripId, Samples: samples})
		if err != nil {
			return 0, store.BatchCounts{}, err
		}
		key := sampleObjectKey(member, in.ClientTripId, id)
		if err := s.objects.Put(ctx, key, obj); err != nil {
			return 0, store.BatchCounts{}, objectStoreFailed(err)
		}
		first, last := samples[0].T.Time, samples[len(samples)-1].T.Time
		b.ObjectKey, b.FirstSampleT, b.LastSampleT = &key, &first, &last
	}
	outcome, stored, err := s.telemetry.StoreBatch(ctx, member, b)
	// Nothing was stored: remove the object again. After an error the
	// transaction may still have committed (a connection lost mid-commit),
	// so the object stays; if the row didn't commit it is an orphan for
	// reconciliation (#179), never a row without its samples.
	if err == nil && outcome != store.BatchAccepted && b.ObjectKey != nil {
		s.removeObjects(ctx, []string{*b.ObjectKey})
	}
	return outcome, stored, err
}

// objectStoreFailed maps an object storage error to a retryable 503: the
// app keeps the batch queued and retries.
func objectStoreFailed(err error) error {
	if errors.Is(err, context.Canceled) {
		return err
	}
	return &objectStoreError{err: err}
}

type objectStoreError struct{ err error }

func (e *objectStoreError) Error() string { return "object storage: " + e.err.Error() }
func (e *objectStoreError) Unwrap() error { return e.err }

// removeObjects deletes telemetry objects whose rows are gone (or were
// never stored), best effort: a failure is logged, and the object stays
// until reconciliation removes it (#179).
func (s *Server) removeObjects(ctx context.Context, keys []string) {
	if s.objects == nil || len(keys) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), objectCleanupTimeout)
	defer cancel()
	failed := 0
	var last error
	for _, k := range keys {
		if err := s.objects.Delete(ctx, k); err != nil {
			failed++
			last = err
		}
	}
	if failed > 0 {
		s.logger.ErrorContext(ctx, "telemetry objects left in object storage",
			slog.String("request_id", reqctx.RequestID(ctx)),
			slog.Int("objects", failed), slog.Any("err", last))
	}
}

// uploadTelemetryReplay rewrites a stored POST /v1/telemetry/batches
// response for replay: a deletion tombstone beats a stored replay, so a
// batch whose trip has since been deleted is replayed as trip_deleted,
// as a new upload of it would be.
func (s *Server) uploadTelemetryReplay(ctx context.Context, member uuid.UUID, body []byte, status int, stored []byte) ([]byte, error) {
	if s.trips == nil || status != http.StatusAccepted {
		return stored, nil
	}
	var req struct {
		ClientTripID uuid.UUID `json:"clientTripId"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		return nil, fmt.Errorf("telemetry replay: request body: %w", err)
	}
	var ack api.TelemetryBatchAck
	if err := json.Unmarshal(stored, &ack); err != nil {
		return nil, fmt.Errorf("stored telemetry response: %w", err)
	}
	if ack.Status == api.TelemetryBatchAckStatusTripDeleted {
		return stored, nil
	}
	deleted, err := s.trips.DeletedClientTrips(ctx, member, []uuid.UUID{req.ClientTripID})
	if err != nil || !deleted[req.ClientTripID] {
		return stored, err
	}
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(telemetryAck(ack.ClientBatchId, api.TelemetryBatchAckStatusTripDeleted, store.BatchCounts{}).Body); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// telemetryTrip resolves the tripId of a read-back to the live trip's
// clientTripId.
func (s *Server) telemetryTrip(ctx context.Context, member uuid.UUID, tripID string) (uuid.UUID, error) {
	ref, ok := tripRef(tripID)
	if !ok {
		return uuid.UUID{}, tripNotFound()
	}
	trip, err := s.trips.GetTrip(ctx, member, ref)
	if errors.Is(err, store.ErrNotFound) {
		return uuid.UUID{}, tripNotFound()
	}
	if err != nil {
		return uuid.UUID{}, err
	}
	return trip.ClientTripID, nil
}

// Cursors of the read-backs: versioned, opaque base64url of the last
// item's position. A cursor only names a position in a trip's telemetry,
// so it reveals nothing and needs no signature.
const (
	sampleCursorPrefix = "s1:"
	eventCursorPrefix  = "e1:"
)

func encodeSampleCursor(t time.Time) string {
	return base64.RawURLEncoding.EncodeToString([]byte(sampleCursorPrefix + strconv.FormatInt(t.UnixMilli(), 10)))
}

func decodeSampleCursor(c string) (time.Time, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return time.Time{}, false
	}
	s, ok := strings.CutPrefix(string(raw), sampleCursorPrefix)
	if !ok {
		return time.Time{}, false
	}
	ms, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return time.Time{}, false
	}
	return time.UnixMilli(ms).UTC(), true
}

func encodeEventCursor(p store.EventPos) string {
	return base64.RawURLEncoding.EncodeToString([]byte(eventCursorPrefix + strconv.FormatInt(p.T.UnixMilli(), 10) + ":" + strconv.FormatInt(p.ID, 10)))
}

func decodeEventCursor(c string) (store.EventPos, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return store.EventPos{}, false
	}
	s, ok := strings.CutPrefix(string(raw), eventCursorPrefix)
	if !ok {
		return store.EventPos{}, false
	}
	ts, ids, ok := strings.Cut(s, ":")
	if !ok {
		return store.EventPos{}, false
	}
	ms, err1 := strconv.ParseInt(ts, 10, 64)
	id, err2 := strconv.ParseInt(ids, 10, 64)
	if err1 != nil || err2 != nil {
		return store.EventPos{}, false
	}
	return store.EventPos{T: time.UnixMilli(ms).UTC(), ID: id}, true
}

func invalidCursor() *problem.Problem {
	return problem.New(http.StatusBadRequest, problem.CodeBadRequest, "The cursor is invalid or has expired.")
}

// candidate is a sample read back, with the batch it came from.
type candidate struct {
	sample api.Sample
	batch  uuid.UUID
}

// ListTripSamples implements GET /v1/trips/{tripId}/samples: the trip's
// samples in t order, one per t (repeats across batches are dropped).
//
// Batches are read in (first sample, id) order. Once limit+1 samples are
// known and the next batch starts after the (limit+1)-th of them, no later
// batch can change the page, so reading stops.
func (s *Server) ListTripSamples(ctx context.Context, req api.ListTripSamplesRequestObject) (api.ListTripSamplesResponseObject, error) {
	if s.telemetry == nil || s.objects == nil || s.trips == nil {
		return nil, telemetryUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	limit := defaultSampleLimit
	if req.Params.Limit != nil {
		limit = min(max(*req.Params.Limit, 1), maxSampleLimit)
	}
	var after *time.Time
	if req.Params.Cursor != nil {
		t, ok := decodeSampleCursor(*req.Params.Cursor)
		if !ok {
			return nil, invalidCursor()
		}
		after = &t
	}
	clientTrip, err := s.telemetryTrip(ctx, member, req.TripId)
	if err != nil {
		return nil, err
	}

	cands := map[int64]candidate{}
	// threshold is the (limit+1)-th smallest t known, once there are that many.
	threshold := func() (time.Time, bool) {
		if len(cands) <= limit {
			return time.Time{}, false
		}
		ts := slices.Sorted(func(yield func(int64) bool) {
			for t := range cands {
				if !yield(t) {
					return
				}
			}
		})
		// Keep only the limit+1 smallest: larger ones can't be on this page.
		for _, t := range ts[limit+1:] {
			delete(cands, t)
		}
		return time.UnixMilli(ts[limit]).UTC(), true
	}
	var afterBatch *store.SampleBatch
read:
	for {
		batches, err := s.telemetry.ListSampleBatches(ctx, member, clientTrip, after, afterBatch, sampleBatchChunk)
		if err != nil {
			return nil, err
		}
		for i := range batches {
			b := batches[i]
			if th, ok := threshold(); ok && b.FirstSampleT.After(th) {
				break read
			}
			obj, err := s.objects.Get(ctx, b.ObjectKey)
			if errors.Is(err, objstore.ErrNotFound) {
				s.logger.ErrorContext(ctx, "telemetry object missing; its samples are skipped",
					slog.String("request_id", reqctx.RequestID(ctx)), slog.String("batch", b.ID.String()))
				continue
			}
			if err != nil {
				return nil, objectStoreFailed(err)
			}
			o, err := decodeSamples(obj)
			if err != nil {
				return nil, fmt.Errorf("telemetry object of batch %s: %w", b.ID, err)
			}
			for _, smp := range o.Samples {
				t := smp.T.UnixMilli()
				if after != nil && t <= after.UnixMilli() {
					continue
				}
				if c, ok := cands[t]; ok && bytes.Compare(c.batch[:], b.ID[:]) <= 0 {
					continue // the earliest stored batch wins
				}
				cands[t] = candidate{sample: smp, batch: b.ID}
			}
		}
		if len(batches) < sampleBatchChunk {
			break
		}
		afterBatch = &batches[len(batches)-1]
	}
	items := make([]api.Sample, 0, min(len(cands), limit))
	for _, c := range cands {
		items = append(items, c.sample)
	}
	slices.SortFunc(items, func(a, b api.Sample) int { return a.T.Compare(b.T.Time) })
	next := ""
	if len(items) > limit {
		items = items[:limit]
		next = encodeSampleCursor(items[limit-1].T.Time)
	}
	return api.ListTripSamples200JSONResponse{Items: items, NextCursor: api.NullIfEmpty(next)}, nil
}

// optional is a nullable response field that is omitted when p is nil.
func optional[T any](p *T) nullable.Nullable[T] {
	if p == nil {
		return nil
	}
	return nullable.NewNullableWithValue(*p)
}

// ListTripEvents implements GET /v1/trips/{tripId}/events in (t, id) order.
func (s *Server) ListTripEvents(ctx context.Context, req api.ListTripEventsRequestObject) (api.ListTripEventsResponseObject, error) {
	if s.telemetry == nil || s.trips == nil {
		return nil, telemetryUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	limit := pageSize(req.Params.Limit)
	var after *store.EventPos
	if req.Params.Cursor != nil {
		p, ok := decodeEventCursor(*req.Params.Cursor)
		if !ok {
			return nil, invalidCursor()
		}
		after = &p
	}
	clientTrip, err := s.telemetryTrip(ctx, member, req.TripId)
	if err != nil {
		return nil, err
	}
	rows, err := s.telemetry.ListEvents(ctx, member, clientTrip, after, limit+1)
	if err != nil {
		return nil, err
	}
	next := ""
	if len(rows) > limit {
		rows = rows[:limit]
		last := rows[limit-1]
		next = encodeEventCursor(store.EventPos{T: last.T, ID: last.ID})
	}
	items := make([]api.Event, len(rows))
	for i, e := range rows {
		active := e.Active
		items[i] = api.Event{
			ClientEventId: e.ClientEventID, T: timeutil.NewUtcInstant(e.T), Type: api.EventType(e.Type), Active: &active,
			Lat: optional(e.Lat), Lon: optional(e.Lon), Dtc: optional(e.Dtc), Value: optional(e.Value),
		}
	}
	return api.ListTripEvents200JSONResponse{Items: items, NextCursor: api.NullIfEmpty(next)}, nil
}
