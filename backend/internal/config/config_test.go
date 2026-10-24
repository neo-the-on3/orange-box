package config

import (
	"strings"
	"testing"
	"time"
)

func env(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoadDefaults(t *testing.T) {
	t.Parallel()
	c, err := Load(env(map[string]string{"ENV": "local"}))
	if err != nil {
		t.Fatal(err)
	}
	if c.Port != 8080 || c.AuthTestMode || c.JWTIssuer != "orange-box" ||
		c.RequestTimeout != 30*time.Second || c.ShutdownTimeout != 8*time.Second ||
		c.UploadConcurrency != 2 || c.UploadQueueWait != 2*time.Second || c.UploadReadTimeout != 10*time.Second {
		t.Errorf("unexpected defaults: %+v", c)
	}
}

func TestLoadTestMode(t *testing.T) {
	t.Parallel()
	const good = "482916"
	cases := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"local", map[string]string{"ENV": "local", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, ""},
		{"ci", map[string]string{"ENV": "ci", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, ""},
		{"sandbox", map[string]string{"ENV": "sandbox", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, ""},
		{"env unset", map[string]string{"AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE=true is only allowed"},
		{"env empty", map[string]string{"ENV": "", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE=true is only allowed"},
		{"production", map[string]string{"ENV": "production", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good, "JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k"}, "AUTH_TEST_MODE=true is only allowed"},
		{"prod", map[string]string{"ENV": "prod", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE=true is only allowed"},
		{"Production", map[string]string{"ENV": "Production", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE=true is only allowed"},
		{"Local (case)", map[string]string{"ENV": "Local", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE=true is only allowed"},
		{"local with space", map[string]string{"ENV": "local ", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE=true is only allowed"},
		{"code missing", map[string]string{"ENV": "local", "AUTH_TEST_MODE": "true"}, "AUTH_TEST_CODE"},
		{"code trivial", map[string]string{"ENV": "local", "AUTH_TEST_MODE": "true", "AUTH_TEST_CODE": "123456"}, "AUTH_TEST_CODE"},
		{"mode TRUE", map[string]string{"ENV": "local", "AUTH_TEST_MODE": "TRUE", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE must be"},
		{"mode 1", map[string]string{"ENV": "local", "AUTH_TEST_MODE": "1", "AUTH_TEST_CODE": good}, "AUTH_TEST_MODE must be"},
		{"mode off in prod", map[string]string{"ENV": "production", "AUTH_TEST_MODE": "false", "JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k"}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			c, err := Load(env(tc.env))
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if want := tc.env["AUTH_TEST_MODE"] == "true"; c.AuthTestMode != want {
					t.Errorf("AuthTestMode = %v, want %v", c.AuthTestMode, want)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error = %v, want it to contain %q", err, tc.wantErr)
			}
			if code := tc.env["AUTH_TEST_CODE"]; code != "" && strings.Contains(err.Error(), code) {
				t.Errorf("error leaks the test code: %v", err)
			}
		})
	}
}

func TestValidateTestCode(t *testing.T) {
	t.Parallel()
	for _, ok := range []string{"482916", "042917", "135790", "112233", "123457"} {
		if err := ValidateTestCode(ok); err != nil {
			t.Errorf("ValidateTestCode(%q) = %v, want nil", ok, err)
		}
	}
	for _, bad := range []string{
		"", "12345", "1234567", "12a456", "１２３４５６", " 48291",
		"000000", "111111", "999999",
		"012345", "123456", "456789", "987654", "543210", "765432",
	} {
		if err := ValidateTestCode(bad); err == nil {
			t.Errorf("ValidateTestCode(%q) = nil, want error", bad)
		}
	}
}

func TestLoadJWTKeyRules(t *testing.T) {
	t.Parallel()
	if _, err := Load(env(map[string]string{"ENV": "production"})); err == nil || !strings.Contains(err.Error(), "JWT_PRIVATE_KEY is required") {
		t.Errorf("production without key: err = %v", err)
	}
	if _, err := Load(env(map[string]string{})); err == nil || !strings.Contains(err.Error(), "JWT_PRIVATE_KEY is required") {
		t.Errorf("unset ENV without key: err = %v", err)
	}
	if _, err := Load(env(map[string]string{"ENV": "local", "JWT_PRIVATE_KEY": "pem"})); err == nil || !strings.Contains(err.Error(), "JWT_KEY_ID") {
		t.Errorf("key without kid: err = %v", err)
	}
}

func TestLoadRejectsBadValues(t *testing.T) {
	t.Parallel()
	for name, e := range map[string]map[string]string{
		"port text":     {"ENV": "local", "PORT": "http"},
		"port range":    {"ENV": "local", "PORT": "70000"},
		"port negative": {"ENV": "local", "PORT": "-1"},
		"timeout":       {"ENV": "local", "REQUEST_TIMEOUT": "soon"},
		"shutdown zero": {"ENV": "local", "SHUTDOWN_TIMEOUT": "0s"},
		"log level":     {"ENV": "local", "LOG_LEVEL": "loud"},
		"uploads zero":  {"ENV": "local", "UPLOAD_CONCURRENCY": "0"},
		"uploads text":  {"ENV": "local", "UPLOAD_CONCURRENCY": "many"},
		"queue wait":    {"ENV": "local", "UPLOAD_QUEUE_WAIT": "-1s"},
		"read timeout":  {"ENV": "local", "UPLOAD_READ_TIMEOUT": "soon"},
	} {
		if _, err := Load(env(e)); err == nil {
			t.Errorf("%s: Load succeeded, want error", name)
		}
	}
}
