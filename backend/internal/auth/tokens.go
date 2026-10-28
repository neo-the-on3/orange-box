// Package auth holds sign-in for the Orange Box API: access tokens, the
// member store interface and the test-mode one-time codes.
//
// Until #106 lands, only test-mode sign-in exists (Kiran's decision: full
// auth comes last). Access tokens are already the real format, so nothing
// that consumes them changes when #106 adds e-mailed codes and refresh tokens.
package auth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// Token parameters fixed by openapi-v2.yaml (bearerAuth) and REQUIREMENTS.md §5.
const (
	Audience       = "orange-box-api"
	AccessTokenTTL = 15 * time.Minute
	clockLeeway    = 30 * time.Second
)

// Errors returned by Verify. They never contain the token.
var (
	ErrTokenExpired = errors.New("access token expired")
	ErrTokenInvalid = errors.New("access token invalid")
)

// Claims are the access JWT claims.
type Claims struct {
	jwt.RegisteredClaims
}

// Tokens issues and verifies ES256 access JWTs. Verification looks the key up
// by the kid header, so keys can be rotated by adding the next key before
// signing with it.
type Tokens struct {
	issuer  string
	kid     string
	signKey *ecdsa.PrivateKey
	verify  map[string]*ecdsa.PublicKey
	now     func() time.Time
}

// NewTokens returns a Tokens that signs with key under kid.
func NewTokens(issuer, kid string, key *ecdsa.PrivateKey) (*Tokens, error) {
	if key == nil || key.Curve != elliptic.P256() {
		return nil, errors.New("JWT signing key must be an ECDSA P-256 key")
	}
	if kid == "" || issuer == "" {
		return nil, errors.New("JWT key id and issuer are required")
	}
	return &Tokens{
		issuer:  issuer,
		kid:     kid,
		signKey: key,
		verify:  map[string]*ecdsa.PublicKey{kid: &key.PublicKey},
		now:     time.Now,
	}, nil
}

// AddVerificationKey trusts tokens signed by pub under kid (key rotation).
func (t *Tokens) AddVerificationKey(kid string, pub *ecdsa.PublicKey) {
	t.verify[kid] = pub
}

// KeyID returns the kid new tokens are signed with.
func (t *Tokens) KeyID() string { return t.kid }

// Issue returns a signed access token for memberID and its lifetime.
func (t *Tokens) Issue(memberID string) (string, time.Duration, error) {
	now := t.now()
	claims := Claims{RegisteredClaims: jwt.RegisteredClaims{
		Issuer:    t.issuer,
		Subject:   memberID,
		Audience:  jwt.ClaimStrings{Audience},
		IssuedAt:  jwt.NewNumericDate(now),
		NotBefore: jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(now.Add(AccessTokenTTL)),
		ID:        uuid.NewString(),
	}}
	tok := jwt.NewWithClaims(jwt.SigningMethodES256, claims)
	tok.Header["kid"] = t.kid
	s, err := tok.SignedString(t.signKey)
	if err != nil {
		return "", 0, fmt.Errorf("sign access token: %w", err)
	}
	return s, AccessTokenTTL, nil
}

// Verify checks signature, kid, alg, iss, aud, exp, nbf and sub, and returns
// the member id (sub).
func (t *Tokens) Verify(raw string) (string, error) {
	member, _, err := t.VerifyIssued(raw)
	return member, err
}

// VerifyIssued is Verify that also returns the token's issue time (iat,
// whole seconds, UTC), so a caller can reject tokens issued before a
// member's tokensValidAfter (DELETE /v1/me).
func (t *Tokens) VerifyIssued(raw string) (string, time.Time, error) {
	var claims Claims
	_, err := jwt.ParseWithClaims(raw, &claims, func(tok *jwt.Token) (any, error) {
		kid, _ := tok.Header["kid"].(string)
		key, ok := t.verify[kid]
		if !ok {
			return nil, ErrTokenInvalid
		}
		return key, nil
	},
		jwt.WithValidMethods([]string{jwt.SigningMethodES256.Alg()}),
		jwt.WithIssuer(t.issuer),
		jwt.WithAudience(Audience),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(clockLeeway),
		jwt.WithTimeFunc(t.now),
	)
	switch {
	case errors.Is(err, jwt.ErrTokenExpired):
		return "", time.Time{}, ErrTokenExpired
	case err != nil:
		return "", time.Time{}, ErrTokenInvalid
	}
	if _, err := uuid.Parse(claims.Subject); err != nil {
		return "", time.Time{}, ErrTokenInvalid
	}
	if claims.IssuedAt == nil { // the server always sets it
		return "", time.Time{}, ErrTokenInvalid
	}
	return claims.Subject, claims.IssuedAt.UTC(), nil
}

// ParsePrivateKeyPEM parses a P-256 private key in PKCS#8 or SEC 1 PEM.
func ParsePrivateKeyPEM(data string) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(data))
	if block == nil {
		return nil, errors.New("JWT_PRIVATE_KEY is not PEM")
	}
	if k, err := x509.ParseECPrivateKey(block.Bytes); err == nil {
		return k, nil
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, errors.New("JWT_PRIVATE_KEY is not a PKCS#8 or SEC 1 EC private key")
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, errors.New("JWT_PRIVATE_KEY must be an ECDSA P-256 key")
	}
	return ec, nil
}

// GenerateDevKey returns a fresh P-256 key and a kid derived from its public
// key. Tokens signed with it stop verifying when the process restarts.
func GenerateDevKey() (*ecdsa.PrivateKey, string, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, "", err
	}
	der, err := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if err != nil {
		return nil, "", err
	}
	sum := sha256.Sum256(der)
	return k, "dev-" + base64.RawURLEncoding.EncodeToString(sum[:8]), nil
}
