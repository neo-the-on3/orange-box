-- name: UpsertTrip :one
-- Upserts a trip snapshot by (member, clientTripId). An existing trip is
-- updated only while it is live, still in_progress and the new snapshot is
-- newer; otherwise no row is returned and the caller reads the trip with
-- GetTripByClientID to tell unchanged from trip_deleted. inserted is true
-- for a new trip.
INSERT INTO trips (
    id, member_id, client_trip_id, vehicle_id, state, started_at, ended_at, snapshot_at,
    duration_s, distance_km, engine_distance_km, max_speed_kmh, avg_speed_kmh, max_rpm,
    harsh_accel_count, harsh_brake_count, harsh_start_count, harsh_stop_count,
    fuel_used_l, fuel_economy_km_per_l, fuel_cut_time_s, idle_time_s, warm_up_time_s,
    accel_time_s, eco_speed_time_s, over_speed_time_s, speed_bands, eco_score, safety_score,
    battery_max_v, battery_avg_v, coolant_max_c, coolant_avg_c, dtc, simulated, app
) VALUES (
    @id, @member_id, @client_trip_id, @vehicle_id::uuid, @state::text, @started_at::timestamptz,
    sqlc.narg(ended_at), @snapshot_at::timestamptz,
    sqlc.narg(duration_s), sqlc.narg(distance_km), sqlc.narg(engine_distance_km),
    sqlc.narg(max_speed_kmh), sqlc.narg(avg_speed_kmh), sqlc.narg(max_rpm),
    sqlc.narg(harsh_accel_count), sqlc.narg(harsh_brake_count), sqlc.narg(harsh_start_count),
    sqlc.narg(harsh_stop_count), sqlc.narg(fuel_used_l), sqlc.narg(fuel_economy_km_per_l),
    sqlc.narg(fuel_cut_time_s), sqlc.narg(idle_time_s), sqlc.narg(warm_up_time_s),
    sqlc.narg(accel_time_s), sqlc.narg(eco_speed_time_s), sqlc.narg(over_speed_time_s),
    sqlc.narg(speed_bands), sqlc.narg(eco_score), sqlc.narg(safety_score),
    sqlc.narg(battery_max_v), sqlc.narg(battery_avg_v), sqlc.narg(coolant_max_c),
    sqlc.narg(coolant_avg_c), sqlc.narg(dtc), @simulated::boolean, sqlc.narg(app)
)
ON CONFLICT (member_id, client_trip_id) DO UPDATE SET
    vehicle_id            = EXCLUDED.vehicle_id,
    state                 = EXCLUDED.state,
    started_at            = EXCLUDED.started_at,
    ended_at              = EXCLUDED.ended_at,
    snapshot_at           = EXCLUDED.snapshot_at,
    duration_s            = EXCLUDED.duration_s,
    distance_km           = EXCLUDED.distance_km,
    engine_distance_km    = EXCLUDED.engine_distance_km,
    max_speed_kmh         = EXCLUDED.max_speed_kmh,
    avg_speed_kmh         = EXCLUDED.avg_speed_kmh,
    max_rpm               = EXCLUDED.max_rpm,
    harsh_accel_count     = EXCLUDED.harsh_accel_count,
    harsh_brake_count     = EXCLUDED.harsh_brake_count,
    harsh_start_count     = EXCLUDED.harsh_start_count,
    harsh_stop_count      = EXCLUDED.harsh_stop_count,
    fuel_used_l           = EXCLUDED.fuel_used_l,
    fuel_economy_km_per_l = EXCLUDED.fuel_economy_km_per_l,
    fuel_cut_time_s       = EXCLUDED.fuel_cut_time_s,
    idle_time_s           = EXCLUDED.idle_time_s,
    warm_up_time_s        = EXCLUDED.warm_up_time_s,
    accel_time_s          = EXCLUDED.accel_time_s,
    eco_speed_time_s      = EXCLUDED.eco_speed_time_s,
    over_speed_time_s     = EXCLUDED.over_speed_time_s,
    speed_bands           = EXCLUDED.speed_bands,
    eco_score             = EXCLUDED.eco_score,
    safety_score          = EXCLUDED.safety_score,
    battery_max_v         = EXCLUDED.battery_max_v,
    battery_avg_v         = EXCLUDED.battery_avg_v,
    coolant_max_c         = EXCLUDED.coolant_max_c,
    coolant_avg_c         = EXCLUDED.coolant_avg_c,
    dtc                   = EXCLUDED.dtc,
    simulated             = EXCLUDED.simulated,
    app                   = EXCLUDED.app,
    updated_at            = now()
WHERE trips.deleted_at IS NULL
  AND trips.state = 'in_progress'
  AND EXCLUDED.snapshot_at > trips.snapshot_at
RETURNING id, (xmax = 0)::boolean AS inserted;

-- name: GetTripByClientID :one
-- Includes tombstones (deleted_at set).
SELECT * FROM trips
WHERE member_id = @member_id AND client_trip_id = @client_trip_id;

-- name: GetTrip :one
SELECT * FROM trips
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL;

-- name: LockTripForTelemetry :one
-- Taken by a telemetry upload before it stores anything: a tombstone means
-- trip_deleted, and the share lock makes a concurrent trip delete wait until
-- the upload commits (so the delete then removes its events too). No row
-- means the summary has not arrived yet, which is normal.
SELECT id, deleted_at FROM trips
WHERE member_id = @member_id AND client_trip_id = @client_trip_id
FOR SHARE;

-- name: ListTrips :many
-- History view (no since): startedAt desc, id desc, optionally bounded by
-- from (inclusive) and to (exclusive). Cursor is the last row's
-- (started_at, id); pass NULLs for the first page.
SELECT * FROM trips
WHERE member_id = @member_id
  AND deleted_at IS NULL
  AND (sqlc.narg(started_from)::timestamptz IS NULL OR started_at >= sqlc.narg(started_from)::timestamptz)
  AND (sqlc.narg(started_before)::timestamptz IS NULL OR started_at < sqlc.narg(started_before)::timestamptz)
  AND (sqlc.narg(cursor_started_at)::timestamptz IS NULL
       OR (started_at, id) < (sqlc.narg(cursor_started_at)::timestamptz, @cursor_id::uuid))
ORDER BY started_at DESC, id DESC
LIMIT @max_rows;

-- name: ListTripsByVehicle :many
-- ListTrips filtered by vehicle.
SELECT * FROM trips
WHERE member_id = @member_id
  AND vehicle_id = @vehicle_id::uuid
  AND deleted_at IS NULL
  AND (sqlc.narg(started_from)::timestamptz IS NULL OR started_at >= sqlc.narg(started_from)::timestamptz)
  AND (sqlc.narg(started_before)::timestamptz IS NULL OR started_at < sqlc.narg(started_before)::timestamptz)
  AND (sqlc.narg(cursor_started_at)::timestamptz IS NULL
       OR (started_at, id) < (sqlc.narg(cursor_started_at)::timestamptz, @cursor_id::uuid))
ORDER BY started_at DESC, id DESC
LIMIT @max_rows;

-- name: ListTripChanges :many
-- Sync walk (GET /v1/trips?since=): live trips written at or after the
-- token's watermark, in change order. The cursor is the last row's
-- (writer_xid, id); pass (0, the nil UUID) for the first page. Read the
-- first page in the transaction that took SyncSnapshot, after it.
SELECT * FROM trips
WHERE member_id = @member_id
  AND deleted_at IS NULL
  AND writer_xid >= @watermark::xid8
  AND (writer_xid, id) > (@cursor_xid::xid8, @cursor_id::uuid)
ORDER BY writer_xid, id
LIMIT @max_rows;

-- name: ListTripDeletions :many
-- GET /v1/trips/deletions: tombstones written at or after the watermark, in
-- change order, with the same cursor as ListTripChanges.
SELECT id, client_trip_id, vehicle_id, deleted_at::timestamptz AS deleted_at, writer_xid
FROM trips
WHERE member_id = @member_id
  AND deleted_at IS NOT NULL
  AND writer_xid >= @watermark::xid8
  AND (writer_xid, id) > (@cursor_xid::xid8, @cursor_id::uuid)
ORDER BY writer_xid, id
LIMIT @max_rows;

-- name: SyncSnapshot :one
-- The watermark a sync token carries (the oldest transaction still running
-- in this snapshot) and its issue time, on the primary.
--
-- Order matters (#176): run it as the FIRST statement of the REPEATABLE
-- READ transaction that reads the walk's first page (db.Trips does). The
-- transaction's one snapshot is then both the token's watermark and what
-- the page sees, so a write that commits after the page was read has a
-- writer_xid at or above the watermark and comes back in the next walk.
-- Taken after the read, or in another transaction, it can sit above a
-- write the page did not see, and that write is lost.
SELECT pg_snapshot_xmin(pg_current_snapshot())::xid8 AS watermark, now()::timestamptz AS issued_at;

-- name: SystemIdentifier :one
-- The cluster identity a sync token carries. Needs execute permission on
-- pg_control_system(); deployments without it use a configured id instead.
SELECT system_identifier::bigint AS system_identifier FROM pg_control_system();

-- name: GetTripPurgeBoundary :one
SELECT max_deleted_at, max_writer_xid FROM trip_purge_boundaries
WHERE member_id = @member_id;

-- name: TombstoneTrip :one
-- DELETE /v1/trips/{id}: erases the trip data and keeps a tombstone. Delete
-- its telemetry in the same transaction with DeleteTelemetry.
UPDATE trips SET
    state = NULL, started_at = NULL, ended_at = NULL, snapshot_at = NULL,
    duration_s = NULL, distance_km = NULL, engine_distance_km = NULL, max_speed_kmh = NULL,
    avg_speed_kmh = NULL, max_rpm = NULL, harsh_accel_count = NULL, harsh_brake_count = NULL,
    harsh_start_count = NULL, harsh_stop_count = NULL, fuel_used_l = NULL,
    fuel_economy_km_per_l = NULL, fuel_cut_time_s = NULL, idle_time_s = NULL,
    warm_up_time_s = NULL, accel_time_s = NULL, eco_speed_time_s = NULL,
    over_speed_time_s = NULL, speed_bands = NULL, eco_score = NULL, safety_score = NULL,
    battery_max_v = NULL, battery_avg_v = NULL, coolant_max_c = NULL, coolant_avg_c = NULL,
    dtc = NULL, simulated = NULL, app = NULL, points_awarded = NULL,
    updated_at = now(),
    deleted_at = now()
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL
RETURNING id, client_trip_id;

-- name: TombstoneVehicleTrips :many
-- DELETE /v1/vehicles/{id}?cascade=true: tombstones every live trip of the
-- vehicle. Delete their telemetry with DeleteTelemetry.
UPDATE trips SET
    state = NULL, started_at = NULL, ended_at = NULL, snapshot_at = NULL,
    duration_s = NULL, distance_km = NULL, engine_distance_km = NULL, max_speed_kmh = NULL,
    avg_speed_kmh = NULL, max_rpm = NULL, harsh_accel_count = NULL, harsh_brake_count = NULL,
    harsh_start_count = NULL, harsh_stop_count = NULL, fuel_used_l = NULL,
    fuel_economy_km_per_l = NULL, fuel_cut_time_s = NULL, idle_time_s = NULL,
    warm_up_time_s = NULL, accel_time_s = NULL, eco_speed_time_s = NULL,
    over_speed_time_s = NULL, speed_bands = NULL, eco_score = NULL, safety_score = NULL,
    battery_max_v = NULL, battery_avg_v = NULL, coolant_max_c = NULL, coolant_avg_c = NULL,
    dtc = NULL, simulated = NULL, app = NULL, points_awarded = NULL,
    updated_at = now(),
    deleted_at = now()
WHERE member_id = @member_id AND vehicle_id = @vehicle_id::uuid AND deleted_at IS NULL
RETURNING id, client_trip_id;

-- name: PurgeTripTombstones :many
-- Purges up to max_rows trip tombstones deleted before deleted_before and,
-- in the same statement, raises each affected member's purge boundary so
-- sync tokens that might still need them expire. Returns the members whose
-- boundary moved.
WITH purged AS (
    DELETE FROM trips
    WHERE id IN (
        SELECT t.id FROM trips t
        WHERE t.deleted_at < @deleted_before::timestamptz
        ORDER BY t.deleted_at
        LIMIT @max_rows
        FOR UPDATE SKIP LOCKED
    )
    RETURNING member_id, deleted_at, writer_xid
), per_member AS (
    SELECT member_id, max(deleted_at) AS max_deleted_at, max(writer_xid) AS max_writer_xid
    FROM purged
    GROUP BY member_id
)
INSERT INTO trip_purge_boundaries AS b (member_id, max_deleted_at, max_writer_xid)
SELECT member_id, max_deleted_at, max_writer_xid FROM per_member
ON CONFLICT (member_id) DO UPDATE SET
    max_deleted_at = GREATEST(b.max_deleted_at, EXCLUDED.max_deleted_at),
    max_writer_xid = GREATEST(b.max_writer_xid, EXCLUDED.max_writer_xid),
    updated_at     = now()
RETURNING member_id;

-- name: ListDrivingDays :many
-- GET /v1/trips/days: trips per local calendar day in time zone tz (an IANA
-- name the handler has validated), for trips starting in
-- [started_from, started_before). vehicle_id NULL means every vehicle.
SELECT (started_at AT TIME ZONE @tz::text)::date AS day, count(*) AS trip_count
FROM trips
WHERE member_id = @member_id
  AND deleted_at IS NULL
  AND started_at >= @started_from::timestamptz
  AND started_at < @started_before::timestamptz
  AND (sqlc.narg(vehicle_id)::uuid IS NULL OR vehicle_id = sqlc.narg(vehicle_id)::uuid)
GROUP BY day
ORDER BY day;
