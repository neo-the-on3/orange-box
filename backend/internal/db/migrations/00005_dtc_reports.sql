-- DTC read/clear reports (DtcReport in openapi-v2.yaml). De-duplicated by
-- clientReportId per member. vehicle_id has no foreign key, like trips:
-- a non-cascading vehicle delete leaves the reports with the member, and a
-- cascading one deletes them explicitly.

-- +goose Up
CREATE TABLE dtc_reports (
    id                    uuid        PRIMARY KEY,
    member_id             uuid        NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    vehicle_id            uuid        NOT NULL,
    client_report_id      uuid        NOT NULL,
    kind                  text        NOT NULL,
    read_at               timestamptz NOT NULL,
    mil                   boolean     NOT NULL,
    -- NULL means the array was absent; '{}' means sent empty.
    stored                text[],
    pending               text[],
    permanent             text[],
    vin                   text,
    client_trip_id        uuid,
    odometer_km           bigint,
    distance_since_mil_km bigint,
    created_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT dtc_reports_member_client_key UNIQUE (member_id, client_report_id),
    CONSTRAINT dtc_reports_kind_values CHECK (kind IN ('read', 'cleared'))
);

-- GET /v1/vehicles/{id}/dtc-reports, newest first.
CREATE INDEX dtc_reports_vehicle_idx ON dtc_reports (member_id, vehicle_id, read_at DESC, id DESC);

-- +goose Down
DROP TABLE dtc_reports;
