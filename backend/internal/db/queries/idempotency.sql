-- name: ClaimIdempotencyKey :one
-- Claims a key for a new request under claim_token (a fresh UUID per claim).
-- A stored key past expires_at counts as absent: it is replaced in the same
-- statement, even before the purge job removes it. Returns no row when a
-- live key exists; the caller reads it with GetIdempotencyKey and replays
-- (same fingerprint, completed), answers 409 (in progress), 422 (other
-- fingerprint) or tries ReclaimIdempotencyKey (lease expired).
INSERT INTO idempotency_keys (member_id, method, route, key, fingerprint, claim_token, locked_until, expires_at)
VALUES (@member_id, @method, @route, @key, @fingerprint, @claim_token::uuid, @locked_until::timestamptz, @expires_at)
ON CONFLICT (member_id, method, route, key) DO UPDATE SET
    fingerprint      = EXCLUDED.fingerprint,
    state            = 'in_progress',
    claim_token      = EXCLUDED.claim_token,
    locked_until     = EXCLUDED.locked_until,
    response_status  = NULL,
    response_headers = NULL,
    response_body    = NULL,
    created_at       = now(),
    completed_at     = NULL,
    expires_at       = EXCLUDED.expires_at
WHERE idempotency_keys.expires_at < now()
RETURNING member_id, key;

-- name: GetIdempotencyKey :one
SELECT * FROM idempotency_keys
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key;

-- name: ReclaimIdempotencyKey :one
-- Takes over an in-progress key whose lease ran out (its request died or
-- stalled), if the fingerprint matches, under a fresh claim_token: the old
-- owner can then no longer complete or release it. Returns no row otherwise.
UPDATE idempotency_keys SET locked_until = @locked_until::timestamptz, claim_token = @claim_token::uuid
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key
  AND state = 'in_progress' AND locked_until < now() AND fingerprint = @fingerprint
RETURNING member_id, key;

-- name: CompleteIdempotencyKey :execrows
-- Stores the response to replay. Affects no row unless claim_token is the
-- current claim's (a request whose key was reclaimed has lost it).
UPDATE idempotency_keys SET
    state            = 'completed',
    locked_until     = NULL,
    response_status  = @response_status::smallint,
    response_headers = sqlc.narg(response_headers),
    response_body    = sqlc.narg(response_body),
    completed_at     = now()
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key
  AND state = 'in_progress' AND claim_token = @claim_token::uuid;

-- name: ReleaseIdempotencyKey :execrows
-- Drops an in-progress claim when the request failed without a response
-- worth replaying (e.g. a 5xx), so the client can retry with the same key.
-- Affects no row unless claim_token is the current claim's.
DELETE FROM idempotency_keys
WHERE member_id = @member_id AND method = @method AND route = @route AND key = @key
  AND state = 'in_progress' AND claim_token = @claim_token::uuid;

-- name: PurgeExpiredIdempotencyKeys :execrows
DELETE FROM idempotency_keys
WHERE (member_id, method, route, key) IN (
    SELECT k.member_id, k.method, k.route, k.key FROM idempotency_keys k
    WHERE k.expires_at < now()
    LIMIT @max_rows
    FOR UPDATE SKIP LOCKED
);
