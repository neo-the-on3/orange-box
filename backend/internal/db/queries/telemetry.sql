-- name: InsertTelemetryBatch :one
-- Returns no row when this clientBatchId was already stored; the caller
-- then answers status=duplicate with GetTelemetryBatchByClientID's counts.
INSERT INTO telemetry_batches (
    id, member_id, client_batch_id, client_trip_id, vehicle_id, object_key,
    accepted_samples, accepted_events, dropped_samples, dropped_events,
    first_sample_t, last_sample_t, body_bytes
) VALUES (
    @id, @member_id, @client_batch_id, @client_trip_id, @vehicle_id, sqlc.narg(object_key),
    @accepted_samples, @accepted_events, @dropped_samples, @dropped_events,
    sqlc.narg(first_sample_t), sqlc.narg(last_sample_t), @body_bytes
)
ON CONFLICT (member_id, client_batch_id) DO NOTHING
RETURNING *;

-- name: GetTelemetryBatchByClientID :one
SELECT * FROM telemetry_batches
WHERE member_id = @member_id AND client_batch_id = @client_batch_id;

-- name: ListTripBatches :many
-- The batches holding a trip's samples, for read-back from object storage.
SELECT * FROM telemetry_batches
WHERE member_id = @member_id AND client_trip_id = @client_trip_id
ORDER BY first_sample_t NULLS LAST, id;

-- name: InsertTripEvents :execrows
-- Stores a batch's events, skipping duplicates of stored events (by
-- clientEventId or by the fallback key) and of each other. events is a JSON
-- array of objects with keys client_event_id, t, type, active, lat, lon, dtc
-- and value; t already truncated to milliseconds. Returns the rows stored.
INSERT INTO trip_events (member_id, client_trip_id, batch_id, client_event_id, t, type, active, lat, lon, dtc, value)
SELECT @member_id, @client_trip_id, @batch_id, e.client_event_id, e.t, e.type, COALESCE(e.active, true),
       e.lat, e.lon, e.dtc, e.value
FROM jsonb_to_recordset(@events::jsonb) AS e(
    client_event_id uuid, t timestamptz, type text, active boolean,
    lat double precision, lon double precision, dtc text, value double precision)
ON CONFLICT DO NOTHING;

-- name: ListTripEvents :many
-- Read-back in (t, id) order. Pass NULL cursor_t for the first page.
SELECT id, client_event_id, t, type, active, lat, lon, dtc, value FROM trip_events
WHERE member_id = @member_id
  AND client_trip_id = @client_trip_id
  AND (sqlc.narg(cursor_t)::timestamptz IS NULL OR (t, id) > (sqlc.narg(cursor_t)::timestamptz, @cursor_id::bigint))
ORDER BY t, id
LIMIT @max_rows;

-- name: DeleteTelemetry :many
-- Deletes telemetry (batch metadata and events) of the given trips and, if
-- vehicle_id is not NULL, every batch uploaded for that vehicle (cascade
-- delete). Returns the object keys to remove from object storage.
WITH batches AS (
    DELETE FROM telemetry_batches tb
    WHERE tb.member_id = @member_id
      AND (tb.client_trip_id = ANY (@client_trip_ids::uuid[]) OR tb.vehicle_id = sqlc.narg(vehicle_id)::uuid)
    RETURNING tb.client_trip_id, tb.object_key
), events AS (
    DELETE FROM trip_events te
    WHERE te.member_id = @member_id
      AND (te.client_trip_id = ANY (@client_trip_ids::uuid[])
           OR te.client_trip_id IN (SELECT b.client_trip_id FROM batches b))
)
SELECT object_key::text AS object_key FROM batches WHERE object_key IS NOT NULL;

-- Telemetry uploads and trip deletes serialise per (member, clientTripId)
-- on a transaction-level advisory lock in namespace 7311 (a hash of the
-- pair; a collision only serialises two unrelated trips). An upload takes
-- it shared, a delete exclusive, so a trip that is created and deleted
-- while an upload runs (the upload saw no trip row to share-lock) can't
-- leave the upload's batch behind its tombstone. Lock order everywhere:
-- vehicle row, then this lock, then the trip row.

-- name: LockClientTripTelemetryShared :exec
SELECT pg_advisory_xact_lock_shared(7311, hashtext((@member_id::uuid)::text || '/' || (@client_trip_id::uuid)::text));

-- name: LockClientTripsTelemetry :exec
-- Exclusive, for deletes; keys in a fixed order.
SELECT pg_advisory_xact_lock(7311, k.key)
FROM (
    SELECT DISTINCT hashtext((@member_id::uuid)::text || '/' || c::text) AS key
    FROM unnest(@client_trip_ids::uuid[]) AS c
    ORDER BY 1
) AS k;

-- name: LockVehicleTripsTelemetry :exec
-- Exclusive, for DELETE /v1/vehicles/{id}?cascade=true: every live trip of
-- the vehicle (taken after the vehicle row lock, before tombstoning).
SELECT pg_advisory_xact_lock(7311, k.key)
FROM (
    SELECT DISTINCT hashtext((@member_id::uuid)::text || '/' || t.client_trip_id::text) AS key
    FROM trips t
    WHERE t.member_id = @member_id AND t.vehicle_id = @vehicle_id::uuid AND t.deleted_at IS NULL
    ORDER BY 1
) AS k;

-- name: ListSampleBatches :many
-- Read-back of GET /v1/trips/{tripId}/samples: the trip's batches with
-- samples later than after_t (all if NULL), in (first sample, id) order,
-- continuing after (after_first, after_id) if after_first is not NULL.
SELECT id, object_key::text AS object_key,
       first_sample_t::timestamptz AS first_sample_t, last_sample_t::timestamptz AS last_sample_t
FROM telemetry_batches
WHERE member_id = @member_id
  AND client_trip_id = @client_trip_id
  AND object_key IS NOT NULL
  AND first_sample_t IS NOT NULL
  AND (sqlc.narg(after_t)::timestamptz IS NULL OR last_sample_t > sqlc.narg(after_t)::timestamptz)
  AND (sqlc.narg(after_first)::timestamptz IS NULL
       OR (first_sample_t, id) > (sqlc.narg(after_first)::timestamptz, @after_id::uuid))
ORDER BY first_sample_t, id
LIMIT @max_rows;
