-- name: InsertVehicle :one
-- Returns no row when the member already has (or had, as a tombstone) this
-- clientVehicleId; the caller then reads it with GetVehicleByClientID.
INSERT INTO vehicles (
    id, member_id, client_vehicle_id, nickname, variant_id, make, model, fuel_type,
    engine_displacement_cc, model_year, plate_number, vin, odometer_km, idle_stop_go,
    obd_elm_version, obd_protocol, archived
) VALUES (
    @id, @member_id, sqlc.narg(client_vehicle_id), sqlc.narg(nickname), sqlc.narg(variant_id),
    @make::text, @model::text, @fuel_type::text, sqlc.narg(engine_displacement_cc), sqlc.narg(model_year),
    sqlc.narg(plate_number), sqlc.narg(vin), sqlc.narg(odometer_km), sqlc.narg(idle_stop_go),
    sqlc.narg(obd_elm_version), sqlc.narg(obd_protocol), @archived
)
ON CONFLICT (member_id, client_vehicle_id) DO NOTHING
RETURNING *;

-- name: GetVehicleByClientID :one
-- Includes tombstones (deleted_at set): POST /v1/vehicles answers 410 for one.
SELECT * FROM vehicles
WHERE member_id = @member_id AND client_vehicle_id = @client_vehicle_id::uuid;

-- name: GetVehicle :one
SELECT * FROM vehicles
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL;

-- name: GetVehicleForUpdate :one
-- DELETE /v1/vehicles/{id}: locks the live vehicle so the If-Match check,
-- the delete and a cascade happen against the version that is deleted. A
-- concurrent PATCH waits, then finds no live row.
SELECT * FROM vehicles
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL
FOR UPDATE;

-- name: VehicleExists :one
-- Ownership check for trip, telemetry and DTC uploads.
SELECT EXISTS (
    SELECT 1 FROM vehicles
    WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL
);

-- name: ListVehicles :many
-- Keyset page in id order. Pass after_id = NULL for the first page and
-- max_rows = limit + 1 to detect a next page.
SELECT * FROM vehicles
WHERE member_id = @member_id
  AND deleted_at IS NULL
  AND (@include_archived::boolean OR NOT archived)
  AND (sqlc.narg(after_id)::uuid IS NULL OR id > sqlc.narg(after_id)::uuid)
ORDER BY id
LIMIT @max_rows;

-- name: UpdateVehicle :one
-- Writes the full merged vehicle (the handler applies the merge patch to
-- the current row). Returns no row when missing or expected_version is stale.
UPDATE vehicles SET
    nickname               = sqlc.narg(nickname),
    variant_id             = sqlc.narg(variant_id),
    make                   = @make::text,
    model                  = @model::text,
    fuel_type              = @fuel_type::text,
    engine_displacement_cc = sqlc.narg(engine_displacement_cc),
    model_year             = sqlc.narg(model_year),
    plate_number           = sqlc.narg(plate_number),
    vin                    = sqlc.narg(vin),
    odometer_km            = sqlc.narg(odometer_km),
    idle_stop_go           = sqlc.narg(idle_stop_go),
    obd_elm_version        = sqlc.narg(obd_elm_version),
    obd_protocol           = sqlc.narg(obd_protocol),
    archived               = @archived,
    version                = version + 1,
    updated_at             = now()
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL AND version = @expected_version
RETURNING *;

-- name: TombstoneVehicle :one
-- Deletes a vehicle that has a clientVehicleId, keeping a tombstone with no
-- vehicle data. Returns no row when missing or expected_version is stale.
UPDATE vehicles SET
    nickname = NULL, variant_id = NULL, make = NULL, model = NULL, fuel_type = NULL,
    engine_displacement_cc = NULL, model_year = NULL, plate_number = NULL, vin = NULL,
    odometer_km = NULL, idle_stop_go = NULL, obd_elm_version = NULL, obd_protocol = NULL,
    archived = false,
    version = version + 1,
    updated_at = now(),
    deleted_at = now()
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL AND version = @expected_version
  AND client_vehicle_id IS NOT NULL
RETURNING id, client_vehicle_id, deleted_at;

-- name: DeleteVehicle :execrows
-- Deletes a vehicle without a clientVehicleId outright (nothing to remember).
DELETE FROM vehicles
WHERE member_id = @member_id AND id = @id AND deleted_at IS NULL AND version = @expected_version
  AND client_vehicle_id IS NULL;

-- name: PurgeVehicleTombstones :execrows
DELETE FROM vehicles
WHERE id IN (
    SELECT v.id FROM vehicles v
    WHERE v.deleted_at < @deleted_before::timestamptz
    ORDER BY v.deleted_at
    LIMIT @max_rows
    FOR UPDATE SKIP LOCKED
);
