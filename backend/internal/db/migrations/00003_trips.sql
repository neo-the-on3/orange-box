-- Trip summaries (TripUpload/Trip in openapi-v2.yaml) and their deletion
-- tombstones, plus what incremental sync needs.
--
-- Tombstones live in the same table: deleting a trip clears every data
-- column and sets deleted_at, keeping only id, client_trip_id, vehicle_id
-- and deleted_at. The unique key on (member_id, client_trip_id) then makes a
-- late snapshot racing a delete safe without extra locking: the upsert's
-- ON CONFLICT ... WHERE deleted_at IS NULL re-checks the latest row version.
--
-- Sync tokens: every write stamps writer_xid with pg_current_xact_id() (a
-- trigger, so no query can forget). A walk with a token returns rows whose
-- writer_xid is at or above the token's watermark,
-- pg_snapshot_xmin(pg_current_snapshot()) taken when the walk started. Live
-- rows feed GET /v1/trips?since=, tombstones feed GET /v1/trips/deletions.
-- Purging tombstones records a per-member boundary that expires older tokens.

-- +goose Up
-- +goose StatementBegin
CREATE FUNCTION set_writer_xid() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.writer_xid := pg_current_xact_id();
    RETURN NEW;
END
$$;
-- +goose StatementEnd

CREATE TABLE trips (
    id                    uuid             PRIMARY KEY,
    member_id             uuid             NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    client_trip_id        uuid             NOT NULL,
    -- No foreign key: a deleted vehicle's trips keep a dangling vehicle_id
    -- (DELETE /v1/vehicles/{id} without cascade). Ownership is checked on write.
    vehicle_id            uuid,
    state                 text,
    started_at            timestamptz,
    ended_at              timestamptz,
    snapshot_at           timestamptz,
    duration_s            bigint,
    distance_km           double precision,
    engine_distance_km    double precision,
    max_speed_kmh         double precision,
    avg_speed_kmh         double precision,
    max_rpm               bigint,
    harsh_accel_count     bigint,
    harsh_brake_count     bigint,
    harsh_start_count     bigint,
    harsh_stop_count      bigint,
    fuel_used_l           double precision,
    fuel_economy_km_per_l double precision,
    fuel_cut_time_s       double precision,
    idle_time_s           double precision,
    warm_up_time_s        double precision,
    accel_time_s          double precision,
    eco_speed_time_s      double precision,
    over_speed_time_s     double precision,
    -- Nested API objects kept as sent (SpeedBand[], DtcSet, app info).
    speed_bands           jsonb,
    eco_score             double precision,
    safety_score          double precision,
    battery_max_v         double precision,
    battery_avg_v         double precision,
    coolant_max_c         double precision,
    coolant_avg_c         double precision,
    dtc                   jsonb,
    simulated             boolean,
    app                   jsonb,
    points_awarded        bigint,
    created_at            timestamptz      NOT NULL DEFAULT now(),
    updated_at            timestamptz      NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    writer_xid            xid8             NOT NULL DEFAULT pg_current_xact_id(),

    CONSTRAINT trips_member_client_key UNIQUE (member_id, client_trip_id),
    CONSTRAINT trips_state_values CHECK (state IN ('in_progress', 'completed')),
    CONSTRAINT trips_live_fields CHECK (
        deleted_at IS NOT NULL
        OR (vehicle_id IS NOT NULL AND state IS NOT NULL AND started_at IS NOT NULL
            AND snapshot_at IS NOT NULL AND simulated IS NOT NULL)),
    CONSTRAINT trips_tombstone_has_no_data CHECK (
        deleted_at IS NULL OR (state IS NULL AND started_at IS NULL AND snapshot_at IS NULL))
);

CREATE TRIGGER trips_set_writer_xid
    BEFORE INSERT OR UPDATE ON trips
    FOR EACH ROW EXECUTE FUNCTION set_writer_xid();

-- History view: GET /v1/trips without since (startedAt desc, id desc),
-- optionally from/to, and GET /v1/trips/days.
CREATE INDEX trips_member_started_idx ON trips (member_id, started_at DESC, id DESC)
    WHERE deleted_at IS NULL;
-- The same filtered by vehicle, and the vehicle cascade delete.
CREATE INDEX trips_member_vehicle_started_idx ON trips (member_id, vehicle_id, started_at DESC, id DESC)
    WHERE deleted_at IS NULL;
-- Sync walks in change order (writer_xid, id): live rows and tombstones.
CREATE INDEX trips_member_sync_idx ON trips (member_id, writer_xid, id);
-- Tombstone purge after the retention period.
CREATE INDEX trips_tombstone_purge_idx ON trips (deleted_at) WHERE deleted_at IS NOT NULL;

-- Per-member purge boundary: the latest deleted_at and the highest
-- writer_xid among purged trip tombstones. A sync token is accepted only if
-- its issuedAt is after max_deleted_at and its watermark is above
-- max_writer_xid.
CREATE TABLE trip_purge_boundaries (
    member_id      uuid        PRIMARY KEY REFERENCES members (id) ON DELETE CASCADE,
    max_deleted_at timestamptz NOT NULL,
    max_writer_xid xid8        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE trip_purge_boundaries;
DROP TABLE trips;
DROP FUNCTION set_writer_xid();
