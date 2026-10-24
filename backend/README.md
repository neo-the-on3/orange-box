# Orange Box backend

Go REST API for the Orange Box Android app. The app works fully offline; this
service is an optional sync and backup target.

This folder is its own Go module (`github.com/neo-the-on3/orange-box/backend`)
inside the Orange Box monorepo. It has its own CI workflow
(`.github/workflows/backend.yml`) that runs only when `backend/**`,
`docs/backend/**` or that workflow file changes.

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
- Every other operation answers `501` (`code=not_implemented`) after routing,
  auth and validation, so clients and contract tests see the real shapes.

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
it restarts. Members are kept in memory until the database lands (#105).

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
| `SHUTDOWN_TIMEOUT` | `8s`         | Graceful shutdown budget (inside Docker's 10 s stop grace). |
| `LOG_LEVEL`        | `info`       | `debug`, `info`, `warn` or `error`. |
| `DATABASE_URL`     | none         | Postgres DSN (planned, #105). |

Keep local values in a `.env` (git-ignored); never commit secrets. To make a
signing key: `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256`.

## Layout

| Path                     | What |
| ------------------------ | ---- |
| `cmd/server`             | `main`: config, listener, graceful shutdown |
| `internal/api`           | **Generated** strict server + models (`api.gen.go`) and 501 stubs (`stubs.gen.go`) |
| `internal/httpapi`       | Router, middleware, OpenAPI validation, handlers |
| `internal/auth`          | ES256 access tokens, member store interface, test-mode codes |
| `internal/config`        | Environment config (fails closed) |
| `internal/problem`       | RFC 9457 `application/problem+json` errors |
| `internal/logging`       | slog JSON logger with redaction |
| `internal/timeutil`      | `UtcInstant` (0–9 fractional digits in, truncated to ms, 3 digits out) |
| `oapi-codegen.yaml`, `oapi-overlay.yaml` | Code generation config; the overlay adds Go-only hints without touching the spec |

### Request pipeline

request id → access log → panic recovery → default headers → timeout →
OpenAPI middleware (route lookup with 404/405, `Content-Encoding` and body
limits, bearer auth, kin-openapi validation) → generated strict handlers.

- `X-Request-Id`: a client UUID is echoed, otherwise a UUIDv7 is generated.
  Every problem carries it as `requestId`.
- `Content-Encoding: gzip` is accepted only on the three batch uploads
  (1 MiB compressed / 8 MiB inflated, `413` beyond); anything else is `415`
  (`unsupported_encoding`). Other bodies are limited to 64 KiB.
- Validation errors are `422` (`validation_failed`) with JSON pointers; bad
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
| `make generate`       | Regenerate `internal/api` from the spec                |
| `make generate-check` | Fail if the committed generated code is stale          |
| `make docker`         | `docker build -t orange-box-backend:dev .`             |
| `make check`          | generate-check, vet, test, lint, vuln, build and docker |

After changing the spec, run `make generate` and commit the result; CI fails
if `internal/api` is stale.

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
