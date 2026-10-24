// Package synctoken encodes the opaque sync tokens (since / nextSince) and
// page cursors of GET /v1/trips and GET /v1/trips/deletions
// (openapi-v2.yaml, Conventions → Incremental sync).
//
// Both are base64url (no padding) of a version byte, a binary payload and
// an HMAC-SHA256 over the member id and the payload, so a client can
// neither forge nor edit them, nor use another member's. A sync token
// carries the deployment identity, issuedAt and the xid8 watermark. A
// cursor also carries the walk it belongs to: its kind, its since token,
// the token the walk will end with, a hash of its filters and the position
// of the previous page's last item.
package synctoken

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

// Format versions. A token or cursor with another version is invalid.
const (
	tokenVersion  byte = 1
	cursorVersion byte = 1
)

// MinKeyLen is the shortest accepted secret (SYNC_TOKEN_KEY), in bytes.
const MinKeyLen = 32

// maxIdentity bounds the deployment identity, so tokens stay well under
// the spec's 512-character limit.
const maxIdentity = 80

// ErrInvalid means a token or cursor failed to decode or verify. It never
// says why, so it can't be used as an oracle.
var ErrInvalid = errors.New("synctoken: invalid")

// Codec signs and verifies tokens and cursors with one secret.
type Codec struct {
	tokenKey, cursorKey []byte
}

// New returns a codec for secret (at least MinKeyLen bytes). Tokens and
// cursors are signed with keys derived from it, so neither can pass as the
// other.
func New(secret []byte) (*Codec, error) {
	if len(secret) < MinKeyLen {
		return nil, errors.New("synctoken: the key must be at least 32 bytes")
	}
	derive := func(label string) []byte {
		m := hmac.New(sha256.New, secret)
		m.Write([]byte(label))
		return m.Sum(nil)
	}
	return &Codec{tokenKey: derive("orange-box sync token"), cursorKey: derive("orange-box page cursor")}, nil
}

// GenerateKey returns a random secret (for dev environments without
// SYNC_TOKEN_KEY).
func GenerateKey() []byte {
	b := make([]byte, MinKeyLen)
	_, _ = rand.Read(b) // never fails (crypto/rand)
	return b
}

func seal(key []byte, version byte, member uuid.UUID, payload []byte) string {
	m := hmac.New(sha256.New, key)
	m.Write(member[:])
	m.Write([]byte{version})
	m.Write(payload)
	out := append([]byte{version}, payload...)
	return base64.RawURLEncoding.EncodeToString(m.Sum(out))
}

func open(key []byte, version byte, member uuid.UUID, s string) ([]byte, error) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(raw) < 1+sha256.Size || raw[0] != version {
		return nil, ErrInvalid
	}
	payload, mac := raw[1:len(raw)-sha256.Size], raw[len(raw)-sha256.Size:]
	m := hmac.New(sha256.New, key)
	m.Write(member[:])
	m.Write([]byte{version})
	m.Write(payload)
	if !hmac.Equal(mac, m.Sum(nil)) {
		return nil, ErrInvalid
	}
	return payload, nil
}

// Token returns the sync token for p, bound to member.
func (c *Codec) Token(member uuid.UUID, p store.SyncPoint) string {
	return seal(c.tokenKey, tokenVersion, member, appendPoint(nil, p))
}

// ParseToken verifies and decodes a sync token of member.
func (c *Codec) ParseToken(member uuid.UUID, s string) (store.SyncPoint, error) {
	payload, err := open(c.tokenKey, tokenVersion, member, s)
	if err != nil {
		return store.SyncPoint{}, err
	}
	r := reader{b: payload}
	p := r.point()
	if !r.done() {
		return store.SyncPoint{}, ErrInvalid
	}
	return p, nil
}

// Kind is the walk a cursor belongs to.
type Kind byte

const (
	// History is GET /v1/trips without since.
	History Kind = iota + 1
	// Changes is GET /v1/trips?since=.
	Changes
	// Deletions is GET /v1/trips/deletions.
	Deletions
)

// Cursor is a decoded page cursor.
type Cursor struct {
	Kind Kind
	// Filter is a hash of a history walk's filters (zero otherwise).
	Filter [16]byte
	// Since is the token a Changes or Deletions walk started from.
	Since *store.SyncPoint
	// Next is the token the walk ends with (nil for a filtered history walk).
	Next *store.SyncPoint
	// History is the last item's position in a History walk.
	History store.HistoryPos
	// Change is the last item's position in a Changes or Deletions walk.
	Change store.ChangePos
}

// Cursor returns the cursor for cur, bound to member.
func (c *Codec) Cursor(member uuid.UUID, cur Cursor) string {
	b := []byte{byte(cur.Kind)}
	b = append(b, cur.Filter[:]...)
	b = appendOptPoint(b, cur.Since)
	b = appendOptPoint(b, cur.Next)
	if cur.Kind == History {
		b = binary.BigEndian.AppendUint64(b, uint64(cur.History.StartedAt.UnixMicro()))
		b = append(b, cur.History.ID[:]...)
	} else {
		b = binary.BigEndian.AppendUint64(b, cur.Change.Xid)
		b = append(b, cur.Change.ID[:]...)
	}
	return seal(c.cursorKey, cursorVersion, member, b)
}

// ParseCursor verifies and decodes a cursor of member.
func (c *Codec) ParseCursor(member uuid.UUID, s string) (Cursor, error) {
	payload, err := open(c.cursorKey, cursorVersion, member, s)
	if err != nil {
		return Cursor{}, err
	}
	r := reader{b: payload}
	var cur Cursor
	cur.Kind = Kind(r.byte())
	copy(cur.Filter[:], r.take(16))
	cur.Since = r.optPoint()
	cur.Next = r.optPoint()
	switch cur.Kind {
	case History:
		cur.History.StartedAt = time.UnixMicro(int64(r.uint64())).UTC() //nolint:gosec // round-trips through uint64
		copy(cur.History.ID[:], r.take(16))
	case Changes, Deletions:
		cur.Change.Xid = r.uint64()
		copy(cur.Change.ID[:], r.take(16))
	default:
		return Cursor{}, ErrInvalid
	}
	if !r.done() {
		return Cursor{}, ErrInvalid
	}
	return cur, nil
}

// FilterHash hashes a history walk's filters for Cursor.Filter.
func FilterHash(parts ...string) [16]byte {
	h := sha256.New()
	for _, p := range parts {
		h.Write(binary.BigEndian.AppendUint32(nil, uint32(len(p)))) //nolint:gosec // short query values
		h.Write([]byte(p))
	}
	var out [16]byte
	copy(out[:], h.Sum(nil))
	return out
}

func appendPoint(b []byte, p store.SyncPoint) []byte {
	id := p.Identity
	if len(id) > maxIdentity {
		id = id[:maxIdentity] // never the case for identities the store makes
	}
	b = append(b, byte(len(id))) //nolint:gosec // len(id) <= maxIdentity < 256
	b = append(b, id...)
	b = binary.BigEndian.AppendUint64(b, uint64(p.IssuedAt.UnixMicro()))
	return binary.BigEndian.AppendUint64(b, p.Watermark)
}

func appendOptPoint(b []byte, p *store.SyncPoint) []byte {
	if p == nil {
		return append(b, 0)
	}
	return appendPoint(append(b, 1), *p)
}

// reader decodes a payload; any short read marks it bad.
type reader struct {
	b   []byte
	bad bool
}

func (r *reader) take(n int) []byte {
	if r.bad || len(r.b) < n {
		r.bad = true
		return make([]byte, n)
	}
	out := r.b[:n]
	r.b = r.b[n:]
	return out
}

func (r *reader) byte() byte     { return r.take(1)[0] }
func (r *reader) uint64() uint64 { return binary.BigEndian.Uint64(r.take(8)) }
func (r *reader) done() bool     { return !r.bad && len(r.b) == 0 }
func (r *reader) point() store.SyncPoint {
	n := int(r.byte())
	if n > maxIdentity {
		r.bad = true
		return store.SyncPoint{}
	}
	id := string(r.take(n))
	issued := time.UnixMicro(int64(r.uint64())).UTC() //nolint:gosec // round-trips through uint64
	return store.SyncPoint{Identity: id, IssuedAt: issued, Watermark: r.uint64()}
}

func (r *reader) optPoint() *store.SyncPoint {
	switch r.byte() {
	case 0:
		return nil
	case 1:
		p := r.point()
		return &p
	default:
		r.bad = true
		return nil
	}
}
