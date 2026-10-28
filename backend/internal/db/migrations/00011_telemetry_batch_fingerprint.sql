-- A telemetry batch's content fingerprint: SHA-256 of its normalised
-- content (clientTripId, vehicleId, samples and events). A clientBatchId
-- names fixed content, so a later upload of the same clientBatchId with
-- another fingerprint is rejected (422 client_batch_id_reused) instead of
-- being acknowledged as a duplicate and silently dropped. Rows stored
-- before this migration have none and keep answering duplicate.

-- +goose Up
ALTER TABLE telemetry_batches ADD COLUMN content_sha256 bytea
    CONSTRAINT telemetry_batches_content_sha256_len CHECK (octet_length(content_sha256) = 32);

-- +goose Down
ALTER TABLE telemetry_batches DROP COLUMN content_sha256;
