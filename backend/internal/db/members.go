package db

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Members is the Postgres auth.MemberStore.
type Members struct {
	pool *pgxpool.Pool
	q    *dbgen.Queries
}

var (
	_ auth.MemberStore = (*Members)(nil)
	_ store.Profiles   = (*Members)(nil)
)

// NewMembers returns a member store on pool.
func NewMembers(pool *pgxpool.Pool) *Members {
	return &Members{pool: pool, q: dbgen.New(pool)}
}

// FindOrCreateByEmail implements auth.MemberStore. email must already be
// normalised (auth.NormaliseEmail). Concurrent first sign-ins with the same
// address create one member: the loser of the insert race reads the winner.
func (s *Members) FindOrCreateByEmail(ctx context.Context, email string) (auth.Member, bool, error) {
	if email == "" {
		return auth.Member{}, false, errors.New("members: empty e-mail address")
	}
	// Two attempts cover the only retryable race: the live member that
	// blocked our insert was deleted (address detached) before we read it.
	for range 2 {
		id, err := uuid.NewV7()
		if err != nil {
			return auth.Member{}, false, err
		}
		row, err := s.q.InsertMember(ctx, dbgen.InsertMemberParams{ID: id, Email: email})
		if err == nil {
			return toAuthMember(row), true, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return auth.Member{}, false, err
		}
		row, err = s.q.GetMemberByEmail(ctx, email)
		if err == nil {
			return toAuthMember(row), false, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return auth.Member{}, false, err
		}
	}
	return auth.Member{}, false, errors.New("members: address changed hands during sign-in; retry")
}

func toAuthMember(m dbgen.Member) auth.Member {
	var email string
	if m.Email != nil {
		email = *m.Email
	}
	return auth.Member{ID: m.ID.String(), Email: email, CreatedAt: m.CreatedAt.UTC()}
}

// GetProfile implements store.Profiles.
func (s *Members) GetProfile(ctx context.Context, member uuid.UUID) (store.Profile, error) {
	row, err := s.q.GetMember(ctx, member)
	if errors.Is(err, pgx.ErrNoRows) {
		return store.Profile{}, store.ErrNotFound
	}
	if err != nil {
		return store.Profile{}, err
	}
	return toProfile(row), nil
}

// UpdateProfile implements store.Profiles. The row is locked for the whole
// read-check-write, so concurrent patches (and DELETE /v1/me) take turns:
// each check sees the version it writes over and no patch is lost.
func (s *Members) UpdateProfile(ctx context.Context, member uuid.UUID, check func(store.Profile) error, patch func(store.ProfileFields) store.ProfileFields) (store.Profile, error) {
	var out store.Profile
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		q := s.q.WithTx(tx)
		row, err := q.GetMemberForUpdate(ctx, member)
		if errors.Is(err, pgx.ErrNoRows) {
			return store.ErrNotFound
		}
		if err != nil {
			return err
		}
		cur := toProfile(row)
		if err := check(cur); err != nil {
			return err
		}
		next := patch(cur.ProfileFields)
		if equalPtr(next.DisplayName, cur.DisplayName) && equalPtr(next.CountryCode, cur.CountryCode) &&
			equalPtr(next.RegionCode, cur.RegionCode) && next.RankingOptIn == cur.RankingOptIn {
			out = cur // nothing changed: same version, same ETag
			return nil
		}
		if next.DisplayName == nil && cur.DisplayName != nil {
			return errors.New("members: displayName can't be cleared")
		}
		updated, err := q.UpdateMemberProfile(ctx, dbgen.UpdateMemberProfileParams{
			SetDisplayName: true, DisplayName: next.DisplayName,
			SetCountryCode: true, CountryCode: next.CountryCode,
			SetRegionCode: true, RegionCode: next.RegionCode,
			RankingOptIn: &next.RankingOptIn,
			ID:           member, ExpectedVersion: cur.Version,
		})
		if err != nil {
			// No row can't happen under the lock; report it rather than retry.
			return fmt.Errorf("update profile: %w", err)
		}
		out = toProfile(updated)
		return nil
	})
	if err != nil {
		return store.Profile{}, err
	}
	return out, nil
}

// DeleteAccount implements store.Profiles with MarkMemberDeleted: the
// address is detached, tokens_valid_after and deleted_at are set, and the
// row waits for the erasure job (ListMembersPendingErasure, EraseMember).
// Refresh-token families don't exist yet (#106); revoking them belongs in
// this transaction once they do.
func (s *Members) DeleteAccount(ctx context.Context, member uuid.UUID) (time.Time, error) {
	row, err := s.q.MarkMemberDeleted(ctx, member)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, store.ErrNotFound
	}
	if err != nil {
		return time.Time{}, err
	}
	if row.DeletedAt == nil {
		return time.Time{}, errors.New("members: deleted member without deleted_at")
	}
	return row.DeletedAt.UTC(), nil
}

// TokenRevoked implements store.Profiles. An erased member has no row, a
// deleted one has deleted_at; either way no token is valid. Otherwise a
// token issued before tokens_valid_after is rejected. JWT iat has whole
// seconds, so a token from the cut-off's own second counts as older.
func (s *Members) TokenRevoked(ctx context.Context, member uuid.UUID, issuedAt time.Time) (bool, error) {
	st, err := s.q.GetMemberAuthState(ctx, member)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, err
	}
	if st.DeletedAt != nil {
		return true, nil
	}
	return st.TokensValidAfter != nil && issuedAt.Before(*st.TokensValidAfter), nil
}

func toProfile(m dbgen.Member) store.Profile {
	var email string
	if m.Email != nil {
		email = *m.Email
	}
	return store.Profile{
		ID: m.ID, Email: email, AuthProvider: m.AuthProvider,
		ProfileFields: store.ProfileFields{
			DisplayName: m.DisplayName, CountryCode: m.CountryCode, RegionCode: m.RegionCode, RankingOptIn: m.RankingOptIn,
		},
		Version: m.Version, CreatedAt: m.CreatedAt.UTC(), UpdatedAt: m.UpdatedAt.UTC(),
	}
}

func equalPtr[T comparable](a, b *T) bool {
	return (a == nil) == (b == nil) && (a == nil || *a == *b)
}
