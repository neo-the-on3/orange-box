package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/api"
	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/objstore"
	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
	"github.com/neo-the-on3/orange-box/backend/internal/synctoken"
)

// Lifetimes reported to the app. Refresh tokens arrive with #106.
const refreshTokenTTL = 30 * 24 * time.Hour

// retryAfterEmailUnavailable is sent with the 503 for non-test addresses
// until e-mailed codes exist (#106).
const retryAfterEmailUnavailable = 3600

// Server implements the strict server. Operations it doesn't override fall
// through to api.Unimplemented and answer 501.
type Server struct {
	api.Unimplemented

	apiVersion string
	tokens     *auth.Tokens
	members    auth.MemberStore
	codes      *auth.PendingCodes
	testMode   bool
	testCode   string
	now        func() time.Time
	ready      func(context.Context) error
	logger     *slog.Logger
	vehicles   store.Vehicles   // nil without a database
	trips      store.Trips      // nil without a database
	telemetry  store.Telemetry  // nil without a database
	objects    objstore.Store   // nil without object storage
	dtcReports store.DtcReports // nil without a database
	sync       *synctoken.Codec
}

// readyTimeout bounds the readiness check in GET /v1/health.
const readyTimeout = 2 * time.Second

// retryAfterUnavailable is sent with the health 503.
const retryAfterUnavailable = 5

var _ api.StrictServerInterface = (*Server)(nil)

// GetHealth implements GET /v1/health.
func (s *Server) GetHealth(ctx context.Context, _ api.GetHealthRequestObject) (api.GetHealthResponseObject, error) {
	id := reqctx.RequestID(ctx)
	if s.ready != nil {
		rctx, cancel := context.WithTimeout(ctx, readyTimeout)
		err := s.ready(rctx)
		cancel()
		if err != nil {
			s.logger.WarnContext(ctx, "readiness check failed", slog.Any("err", err))
			return nil, problem.New(http.StatusServiceUnavailable, problem.CodeUnavailable,
				"A backing service is unavailable; retry later.").
				WithTitle("Service unavailable").WithRetryAfter(retryAfterUnavailable)
		}
	}
	return api.GetHealth200JSONResponse{
		Body: api.Health{
			Status:     api.HealthStatusOk,
			ApiVersion: s.apiVersion,
			Time:       s.now().UTC().Truncate(time.Second),
		},
		Headers: api.GetHealth200ResponseHeaders{XRequestId: &id},
	}, nil
}

// RequestOtp implements POST /v1/auth/otp. Until #106 only test-mode
// addresses can sign in; every other address gets a 503 that reveals nothing
// about membership.
func (s *Server) RequestOtp(ctx context.Context, req api.RequestOtpRequestObject) (api.RequestOtpResponseObject, error) {
	email := auth.NormaliseEmail(string(req.Body.Email))
	if auth.IsTestAddress(email) {
		if !s.testMode {
			return nil, testAddressRejected()
		}
		wait := s.codes.Put(email, s.testCode) // no e-mail is sent for the reserved domain
		id, noStore, resend := reqctx.RequestID(ctx), "no-store", int(math.Ceil(wait.Seconds()))
		return api.RequestOtp202JSONResponse{
			Body:    api.OtpResponse{ExpiresIn: int(auth.OtpTTL.Seconds()), ResendAfter: &resend},
			Headers: api.RequestOtp202ResponseHeaders{CacheControl: &noStore, XRequestId: &id},
		}, nil
	}
	return nil, problem.New(http.StatusServiceUnavailable, problem.CodeEmailUnavailable,
		"E-mail sign-in is not available on this server yet.").
		WithTitle("E-mail sign-in unavailable").WithRetryAfter(retryAfterEmailUnavailable)
}

// CreateToken implements POST /v1/auth/token (email_otp grant only; the
// refresh_token grant arrives with #106).
func (s *Server) CreateToken(ctx context.Context, req api.CreateTokenRequestObject) (api.CreateTokenResponseObject, error) {
	grant, err := req.Body.ValueByDiscriminator()
	if err != nil {
		return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "Unknown grantType.")
	}
	switch g := grant.(type) {
	case api.EmailOtpGrant:
		return s.emailOtpGrant(ctx, g)
	case api.RefreshGrant:
		return nil, problem.New(http.StatusNotImplemented, problem.CodeNotImplemented,
			"Refresh tokens are not supported yet; sign in again with a code.")
	default:
		return nil, problem.New(http.StatusBadRequest, problem.CodeBadRequest, "Unknown grantType.")
	}
}

func (s *Server) emailOtpGrant(ctx context.Context, g api.EmailOtpGrant) (api.CreateTokenResponseObject, error) {
	email := auth.NormaliseEmail(string(g.Email))
	if auth.IsTestAddress(email) && !s.testMode {
		return nil, testAddressRejected()
	}
	// Outside the test domain no code can be pending until #106, which is
	// exactly the spec's "no pending code" case: invalid_grant.
	result, retry := s.codes.Verify(email, g.Code)
	switch result {
	case auth.VerifyOK:
	case auth.VerifyAttemptsExceeded:
		return nil, problem.New(http.StatusBadRequest, problem.CodeOtpAttemptsExceeded, "Request a new code.").
			WithTitle("Too many attempts")
	case auth.VerifyLocked:
		return nil, problem.New(http.StatusBadRequest, problem.CodeOtpLocked,
			"Too many failed codes. Try again later.").
			WithTitle("Sign-in temporarily locked").WithRetryAfter(int(math.Ceil(retry.Seconds())))
	default:
		return nil, problem.New(http.StatusBadRequest, problem.CodeInvalidGrant,
			"The code or refresh token is invalid or expired.").WithTitle("Invalid grant")
	}

	member, created, err := s.members.FindOrCreateByEmail(ctx, email)
	if err != nil {
		return nil, fmt.Errorf("find or create member: %w", err)
	}
	access, ttl, err := s.tokens.Issue(member.ID)
	if err != nil {
		return nil, err
	}
	refresh, err := placeholderRefreshToken()
	if err != nil {
		return nil, err
	}
	id, noStore := reqctx.RequestID(ctx), "no-store"
	return api.CreateToken200JSONResponse{
		Body: api.TokenResponse{
			AccessToken:      access,
			TokenType:        api.TokenResponseTokenTypeBearer,
			ExpiresIn:        int(ttl.Seconds()),
			RefreshToken:     refresh,
			RefreshExpiresIn: int(refreshTokenTTL.Seconds()),
			MemberId:         member.ID,
			IsNewMember:      created,
		},
		Headers: api.CreateToken200ResponseHeaders{CacheControl: &noStore, XRequestId: &id},
	}, nil
}

// placeholderRefreshToken returns a random opaque token so responses match
// the schema. It is not stored and can't be redeemed until #106.
func placeholderRefreshToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "rt_" + base64.RawURLEncoding.EncodeToString(b), nil
}

func testAddressRejected() *problem.Problem {
	return problem.New(http.StatusUnprocessableEntity, problem.CodeTestAddressRejected,
		"Addresses in the reserved test domain are only accepted when test mode is on.").
		WithTitle("Test address not allowed").
		WithErrors(problem.FieldError{Pointer: "/email", Detail: "reserved test domain"})
}
