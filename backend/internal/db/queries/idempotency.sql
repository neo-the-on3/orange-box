-- name: ClaimIdempotencyKey :one
-- Claims a key for a new request. Returns no row when the key exists; the
-- caller reads it with GetIdempotencyKey and replays (same fingerprint,
-- completed), answers 409 (in progress), 422 (other fingerprint) or tries
-- ReclaimIdempotencyKey (lease expired).
INSERT INTO idempotency_keys (member_id, method, route, key, fingerprint, locked_until, expires_at)
VALUES (@member_id, @method, @route, @key, @fingerprint, @locked_until::timestamptz, @expires_at)
ON CONFLICT (member_id, method, route, key) DO NOTHING
RETURNING member_id, key;

-- name: GetIdempotencyKey :one
SELECT * FROM idempotency_keys
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key;

-- name: ReclaimIdempotencyKey :one
-- Takes over an in-progress key whose lease ran out (its request died),
-- if the fingerprint matches. Returns no row otherwise.
UPDATE idempotency_keys SET locked_until = @locked_until::timestamptz
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key
  AND state = 'in_progress' AND locked_until < now() AND fingerprint = @fingerprint
RETURNING member_id, key;

-- name: CompleteIdempotencyKey :execrows
UPDATE idempotency_keys SET
    state            = 'completed',
    locked_until     = NULL,
    response_status  = @response_status::smallint,
    response_headers = sqlc.narg(response_headers),
    response_body    = sqlc.narg(response_body),
    completed_at     = now()
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key
  AND state = 'in_progress';

-- name: ReleaseIdempotencyKey :execrows
-- Drops an in-progress claim when the request failed without a response
-- worth replaying (e.g. a 5xx), so the client can retry with the same key.
DELETE FROM idempotency_keys
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key
  AND state = 'in_progress';

-- name: PurgeExpiredIdempotencyKeys :execrows
DELETE FROM idempotency_keys
WHERE (member_id, method, route, key) IN (
    SELECT k.member_id, k.method, k.route, k.key FROM idempotency_keys k
    WHERE k.expires_at < now()
    LIMIT @max_rows
    FOR UPDATE SKIP LOCKED
);
