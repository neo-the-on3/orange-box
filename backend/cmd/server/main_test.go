package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/config"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
	"github.com/neo-the-on3/orange-box/backend/internal/testutil"
)

var testCode = testutil.RandomTestCode()

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func testConfig(t *testing.T) config.Config {
	t.Helper()
	cfg, err := config.Load(func(k string) string {
		return map[string]string{"ENV": "ci", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": testCode, "SHUTDOWN_TIMEOUT": "2s"}[k]
	})
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

func TestRunServesAndShutsDown(t *testing.T) {
	t.Parallel()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	logs := &syncBuffer{}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- run(ctx, ln, testConfig(t), logging.New(logs, slog.LevelDebug)) }()

	waitHealthy(t, ln.Addr().String())
	if !strings.Contains(logs.String(), `"addr":"`+ln.Addr().String()+`"`) {
		t.Errorf("listening log lacks the bound address: %s", logs.String())
	}

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("run: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("run did not return after cancel")
	}
	if !strings.Contains(logs.String(), `"msg":"stopped"`) {
		t.Errorf("no stopped log: %s", logs.String())
	}
}

func TestRunDrainsInFlightRequests(t *testing.T) {
	t.Parallel()
	// A slow client body keeps a request in flight while shutdown starts.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- run(ctx, ln, testConfig(t), logging.New(io.Discard, slog.LevelError)) }()
	waitHealthy(t, ln.Addr().String())

	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	body := `{"email":"drain@test.orangebox.invalid"}`
	head := "POST /v1/auth/otp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nConnection: close\r\n" +
		"Content-Length: " + strconv.Itoa(len(body)) + "\r\n\r\n"
	if _, err := io.WriteString(conn, head+body[:10]); err != nil {
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond) // the handler is now blocked reading the body
	cancel()
	time.Sleep(200 * time.Millisecond)
	if _, err := io.WriteString(conn, body[10:]); err != nil {
		t.Fatal(err)
	}
	res, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("in-flight request failed: %v", err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusAccepted {
		t.Errorf("in-flight request: %d, want 202", res.StatusCode)
	}
	if err := <-done; err != nil {
		t.Errorf("run: %v", err)
	}
}

func TestRunReturnsServeError(t *testing.T) {
	t.Parallel()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	_ = ln.Close() // Serve fails at once on a closed listener
	if err := run(context.Background(), ln, testConfig(t), logging.New(io.Discard, slog.LevelError)); err == nil {
		t.Error("run on a closed listener returned nil")
	}
}

func TestNewTokensFromPEM(t *testing.T) {
	t.Parallel()
	cfg := testConfig(t)
	cfg.JWTPrivateKeyPEM, cfg.JWTKeyID = "not pem", "k1"
	if _, err := newTokens(cfg, logging.New(io.Discard, slog.LevelError)); err == nil {
		t.Error("bad PEM accepted")
	}
}

// waitHealthy polls GET /v1/health until the server answers 200.
func waitHealthy(t *testing.T, addr string) {
	t.Helper()
	var lastErr error
	for range 100 {
		req, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, "http://"+addr+"/v1/health", nil)
		res, err := http.DefaultClient.Do(req)
		if err == nil {
			body, _ := io.ReadAll(res.Body)
			_ = res.Body.Close()
			if res.StatusCode == http.StatusOK && strings.Contains(string(body), `"status":"ok"`) {
				return
			}
			lastErr = fmt.Errorf("health: %d %s", res.StatusCode, body)
		} else {
			lastErr = err
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("server not healthy: %v", lastErr)
}

// TestMainRefusesToStart re-runs the test binary as the server with unsafe
// settings and expects exit status 1 without the test code in the output.
func TestMainRefusesToStart(t *testing.T) {
	t.Parallel()
	if os.Getenv("ORANGE_BOX_RUN_MAIN") == "1" {
		main()
		return
	}
	cases := map[string][]string{
		"test mode in production": {"ENV=production", "AUTH_TEST_MODE=true", "AUTH_TEST_CODE=" + testCode, "JWT_PRIVATE_KEY=x", "JWT_KEY_ID=k"},
		"test mode, ENV unset":    {"AUTH_TEST_MODE=true", "AUTH_TEST_CODE=" + testCode},
		"trivial test code":       {"ENV=local", "AUTH_TEST_MODE=true", "AUTH_TEST_CODE=123456"},
		"bad PORT":                {"ENV=local", "PORT=http"},
		"port in use":             {"ENV=local", "PORT=" + busyPort(t)},
	}
	for name, env := range cases {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			cmd := exec.CommandContext(t.Context(), os.Args[0], "-test.run=^TestMainRefusesToStart$")
			cmd.Env = append([]string{"ORANGE_BOX_RUN_MAIN=1", "PATH=" + os.Getenv("PATH")}, env...)
			out, err := cmd.CombinedOutput()
			var exitErr *exec.ExitError
			if !errors.As(err, &exitErr) || exitErr.ExitCode() != 1 {
				t.Fatalf("err = %v, output: %s", err, out)
			}
			if strings.Contains(string(out), testCode) || strings.Contains(string(out), `"msg":"listening"`) {
				t.Errorf("unexpected output: %s", out)
			}
		})
	}
}

func busyPort(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	return strconv.Itoa(ln.Addr().(*net.TCPAddr).Port)
}
