package auth

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"
)

// Member is the part of a member that sign-in needs.
type Member struct {
	ID        string
	Email     string
	CreatedAt time.Time
}

// MemberStore finds members by their normalised e-mail address. The
// Postgres implementation arrives with #105; MemoryMembers stands in until
// then.
type MemberStore interface {
	// FindOrCreateByEmail returns the live member with this normalised
	// address, creating one if none exists. created reports a new member.
	FindOrCreateByEmail(ctx context.Context, email string) (m Member, created bool, err error)
}

// MemoryMembers is an in-memory MemberStore. Members are lost on restart.
type MemoryMembers struct {
	mu      sync.Mutex
	byEmail map[string]Member
}

// NewMemoryMembers returns an empty in-memory store.
func NewMemoryMembers() *MemoryMembers {
	return &MemoryMembers{byEmail: map[string]Member{}}
}

// FindOrCreateByEmail implements MemberStore.
func (s *MemoryMembers) FindOrCreateByEmail(_ context.Context, email string) (Member, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if m, ok := s.byEmail[email]; ok {
		return m, false, nil
	}
	id, err := uuid.NewV7()
	if err != nil {
		return Member{}, false, err
	}
	m := Member{ID: id.String(), Email: email, CreatedAt: time.Now().UTC()}
	s.byEmail[email] = m
	return m, true, nil
}
