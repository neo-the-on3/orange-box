-- Trip summary fields added in spec 1.0.0-draft.9: overSpeedCount,
-- fuelCutCount and endReason (an open set of strings, validated by the
-- API). Tombstones clear them like every other data column.

-- +goose Up
ALTER TABLE trips
    ADD COLUMN over_speed_count bigint,
    ADD COLUMN fuel_cut_count   bigint,
    ADD COLUMN end_reason       text;

-- +goose Down
ALTER TABLE trips
    DROP COLUMN over_speed_count,
    DROP COLUMN fuel_cut_count,
    DROP COLUMN end_reason;
