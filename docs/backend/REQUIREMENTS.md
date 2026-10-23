# Orange Box Backend: Technical Requirements

_Draft for a backend developer, 2026-10-23. Contract: [`openapi-v2.yaml`](openapi-v2.yaml) (OpenAPI 3.1, REST, path prefix `/v1`). Legacy reference only: [`../openapi.yaml`](../openapi.yaml)._

> **Every number in this document marked "proposal" is a starting point for discussion, not a measured fact or a commitment.** Facts about the legacy server come only from what the legacy Android client code shows.

## 1. Context

Orange Box is a clean-room Kotlin + Jetpack Compose rewrite of CarooLive, an Android app that reads car data from a Bluetooth ELM327 OBD-II dongle. It shows a live driving dashboard, records trips, and reads and clears trouble codes (DTCs).

The legacy app talked to a Pokevian backend. From the client code we know:
- Two hosts on a raw IP over plain HTTP: a service server (port 9801) and a "gathering" telemetry server (port 9701). Both timed out on 2026-10-23, so the backend is gone and could not be tested.
- RPC style: almost everything was `POST /smart.json`, form-encoded, multiplexed by a `cmd` field with a JSON string in `data`. Telemetry was base64(gzip(JSON)) inside a form field.
- No real authentication. The server trusted a client-supplied e-mail (`loginId`) and `memberNo` in request bodies, and the client trusted every TLS certificate.
- Many "connected" screens (dashboards, charts, rankings, notices, terms) were server-rendered HTML in WebViews. Their content can't be recovered from the client.

This document replaces that with a modern REST API. **We are not building the server.** We're asking a backend developer to build it to this contract.

## 2. The app's optional-backend model

The app is fully usable with no server. Backend integration is a setting:

| Mode | Behaviour |
|---|---|
| **Off** (default) | No network calls. Trips, stats, DTC history and the vehicle catalogue are local (Room + bundled seed data). |
| **Fake** | In-app fake repositories return canned responses shaped by this spec. No network. Used for UI work and tests. |
| **Remote** | The app calls this API at a user-configured HTTPS base URL. Uploads are queued and synced in the background. |

Consequences for the server:
- The server is a **sync and backup target**, not the source of truth for driving data. The phone records everything first and uploads later, sometimes days later.
- **Stats, rankings and points are the only Remote-only features.** Everything else already works on the device.
- The app must keep working when the server is down, slow or misconfigured.

## 3. Scope

**In scope**
- Auth: OIDC sign-in (Google ID token), short-lived access tokens, refresh, logout.
- Member profile, and account deletion with real erasure.
- Vehicles CRUD with optimistic concurrency.
- Vehicle catalogue (makes → models → variants) and reference data (countries, regions), read-only and cacheable.
- Trip summaries: idempotent batch upsert, list, get, delete, driving-day calendar.
- Telemetry: batched samples and events with gzip, idempotent, read-back per trip.
- Diagnostics: DTC read/clear reports per vehicle.
- Remote-only: stats summary, rankings (opt-in), points balance and ledger.
- Health endpoint.

**Out of scope**
- Dashcam/video, including the legacy `video` references in telemetry messages.
- YouTube upload.
- Push notifications (legacy GCM/FingerPush).
- Facebook login and the legacy Google+ sign-in. Only OIDC ID-token verification is in scope.
- Server-rendered web pages (`/m/view.do`, `/m/chart.do`, `/web/contractDetail_*.html`), the notice board, the WebView JS bridge, and session cookies.
- Any compatibility with the legacy `/smart.json` RPC format.

## 4. API overview

All paths are under `https://{host}/v1`. Full detail is in `openapi-v2.yaml`; every operation has an `x-legacy-mapping` field.

| Resource | Methods and paths | Purpose | Legacy equivalent |
|---|---|---|---|
| Health | `GET /v1/health` | Liveness; the app tests a base URL with it | none |
| Auth | `POST /v1/auth/token`, `POST /v1/auth/logout` | Exchange an ID token or refresh token for tokens; revoke | `checkMember`, `regMember`, `/mbrLogin.do` login/logout |
| Me | `GET`, `PATCH`, `DELETE /v1/me` | Profile; account deletion | `checkMember`, `regMember`, `withdrawMember` |
| Vehicles | `GET`/`POST /v1/vehicles`; `GET`/`PATCH`/`DELETE /v1/vehicles/{id}` | Vehicle CRUD (ETag/If-Match) | `getMemberCarList`, `insertCar`, `updateCar` |
| Catalogue | `GET /v1/catalogue/makes`, `/makes/{id}/models`, `/variants`, `/variants/{id}` | Vehicle picker with filters and facets | `getCarModelList` (6-step `targetField` cascade) |
| Reference | `GET /v1/reference/countries`, `/countries/{cc}/regions` | Country and region lists | `getCodeList` (`CountryCd`, `Local<cc>`) |
| Trips | `POST /v1/trips/batch`; `GET /v1/trips`, `/v1/trips/days`, `/v1/trips/{id}`; `DELETE /v1/trips/{id}` | Idempotent trip upsert, history, calendar | `carMessage` (trip), `/m/trip.do qSelectUserDrivingTripDate` |
| Telemetry | `POST /v1/telemetry/batches`; `GET /v1/trips/{id}/samples`, `/events` | Batched samples and events, read-back | `carMessage` (location, event) |
| Diagnostics | `POST`/`GET /v1/vehicles/{id}/dtc-reports` | DTC read and clear records | DTC strings inside `carMessage` |
| Stats (Remote-only) | `GET /v1/stats/summary` | Period aggregates and score stars | `getRanking.do`, `getScoreStar.do` |
| Rankings (Remote-only) | `GET /v1/rankings/{metric}`, `/v1/rankings/{metric}/me` | Opt-in leaderboards, own position | `*RankingTop10.do`, `getBestTrip*`, `getRanking.do` |
| Points (Remote-only) | `GET /v1/me/points`, `/v1/me/points/ledger` | Balance and history | `Rank.memberPt*` fields |

## 5. Auth and security requirements

- **HTTPS only**, TLS 1.2 or newer (TLS 1.3 preferred). Refuse plain HTTP rather than redirecting it. HSTS on any web-facing host. The app disables cleartext and uses no custom TrustManagers, so certificates must chain to a public CA.
- **Sign-in (preferred model)**: the app obtains a Google ID token with Credential Manager and calls `POST /v1/auth/token` (`grantType=id_token`). The server verifies the signature against the provider JWKS, plus `iss`, `aud` (the app's OAuth client id), `exp` and `nonce`. It finds or creates the member by (`iss`, `sub`), never by e-mail.
- **Tokens (proposal)**: an access JWT (RS256 or ES256) valid for 15 minutes, with `sub` = member id and `aud` = `orange-box-api`. A refresh token valid for 30 days, single use and rotated on every refresh; reusing an old one revokes the whole token family. Store refresh tokens hashed.
- **Alternative model**: accept the provider's ID token directly as the bearer token and skip `/v1/auth/token`. It's simpler but ties sessions to roughly 1-hour ID-token lifetimes. This is open question 1.
- **Identity comes from the token only.** Never accept a member id in a body or query string for authorisation. Every resource is scoped to the token's member; another member's resource returns `404`.
- **Least data**: legacy phone number, mobile carrier, sex, birth date, IP and weather are not collected. The OBD adapter's Bluetooth MAC stays on the phone. VIN and plate number are optional and treated as personal data.
- **Rankings privacy**: off by default (`rankingOptIn=false`). Leaderboards show only `displayName`, an optional region and a make/model label, never e-mail, VIN or plate. Legacy ranking DTOs carried other members' `loginId` and e-mail; that must not happen here.
- **Abuse controls**: rate limits per member and per IP, request size limits, and input validation from the JSON Schemas in the spec. Trips marked `simulated=true` are excluded from rankings and points.
- **Secrets**: no secrets in the app. The server holds its signing keys in a KMS or secret manager and rotates them, publishing its JWKS if clients ever need it.

## 6. Data model summary

| Entity | Key fields | Notes |
|---|---|---|
| Member | `id`, OIDC (`iss`, `sub`), `displayName`, `email` (from token), `countryCode`, `regionCode`, `rankingOptIn` | Legacy `SmartMember`, minimised |
| Vehicle | `id`, `clientVehicleId`, `make`, `model`, `fuelType`, `engineDisplacementCc`, `modelYear`, `variantId?`, `vin?`, `plateNumber?`, `odometerKm`, `idleStopGo`, `obdAdapter{elmVersion, obdProtocol}`, `archived`, `updatedAt`, ETag | Legacy `SmartCar`; MAC dropped |
| Catalogue | Make → Model (`bodyType`, year range) → Variant (`fuelType`, `engineDisplacementCc`, `releaseYear`, `officialFuelEconomyKmPerL`) | Legacy `SmartCarModel`; the data source is open question 5 |
| Reference | Country (ISO 3166-1), Region (ISO 3166-2) | Legacy `SmartCode` |
| Trip | `clientTripId` (UUID), `vehicleId`, `state` (`in_progress`/`completed`), `startedAt`, `endedAt`, `snapshotAt`, distance, duration, speeds, harsh-event counts, fuel, time in idle/eco/overspeed/fuel-cut, `speedBands[]`, scores, battery and coolant stats, `dtc{stored,pending,permanent}`, `simulated` | Legacy `SmartTrip`, with unit-bearing names |
| Sample | `t`, `lat`, `lon`, `speedKmh`, `rpm`, `mafGps`, `mapKpa`, `intakeAirC`, `coolantC`, `engineLoadPct`, `throttlePct`, `fuelLevelPct`, `batteryV`, `mil`, `accel{x,y,z}`, `pids{}` | Legacy `SmartRecord`; time-series volume |
| Event | `t`, `type` (harsh driving, warnings, MIL, impact, ...), `active`, `lat`, `lon`, `dtc?`, `value?` | Legacy `SmartEvent` / `EventTpCd` |
| DtcReport | `clientReportId`, `vehicleId`, `kind` (`read`/`cleared`), `readAt`, `mil`, code sets, `vin?` | New first-class record |
| Stats / Ranking / Points | Derived server-side from trips; points ledger entries | Remote-only. The legacy scoring and points rules aren't visible in the client |

Conventions: timestamps in UTC ISO-8601 with `Z`; metric units in field names; server ids are opaque (UUIDv7 suggested); clients generate UUIDs for de-duplication.

## 7. Telemetry ingestion expectations

- **Flow**: while driving, the app records samples and events locally (Room). In Remote mode a WorkManager job uploads trip snapshots (`POST /v1/trips/batch`) and telemetry batches (`POST /v1/telemetry/batches`). Uploads need network connectivity, not a live drive.
- **Order independence**: a trip summary may arrive before or after its telemetry, and batches may arrive out of order or days late (offline backlog). The server must link everything by `clientTripId` and must not assume order.
- **Trip snapshots**: the legacy app re-uploaded a trip summary periodically during a drive (once it passed 1 km) and again at the end. v1 keeps that: upsert by `clientTripId`, ignore snapshots older than the stored `snapshotAt`, and treat a `completed` trip as immutable except for deletion.
- **Idempotency**: every upload carries an `Idempotency-Key` header, plus `clientBatchId` or `clientReportId` in the body. The same key and body returns the original response; the same key with a different body returns `422`; a retry while the original is still processing returns `409`. Keep keys for at least 24 hours (proposal; 7 days would better cover long offline backlogs). Samples are also de-duplicated by (`clientTripId`, `t`).
- **Batch limits (proposal)**: up to 5,000 samples and 500 events per telemetry batch, up to 100 trips per trip batch, and at most 1 MiB compressed or 8 MiB uncompressed per request. Anything larger returns `413`. `Content-Encoding: gzip` must be accepted; other encodings return `415`.
- **Volume (estimate)**: at about 1 sample per second, a 1-hour drive is about 3,600 samples, roughly 0.9 MB of JSON before gzip and well under 1 MB after. The app sends one or more batches per drive.
- **Acknowledgement**: `202` means durably stored. Aggregation (stats, rankings, points) may lag (proposal: under 5 minutes).
- **Client retry policy (app side, proposal)**: exponential backoff with jitter starting at 30 s and capped at 1 h. The app honours `Retry-After` on `429`/`503`, stops retrying on other `4xx` except `408`/`409`, keeps a backlog of up to 30 days or 100 MB, and uploads oldest first.
- **Offline backlog sync**: after a long offline period the app may send many batches back to back. Rate limits should allow a backlog drain (proposal: at least 60 upload requests per minute per member).

## 8. Non-functional requirements (all targets are proposals)

| Area | Requirement |
|---|---|
| Transport | HTTPS only, TLS 1.2+ (1.3 preferred), HSTS, modern cipher suites |
| Latency | p95 under 300 ms for reads and profile/vehicle writes; p95 under 500 ms to acknowledge an upload batch of 1 MiB or less, measured at the server edge |
| Availability | 99.5% monthly for auth and upload endpoints; Remote-only features (stats, rankings, points) may degrade first |
| Durability | An acknowledged upload (`200`/`202`) is never lost: replicated storage plus daily backups |
| Scalability | Initial sizing for 10k members and 1k concurrent drivers; horizontally scalable stateless API |
| Retention | Raw samples 12 months; trip summaries and DTC reports until the user deletes them or their account; request logs 30 days; idempotency keys 24 h–7 d |
| Privacy and deletion | `DELETE /v1/me` revokes tokens immediately and erases all member data within 30 days, backups included as they rotate. Provide a data export on request (open question). Follow GDPR-style principles: data minimisation, purpose limitation and documented processors. Check India's DPDP Act if hosting or users are in India |
| Logging | Structured logs with `X-Request-Id`, member id (or a hash of it), route, status and latency. Never log tokens, ID tokens, e-mail, VIN, plate, precise location or request bodies |
| Observability | Metrics for rate, errors and latency per route; upload backlog and aggregation lag; alerting on 5xx rate and auth failures |
| Rate limiting | `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` on responses, `Retry-After` on `429`/`503`. Starting values: 120 req/min per member overall and 60 uploads/min per member |
| Caching | Catalogue and reference data: `ETag` plus `Cache-Control: public, max-age=86400`; `If-None-Match` → `304` |
| Compatibility | Additive changes only within `/v1`; clients ignore unknown fields and map unknown enum values to `other` |

## 9. Error format

Every error is `application/problem+json` (RFC 9457):

```json
{
  "type": "https://api.example.invalid/problems/validation",
  "title": "Validation failed",
  "status": 422,
  "detail": "2 fields are invalid",
  "instance": "/v1/vehicles",
  "code": "validation_failed",
  "requestId": "0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a10",
  "errors": [{ "pointer": "/vin", "detail": "must be 17 characters" }]
}
```

Status codes used: `400` malformed or expired cursor, `401` missing or expired token (with `WWW-Authenticate`), `403` not allowed, `404` missing or not owned, `409` idempotent request in flight, `412` stale `If-Match`, `413` too large, `415` bad media type or encoding, `422` validation or idempotency-key reuse, `428` `If-Match` missing, `429` rate limited, `503` maintenance. Problem bodies contain no PII, tokens or stack traces. `code` values are stable and documented.

## 10. Environments and base URL configuration

| Environment | Purpose | Who provides it |
|---|---|---|
| Local | Developer machine, e.g. Docker Compose | Backend developer |
| Sandbox | Stable HTTPS host with seeded data, test accounts and a way to mint test tokens; resettable | Backend developer (requested) |
| Production | Real users | Backend developer / operator |

How the app finds the server:
- `BackendConfig { mode, baseUrl }` is stored on the device and edited in **Settings → Server (advanced)**. The base URL must be `https://`; anything else is rejected. The app appends `/v1/...`.
- The app calls `GET /v1/health` to test a URL before switching to Remote mode.
- Debug builds accept a launch extra `backend_mode=off|fake|remote` for testing. Release builds ignore it.
- There is no hard-coded production URL in the app until one exists. The URL is configuration.

## 11. Contract testing

- `openapi-v2.yaml` is the contract. The app's **Fake mode** and its MockWebServer contract tests are built from the spec's schemas and examples, so drift shows up in the app's CI.
- The spec is validated with `openapi-spec-validator` and `redocly lint` (both pass on this draft). We suggest adding the same check to the backend's CI.
- **Requested from the backend developer**: a sandbox environment (section 10) with test accounts and a test-token path that works from GitHub Actions, published API docs generated from the spec, and contract tests on the server side (e.g. Schemathesis or Dredd against the spec).
- Propose changes as pull requests to `docs/backend/openapi-v2.yaml` in `neo-the-on3/orange-box`, so app and server agree before code changes.

## 12. Versioning policy

- The major version is in the path (`/v1`). Within v1, changes are additive only: new endpoints, optional fields and enum values.
- Breaking changes go to `/v2`. `/v1` stays available for at least 12 months after `/v2` is GA (proposal), signalled with `Deprecation` and `Sunset` headers.
- `info.version` in the spec follows SemVer. The app records the spec version it was built against.

## 13. Open questions for the backend developer

1. **Auth model**: token exchange (`/v1/auth/token` with our own JWTs and refresh tokens) or direct OIDC ID-token bearer verification? Which providers besides Google, if any?
2. **App sign-in**: the v1 app has no sign-in. Remote mode needs a sign-in flow added in M9. Confirm Google via Credential Manager is acceptable.
3. **Hosting and data residency**: where will data live (region, cloud)? Does GDPR or India's DPDP Act apply? Who is the data controller?
4. **Raw telemetry**: store every sample (time-series DB?) or only trip summaries plus downsampled tracks? This drives cost and the retention proposal.
5. **Vehicle catalogue source**: the legacy catalogue (about 700 Korean-market models) lived on the dead server. What licensed or open data source will back `/v1/catalogue`, and for which markets?
6. **Scores and points**: compute scores server-side from telemetry, or accept the client's `ecoScore`/`safetyScore`? What are the points earning, expiry and redemption rules? Redemption isn't specified.
7. **Location precision**: keep full-precision GPS in samples, or coarsen or trim the start and end of trips to protect home and work locations?
8. **Limits**: are the proposed batch sizes, rate limits and idempotency-key retention (24 h vs 7 d) workable?
9. **Multi-device**: the same account on two phones. Is last-writer-wins plus ETag enough for vehicles? Should trips sync down to a second device?
10. **Data export**: add `GET /v1/me/export` (async) for portability requests in v1, or handle it manually?
11. **Fuel cost**: `StatsSummary.fuelCost` needs a fuel price and currency source. Keep, defer or drop?
12. **Operations**: who runs production, what are the on-call and SLO expectations, and what budget?

## 14. References

- `docs/backend/openapi-v2.yaml`: this contract (OpenAPI 3.1)
- `docs/openapi.yaml`: legacy reverse-documented contract (reference only)
- `docs/FEASIBILITY.md` §3: legacy backend summary
- `docs/PLAN.md` §2: the app's optional-backend architecture
- GitHub issues: #63 (M9 optional backend), #77 (OpenAPI spec tracking)
