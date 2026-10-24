package auth

import (
	"crypto/sha256"
	"crypto/subtle"
	"strings"
	"sync"
	"time"
)

// One-time code parameters from openapi-v2.yaml (POST /v1/auth/otp).
const (
	OtpTTL         = 10 * time.Minute
	OtpResendAfter = 60 * time.Second
	OtpMaxAttempts = 5
	// TestDomain is the reserved test-mode domain (RFC 2606 .invalid).
	TestDomain = "@test.orangebox.invalid"
)

// NormaliseEmail trims and lower-cases an address, as the spec requires.
func NormaliseEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

// IsTestAddress reports whether a normalised address is in the reserved test domain.
func IsTestAddress(email string) bool {
	return strings.HasSuffix(email, TestDomain) && len(email) > len(TestDomain)
}

// VerifyResult is the outcome of checking a code.
type VerifyResult int

// Verify outcomes.
const (
	// VerifyOK means the code matched and has been consumed.
	VerifyOK VerifyResult = iota
	// VerifyInvalid covers a wrong or expired code and no pending code.
	VerifyInvalid
	// VerifyAttemptsExceeded means the code has been invalidated after too many wrong attempts.
	VerifyAttemptsExceeded
)

type pendingCode struct {
	hash      [32]byte
	expiresAt time.Time
	attempts  int
	exhausted bool
}

// PendingCodes holds issued one-time codes in memory, hashed, keyed by a hash
// of the normalised address. Today only test-mode codes go in here; #106
// replaces it with a Postgres-backed store with per-address lockouts.
type PendingCodes struct {
	mu    sync.Mutex
	codes map[[32]byte]*pendingCode
	now   func() time.Time
}

// NewPendingCodes returns an empty store.
func NewPendingCodes() *PendingCodes {
	return &PendingCodes{codes: map[[32]byte]*pendingCode{}, now: time.Now}
}

// Put stores code for email, replacing any earlier code.
func (p *PendingCodes) Put(email, code string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	p.sweep(now)
	p.codes[sha256.Sum256([]byte(email))] = &pendingCode{
		hash:      sha256.Sum256([]byte(code)),
		expiresAt: now.Add(OtpTTL),
	}
}

// Verify checks code for email in constant time. A match consumes the code;
// the OtpMaxAttempts-th wrong attempt invalidates it.
func (p *PendingCodes) Verify(email, code string) VerifyResult {
	p.mu.Lock()
	defer p.mu.Unlock()
	key := sha256.Sum256([]byte(email))
	pc, ok := p.codes[key]
	switch {
	case !ok:
		return VerifyInvalid
	case pc.exhausted:
		return VerifyAttemptsExceeded
	case !p.now().Before(pc.expiresAt):
		delete(p.codes, key)
		return VerifyInvalid
	}
	got := sha256.Sum256([]byte(code))
	if subtle.ConstantTimeCompare(got[:], pc.hash[:]) == 1 {
		delete(p.codes, key)
		return VerifyOK
	}
	pc.attempts++
	if pc.attempts >= OtpMaxAttempts {
		pc.exhausted = true
		return VerifyAttemptsExceeded
	}
	return VerifyInvalid
}

// sweep drops expired codes so the map can't grow without bound.
func (p *PendingCodes) sweep(now time.Time) {
	for k, pc := range p.codes {
		if !now.Before(pc.expiresAt) {
			delete(p.codes, k)
		}
	}
}
