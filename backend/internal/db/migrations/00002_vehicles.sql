-- Vehicles (Vehicle in openapi-v2.yaml). A deleted vehicle that had a
-- clientVehicleId stays as a tombstone row: its data columns are cleared
-- and deleted_at is set, so POST /v1/vehicles with the same clientVehicleId
-- can answer 410 for the 90-day retention. Keeping the tombstone in the same
-- table (rather than a side table) lets the unique key on
-- (member_id, client_vehicle_id) settle a create racing a delete atomically.
-- Vehicles without a clientVehicleId are deleted outright.

-- +goose Up
CREATE TABLE vehicles (
    id                     uuid        PRIMARY KEY,
    member_id              uuid        NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    client_vehicle_id      uuid,
    nickname               text,
    variant_id             text,
    make                   text,
    model                  text,
    -- Free text: the API enum may gain values, and the API validates it.
    fuel_type              text,
    engine_displacement_cc integer,
    model_year             smallint,
    plate_number           text,
    vin                    text,
    odometer_km            bigint,
    idle_stop_go           boolean,
    obd_elm_version        text,
    obd_protocol           smallint,
    archived               boolean     NOT NULL DEFAULT false,
    -- Bumped on every change; the ETag for PATCH/DELETE.
    version                bigint      NOT NULL DEFAULT 1,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    deleted_at             timestamptz,

    CONSTRAINT vehicles_live_fields CHECK (
        deleted_at IS NOT NULL OR (make IS NOT NULL AND model IS NOT NULL AND fuel_type IS NOT NULL)),
    CONSTRAINT vehicles_tombstone_has_client_id CHECK (deleted_at IS NULL OR client_vehicle_id IS NOT NULL)
);

-- NULL client ids are distinct, so vehicles created without one never clash.
CREATE UNIQUE INDEX vehicles_member_client_key ON vehicles (member_id, client_vehicle_id);
-- GET /v1/vehicles: keyset pagination by id (UUIDv7, so creation order).
CREATE INDEX vehicles_member_list_idx ON vehicles (member_id, id) WHERE deleted_at IS NULL;
-- Tombstone purge after the retention period.
CREATE INDEX vehicles_tombstone_purge_idx ON vehicles (deleted_at) WHERE deleted_at IS NOT NULL;

-- +goose Down
DROP TABLE vehicles;
