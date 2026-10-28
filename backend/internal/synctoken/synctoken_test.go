package synctoken

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func codec(t *testing.T) *Codec {
	t.Helper()
	c, err := New(GenerateKey())
	if err != nil {
		t.Fatal(err)
	}
	return c
}

var point = store.SyncPoint{
	Identity:  "pg:7699962005555169022",
	IssuedAt:  time.Date(2026, 10, 24, 21, 15, 3, 123456000, time.UTC),
	Watermark: 18446744073709551000,
}

func TestTokenRoundTrip(t *testing.T) {
	t.Parallel()
	c, member := codec(t), uuid.New()
	s := c.Token(member, point)
	if len(s) > 512 {
		t.Fatalf("token is %d characters", len(s))
	}
	got, err := c.ParseToken(member, s)
	if err != nil || got != point {
		t.Fatalf("ParseToken = %+v, %v; want %+v", got, err, point)
	}
	if _, err := c.ParseToken(uuid.New(), s); !errors.Is(err, ErrInvalid) {
		t.Errorf("another member's token: %v", err)
	}
	other := codec(t)
	if _, err := other.ParseToken(member, s); !errors.Is(err, ErrInvalid) {
		t.Errorf("token signed with another key: %v", err)
	}
	if _, err := c.ParseCursor(member, s); !errors.Is(err, ErrInvalid) {
		t.Errorf("token accepted as a cursor: %v", err)
	}
}

func TestTokenTampering(t *testing.T) {
	t.Parallel()
	c, member := codec(t), uuid.New()
	raw, _ := base64.RawURLEncoding.DecodeString(c.Token(member, point))
	for i := range raw {
		b := append([]byte(nil), raw...)
		b[i] ^= 0x01
		if _, err := c.ParseToken(member, base64.RawURLEncoding.EncodeToString(b)); !errors.Is(err, ErrInvalid) {
			t.Fatalf("flipping byte %d: %v", i, err)
		}
	}
	for _, s := range []string{"", "!!!", "AQ", base64.RawURLEncoding.EncodeToString(raw[:len(raw)-1]), c.Token(member, point) + "A"} {
		if _, err := c.ParseToken(member, s); !errors.Is(err, ErrInvalid) {
			t.Errorf("ParseToken(%q): %v", s, err)
		}
	}
	// Another format version is invalid even with a valid MAC.
	if _, err := open(c.tokenKey, tokenVersion, member, seal(c.tokenKey, tokenVersion+1, member, appendPoint(nil, point))); !errors.Is(err, ErrInvalid) {
		t.Errorf("version 2 token: %v", err)
	}
}

func TestCursorRoundTrip(t *testing.T) {
	t.Parallel()
	c, member := codec(t), uuid.New()
	next := point
	next.Watermark++
	// The longest identity accepted.
	long := strings.Repeat("d", maxIdentity)
	far := next
	far.Identity = long
	for _, cur := range []Cursor{
		{Kind: History, Identity: "pg:1/dep:a", Filter: FilterHash("v", "", ""), Next: nil, History: store.HistoryPos{StartedAt: point.IssuedAt, ID: uuid.New()}},
		{Kind: Changes, Identity: long, Since: &far, Next: &far, Change: store.ChangePos{Xid: 7, ID: uuid.New()}},
		{Kind: History, Next: &next, History: store.HistoryPos{StartedAt: time.UnixMicro(-5).UTC(), ID: uuid.New()}},
		{Kind: Changes, Since: &point, Next: &next, Change: store.ChangePos{Xid: 42, ID: uuid.New()}},
		{Kind: Deletions, Since: &point, Next: &next, Change: store.ChangePos{Xid: 1 << 63, ID: uuid.New()}},
	} {
		s := c.Cursor(member, cur)
		if len(s) > 512 {
			t.Fatalf("cursor is %d characters", len(s))
		}
		got, err := c.ParseCursor(member, s)
		if err != nil {
			t.Fatal(err)
		}
		if got.Kind != cur.Kind || got.Identity != cur.Identity || got.Filter != cur.Filter || got.History != cur.History || got.Change != cur.Change ||
			(got.Since == nil) != (cur.Since == nil) || (got.Next == nil) != (cur.Next == nil) ||
			(cur.Since != nil && *got.Since != *cur.Since) || (cur.Next != nil && *got.Next != *cur.Next) {
			t.Fatalf("ParseCursor = %+v, want %+v", got, cur)
		}
		if _, err := c.ParseToken(member, s); !errors.Is(err, ErrInvalid) {
			t.Errorf("cursor accepted as a token: %v", err)
		}
		if _, err := c.ParseCursor(uuid.New(), s); !errors.Is(err, ErrInvalid) {
			t.Errorf("another member's cursor: %v", err)
		}
	}
	if FilterHash("ab", "") == FilterHash("a", "b") {
		t.Error("filter hash collides by concatenation")
	}
}

func TestNewRejectsShortKey(t *testing.T) {
	t.Parallel()
	if _, err := New(make([]byte, 31)); err == nil {
		t.Fatal("31-byte key accepted")
	}
}

// Cursors and tokens are signed, so a client can't edit their times; a
// time outside the range the API stores (and Postgres accepts) is still
// invalid rather than reaching a query.
func TestTimesOutOfRange(t *testing.T) {
	t.Parallel()
	c, member := codec(t), uuid.New()
	for _, at := range []time.Time{
		time.UnixMicro(-210866803200000001).UTC(), // before 4713 BC
		time.Date(0, 12, 31, 23, 59, 59, 0, time.UTC),
		time.Date(10000, 1, 1, 0, 0, 0, 0, time.UTC),
	} {
		s := c.Cursor(member, Cursor{Kind: History, Identity: point.Identity, History: store.HistoryPos{StartedAt: at, ID: uuid.New()}})
		if _, err := c.ParseCursor(member, s); !errors.Is(err, ErrInvalid) {
			t.Errorf("history cursor at %v: %v", at, err)
		}
		p := point
		p.IssuedAt = at
		if _, err := c.ParseToken(member, c.Token(member, p)); !errors.Is(err, ErrInvalid) {
			t.Errorf("token issued at %v: %v", at, err)
		}
		s = c.Cursor(member, Cursor{Kind: Changes, Identity: point.Identity, Since: &p, Next: &point})
		if _, err := c.ParseCursor(member, s); !errors.Is(err, ErrInvalid) {
			t.Errorf("changes cursor since %v: %v", at, err)
		}
	}
	end := time.Date(9999, 12, 31, 23, 59, 59, 999999000, time.UTC)
	s := c.Cursor(member, Cursor{Kind: History, Identity: point.Identity, History: store.HistoryPos{StartedAt: end, ID: uuid.New()}})
	if got, err := c.ParseCursor(member, s); err != nil || !got.History.StartedAt.Equal(end) {
		t.Errorf("history cursor at the end of 9999: %+v, %v", got, err)
	}
}
