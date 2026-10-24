# Orange Box backend

Go REST API for the Orange Box Android app. The app works fully offline; this
service is an optional sync and backup target.

This folder is its own Go module (`github.com/neo-the-on3/orange-box/backend`)
inside the Orange Box monorepo. It has its own CI workflow
(`.github/workflows/backend.yml`) that runs only when `backend/**`,
`docs/backend/**` or that workflow file changes.

## Status

Scaffold only: a `net/http` server with `GET /v1/health`. The real API comes
next (see [Plan](#plan)).

## Requirements

- Go 1.27 or newer
- `golangci-lint` v2 and `govulncheck` for `make lint` / `make vuln`
  (`go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@latest`,
  `go install golang.org/x/vuln/cmd/govulncheck@latest`)
- Docker, for the container image
- Node.js and `pip install openapi-spec-validator`, for `make spec-lint`

## Run locally

```sh
cd backend
make run                       # listens on :8080
curl -s localhost:8080/v1/health
# {"status":"ok"}
```

The server logs JSON to stdout and shuts down gracefully on SIGINT/SIGTERM.

### Environment

| Variable       | Default | Used by                     |
| -------------- | ------- | --------------------------- |
| `PORT`         | `8080`  | HTTP listener (used today)  |
| `DATABASE_URL` | none    | Postgres DSN (planned)      |

Planned variables, not read yet, will be documented here as they land
(for example `DATABASE_URL=postgres://orange:orange@localhost:5432/orange?sslmode=disable`,
object storage credentials for telemetry batches, the JWT signing key and the
e-mail provider for OTP codes). Keep them in a local `.env`, which is
git-ignored; never commit secrets.

## Make targets

| Target           | What it does                                           |
| ---------------- | ------------------------------------------------------ |
| `make build`     | Static binary in `bin/server` (`CGO_ENABLED=0`)        |
| `make run`       | `go run ./cmd/server`                                  |
| `make test`      | `go test -race ./...`                                  |
| `make vet`       | `go vet ./...`                                         |
| `make lint`      | `golangci-lint run` (config in `.golangci.yml`)        |
| `make vuln`      | `govulncheck ./...`                                    |
| `make spec-lint` | Redocly + openapi-spec-validator on the v2 spec        |
| `make generate`  | Placeholder for oapi-codegen and sqlc                  |
| `make docker`    | `docker build -t orange-box-backend:dev .`             |
| `make check`     | vet, test, lint, vuln and build in one go              |

## Container

```sh
docker build -t orange-box-backend:dev .
docker run --rm -p 8080:8080 orange-box-backend:dev
```

Multi-stage build: a static `CGO_ENABLED=0` binary on a distroless static base
(`gcr.io/distroless/static-debian12:nonroot`), running as non-root UID 65532.

## Plan

- **API contract:** `../docs/backend/openapi-v2.yaml` (arrives with PR #81).
  Server interfaces will be generated with `oapi-codegen` in strict server
  mode, with request validation from `kin-openapi`. Errors are RFC 9457
  `problem+json`.
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
