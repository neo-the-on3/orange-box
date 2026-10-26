-- Vehicle make and model are optional since spec 1.0.0-draft.9: an app
-- vehicle can have just a nickname. A live vehicle now needs only its
-- fuel type.

-- +goose Up
ALTER TABLE vehicles DROP CONSTRAINT vehicles_live_fields;
ALTER TABLE vehicles ADD CONSTRAINT vehicles_live_fields CHECK (deleted_at IS NOT NULL OR fuel_type IS NOT NULL);

-- +goose Down
-- The old constraint needs make and model on every live vehicle: fill the
-- missing ones with an empty string (the old API allowed it).
UPDATE vehicles SET make = COALESCE(make, ''), model = COALESCE(model, '')
WHERE deleted_at IS NULL AND (make IS NULL OR model IS NULL);
ALTER TABLE vehicles DROP CONSTRAINT vehicles_live_fields;
ALTER TABLE vehicles ADD CONSTRAINT vehicles_live_fields CHECK (
    deleted_at IS NOT NULL OR (make IS NOT NULL AND model IS NOT NULL AND fuel_type IS NOT NULL));
