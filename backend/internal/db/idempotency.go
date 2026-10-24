package db

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Idempotency is the Postgres store.Idempotency.
type Idempotency struct {
	q *dbgen.Queries
}

var _ store.Idempotency = (*Idempotency)(nil)

// NewIdempotency returns an Idempotency-Key store on pool.
func NewIdempotency(pool *pgxpool.Pool) *Idempotency {
	return &Idempotency{q: dbgen.New(pool)}
}

// Claim implements store.Idempotency.
func (s *Idempotency) Claim(ctx context.Context, sc store.IdempotencyScope, fingerprint []byte, token uuid.UUID, lockedUntil, expiresAt time.Time) (bool, error) {
	_, err := s.q.ClaimIdempotencyKey(ctx, dbgen.ClaimIdempotencyKeyParams{
		MemberID: sc.MemberID, Method: sc.Method, Route: sc.Route, Key: sc.Key,
		Fingerprint: fingerprint, ClaimToken: token, LockedUntil: lockedUntil, ExpiresAt: expiresAt,
	})
	return rowResult(err)
}

// Get implements store.Idempotency.
func (s *Idempotency) Get(ctx context.Context, sc store.IdempotencyScope) (store.IdempotencyRecord, error) {
	row, err := s.q.GetIdempotencyKey(ctx, dbgen.GetIdempotencyKeyParams{MemberID: sc.MemberID, Method: sc.Method, Route: sc.Route, Key: sc.Key})
	if errors.Is(err, pgx.ErrNoRows) {
		return store.IdempotencyRecord{}, store.ErrNotFound
	}
	if err != nil {
		return store.IdempotencyRecord{}, err
	}
	rec := store.IdempotencyRecord{Fingerprint: row.Fingerprint, Completed: row.State == "completed", Body: row.ResponseBody}
	if row.ResponseStatus != nil {
		rec.Status = int(*row.ResponseStatus)
	}
	if len(row.ResponseHeaders) > 0 {
		if err := json.Unmarshal(row.ResponseHeaders, &rec.Headers); err != nil {
			return store.IdempotencyRecord{}, fmt.Errorf("idempotency: stored headers: %w", err)
		}
	}
	return rec, nil
}

// Reclaim implements store.Idempotency.
func (s *Idempotency) Reclaim(ctx context.Context, sc store.IdempotencyScope, fingerprint []byte, token uuid.UUID, lockedUntil time.Time) (bool, error) {
	_, err := s.q.ReclaimIdempotencyKey(ctx, dbgen.ReclaimIdempotencyKeyParams{
		MemberID: sc.MemberID, Method: sc.Method, Route: sc.Route, Key: sc.Key,
		Fingerprint: fingerprint, ClaimToken: token, LockedUntil: lockedUntil,
	})
	return rowResult(err)
}

// Complete implements store.Idempotency.
func (s *Idempotency) Complete(ctx context.Context, sc store.IdempotencyScope, token uuid.UUID, status int, headers map[string]string, body []byte) (bool, error) {
	if status < 100 || status > math.MaxInt16 {
		return false, fmt.Errorf("idempotency: status %d out of range", status)
	}
	h, err := json.Marshal(headers)
	if err != nil {
		return false, err
	}
	if body == nil {
		body = []byte{}
	}
	n, err := s.q.CompleteIdempotencyKey(ctx, dbgen.CompleteIdempotencyKeyParams{
		MemberID: sc.MemberID, Method: sc.Method, Route: sc.Route, Key: sc.Key, ClaimToken: token,
		ResponseStatus: int16(status), ResponseHeaders: h, ResponseBody: body,
	})
	return n == 1, err
}

// Release implements store.Idempotency.
func (s *Idempotency) Release(ctx context.Context, sc store.IdempotencyScope, token uuid.UUID) (bool, error) {
	n, err := s.q.ReleaseIdempotencyKey(ctx, dbgen.ReleaseIdempotencyKeyParams{
		MemberID: sc.MemberID, Method: sc.Method, Route: sc.Route, Key: sc.Key, ClaimToken: token,
	})
	return n == 1, err
}

// rowResult maps a :one query that returns no row on conflict to ok=false.
func rowResult(err error) (bool, error) {
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}
