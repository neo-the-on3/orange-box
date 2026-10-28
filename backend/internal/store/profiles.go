package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// ErasureWindow is how long after DELETE /v1/me the member's data must be
// erased (openapi-v2.yaml, deleteMe: "within 30 days", proposal).
const ErasureWindow = 30 * 24 * time.Hour

// Profile is a live member's profile (Profile in the spec).
type Profile struct {
	ID           uuid.UUID
	Email        string
	AuthProvider string
	ProfileFields
	// Version is bumped by every change; the ETag is derived from it.
	Version   int64
	CreatedAt time.Time
	UpdatedAt time.Time
}

// ProfileFields are the writable fields (ProfilePatch in the spec). A nil
// pointer is NULL: DisplayName is NULL until the member first sets one.
type ProfileFields struct {
	DisplayName  *string
	CountryCode  *string
	RegionCode   *string
	RankingOptIn bool
}

// Profiles stores the members' profiles and account deletion. Every method
// is scoped to one member, and only a live member (not deleted) is read or
// changed: a deleted or erased one is ErrNotFound.
type Profiles interface {
	// GetProfile returns the live member's profile.
	GetProfile(ctx context.Context, member uuid.UUID) (Profile, error)
	// UpdateProfile locks the live member, calls check (an error, e.g. a
	// stale If-Match, is returned as is), applies patch and stores the
	// result with a new version, unless patch changed nothing.
	UpdateProfile(ctx context.Context, member uuid.UUID, check func(Profile) error, patch func(ProfileFields) ProfileFields) (Profile, error)
	// DeleteAccount marks the live member deleted in one statement: the
	// e-mail address is detached, every token is revoked and erasure is
	// scheduled. It returns when the deletion was requested.
	DeleteAccount(ctx context.Context, member uuid.UUID) (requestedAt time.Time, err error)
	// TokenRevoked reports whether an access token for member issued at
	// issuedAt must be rejected: the member is deleted or erased, or the
	// token is older than the member's tokensValidAfter.
	TokenRevoked(ctx context.Context, member uuid.UUID, issuedAt time.Time) (bool, error)
}
