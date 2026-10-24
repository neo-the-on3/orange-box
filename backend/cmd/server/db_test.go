package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"testing"

	"github.com/neo-the-on3/orange-box/backend/internal/config"
	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
)

func dbConfig(t *testing.T, url string, extra map[string]string) config.Config {
	t.Helper()
	env := map[string]string{"ENV": "ci", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": testCode, "SHUTDOWN_TIMEOUT": "2s", "DATABASE_URL": url}
	for k, v := range extra {
		env[k] = v
	}
	cfg, err := config.Load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

// startServer runs the server on a free port until the test ends.
func startServer(t *testing.T, cfg config.Config, logs io.Writer) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- run(ctx, ln, cfg, logging.New(logs, slog.LevelDebug)) }()
	t.Cleanup(func() {
		cancel()
		if err := <-done; err != nil {
			t.Errorf("run: %v", err)
		}
	})
	waitHealthy(t, ln.Addr().String())
	return "http://" + ln.Addr().String()
}

// noKeepAlive leaves no connection behind that graceful shutdown would
// wait for (a dialled-but-unused connection counts as active for 5 s).
var noKeepAlive = &http.Client{Transport: &http.Transport{DisableKeepAlives: true}}

func signIn(t *testing.T, base, email string) (memberID string, isNew bool) {
	t.Helper()
	post := func(path, body string) *http.Response {
		req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, base+path, strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		res, err := noKeepAlive.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return res
	}
	res := post("/v1/auth/otp", `{"email":"`+email+`"}`)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusAccepted {
		t.Fatalf("otp: %d", res.StatusCode)
	}
	res = post("/v1/auth/token", `{"grantType":"email_otp","email":"`+email+`","code":"`+testCode+`"}`)
	defer res.Body.Close()
	var tok struct {
		MemberID    string `json:"memberId"`
		IsNewMember bool   `json:"isNewMember"`
	}
	if err := json.NewDecoder(res.Body).Decode(&tok); err != nil || res.StatusCode != http.StatusOK {
		t.Fatalf("token: %d, %v", res.StatusCode, err)
	}
	return tok.MemberID, tok.IsNewMember
}

// TestRunUsesPostgresMembers signs in through the HTTP API with
// DATABASE_URL set and checks the member lands in Postgres and survives a
// server restart.
func TestRunUsesPostgresMembers(t *testing.T) {
	t.Parallel()
	url := dbtest.NewDatabase(t)
	logs := &syncBuffer{}
	cfg := dbConfig(t, url, map[string]string{"MIGRATE_ON_START": "true"})

	base := startServer(t, cfg, logs)
	id, isNew := signIn(t, base, "pg@test.orangebox.invalid")
	if !isNew || id == "" {
		t.Fatalf("first sign-in: id=%q isNew=%v", id, isNew)
	}
	if !strings.Contains(logs.String(), `"msg":"using Postgres"`) || !strings.Contains(logs.String(), `"msg":"applied migration"`) {
		t.Errorf("logs lack the Postgres/migration lines: %s", logs.String())
	}
	if strings.Contains(logs.String(), "orangebox_dev") {
		t.Error("logs contain the database password")
	}

	// A second server on the same database (no MIGRATE_ON_START needed now)
	// knows the member.
	base2 := startServer(t, dbConfig(t, url, nil), io.Discard)
	id2, isNew2 := signIn(t, base2, "pg@test.orangebox.invalid")
	if isNew2 || id2 != id {
		t.Fatalf("after restart: id=%q isNew=%v, want %q and false", id2, isNew2, id)
	}
	var count int
	pool := dbtest.Open(t, url)
	if err := pool.QueryRow(context.Background(), `SELECT count(*) FROM members WHERE email = 'pg@test.orangebox.invalid'`).Scan(&count); err != nil || count != 1 {
		t.Fatalf("members rows = %d, %v", count, err)
	}
}

func TestRunRefusesUnmigratedDatabase(t *testing.T) {
	t.Parallel()
	cfg := dbConfig(t, dbtest.NewDatabase(t), nil)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	err = run(context.Background(), ln, cfg, logging.New(io.Discard, slog.LevelError))
	if !errors.Is(err, db.ErrNotMigrated) {
		t.Fatalf("run = %v, want ErrNotMigrated", err)
	}
}

func TestRunRefusesUnreachableDatabase(t *testing.T) {
	t.Parallel()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	// A closed local port: the connection is refused at once.
	cfg := dbConfig(t, "postgres://u:p@127.0.0.1:"+busyClosedPort(t)+"/db?connect_timeout=2", nil)
	if err := run(context.Background(), ln, cfg, logging.New(io.Discard, slog.LevelError)); err == nil {
		t.Fatal("run succeeded without a database")
	}
}

func busyClosedPort(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	_, port, _ := net.SplitHostPort(ln.Addr().String())
	_ = ln.Close()
	return port
}

func TestMigrateCommand(t *testing.T) {
	t.Parallel()
	url := dbtest.NewDatabase(t)
	getenv := func(k string) string { return map[string]string{"DATABASE_URL": url}[k] }
	runCmd := func(args ...string) (int, string) {
		var out bytes.Buffer
		code := migrateCommand(args, getenv, &out)
		return code, out.String()
	}
	if code, out := runCmd("version"); code != 0 || strings.TrimSpace(out) != "0" {
		t.Fatalf("version before up: %d %q", code, out)
	}
	if code, out := runCmd("up"); code != 0 || !strings.Contains(out, "applied migration") {
		t.Fatalf("up: %d %s", code, out)
	}
	if code, out := runCmd("status"); code != 0 || strings.Contains(out, "pending") || !strings.Contains(out, "00001") {
		t.Fatalf("status: %d %s", code, out)
	}
	if code, _ := runCmd("down"); code != 0 {
		t.Fatalf("down: %d", code)
	}
	if code, out := runCmd("status"); code != 0 || strings.Count(out, "pending") != 1 {
		t.Fatalf("status after down: %d %s", code, out)
	}
	if code, out := runCmd("up"); code != 0 || strings.Count(out, "applied migration") != 1 {
		t.Fatalf("second up: %d %s", code, out)
	}
	if code, _ := runCmd("sideways"); code != 1 {
		t.Errorf("unknown command: exit %d, want 1", code)
	}
	if code, out := runCmd(); code != 2 || !strings.Contains(out, "usage") {
		t.Errorf("no command: exit %d %q", code, out)
	}
	if code := migrateCommand([]string{"up"}, func(string) string { return "" }, io.Discard); code != 1 {
		t.Errorf("no DATABASE_URL: exit %d, want 1", code)
	}
}

// TestHealthReportsDatabaseOutage stops answering for the database (by
// terminating the server's connections and dropping the database) and
// expects GET /v1/health to turn into 503.
func TestHealthReportsDatabaseOutage(t *testing.T) {
	t.Parallel()
	url := dbtest.NewDatabase(t)
	base := startServer(t, dbConfig(t, url, map[string]string{"MIGRATE_ON_START": "true"}), io.Discard)
	get := func() int {
		req, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, base+"/v1/health", nil)
		res, err := noKeepAlive.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res.StatusCode
	}
	if code := get(); code != http.StatusOK {
		t.Fatalf("health with database up: %d", code)
	}
	admin := dbtest.Open(t, dbtest.BaseURL(t))
	name := url[strings.LastIndex(url, "/")+1:]
	if i := strings.Index(name, "?"); i >= 0 {
		name = name[:i]
	}
	if _, err := admin.Exec(context.Background(), "DROP DATABASE "+name+" WITH (FORCE)"); err != nil {
		t.Fatal(err)
	}
	if code := get(); code != http.StatusServiceUnavailable {
		t.Fatalf("health with database gone: %d, want 503", code)
	}
}
