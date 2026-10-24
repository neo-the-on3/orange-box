package logging

import (
	"bytes"
	"errors"
	"log/slog"
	"strings"
	"testing"
)

func TestRedaction(t *testing.T) {
	t.Parallel()
	var buf bytes.Buffer
	l := New(&buf, slog.LevelDebug).With(slog.String("refresh_token", "rt_secret"))
	l.Info("sign-in for driver@example.com",
		slog.String("email", "driver@example.com"),
		slog.String("code", "042917"),
		slog.String("accessToken", "abc"),
		slog.String("Authorization", "Bearer abc"),
		slog.String("vin", "1HGCM82633A004352"),
		slog.String("note", "token eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln for a@b.co"),
		slog.Any("err", errors.New("lookup driver@example.com failed")),
		slog.Group("req", slog.String("plate_number", "KA01AB1234"), slog.Int("status", 200)),
		slog.Any("struct", struct{ Email string }{"x@y.zz"}),
	)
	out := buf.String()
	for _, leak := range []string{"driver@example.com", "042917", "rt_secret", "Bearer abc", "1HGCM82633A004352", "eyJhbGci", "a@b.co", "KA01AB1234", "x@y.zz"} {
		if strings.Contains(out, leak) {
			t.Errorf("log leaks %q: %s", leak, out)
		}
	}
	for _, want := range []string{`"status":200`, "[email]", "[jwt]", Redacted} {
		if !strings.Contains(out, want) {
			t.Errorf("log missing %q: %s", want, out)
		}
	}
}

func TestLevel(t *testing.T) {
	t.Parallel()
	var buf bytes.Buffer
	l := New(&buf, slog.LevelWarn)
	l.Info("hidden")
	l.WithGroup("g").Warn("shown", slog.String("k", "v"))
	if strings.Contains(buf.String(), "hidden") || !strings.Contains(buf.String(), `"g":{"k":"v"}`) {
		t.Errorf("unexpected output: %s", buf.String())
	}
}
