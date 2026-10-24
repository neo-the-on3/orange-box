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

// Test-mode guessing limits. Every test address accepts the same static
// code, so besides the per-address limit there is a process-wide cap on
// failed verifications; otherwise spreading guesses over many addresses
// would brute-force the shared code. #106 replaces this with the spec's
// per-address and per-IP lockouts.
const (
	// OtpFailureWindow is how long failed attempts count against an address.
	// Requesting a new code does not reset them.
	OtpFailureWindow = OtpTTL
	// GlobalFailureCap is the most failed verifications allowed per
	// GlobalFailureWindow across all addresses.
	GlobalFailureCap    = 100
	GlobalFailureWindow = 10 * time.Minute
	sweepInterval       = time.Minute
)

// VerifyResult is the outcome of checking a code.
type VerifyResult int

// Verify outcomes.
const (
	// VerifyOK means the code matched and has been consumed.
	VerifyOK VerifyResult = iota
	// VerifyInvalid covers a wrong or expired code and no pending code.
	VerifyInvalid
	// VerifyAttemptsExceeded means the address used up its attempts; a new
	// code doesn't help until OtpFailureWindow has passed.
	VerifyAttemptsExceeded
	// VerifyLocked means the process-wide failure cap was hit; the retry
	// duration says when verification reopens.
	VerifyLocked
)

type addressState struct {
	hasCode   bool
	hash      [32]byte
	sentAt    time.Time
	expiresAt time.Time

	failures    int
	failuresEnd time.Time // when the current failure window closes
}

// PendingCodes holds issued one-time codes in memory, hashed, keyed by a hash
// of the normalised address, with per-address attempt counters and a
// process-wide failure cap. Today only test-mode codes go in here; #106
// replaces it with a Postgres-backed store.
type PendingCodes struct {
	mu        sync.Mutex
	addrs     map[[32]byte]*addressState
	now       func() time.Time
	lastSweep time.Time

	globalFailures int
	globalEnd      time.Time
}

// NewPendingCodes returns an empty store.
func NewPendingCodes() *PendingCodes {
	return &PendingCodes{addrs: map[[32]byte]*addressState{}, now: time.Now}
}

// Put issues code for email unless a code was sent less than
// OtpResendAfter ago, in which case the pending code stays as it is. Failed
// attempts are kept either way. It returns how long until the app may offer
// to resend.
func (p *PendingCodes) Put(email, code string) (resendAfter time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	p.maybeSweep(now)
	key := sha256.Sum256([]byte(email))
	st, ok := p.addrs[key]
	if !ok {
		st = &addressState{}
		p.addrs[key] = st
	}
	if st.hasCode && now.Before(st.expiresAt) && now.Sub(st.sentAt) < OtpResendAfter {
		return OtpResendAfter - now.Sub(st.sentAt)
	}
	st.hasCode = true
	st.hash = sha256.Sum256([]byte(code))
	st.sentAt = now
	st.expiresAt = now.Add(OtpTTL)
	return OtpResendAfter
}

// Verify checks code for email in constant time. A match consumes the code
// and clears the address's failures. retry is set for VerifyLocked.
func (p *PendingCodes) Verify(email, code string) (res VerifyResult, retry time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	if p.globalFailures >= GlobalFailureCap && now.Before(p.globalEnd) {
		return VerifyLocked, p.globalEnd.Sub(now)
	}
	st, ok := p.addrs[sha256.Sum256([]byte(email))]
	if !ok || !st.hasCode {
		return VerifyInvalid, 0 // nothing pending: can't succeed, so not counted
	}
	if !now.Before(st.failuresEnd) {
		st.failures = 0
	}
	if st.failures >= OtpMaxAttempts {
		return VerifyAttemptsExceeded, 0
	}
	got := sha256.Sum256([]byte(code))
	if now.Before(st.expiresAt) && subtle.ConstantTimeCompare(got[:], st.hash[:]) == 1 {
		st.hasCode = false
		st.failures = 0
		return VerifyOK, 0
	}

	// Wrong or expired code: counts against the address and the process.
	if st.failures == 0 {
		st.failuresEnd = now.Add(OtpFailureWindow)
	}
	st.failures++
	if !now.Before(p.globalEnd) {
		p.globalFailures, p.globalEnd = 0, now.Add(GlobalFailureWindow)
	}
	p.globalFailures++
	if !now.Before(st.expiresAt) {
		st.hasCode = false
	}
	if st.failures >= OtpMaxAttempts {
		st.hasCode = false
		return VerifyAttemptsExceeded, 0
	}
	return VerifyInvalid, 0
}

// maybeSweep drops idle entries at most once per sweepInterval, so the map
// can't grow without bound and Put stays O(1) amortised.
func (p *PendingCodes) maybeSweep(now time.Time) {
	if now.Sub(p.lastSweep) < sweepInterval {
		return
	}
	p.lastSweep = now
	for k, st := range p.addrs {
		if !now.Before(st.expiresAt) && !now.Before(st.failuresEnd) {
			delete(p.addrs, k)
		}
	}
}
