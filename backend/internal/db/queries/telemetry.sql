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
