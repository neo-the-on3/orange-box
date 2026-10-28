-- name: InsertMember :one
-- Creates a member unless a live member already has this address. Returns
-- no row on conflict; the caller then reads the existing member.
INSERT INTO members (id, email)
VALUES (@id, @email::text)
ON CONFLICT (email) WHERE email IS NOT NULL DO NOTHING
RETURNING *;

-- name: GetMemberByEmail :one
SELECT * FROM members
WHERE email = @email::text AND deleted_at IS NULL;

-- name: GetMember :one
SELECT * FROM members
WHERE id = @id AND deleted_at IS NULL;

-- name: GetMemberForUpdate :one
-- PATCH /v1/me: locks the live member so the If-Match check and the write
-- happen against the version they replace. A concurrent DELETE /v1/me
-- waits its turn; after it, the member is no longer live.
SELECT * FROM members
WHERE id = @id AND deleted_at IS NULL
FOR UPDATE;

-- name: GetMemberAuthState :one
-- What the auth middleware checks on every request: the member exists, is
-- not deleted and the token is not older than tokens_valid_after.
SELECT deleted_at, tokens_valid_after FROM members
WHERE id = @id;

-- name: UpdateMemberProfile :one
-- JSON merge patch of the profile with optimistic concurrency. A NULL
-- argument leaves the column unchanged unless its set_* flag is true (so
-- countryCode/regionCode can be cleared). Returns no row when the member is
-- gone or expected_version is stale.
UPDATE members SET
    display_name   = CASE WHEN @set_display_name::boolean THEN sqlc.narg(display_name) ELSE display_name END,
    country_code   = CASE WHEN @set_country_code::boolean THEN sqlc.narg(country_code) ELSE country_code END,
    region_code    = CASE WHEN @set_region_code::boolean THEN sqlc.narg(region_code) ELSE region_code END,
    ranking_opt_in = COALESCE(sqlc.narg(ranking_opt_in), ranking_opt_in),
    version        = version + 1,
    updated_at     = now()
WHERE id = @id AND deleted_at IS NULL AND version = @expected_version
RETURNING *;

-- name: MarkMemberDeleted :one
-- DELETE /v1/me: detaches the e-mail address, revokes every token at once
-- and schedules erasure. Returns no row if already deleted.
UPDATE members SET
    email              = NULL,
    deleted_at         = now(),
    tokens_valid_after = now(),
    updated_at         = now()
WHERE id = @id AND deleted_at IS NULL
RETURNING id, deleted_at;

-- name: ListMembersPendingErasure :many
SELECT id, deleted_at FROM members
WHERE deleted_at IS NOT NULL AND deleted_at < @before::timestamptz
ORDER BY deleted_at
LIMIT @max_rows;

-- name: EraseMember :execrows
-- Erases a deleted member; ON DELETE CASCADE removes everything they own
-- in Postgres. Object storage is cleaned up separately.
DELETE FROM members
WHERE id = @id AND deleted_at IS NOT NULL;
