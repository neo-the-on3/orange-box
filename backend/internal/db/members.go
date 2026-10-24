package db

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
)

// Members is the Postgres auth.MemberStore.
type Members struct {
	q *dbgen.Queries
}

var _ auth.MemberStore = (*Members)(nil)

// NewMembers returns a member store on pool.
func NewMembers(pool *pgxpool.Pool) *Members {
	return &Members{q: dbgen.New(pool)}
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
