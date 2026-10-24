package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

func newTokens(t *testing.T) *Tokens {
	t.Helper()
	key, kid, err := GenerateDevKey()
	if err != nil {
		t.Fatal(err)
	}
	tk, err := NewTokens("orange-box", kid, key)
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func TestTokenRoundTrip(t *testing.T) {
	t.Parallel()
	tk := newTokens(t)
	member := uuid.NewString()
	raw, ttl, err := tk.Issue(member)
	if err != nil {
		t.Fatal(err)
	}
	if ttl != 15*time.Minute {
		t.Errorf("ttl = %v", ttl)
	}
	got, err := tk.Verify(raw)
	if err != nil || got != member {
		t.Fatalf("Verify = %q, %v; want %q", got, err, member)
	}

	// Header and claims match the spec: ES256, kid, aud, iss, sub, exp.
	parsed, _, err := jwt.NewParser().ParseUnverified(raw, &Claims{})
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Header["alg"] != "ES256" || parsed.Header["kid"] != tk.KeyID() {
		t.Errorf("header = %v", parsed.Header)
	}
	c := parsed.Claims.(*Claims)
	if c.Issuer != "orange-box" || c.Subject != member || len(c.Audience) != 1 || c.Audience[0] != Audience ||
		c.ExpiresAt.Sub(c.IssuedAt.Time) != AccessTokenTTL || c.ID == "" {
		t.Errorf("claims = %+v", c)
	}
}

func TestTokenRejections(t *testing.T) {
	t.Parallel()
	tk := newTokens(t)
	member := uuid.NewString()
	good, _, _ := tk.Issue(member)

	other := newTokens(t)
	otherTok, _, _ := other.Issue(member)

	sign := func(method jwt.SigningMethod, key any, kid string, claims jwt.Claims) string {
		tok := jwt.NewWithClaims(method, claims)
		if kid != "" {
			tok.Header["kid"] = kid
		}
		s, err := tok.SignedString(key)
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	base := func() jwt.RegisteredClaims {
		now := time.Now()
		return jwt.RegisteredClaims{
			Issuer: "orange-box", Subject: member, Audience: jwt.ClaimStrings{Audience},
			IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(time.Minute)),
		}
	}
	withClaims := func(f func(*jwt.RegisteredClaims)) jwt.Claims {
		c := base()
		f(&c)
		return c
	}

	cases := map[string]struct {
		raw  string
		want error
	}{
		"empty":         {"", ErrTokenInvalid},
		"garbage":       {"not.a.jwt", ErrTokenInvalid},
		"tampered":      {good[:len(good)-4] + "AAAA", ErrTokenInvalid},
		"other key":     {otherTok, ErrTokenInvalid},
		"no kid":        {sign(jwt.SigningMethodES256, tk.signKey, "", base()), ErrTokenInvalid},
		"unknown kid":   {sign(jwt.SigningMethodES256, tk.signKey, "nope", base()), ErrTokenInvalid},
		"alg none":      {sign(jwt.SigningMethodNone, jwt.UnsafeAllowNoneSignatureType, tk.KeyID(), base()), ErrTokenInvalid},
		"alg HS256":     {sign(jwt.SigningMethodHS256, []byte("secret"), tk.KeyID(), base()), ErrTokenInvalid},
		"wrong aud":     {sign(jwt.SigningMethodES256, tk.signKey, tk.KeyID(), withClaims(func(c *jwt.RegisteredClaims) { c.Audience = jwt.ClaimStrings{"x"} })), ErrTokenInvalid},
		"wrong iss":     {sign(jwt.SigningMethodES256, tk.signKey, tk.KeyID(), withClaims(func(c *jwt.RegisteredClaims) { c.Issuer = "x" })), ErrTokenInvalid},
		"no exp":        {sign(jwt.SigningMethodES256, tk.signKey, tk.KeyID(), withClaims(func(c *jwt.RegisteredClaims) { c.ExpiresAt = nil })), ErrTokenInvalid},
		"sub not uuid":  {sign(jwt.SigningMethodES256, tk.signKey, tk.KeyID(), withClaims(func(c *jwt.RegisteredClaims) { c.Subject = "admin" })), ErrTokenInvalid},
		"future nbf":    {sign(jwt.SigningMethodES256, tk.signKey, tk.KeyID(), withClaims(func(c *jwt.RegisteredClaims) { c.NotBefore = jwt.NewNumericDate(time.Now().Add(time.Hour)) })), ErrTokenInvalid},
		"expired":       {sign(jwt.SigningMethodES256, tk.signKey, tk.KeyID(), withClaims(func(c *jwt.RegisteredClaims) { c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-time.Hour)) })), ErrTokenExpired},
		"bearer prefix": {"Bearer " + good, ErrTokenInvalid},
	}
	for name, tc := range cases {
		if _, err := tk.Verify(tc.raw); err != tc.want { //nolint:errorlint // sentinel identity is the contract
			t.Errorf("%s: err = %v, want %v", name, err, tc.want)
		}
	}
}

func TestKeyRotation(t *testing.T) {
	t.Parallel()
	old := newTokens(t)
	tok, _, _ := old.Issue(uuid.NewString())
	next := newTokens(t)
	if _, err := next.Verify(tok); err == nil {
		t.Fatal("token from untrusted key verified")
	}
	next.AddVerificationKey(old.KeyID(), &old.signKey.PublicKey)
	if _, err := next.Verify(tok); err != nil {
		t.Fatalf("rotated key: %v", err)
	}
}

func TestParsePrivateKeyPEM(t *testing.T) {
	t.Parallel()
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	pkcs8, _ := x509.MarshalPKCS8PrivateKey(k)
	sec1, _ := x509.MarshalECPrivateKey(k)
	for name, p := range map[string]*pem.Block{
		"pkcs8": {Type: "PRIVATE KEY", Bytes: pkcs8},
		"sec1":  {Type: "EC PRIVATE KEY", Bytes: sec1},
	} {
		got, err := ParsePrivateKeyPEM(string(pem.EncodeToMemory(p)))
		if err != nil || !got.Equal(k) {
			t.Errorf("%s: %v", name, err)
		}
	}
	p384, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(p384)
	for name, in := range map[string]string{
		"not pem": "hello",
		"p384":    string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})),
		"junk":    string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: []byte("x")})),
	} {
		if _, err := ParsePrivateKeyPEM(in); err == nil {
			t.Errorf("%s: parsed, want error", name)
		}
	}
	if _, err := NewTokens("i", "k", p384); err == nil {
		t.Error("NewTokens accepted a P-384 key")
	}
}

func TestPendingCodes(t *testing.T) {
	t.Parallel()
	now := time.Unix(1_800_000_000, 0)
	p := NewPendingCodes()
	p.now = func() time.Time { return now }
	const email, code = "a@test.orangebox.invalid", "482916"

	if got := p.Verify(email, code); got != VerifyInvalid {
		t.Errorf("no pending code: %v", got)
	}
	p.Put(email, code)
	if got := p.Verify(email, code); got != VerifyOK {
		t.Errorf("correct code: %v", got)
	}
	if got := p.Verify(email, code); got != VerifyInvalid {
		t.Errorf("code reused: %v", got)
	}

	p.Put(email, code)
	for i := 1; i < OtpMaxAttempts; i++ {
		if got := p.Verify(email, "000001"); got != VerifyInvalid {
			t.Errorf("wrong attempt %d: %v", i, got)
		}
	}
	if got := p.Verify(email, "000001"); got != VerifyAttemptsExceeded {
		t.Errorf("5th wrong attempt: %v", got)
	}
	if got := p.Verify(email, code); got != VerifyAttemptsExceeded {
		t.Errorf("correct code after exhaustion: %v", got)
	}
	p.Put(email, code) // a new code resets
	if got := p.Verify(email, code); got != VerifyOK {
		t.Errorf("new code: %v", got)
	}

	p.Put(email, code)
	now = now.Add(OtpTTL)
	if got := p.Verify(email, code); got != VerifyInvalid {
		t.Errorf("expired code: %v", got)
	}
}

func TestEmailHelpers(t *testing.T) {
	t.Parallel()
	if NormaliseEmail("  Driver@Test.OrangeBox.Invalid ") != "driver@test.orangebox.invalid" {
		t.Error("NormaliseEmail")
	}
	for in, want := range map[string]bool{
		"a@test.orangebox.invalid":   true,
		"@test.orangebox.invalid":    false,
		"a@xtest.orangebox.invalid":  false,
		"a@test.orangebox.invalid.x": false,
		"a@example.com":              false,
	} {
		if IsTestAddress(in) != want {
			t.Errorf("IsTestAddress(%q) != %v", in, want)
		}
	}
}

func TestMemoryMembers(t *testing.T) {
	t.Parallel()
	s := NewMemoryMembers()
	ctx := context.Background()
	m1, created, err := s.FindOrCreateByEmail(ctx, "a@test.orangebox.invalid")
	if err != nil || !created {
		t.Fatalf("first: %v %v", created, err)
	}
	m2, created, _ := s.FindOrCreateByEmail(ctx, "a@test.orangebox.invalid")
	if created || m2.ID != m1.ID {
		t.Errorf("second: created=%v id=%s want %s", created, m2.ID, m1.ID)
	}
	m3, _, _ := s.FindOrCreateByEmail(ctx, "b@test.orangebox.invalid")
	if m3.ID == m1.ID || !strings.Contains(m3.ID, "-") {
		t.Errorf("third id %q", m3.ID)
	}
}
