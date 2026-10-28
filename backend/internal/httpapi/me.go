package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"unicode"
	"unicode/utf8"

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
	if req.Params.IfMatch == nil || strings.TrimSpace(*req.Params.IfMatch) == "" {
		return nil, problem.New(http.StatusPreconditionRequired, problem.CodePreconditionReq,
			"If-Match with the profile's ETag is required.")
	}
	header, patch := *req.Params.IfMatch, *req.Body
	if patch.DisplayName != nil {
		name, ok := normaliseDisplayName(*patch.DisplayName)
		if !ok {
			return nil, profileInvalid(problem.FieldError{Pointer: "/displayName", Detail: displayNameRule})
		}
		patch.DisplayName = &name
	}
	apply := func(f store.ProfileFields) store.ProfileFields {
		setIf(&f.DisplayName, patch.DisplayName)
		mergeNullable(&f.CountryCode, patch.CountryCode)
		mergeNullable(&f.RegionCode, patch.RegionCode)
		if patch.RankingOptIn != nil {
			f.RankingOptIn = *patch.RankingOptIn
		}
		return f
	}
	p, err := s.profiles.UpdateProfile(ctx, member, func(cur store.Profile) error {
		if !ifMatch(header, profileETag(cur.Version)) {
			return problem.New(http.StatusPreconditionFailed, problem.CodeEtagMismatch,
				"The profile has changed since If-Match's ETag; fetch it again.")
		}
		// Checked on the result of the patch, under the row lock.
		if fe := checkRegion(apply(cur.ProfileFields), patch.RegionCode.IsSpecified()); fe != nil {
			return profileInvalid(*fe)
		}
		return nil
	}, apply)
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
// objects of #179) is a background job that doesn't exist yet (#218); see
// db.Members.DeleteAccount for what it must do about writes that land
// after this commits and about stored idempotency responses.
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

func profileInvalid(f problem.FieldError) *problem.Problem {
	return problem.New(http.StatusUnprocessableEntity, problem.CodeValidationFailed, "1 field is invalid").
		WithTitle("Validation failed").WithErrors(f)
}

const displayNameRule = "must not be blank or contain control, format or invisible characters " +
	"(e.g. bidi overrides, zero-width spaces, blank fillers) or line separators"

// normaliseDisplayName trims surrounding white space and rejects names other
// members could be misled by (they are shown on leaderboards): blank ones,
// and ones with control (Cc) or format (Cf) characters, such as bidi
// overrides and zero-width spaces, line and paragraph separators (Zl, Zp),
// or characters that render as nothing (Other_Default_Ignorable_Code_Point,
// e.g. the Hangul fillers and U+034F, and U+2800 BRAILLE PATTERN BLANK).
// Three format characters are allowed where writing needs them:
//   - U+200D (zero-width joiner) between two other characters, where it
//     joins emoji sequences;
//   - U+200C (zero-width non-joiner) after a letter or mark and before a
//     letter, as in Persian and Indic spellings;
//   - tag characters in an emoji tag sequence (a subdivision flag such as
//     England's): U+1F3F4, then tag digits or lower-case tag letters, then
//     U+E007F CANCEL TAG (UTS #51).
//
// The schema has already checked the length (1–40 characters) of the value
// as sent.
func normaliseDisplayName(s string) (string, bool) {
	s = strings.TrimFunc(s, unicode.IsSpace)
	if s == "" || !utf8.ValidString(s) {
		return "", false
	}
	runes := []rune(s)
	for i := 0; i < len(runes); i++ {
		r := runes[i]
		switch {
		case r == zeroWidthJoiner:
			if i == 0 || i == len(runes)-1 || !joinable(runes[i-1]) || !joinable(runes[i+1]) {
				return "", false
			}
		case r == zeroWidthNonJoiner:
			if i == 0 || i == len(runes)-1 || !unicode.In(runes[i-1], unicode.L, unicode.M) || !unicode.IsLetter(runes[i+1]) {
				return "", false
			}
		case r == blackFlag:
			i += tagSequence(runes[i+1:])
		case unicode.In(r, unicode.Cc, unicode.Cf, unicode.Zl, unicode.Zp, unicode.Other_Default_Ignorable_Code_Point),
			r == brailleBlank:
			return "", false
		}
	}
	return s, true
}

const (
	zeroWidthNonJoiner = '\u200c'
	zeroWidthJoiner    = '\u200d'
	brailleBlank       = '\u2800'
	blackFlag          = '\U0001F3F4'
	cancelTag          = '\U000E007F'
)

// joinable reports whether r may stand next to a zero-width joiner.
func joinable(r rune) bool {
	return r != zeroWidthJoiner && r != zeroWidthNonJoiner && !unicode.IsSpace(r)
}

// tagSequence returns the length of the emoji tag sequence that rs starts
// with (tag digits or lower-case tag letters, then CANCEL TAG), or 0. A
// black flag without one is an ordinary emoji; stray tag characters are
// rejected as format characters.
func tagSequence(rs []rune) int {
	for n, r := range rs {
		switch {
		case r == cancelTag:
			if n == 0 {
				return 0
			}
			return n + 1
		case r >= '\U000E0030' && r <= '\U000E0039', r >= '\U000E0061' && r <= '\U000E007A':
		default:
			return 0
		}
	}
	return 0
}

// checkRegion checks the profile a patch would produce: a regionCode (its
// ISO 3166-2 format is a schema pattern) needs a countryCode equal to its
// country part. The pointer names the field the patch got wrong: regionCode
// when the patch set it, otherwise the countryCode it changed.
func checkRegion(f store.ProfileFields, regionPatched bool) *problem.FieldError {
	if f.RegionCode == nil {
		return nil
	}
	country, _, _ := strings.Cut(*f.RegionCode, "-")
	if f.CountryCode != nil && *f.CountryCode == country {
		return nil
	}
	if regionPatched {
		return &problem.FieldError{Pointer: "/regionCode", Detail: "must be a subdivision of countryCode (e.g. IN-KA for IN)"}
	}
	return &problem.FieldError{Pointer: "/countryCode", Detail: "must match the country of regionCode " + *f.RegionCode + "; change or clear regionCode too"}
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
