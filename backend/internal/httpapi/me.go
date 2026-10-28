package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Profile operations (#107): GET/PATCH/DELETE /v1/me. They need Postgres;
// without DATABASE_URL (only allowed in local, ci and sandbox) they answer
// 501. Sign-in stays test-mode only until #106.

func profilesUnavailable() *problem.Problem {
	return problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
		"Profiles need a database; this server runs without DATABASE_URL.")
}

// memberGone answers a request whose member was deleted after the auth
// middleware let it through (a concurrent DELETE /v1/me): its token is
// revoked now.
func memberGone() *problem.Problem {
	return problem.New(http.StatusUnauthorized, problem.CodeInvalidToken, "The access token has been revoked; sign in again.").
		WithHeader("WWW-Authenticate", `Bearer realm="orange-box", error="invalid_token", error_description="revoked"`)
}

func profileETag(version int64) string { return vehicleETag(version) }

// defaultDisplayName is shown until the member sets a displayName (the
// spec requires one; a new member has none). It is derived from a hash of
// the member id, so it is stable, reveals neither the e-mail address nor
// the id, and is never stored.
func defaultDisplayName(id uuid.UUID) string {
	sum := sha256.Sum256(id[:])
	return "Driver " + hex.EncodeToString(sum[:3])
}

// GetMe implements GET /v1/me.
func (s *Server) GetMe(ctx context.Context, _ api.GetMeRequestObject) (api.GetMeResponseObject, error) {
	if s.profiles == nil {
		return nil, profilesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	p, err := s.profiles.GetProfile(ctx, member)
	if errors.Is(err, store.ErrNotFound) {
		return nil, memberGone()
	}
	if err != nil {
		return nil, err
	}
	etag := profileETag(p.Version)
	return api.GetMe200JSONResponse{Body: toAPIProfile(p), Headers: api.GetMe200ResponseHeaders{ETag: &etag}}, nil
}

// UpdateMe implements PATCH /v1/me (JSON merge patch): an absent field is
// kept, null clears countryCode or regionCode. If-Match is required (428)
// and must match (412).
func (s *Server) UpdateMe(ctx context.Context, req api.UpdateMeRequestObject) (api.UpdateMeResponseObject, error) {
	if s.profiles == nil {
		return nil, profilesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	if req.Params.IfMatch == nil || *req.Params.IfMatch == "" {
		return nil, preconditionRequired()
	}
	header, patch := *req.Params.IfMatch, *req.Body
	p, err := s.profiles.UpdateProfile(ctx, member, func(cur store.Profile) error {
		if !ifMatch(header, profileETag(cur.Version)) {
			return problem.New(http.StatusPreconditionFailed, problem.CodeEtagMismatch,
				"The profile has changed since If-Match's ETag; fetch it again.")
		}
		return nil
	}, func(f store.ProfileFields) store.ProfileFields {
		setIf(&f.DisplayName, patch.DisplayName)
		mergeNullable(&f.CountryCode, patch.CountryCode)
		mergeNullable(&f.RegionCode, patch.RegionCode)
		if patch.RankingOptIn != nil {
			f.RankingOptIn = *patch.RankingOptIn
		}
		return f
	})
	if errors.Is(err, store.ErrNotFound) {
		return nil, memberGone()
	}
	if err != nil {
		return nil, err
	}
	etag := profileETag(p.Version)
	return api.UpdateMe200JSONResponse{Body: toAPIProfile(p), Headers: api.UpdateMe200ResponseHeaders{ETag: &etag}}, nil
}

// DeleteMe implements DELETE /v1/me: it revokes every token at once,
// detaches the e-mail address and schedules erasure, all in one statement.
// The erasure itself (ListMembersPendingErasure, EraseMember, and the R2
// objects of #179) is a background job that doesn't exist yet; see #107.
func (s *Server) DeleteMe(ctx context.Context, _ api.DeleteMeRequestObject) (api.DeleteMeResponseObject, error) {
	if s.profiles == nil {
		return nil, profilesUnavailable()
	}
	member, err := callerID(ctx)
	if err != nil {
		return nil, err
	}
	at, err := s.profiles.DeleteAccount(ctx, member)
	if errors.Is(err, store.ErrNotFound) {
		return nil, memberGone()
	}
	if err != nil {
		return nil, err
	}
	return api.DeleteMe202JSONResponse{
		Status:      api.AccountDeletionStatusScheduled,
		RequestedAt: at,
		CompleteBy:  at.Add(store.ErasureWindow),
	}, nil
}

func toAPIProfile(p store.Profile) api.Profile {
	id, email, provider := p.ID.String(), openapi_types.Email(p.Email), api.ProfileAuthProvider(p.AuthProvider)
	created, updated, optIn := p.CreatedAt.UTC(), p.UpdatedAt.UTC(), p.RankingOptIn
	name := defaultDisplayName(p.ID)
	if p.DisplayName != nil {
		name = *p.DisplayName
	}
	return api.Profile{
		Id:           &id,
		Email:        &email,
		DisplayName:  name,
		CountryCode:  api.NullableFrom(p.CountryCode),
		RegionCode:   api.NullableFrom(p.RegionCode),
		RankingOptIn: &optIn,
		AuthProvider: &provider,
		CreatedAt:    &created,
		UpdatedAt:    &updated,
	}
}
