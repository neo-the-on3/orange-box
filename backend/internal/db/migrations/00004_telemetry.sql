-- Telemetry (POST /v1/telemetry/batches). Raw batches (samples and events as
-- uploaded) go to object storage (Cloudflare R2, #110); Postgres keeps one
-- metadata row per batch for idempotency, read-back planning and retention,
-- and the events themselves, which are small and de-duplicated on insert.
--
-- Telemetry is keyed by (member_id, client_trip_id), not by a trip foreign
-- key: batches may arrive before their trip summary.

-- +goose Up
CREATE TABLE telemetry_batches (
    id               uuid        PRIMARY KEY,
    member_id        uuid        NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    client_batch_id  uuid        NOT NULL,
    client_trip_id   uuid        NOT NULL,
    vehicle_id       uuid        NOT NULL,
    -- Object key of the raw batch; NULL until object storage lands (#110).
    object_key       text,
    -- Counts returned on a duplicate upload (TelemetryBatchAck).
    accepted_samples integer     NOT NULL,
    accepted_events  integer     NOT NULL,
    dropped_samples  integer     NOT NULL DEFAULT 0,
    dropped_events   integer     NOT NULL DEFAULT 0,
    first_sample_t   timestamptz,
    last_sample_t    timestamptz,
    -- Decompressed body size, for quotas and retention reporting.
    body_bytes       integer     NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT telemetry_batches_member_client_key UNIQUE (member_id, client_batch_id),
    CONSTRAINT telemetry_batches_counts CHECK (
        accepted_samples >= 0 AND accepted_events >= 0 AND dropped_samples >= 0 AND dropped_events >= 0)
);

-- Read-back of a trip's samples and the trip delete.
CREATE INDEX telemetry_batches_trip_idx ON telemetry_batches (member_id, client_trip_id, first_sample_t);
-- Raw-sample retention (12 months, proposal).
CREATE INDEX telemetry_batches_created_idx ON telemetry_batches (created_at);

CREATE TABLE trip_events (
    id              bigint           GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id       uuid             NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    client_trip_id  uuid             NOT NULL,
    -- The batch that first delivered the event (no FK: batches and events
    -- are deleted together with the trip).
    batch_id        uuid,
    client_event_id uuid,
    -- Truncated to milliseconds by the server before insert.
    t               timestamptz      NOT NULL,
    type            text             NOT NULL,
    -- An absent `active` is stored as true (the API default).
    active          boolean          NOT NULL DEFAULT true,
    lat             double precision,
    lon             double precision,
    -- NULL means "no code" (absent or null in the upload).
    dtc             text,
    value           double precision,
    created_at      timestamptz      NOT NULL DEFAULT now()
);

-- De-duplication: an event is a duplicate if it matches on clientEventId OR
-- on the fallback key (clientTripId, t, type, active, dtc). Both unique
-- indexes always apply, so INSERT ... ON CONFLICT DO NOTHING drops either.
CREATE UNIQUE INDEX trip_events_client_id_key ON trip_events (member_id, client_trip_id, client_event_id)
    WHERE client_event_id IS NOT NULL;
-- NULLS NOT DISTINCT so two events without a code compare equal. This index
-- also serves read-back in (t, id) order.
CREATE UNIQUE INDEX trip_events_fallback_key ON trip_events (member_id, client_trip_id, t, type, active, dtc)
    NULLS NOT DISTINCT;

-- +goose Down
DROP TABLE trip_events;
DROP TABLE telemetry_batches;
