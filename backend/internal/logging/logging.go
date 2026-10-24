// Package logging builds the server's JSON slog logger with redaction.
//
// Redaction is defence in depth: code should never log e-mail addresses,
// one-time codes, tokens, VINs or plate numbers in the first place. The
// handler blanks attributes with sensitive keys and scrubs e-mail addresses
// and JWT-shaped strings out of every other string value, including errors.
package logging

import (
	"context"
	"io"
	"log/slog"
	"regexp"
	"strings"
)

// Redacted replaces sensitive values.
const Redacted = "[REDACTED]"

// sensitiveKeys are matched case-insensitively against attribute keys, with
// "_" and "-" removed (so access_token, accessToken and Access-Token match).
var sensitiveKeys = map[string]bool{
	"email": true, "emailaddress": true, "code": true, "otp": true, "otpcode": true,
	"token": true, "accesstoken": true, "refreshtoken": true, "idtoken": true,
	"authorization": true, "cookie": true, "setcookie": true, "password": true,
	"secret": true, "authtestcode": true, "jwtprivatekey": true, "privatekey": true,
	"vin": true, "platenumber": true, "plate": true,
}

var (
	emailRE = regexp.MustCompile(`[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}`)
	jwtRE   = regexp.MustCompile(`eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*`)
)

// New returns a JSON logger writing to w at the given level, with redaction.
func New(w io.Writer, level slog.Leveler) *slog.Logger {
	return slog.New(NewRedactingHandler(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: level})))
}

// NewRedactingHandler wraps next with redaction.
func NewRedactingHandler(next slog.Handler) slog.Handler {
	return &redactingHandler{next: next}
}

type redactingHandler struct {
	next slog.Handler
}

func (h *redactingHandler) Enabled(ctx context.Context, l slog.Level) bool {
	return h.next.Enabled(ctx, l)
}

func (h *redactingHandler) Handle(ctx context.Context, r slog.Record) error {
	out := slog.NewRecord(r.Time, r.Level, Scrub(r.Message), r.PC)
	r.Attrs(func(a slog.Attr) bool {
		out.AddAttrs(redactAttr(a))
		return true
	})
	return h.next.Handle(ctx, out)
}

func (h *redactingHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	red := make([]slog.Attr, len(attrs))
	for i, a := range attrs {
		red[i] = redactAttr(a)
	}
	return &redactingHandler{next: h.next.WithAttrs(red)}
}

func (h *redactingHandler) WithGroup(name string) slog.Handler {
	return &redactingHandler{next: h.next.WithGroup(name)}
}

func isSensitiveKey(k string) bool {
	k = strings.NewReplacer("_", "", "-", "", ".", "").Replace(strings.ToLower(k))
	return sensitiveKeys[k]
}

func redactAttr(a slog.Attr) slog.Attr {
	if isSensitiveKey(a.Key) {
		return slog.String(a.Key, Redacted)
	}
	v := a.Value.Resolve()
	switch v.Kind() {
	case slog.KindString:
		return slog.String(a.Key, Scrub(v.String()))
	case slog.KindGroup:
		group := v.Group()
		red := make([]any, len(group))
		for i, g := range group {
			red[i] = redactAttr(g)
		}
		return slog.Group(a.Key, red...)
	case slog.KindAny:
		if err, ok := v.Any().(error); ok {
			return slog.String(a.Key, Scrub(err.Error()))
		}
		if s, ok := v.Any().(interface{ String() string }); ok {
			return slog.String(a.Key, Scrub(s.String()))
		}
		// Arbitrary values could hold anything; don't let them through.
		return slog.String(a.Key, "[unlogged value]")
	default:
		return slog.Attr{Key: a.Key, Value: v}
	}
}

// Scrub removes e-mail addresses and JWT-shaped strings from s.
func Scrub(s string) string {
	if strings.Contains(s, "@") {
		s = emailRE.ReplaceAllString(s, "[email]")
	}
	if strings.Contains(s, "eyJ") {
		s = jwtRE.ReplaceAllString(s, "[jwt]")
	}
	return s
}
