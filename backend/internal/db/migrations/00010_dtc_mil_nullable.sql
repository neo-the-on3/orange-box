-- DtcReportCreate.mil is nullable since spec 1.0.0-draft.9: null means the
-- MIL state is unknown (not read, or a cleared report).

-- +goose Up
ALTER TABLE dtc_reports ALTER COLUMN mil DROP NOT NULL;

-- +goose Down
-- Unknown becomes false, as clients sent it before draft.9.
UPDATE dtc_reports SET mil = false WHERE mil IS NULL;
ALTER TABLE dtc_reports ALTER COLUMN mil SET NOT NULL;
