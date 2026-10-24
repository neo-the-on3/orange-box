-- name: InsertDtcReport :one
-- Returns no row when this clientReportId already exists; the caller then
-- replays it with GetDtcReportByClientID.
INSERT INTO dtc_reports (
    id, member_id, vehicle_id, client_report_id, kind, read_at, mil, stored, pending, permanent,
    vin, client_trip_id, odometer_km, distance_since_mil_km
) VALUES (
    @id, @member_id, @vehicle_id, @client_report_id, @kind, @read_at, @mil,
    sqlc.narg(stored), sqlc.narg(pending), sqlc.narg(permanent), sqlc.narg(vin),
    sqlc.narg(client_trip_id), sqlc.narg(odometer_km), sqlc.narg(distance_since_mil_km)
)
ON CONFLICT (member_id, client_report_id) DO NOTHING
RETURNING *;

-- name: GetDtcReportByClientID :one
SELECT * FROM dtc_reports
WHERE member_id = @member_id AND client_report_id = @client_report_id;

-- name: ListDtcReports :many
-- Newest first by (read_at, id). Pass NULL cursor_read_at for the first page.
SELECT * FROM dtc_reports
WHERE member_id = @member_id
  AND vehicle_id = @vehicle_id
  AND (sqlc.narg(cursor_read_at)::timestamptz IS NULL
       OR (read_at, id) < (sqlc.narg(cursor_read_at)::timestamptz, @cursor_id::uuid))
ORDER BY read_at DESC, id DESC
LIMIT @max_rows;

-- name: DeleteVehicleDtcReports :execrows
DELETE FROM dtc_reports
WHERE member_id = @member_id AND vehicle_id = @vehicle_id;
