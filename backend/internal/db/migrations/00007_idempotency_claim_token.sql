-- Idempotency claim tokens (#175). Every claim of a key, first or after a
-- lease ran out, gets a fresh claim_token, and only the request holding the
-- current token can complete or release the key. Before this, a request
-- whose lease had expired (and whose key was then reclaimed by a retry)
-- could still complete or delete the new owner's claim.

-- +goose Up
ALTER TABLE idempotency_keys ADD COLUMN claim_token uuid;
UPDATE idempotency_keys SET claim_token = gen_random_uuid() WHERE state = 'in_progress';
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_in_progress_has_token
    CHECK (state <> 'in_progress' OR claim_token IS NOT NULL);

-- +goose Down
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_in_progress_has_token;
ALTER TABLE idempotency_keys DROP COLUMN claim_token;
