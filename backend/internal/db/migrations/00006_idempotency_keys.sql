-- Idempotency-Key storage (Conventions → Idempotency). Keys are scoped per
-- (member, HTTP method, route template). The fingerprint is a hash of the
-- path parameters plus the decompressed body. A row is claimed as
-- in_progress (with a lease, so a crashed request does not block retries
-- forever) and completed with the response to replay.

-- +goose Up
CREATE TABLE idempotency_keys (
    member_id        uuid        NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    method           text        NOT NULL,
    route            text        NOT NULL,
    key              uuid        NOT NULL,
    fingerprint      bytea       NOT NULL,
    state            text        NOT NULL DEFAULT 'in_progress',
    locked_until     timestamptz,
    response_status  smallint,
    response_headers jsonb,
    response_body    bytea,
    created_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    expires_at       timestamptz NOT NULL,

    PRIMARY KEY (member_id, method, route, key),
    CONSTRAINT idempotency_keys_state_values CHECK (state IN ('in_progress', 'completed')),
    CONSTRAINT idempotency_keys_completed_has_response CHECK (
        state <> 'completed' OR (response_status IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);

-- +goose Down
DROP TABLE idempotency_keys;
