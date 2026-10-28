# Orange Box backend

Go REST API for the Orange Box Android app. The app works fully offline; this
service is an optional sync and backup target.

This folder is its own Go module (`github.com/neo-the-on3/orange-box/backend`)
inside the Orange Box monorepo. It has its own CI workflow
(`.github/workflows/backend.yml`), which runs only when started by hand; see
[CI](#ci).

## Status

Server skeleton (#104): every operation in
[`../docs/backend/openapi-v2.yaml`](../docs/backend/openapi-v2.yaml) is routed
and validated. Implemented so far:

- `GET /v1/health`
- Test-mode sign-in only (full sign-in is #106 and comes last):
  `POST /v1/auth/otp` and `POST /v1/auth/token` (`grantType=email_otp`) for
  `@test.orangebox.invalid` addresses. Other addresses get `503`
  (`code=email_sign_in_unavailable`) on `/otp` and `invalid_grant` on
  `/token`; `grantType=refresh_token` answers `501`.
- Profile (#107), on Postgres: `GET /v1/me`, `PATCH /v1/me` (JSON merge
  patch; `If-Match` required, `428` when missing, `412` when stale; a patch
  that changes nothing keeps the `ETag`) and `DELETE /v1/me`. Until the
  member sets a `displayName`, `GET` shows `Driver <6 hex>`, derived from a
  hash of the member id and never stored. `DELETE` answers `202`
  (`status=scheduled`, `completeBy` = `requestedAt` + 30 days): in one
  statement it detaches the e-mail address (the address can sign up again
  as a new, empty member), sets `deleted_at` and `tokens_valid_after`.
  **Every authenticated request** checks the member's state in Postgres
  (one primary-key read), so a deleted member's tokens get `401
  invalid_token` at once; a check that fails answers `503`/`500`, never
  lets the request through. **The erasure job isn't built yet** (see the storage
  note below). Without `DATABASE_URL` they answer `501` and nothing is
  checked.
- Vehicles (#108), on Postgres: `GET`/`POST /v1/vehicles` and
  `GET`/`PATCH`/`DELETE /v1/vehicles/{vehicleId}`, with `ETag` /
  `If-Match` (`428` when missing, `412` when stale), `If-None-Match` (`304`),
  JSON merge patch (`null` clears a nullable field, an absent one is kept),
  de-duplication by `clientVehicleId` (`200` with the existing vehicle, `410
  vehicle_deleted` for a deletion tombstone), `?cascade=true` and
  `Idempotency-Key`. Without `DATABASE_URL` they answer `501`.
- Trips (#110), on Postgres: `POST /v1/trips/batch` (per-item results:
  `created`/`updated`/`unchanged`, or `rejected` with `vehicle_not_found` or
  `trip_deleted`; a newer `snapshotAt` replaces, a `completed` trip is
  final), `GET /v1/trips` (history newest first, filtered by `vehicleId` and
  `from`/`to`, or `since=<token>` for the changes since a sync point),
  `GET /v1/trips/deletions?since=<token>`, `GET /v1/trips/days` (trip counts
  per local date in `tz`, up to 366 days), and `GET`/`DELETE
  /v1/trips/{tripRef}` with `client:<clientTripId>` refs. Times are stored as
  UTC milliseconds. Pages carry an opaque `nextCursor`; the last page of an
  unfiltered walk carries `nextSince`. A token that a tombstone purge has
  overtaken, or one from another member or deployment, gets `400
  sync_window_expired` (do a full walk) with a `reason`: `purged`,
  `invalid` (also any cursor that doesn't verify or doesn't belong to the
  request: restart the walk), or `deployment_changed` for a token or cursor
  from before a database restore (see "Restoring the database"). `updatedAt` only moves forward
  (wall clock at write time, milliseconds, at least 1 ms past the previous
  value). `/v1/trips/days` computes local days with the Go time zone
  database embedded in the binary, so aliases such as `Asia/Calcutta` work.
  A deleted trip's item in a replayed batch comes back as `trip_deleted`.
  Without `DATABASE_URL` they answer `501`.
- Trip size limits (in the spec since 1.0.0-draft.9; `internal/httpapi/limits.go`
  also applies them at startup so a spec edit can't loosen them), enforced
  like schema limits (`422` with a pointer): `vehicleId` ≤ 64 characters, `app.version` ≤ 64,
  `app.os` ≤ 32, `app.osVersion` ≤ 128, `speedBands` ≤ 32 items, each `dtc`
  list ≤ 128 codes; a trip is at most 16 KiB of JSON, and a page of trips
  ends early (with a cursor) before 512 KiB.
- `Idempotency-Key` on every operation that declares it (`POST /v1/vehicles`
  and `POST /v1/trips/batch` so far): a retry with the same key and
  request gets the stored response, another request under the key `422
  idempotency_key_reused`, a retry while the first runs `409
  idempotency_in_flight` (also on uploads, where a different upload in flight
  gets `429 upload_in_progress`). Needs Postgres; without it the header is only
  validated.
- Every other operation answers `501` (`code=not_implemented`) after routing,
  auth and validation, so clients and contract tests see the real shapes.

Postgres storage (#105): the schema, goose migrations and sqlc queries for
members, vehicles, trips with deletion tombstones and sync watermarks,
telemetry batch metadata and events, DTC reports and idempotency keys are
in `internal/db` (see [Database](#database)). Members (profiles), vehicles, trips and
idempotency keys are stored in Postgres when `DATABASE_URL` is set; the
handlers for the other resources are still `501` and will use the queries as
they land. Nothing erases deleted members yet: `DELETE /v1/me` schedules erasure, but
the job that runs `ListMembersPendingErasure` and `EraseMember` (and
deletes the member's R2 objects, #179) is still to come, so deleted
members' rows and data stay in Postgres until it lands. Nothing purges
tombstones or expired idempotency keys yet either (#184; the
queries exist; the job is to come).

## Requirements

- Go 1.27 or newer
- `golangci-lint` v2 and `govulncheck` for `make lint` / `make vuln`
  (`go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@latest`,
  `go install golang.org/x/vuln/cmd/govulncheck@v1.9.0`)
- Docker, for the container image
- Node.js and `pip install openapi-spec-validator`, for `make spec-lint`

`oapi-codegen` is a Go tool dependency (`go tool oapi-codegen`), pinned in
`go.mod`; nothing to install.

## Run locally

```sh
cd backend
export AUTH_TEST_CODE=...      # your own 6 digits: not all equal, not a run like 123456; never commit it
make run                       # ENV=local, AUTH_TEST_MODE=true, listens on :8080
curl -s localhost:8080/v1/health

# Sign in as a test member (no e-mail is sent for the reserved domain).
curl -s -X POST localhost:8080/v1/auth/otp -H 'Content-Type: application/json' \
  -d '{"email":"me@test.orangebox.invalid"}'
curl -s -X POST localhost:8080/v1/auth/token -H 'Content-Type: application/json' \
  -d '{"grantType":"email_otp","email":"me@test.orangebox.invalid","code":"'$AUTH_TEST_CODE'"}'
# → {"accessToken":"eyJ...", ...}; send it as `Authorization: Bearer <accessToken>`.
```

Without `JWT_PRIVATE_KEY` the server generates a signing key at startup (only
allowed when `ENV` is `local`, `ci` or `sandbox`), so tokens stop working when
it restarts. Without `DATABASE_URL`, members are kept in memory and lost on
restart, and the profile, vehicle and trip operations answer `501`; see [Database](#database)
to use Postgres.

Test-mode guessing limits (all test addresses share one code): asking for a
code again within `resendAfter` (60 s) keeps the pending one; 5 wrong codes
lock an address for 10 minutes, and a new code doesn't reset that; after 100
wrong codes across all addresses in 10 minutes, every verification gets
`400 otp_locked` with `Retry-After`. Keep test-mode deployments private
anyway until #114.

The server logs JSON to stdout. SIGINT/SIGTERM drain in-flight requests for
up to `SHUTDOWN_TIMEOUT`; a second Ctrl-C exits at once.

### Environment

| Variable           | Default      | Meaning |
| ------------------ | ------------ | ------- |
| `ENV`              | none         | `local`, `ci`, `sandbox`, `production`, ... Test-only features need exactly `local`, `ci` or `sandbox`. |
| `PORT`             | `8080`       | HTTP port (`0` picks a free one; the log shows the bound address). |
| `AUTH_TEST_MODE`   | `false`      | `true` enables test-mode sign-in. With any `ENV` other than `local`/`ci`/`sandbox` (including unset) the server refuses to start. |
| `AUTH_TEST_CODE`   | none         | Required with test mode: 6 digits, not all the same, not an ascending/descending run. Keep it in a secret store, never in the repo. |
| `JWT_PRIVATE_KEY`  | none         | ES256 (P-256) signing key, PEM (PKCS#8 or SEC 1). Required unless `ENV` is `local`/`ci`/`sandbox`. |
| `JWT_KEY_ID`       | none         | `kid` header for `JWT_PRIVATE_KEY`. Required with it. |
| `JWT_ISSUER`       | `orange-box` | `iss` claim. `aud` is always `orange-box-api`. |
| `REQUEST_TIMEOUT`  | `30s`        | Per-request context deadline. |
| `SHUTDOWN_TIMEOUT` | `8s`         | Graceful shutdown budget for draining requests; closing the database pool then takes at most 1 s more, even if the database hangs (pgx alone waits ~15 s per connection), so the default stays inside Docker's 10 s stop grace. |
| `SYNC_CLUSTER_CHECK_INTERVAL` | `15s` | How often a running instance checks, on a fresh connection, that the database endpoint still reaches the cluster its sync identity was computed for (`10s`–`30s`; `cluster_and_deployment_id` only). A switch is detected within this plus 5 s. |
| `LOG_LEVEL`        | `info`       | `debug`, `info`, `warn` or `error`. |
| `UPLOAD_CONCURRENCY` | `2`        | Upload bodies processed at once (1–256). Sized for a 512 MB host; see below. |
| `UPLOAD_QUEUE_WAIT`  | `2s`       | How long an upload waits for a slot before `503 uploads_busy`. |
| `BODY_CONCURRENCY`   | `16`       | Signed-in non-upload requests with a body (profile, vehicles) pre-scanned, validated, decoded and handled at once (1–1024); a further one waits up to `UPLOAD_QUEUE_WAIT`, then gets `503 server_busy` with `Retry-After: 1`. Bodies are read before a slot is taken. |
| `ANON_BODY_CONCURRENCY` | `8`     | The same for anonymous requests (sign-in), in a separate pool so they can't starve members (1–1024). |
| `ANON_READ_CONCURRENCY` | `1024`  | Anonymous requests with a body in flight, reading included (1–65536); bounds buffered anonymous bodies to 16 MiB. Beyond it, `503 server_busy` at once. |
| `UPLOAD_READ_TIMEOUT` | `10s`     | How long an upload body may take to arrive once it has a slot; then `408 request_timeout`. Send large batches gzipped (1 MiB max). |
| `GOMEMLIMIT`         | `400MiB` in the image | Go runtime soft memory limit. Keep it ~80% of the host's memory and raise `UPLOAD_CONCURRENCY` only with it. |
| `DATABASE_URL`     | none         | Postgres URL. Empty means in-memory members and `501` for profiles, vehicles and trips (only allowed when `ENV` is `local`, `ci` or `sandbox`). The server refuses to start if the schema is behind (`server migrate up`) or ahead of the binary. |
| `SYNC_TOKEN_KEY`   | none         | Base64 secret (at least 32 bytes) that signs trip sync tokens and page cursors. Required unless `ENV` is `local`/`ci`/`sandbox`, where a missing one is generated at startup (tokens then die with the process). Rotating it expires every client's token: they do one full walk. |
| `SYNC_DEPLOYMENT_ID` | `dev-<ENV>` in `local`/`ci`/`sandbox` | Names this deployment of the database in sync tokens and cursors, together with the cluster's `system_identifier` (`[A-Za-z0-9._:-]`, up to 64, e.g. `prod-2026-10-25`). Required unless `ENV` is `local`/`ci`/`sandbox`. **Change it after every restore, PITR or branch reset**, with a full restart (see "Restoring the database"). The `dev-<ENV>` default is for dev environments only and never changes, so a restore of a dev database onto the same cluster goes undetected unless you set one (the server warns at startup when it uses the default). |
| `SYNC_IDENTITY_SOURCE` | `cluster_and_deployment_id` | What sync tokens bind. `cluster_and_deployment_id`: the cluster's `system_identifier` and `SYNC_DEPLOYMENT_ID`; the role must be allowed to run `pg_control_system()`, or the server doesn't start, and the server refuses to serve if the endpoint starts reaching another cluster. `deployment_id_only`: `SYNC_DEPLOYMENT_ID` only, for roles that can't (`system_identifier` isn't read, and cluster switches go unnoticed: change `SYNC_DEPLOYMENT_ID` and restart on any cluster change). Every instance must use the same value. |
| `DB_MAX_CONNS`     | `10`         | Postgres pool size (1–200). |
| `MIGRATE_ON_START` | `false`      | `true` applies pending migrations at startup (an advisory lock serialises instances). Otherwise run `server migrate up` as a release step. |

Keep local values in a `.env` (git-ignored); never commit secrets. To make a
signing key: `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256`;
a sync token key: `openssl rand -base64 32`.

## Layout

| Path                     | What |
| ------------------------ | ---- |
| `cmd/server`             | `main`: config, listener, graceful shutdown |
| `internal/api`           | **Generated** strict server + models (`api.gen.go`) and 501 stubs (`stubs.gen.go`) |
| `internal/httpapi`       | Router, middleware, OpenAPI validation, handlers |
| `internal/auth`          | ES256 access tokens, member store interface, test-mode codes |
| `internal/store`         | Storage interfaces and domain types the handlers use (vehicles, trips, idempotency keys) |
| `internal/db`            | Postgres: pool, embedded goose migrations (`migrations/`), sqlc queries (`queries/`), the Postgres member, vehicle, trip and idempotency stores |
| `internal/synctoken`     | HMAC-signed sync tokens and page cursors |
| `internal/db/dbgen`      | **Generated** by sqlc from `queries/` and `migrations/` (`sqlc.yaml`) |
| `internal/db/dbtest`     | Throwaway databases for integration tests |
| `internal/config`        | Environment config (fails closed) |
| `internal/problem`       | RFC 9457 `application/problem+json` errors |
| `internal/logging`       | slog JSON logger with redaction |
| `internal/timeutil`      | `UtcInstant` (0–9 fractional digits in, truncated to ms, 3 digits out) |
| `oapi-codegen.yaml`, `oapi-overlay.yaml` | Code generation config; the overlay adds Go-only hints without touching the spec |

### Request pipeline

request id → access log → panic recovery → default headers → timeout →
OpenAPI middleware (route lookup with 404/405, bearer auth, upload slots,
`Content-Encoding` and body limits, kin-openapi validation) → generated
strict handlers.

- Bearer auth runs before any body byte is read, so unauthenticated
  requests can't make the server buffer or inflate anything.
- At most `UPLOAD_CONCURRENCY` upload bodies are read, inflated and
  validated at once; a further upload waits up to `UPLOAD_QUEUE_WAIT`, then
  gets `503` (`uploads_busy`) with `Retry-After`. Each member may have one
  upload in flight; a second one gets `429` (`upload_in_progress`) with
  `Retry-After: 1`. An upload body must arrive within `UPLOAD_READ_TIMEOUT`
  of getting its slot, else `408` (`request_timeout`).
- Every other body (16 KiB at most) is read first, within 5 s and without
  a processing slot, so slow senders can't hold slots. Anonymous requests
  count against `ANON_READ_CONCURRENCY` from the start of the read to the
  end of the handler. The request then takes a slot from its pool
  (`ANON_BODY_CONCURRENCY` or `BODY_CONCURRENCY`) for prescan, validation,
  decoding and the handler. Uploads are different: the upload slot is
  taken before the body is read (8 MiB bodies can't be buffered freely), so
  a slow upload holds it for at most `UPLOAD_READ_TIMEOUT`, one per member.
- Before decoding, the JSON body is pre-scanned once, with almost no
  allocation:
  - an array longer than its schema's `maxItems` is `422` with a pointer
    (e.g. `/trips`) after reading at most `maxItems + 1` of its elements;
  - a duplicate member name, or a name that differs from a declared
    property only by case (`TRIPS`, `DurationS`), is `422` with a pointer,
    in every object at every depth (including `oneOf` branches);
  - U+0000 in any string or member name, raw or escaped as `\u0000`, is
    `422` with a pointer (Postgres `text` can't store it);
  - more than 500,000 JSON values and member names in an upload body, or
    4,096 in any other body, is `413` (`batch_too_large`);
  - more than 64 levels of nesting is `400`.
- Validation fails fast on bodies over 8 KiB or 1,024 values (the `422`
  lists the first invalid field); smaller bodies list up to 20.
- Nullable optional fields are generated as `nullable.Nullable[T]`
  (`nullable-type: true`), so merge patches can tell `null` (clear the
  field) from absent (leave it).
- The generated handlers decode bodies with `encoding/json/v2` (exact-case
  member names, duplicates rejected; see `internal/api/decode.go` and the
  one-line template override in `internal/api/templates/`), so a handler
  sees exactly what was validated. `encoding/json` v1 matched names
  case-insensitively.
- Memory sizing: with the defaults (2 upload slots, 16 body slots,
  `GOMEMLIMIT=400MiB`) the server fits a 512 MB host. Measured in a
  `--memory=512m` container: 2 members uploading worst-case bodies
  (250k unknown names) back to back plus 120 anonymous 8–16 KiB bodies for
  20 s peaked at 288 MiB.

- `X-Request-Id`: a client UUID is echoed, otherwise a UUIDv7 is generated.
  Every problem carries it as `requestId`.
- `Content-Encoding: gzip` is accepted only on the three batch uploads
  (1 MiB compressed / 8 MiB inflated, `413` beyond); anything else is `415`
  (`unsupported_encoding`). Gzip is inflated as a stream and abandoned as
  soon as the inflated output passes 8 MiB. Other bodies are limited to
  16 KiB.
- Validation errors are `422` (`validation_failed`) with JSON pointers,
  including integers sent as `415.0`, `1e3` or out of range; bad
  parameters, malformed JSON or a missing body are `400`; a wrong
  `Content-Type` is `415`. Messages are built from the schema and never echo
  submitted values.
- Logs never contain e-mail addresses, codes, tokens, VINs or plates: the
  access log records the route template, not the raw path or query, and the
  log handler redacts sensitive keys and scrubs e-mail/JWT patterns.

## Make targets

| Target                | What it does                                           |
| --------------------- | ------------------------------------------------------ |
| `make build`          | Static binary in `bin/server` (`CGO_ENABLED=0`)        |
| `make run`            | Run locally in test mode (needs `AUTH_TEST_CODE`)      |
| `make test`           | `go test -race ./...`                                  |
| `make vet`            | `go vet ./...`                                         |
| `make lint`           | `golangci-lint run` (config in `.golangci.yml`)        |
| `make vuln`           | `govulncheck ./...`                                    |
| `make spec-lint`      | Redocly + openapi-spec-validator on the v2 spec        |
| `make generate`       | Regenerate `internal/api` from the spec and `internal/db/dbgen` from the SQL |
| `make generate-check` | Fail if the committed generated code is stale          |
| `make migrate-up`     | Apply pending migrations to `DATABASE_URL`             |
| `make migrate-down`   | Roll back the latest migration                         |
| `make migrate-status` | List migrations and whether each is applied            |
| `make docker`         | `docker build -t orange-box-backend:dev .`             |
| `make ci`             | generate-check, vet, lint, vuln, test (`-race`), build: the Go checks CI runs |
| `make check`          | `make ci` plus the docker build when a daemon is reachable |

After changing the spec or the SQL, run `make generate` and commit the
result; CI fails if `internal/api` is stale (`make generate-check` also
covers `internal/db/dbgen`).

## Database

Postgres 15 or newer (the schema uses `NULLS NOT DISTINCT`); Neon in
production. The server and `server migrate` check `server_version_num` on
connect and refuse an older server with a clear error. Migrations are embedded in the binary and applied with
`server migrate up` (or `MIGRATE_ON_START=true`); sqlc generates typed
queries into `internal/db/dbgen`.

```sh
export DATABASE_URL='postgres://orangebox:...@localhost:5432/orangebox?sslmode=disable'
make migrate-up      # or: go run ./cmd/server migrate up
make run             # members now persist across restarts
make test            # Postgres integration tests run because DATABASE_URL is set
```

Integration tests create a throwaway database per test on that server
(`ob_test_*`, dropped afterwards), so the role needs `CREATEDB`; they never
touch the database in `DATABASE_URL`. Without `DATABASE_URL` they are
skipped, unless `REQUIRE_DATABASE_TESTS=true`, which makes them fail. The
Backend workflow runs them against a pinned `postgres:17` service with
that set.

With `DATABASE_URL` set, `GET /v1/health` also pings the database (2 s
budget) and answers `503` (`service_unavailable`, `Retry-After: 5`) while
it is unreachable, so use it as a readiness check; a liveness probe that
restarts the container on `503` would restart it during a database
outage.

Schema overview (`internal/db/migrations`):

| Table | Holds |
| ----- | ----- |
| `members` | Profile, `version` (ETag), `tokens_valid_after`, `deleted_at`. E-mail is unique among live members only: `DELETE /v1/me` sets it to NULL at once, a job erases the row later and `ON DELETE CASCADE` removes everything the member owns. |
| `vehicles` | Vehicle fields, `version` (ETag), `archived`. A live vehicle needs only `fuel_type`; `make` and `model` may be NULL (00008). A deleted vehicle with a `clientVehicleId` stays as a data-less tombstone row (`deleted_at` set) so `POST /v1/vehicles` can answer `410`; unique `(member_id, client_vehicle_id)`. |
| `trips` | Trip summaries (including `over_speed_count`, `fuel_cut_count` and `end_reason`, 00009), unique `(member_id, client_trip_id)`. Deleting clears every data column and sets `deleted_at` (the tombstone). `writer_xid` is set to `pg_current_xact_id()` by a trigger on every write. A batch upload holds `FOR SHARE` locks on its live vehicles, so a vehicle delete (and its cascade) waits for it or runs first, and the batch then rejects its trips with `vehicle_not_found`. |
| `trip_purge_boundaries` | Per member, the latest `deleted_at` and highest `writer_xid` among purged trip tombstones; sync tokens at or below it get `sync_window_expired`. |
| `telemetry_batches` | One row per uploaded batch: `clientBatchId` (unique per member), counts for duplicate replies, sample time range, object-storage key (raw batches go to R2 with #110). |
| `trip_events` | Driving events, de-duplicated on insert by `clientEventId` and by the fallback key (`client_trip_id`, `t`, `type`, `active`, `dtc`; `NULLS NOT DISTINCT`). |
| `dtc_reports` | DTC read/clear records, unique `(member_id, client_report_id)`. `mil` is NULL when unknown (00010). |
| `idempotency_keys` | `Idempotency-Key` per (member, method, route template): fingerprint, in-progress lease and claim token (only the current claim can complete or release it), stored response, expiry (an expired key counts as absent). |

Not here yet: OTP codes, lockout counters and refresh-token families (with
full sign-in, #106), the vehicle catalogue and reference data (data source
undecided), stats and the points ledger (rules undecided). Each is a new
migration that doesn't change the tables above.

Sync tokens: a walk's watermark is `pg_snapshot_xmin(pg_current_snapshot())`
(`SyncSnapshot`), and `ListTripChanges` / `ListTripDeletions`
return rows with `writer_xid` at or above it in `(writer_xid, id)` order, so
a write that hadn't committed when the token was taken is never missed.
`db.Trips` reads every sync page in one read-only `REPEATABLE READ`
transaction whose first statement, on a walk's first page, is
`SyncSnapshot`: the token's watermark and the page come from the same
snapshot (#176). Taking the watermark after the read, or in another
transaction, can lose a write that commits in between. A token is
checked against the member's purge boundary in that same transaction
(#177), so a purge committing between the check and the read is invisible
to both: the page still has the tombstones the token needs, and the next
page or walk answers `sync_window_expired`.
Transaction ids are cluster-wide, so busy neighbours only cause repeats.
The deployment identity (`db.DeploymentIdentity`, a 25-character digest)
combines the cluster's `system_identifier` from `pg_control_system()`
(`SystemIdentifier`; not read with `SYNC_IDENTITY_SOURCE=deployment_id_only`)
with `SYNC_DEPLOYMENT_ID`. Tokens and page cursors carry it; a mismatch in
either part answers `sync_window_expired` with `reason=deployment_changed`.
It is computed once at startup (`db.NewTripsWith`) and cached, so walks
don't query it. A transient error reading `system_identifier` is retried:
7 attempts of at most 2 s each with 6.3 s of backoff between them, so at
most about 20 s, each failed attempt logged as a WARN with its SQLSTATE
or error class; then the server refuses to start (closing the pool takes
at most 1 s more, even if the database hangs). `42501`
(insufficient privilege) refuses at once, with a hint to `GRANT EXECUTE ON
FUNCTION pg_control_system() TO <role>` or set
`SYNC_IDENTITY_SOURCE=deployment_id_only` on every instance. It never
falls back on its own, so all instances compute the same identity.

Checking that instances agree: every instance logs a `sync identity` line
at startup with the digest and source (never `system_identifier` or
`SYNC_DEPLOYMENT_ID`), and `server identity`, run with an instance's
environment (e.g. `docker exec <container> /server identity` or
`kubectl exec`), prints the same `identity=d1:... source=...` for deploy
checks to compare. The digest is deliberately not in the public
`GET /v1/health` body: that endpoint is anonymous, and the digest would
let anyone watch for restores and deployment changes. (It is also inside
every sync token, but only members see those.) There is no metrics
endpoint yet; when one is added, export it there as a label.

Cluster switches under a running instance (`db.ClusterGuard`, default
`cluster_and_deployment_id` only), e.g. a blue/green switchover or a
failover to another cluster behind the same address or pooler:

- **Detection bound: within `SYNC_CLUSTER_CHECK_INTERVAL` + 5 s (20 s by
  default; at most 35 s), however busy the pool is.** Every interval
  (10–30 s, default 15 s) a background check opens a fresh connection
  outside the pool, with the pool's settings, reads `system_identifier`,
  and closes it. That is what catches a switch while every pooled
  connection is busy and still reaches the old cluster (a check on those
  can't see it). Every new pool connection is also checked once
  (`AfterConnect`), so a switch seen by a reconnect is caught at once.
  There is still no per-request query. The bound assumes the check can
  connect: a failed check pushes detection to the next good one (one
  failure: up to 2 × interval + 5 s).
- **Unchecked: 3 failed checks in a row make it unhealthy.** If the
  background check fails 3 times in a row (about 45 s at the default; e.g.
  the endpoint now rejects the server's credentials, `28P01`, or is
  unreachable), the instance can't tell which cluster its pooled
  connections reach: one ERROR `CLUSTER UNCHECKED`, and `GET /v1/health`
  answers `503` so the orchestrator takes it out or restarts it (into a
  fail-fast startup if the database is still unreachable). This doesn't
  latch: requests are still served, and the next good check with the
  pinned identity makes health `200` again (INFO).
- **Writes in the detection window go to the old cluster.** Uploads,
  deletions and vehicle edits accepted before the trip are committed to
  the old cluster; clients get trips back by re-uploading after
  `deployment_changed`, but deletions and vehicle edits from that window
  are lost, so trips deleted in it may reappear.
- **Fails closed.** Another `system_identifier`, or `42501`
  (`pg_control_system()` refused, so the cluster can't be checked: e.g.
  the new cluster revokes it), on a new connection or the background
  check trips the guard for good: one ERROR `DATABASE CLUSTER CHANGED`
  (identity digests only; for 42501, the grant to restore), the pool is
  reset so connections to the old cluster close, and from then on no
  connection is even dialled (`BeforeConnect`): every database request
  answers `503 service_unavailable` (`Retry-After: 5`) and
  `GET /v1/health` answers `503`, so the orchestrator restarts the
  instance, which recomputes the identity (clients then get
  `deployment_changed`). It stays down even if the endpoint switches back,
  because it may have mixed both clusters meanwhile.
- A transient error (can't connect, timeout) never trips it: the
  background check logs a WARN with the error class and tries again next
  interval.
- Connections opened before the identity is pinned at startup weren't
  checked, so pinning resets the pool. Connections also live at most
  15 min (±10%) (`connMaxLifetime`), which bounds how long connections to
  an old cluster linger; detection doesn't depend on it.

**`deployment_id_only` can't detect any of this.** With it, change
`SYNC_DEPLOYMENT_ID` and restart every instance at once on *any* change
of the cluster behind `DATABASE_URL`: restores, switchovers, failovers to
another cluster, re-pointed poolers.

### Restoring the database

A restore can lose writes made after its restore point, and transaction
ids before and after it don't compare, so old sync tokens must not be
honoured. `system_identifier` changes with a dump/restore or a new
cluster, but not with a PITR or a physical backup restored in place, and
it may not with a Neon branch reset or restore. So, whenever you restore:

1. Set a new `SYNC_DEPLOYMENT_ID` (e.g. `prod-2026-10-25-restore1`) before
   the server takes traffic again. Never reuse an old value.
2. Restart **every instance at once** (stop all, then start), not with a
   rolling deploy: while old and new instances serve side by side, their
   tokens and cursors reject each other, so clients' walks fail with
   `deployment_changed` until the rollout ends (and restart their
   recovery each time). Check every instance logs the same new
   `sync identity`. The same applies to changing `SYNC_IDENTITY_SOURCE`.
   After the restart, every client's next sync gets `400
   sync_window_expired`, `reason=deployment_changed`: it does a full walk,
   re-creates vehicles the server lost, and re-uploads its local trips the
   walk didn't return (spec: Incremental sync, "Recovery after
   `deployment_changed`"). Deletions made after the restore point are lost
   with it, so a trip deleted on another device in that window can come
   back, and so can a trip deleted elsewhere whose tombstone was purged,
   from a device offline for longer than the retention.
3. Leave `SYNC_TOKEN_KEY` alone: rotating it makes every token `invalid`
   instead, and clients then won't re-upload.

Connection settings: `statement_timeout` and
`idle_in_transaction_session_timeout` default to 15 s (they bound how long
a session can hold the sync watermark back) unless `DATABASE_URL` sets them
as query parameters, e.g. `?sslmode=require&statement_timeout=5s` (pgx
sends unknown query parameters as session settings). On Neon, prefer the
**direct** (non-pooled) endpoint: the server keeps its own small pool, and
a transaction-mode pooler (PgBouncer) doesn't reliably keep session
settings.

`server migrate` and `MIGRATE_ON_START` use their own small pool with
`statement_timeout=0` (whatever `DATABASE_URL` says) and the usual
`lock_timeout`, so a long data migration isn't killed halfway but a
migration stuck behind a lock still fails.

Database errors: a lock or statement timeout (`55P03`, `57014`), a deadlock
or serialization failure, or a pool or query wait that outlives the request
answers `503` (`server_busy`, `Retry-After: 1`) instead of `500`; an
unreachable or read-only database (`25006 read_only_sql_transaction`, e.g.
the old side of a blue/green switchover, or a standby) answers `503`
(`service_unavailable`, `Retry-After: 5`). See `db.Classify`.

## CI

To save GitHub Actions minutes, the Backend workflow has no `pull_request` or
`push` triggers; it runs only through `workflow_dispatch`. Verify on your own
machine while you work, and run CI once per PR, just before merge:

1. Run `make ci` (or `make check`, which adds the image build) in `backend/`
   until it passes.
2. Rebase the branch onto the latest `main` and push it.
3. Start the workflow on that branch and watch it:

   ```sh
   gh workflow run backend.yml --ref <branch>
   gh run list --workflow backend.yml --branch <branch> --limit 1
   gh run watch <run-id>
   ```

4. Merge (rebase merge) only when that run is green **and** Backend Tester has
   signed off. If `main` moves before you merge, rebase and run it again.

A newer run on the same branch cancels the one in progress
(`concurrency: backend-<ref>`). The Go job times out after 15 minutes and the
spec lint after 5. Go modules, the build cache and the golangci-lint cache are
restored between runs.

## Container

```sh
docker build -t orange-box-backend:dev .
docker run --rm -p 8080:8080 -e ENV=local -e AUTH_TEST_MODE=true \
  -e AUTH_TEST_CODE="$AUTH_TEST_CODE" orange-box-backend:dev
```

Multi-stage build: a static `CGO_ENABLED=0` binary on a distroless static base
(`gcr.io/distroless/static-debian12:nonroot`), running as non-root UID 65532.
Both base images are pinned by digest. Without `ENV`/`JWT_PRIVATE_KEY` the
container refuses to start, on purpose.

## Plan

- **API contract:** `../docs/backend/openapi-v2.yaml`. Server interfaces are
  generated with `oapi-codegen` in strict server mode, with request
  validation from `kin-openapi`. Errors are RFC 9457 `problem+json`.
- **Routing:** standard library `net/http` (Go 1.22+ method and path patterns).
- **Auth:** e-mail + one-time code (`POST /v1/auth/otp`, then
  `POST /v1/auth/token` with `grantType=email_otp`). ES256 JWT access tokens
  with a `kid` for key rotation, rotating single-use refresh tokens.
- **Storage:** Postgres via `pgx` with `sqlc`-generated queries and `goose`
  migrations. Raw telemetry batches go to S3-compatible object storage, with
  an index in Postgres.
- **Background jobs:** `river` (Postgres-backed queue) for stats, rankings and
  points aggregation.
- **Ops:** `log/slog` JSON logs with redaction, `golang.org/x/time/rate` rate
  limiting.
- **Hosting:** to be decided between Koyeb's free instance and an Oracle
  Always Free ARM VM (Docker Compose + Caddy). Free tiers cannot meet the
  availability targets in the requirements.
- **No Google services** anywhere in the stack: Sign-In, Firebase, Google
  Cloud hosting, secrets or e-mail. Open-source artifacts such as the
  distroless base image are fine.
