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
		c.UploadConcurrency != 2 || c.UploadQueueWait != 2*time.Second || c.UploadReadTimeout != 10*time.Second || c.BodyConcurrency != 16 ||
		c.AnonBodyConcurrency != 8 || c.AnonReadConcurrency != 1024 ||
		c.DatabaseURL != "" || c.DBMaxConns != 10 || c.MigrateOnStart {
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
		{"mode off in prod", map[string]string{"ENV": "production", "AUTH_TEST_MODE": "false", "JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k", "DATABASE_URL": "postgres://db/ob", "SYNC_TOKEN_KEY": "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "SYNC_DEPLOYMENT_ID": "prod-1"}, ""},
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
		"bodies zero":   {"ENV": "local", "BODY_CONCURRENCY": "0"},
		"anon bodies":   {"ENV": "local", "ANON_BODY_CONCURRENCY": "x"},
		"anon reads":    {"ENV": "local", "ANON_READ_CONCURRENCY": "0"},
		"db conns zero": {"ENV": "local", "DB_MAX_CONNS": "0"},
		"db conns text": {"ENV": "local", "DB_MAX_CONNS": "lots"},
		"migrate flag":  {"ENV": "local", "MIGRATE_ON_START": "yes"},
	} {
		if _, err := Load(env(e)); err == nil {
			t.Errorf("%s: Load succeeded, want error", name)
		}
	}
}

func TestLoadDatabaseRules(t *testing.T) {
	t.Parallel()
	prod := map[string]string{"ENV": "production", "JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k"}
	if _, err := Load(env(prod)); err == nil || !strings.Contains(err.Error(), "DATABASE_URL is required") {
		t.Errorf("production without DATABASE_URL: err = %v", err)
	}
	c, err := Load(env(map[string]string{"ENV": "local", "DATABASE_URL": " postgres://u@h/db ", "DB_MAX_CONNS": "4", "MIGRATE_ON_START": "true"}))
	if err != nil {
		t.Fatal(err)
	}
	if c.DatabaseURL != "postgres://u@h/db" || c.DBMaxConns != 4 || !c.MigrateOnStart {
		t.Errorf("database settings = %q, %d, %v", c.DatabaseURL, c.DBMaxConns, c.MigrateOnStart)
	}
}

func TestLoadSyncTokenRules(t *testing.T) {
	t.Parallel()
	prod := func(extra ...string) map[string]string {
		m := map[string]string{"ENV": "production", "JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k", "DATABASE_URL": "postgres://db/ob"}
		for i := 0; i+1 < len(extra); i += 2 {
			m[extra[i]] = extra[i+1]
		}
		return m
	}
	if _, err := Load(env(prod())); err == nil || !strings.Contains(err.Error(), "SYNC_TOKEN_KEY is required") {
		t.Errorf("production without SYNC_TOKEN_KEY: err = %v", err)
	}
	for _, bad := range []string{"c2hvcnQ=", "not base64!", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZQ"} {
		if _, err := Load(env(prod("SYNC_TOKEN_KEY", bad))); err == nil || !strings.Contains(err.Error(), "SYNC_TOKEN_KEY must be") || strings.Contains(err.Error(), bad) {
			t.Errorf("SYNC_TOKEN_KEY=%q: err = %v", bad, err)
		}
	}
	for bad, want := range map[string]string{
		"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=":                     "not random", // 32 zero bytes
		"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef": "looks like hex",
	} {
		if _, err := Load(env(prod("SYNC_TOKEN_KEY", bad))); err == nil || !strings.Contains(err.Error(), want) || strings.Contains(err.Error(), bad) {
			t.Errorf("SYNC_TOKEN_KEY=%q: err = %v", bad, err)
		}
	}
	for _, good := range []string{"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY", "-_-_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"} {
		c, err := Load(env(prod("SYNC_TOKEN_KEY", good, "SYNC_DEPLOYMENT_ID", "neon:prod-1")))
		if err != nil || len(c.SyncTokenKey) < 32 || c.SyncDeploymentID != "neon:prod-1" {
			t.Errorf("SYNC_TOKEN_KEY=%q: %d-byte key, id %q, %v", good, len(c.SyncTokenKey), c.SyncDeploymentID, err)
		}
	}
	if _, err := Load(env(prod("SYNC_TOKEN_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "SYNC_DEPLOYMENT_ID", "has space"))); err == nil {
		t.Error("SYNC_DEPLOYMENT_ID with a space accepted")
	}
	if c, err := Load(env(map[string]string{"ENV": "local"})); err != nil || c.SyncTokenKey != nil || c.SyncDeploymentID != "dev-local" {
		t.Errorf("local without a key or deployment id: %v, %q, %v", c.SyncTokenKey, c.SyncDeploymentID, err)
	}
}

func TestLoadSyncDeploymentIDRequired(t *testing.T) {
	t.Parallel()
	const key = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
	base := map[string]string{"JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k", "DATABASE_URL": "postgres://db/ob", "SYNC_TOKEN_KEY": key}
	with := func(kv ...string) map[string]string {
		m := map[string]string{}
		for k, v := range base {
			m[k] = v
		}
		for i := 0; i+1 < len(kv); i += 2 {
			m[kv[i]] = kv[i+1]
		}
		return m
	}
	for _, e := range []string{"production", "staging", "prod"} {
		for _, id := range []string{"", "  "} {
			if _, err := Load(env(with("ENV", e, "SYNC_DEPLOYMENT_ID", id))); err == nil || !strings.Contains(err.Error(), "SYNC_DEPLOYMENT_ID is required") {
				t.Errorf("ENV=%s SYNC_DEPLOYMENT_ID=%q: err = %v", e, id, err)
			}
		}
		if c, err := Load(env(with("ENV", e, "SYNC_DEPLOYMENT_ID", "prod-2026-10-25"))); err != nil || c.SyncDeploymentID != "prod-2026-10-25" {
			t.Errorf("ENV=%s with an id: %q, %v", e, c.SyncDeploymentID, err)
		}
	}
	for _, bad := range []string{"has space", strings.Repeat("a", 65), "a/b", "ü"} {
		if _, err := Load(env(with("ENV", "production", "SYNC_DEPLOYMENT_ID", bad))); err == nil || !strings.Contains(err.Error(), "SYNC_DEPLOYMENT_ID must be") {
			t.Errorf("SYNC_DEPLOYMENT_ID=%q: err = %v", bad, err)
		}
	}
	for _, e := range []string{"local", "ci", "sandbox"} {
		c, err := Load(env(map[string]string{"ENV": e}))
		if err != nil || c.SyncDeploymentID != "dev-"+e {
			t.Errorf("ENV=%s default deployment id = %q, %v", e, c.SyncDeploymentID, err)
		}
		if c, err := Load(env(map[string]string{"ENV": e, "SYNC_DEPLOYMENT_ID": "mine"})); err != nil || c.SyncDeploymentID != "mine" {
			t.Errorf("ENV=%s explicit deployment id = %q, %v", e, c.SyncDeploymentID, err)
		}
	}
}

func TestLoadSyncIdentitySource(t *testing.T) {
	t.Parallel()
	prod := map[string]string{"ENV": "production", "JWT_PRIVATE_KEY": "x", "JWT_KEY_ID": "k", "DATABASE_URL": "postgres://db/ob",
		"SYNC_TOKEN_KEY": "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "SYNC_DEPLOYMENT_ID": "prod-1"}
	with := func(v string) map[string]string {
		m := map[string]string{"SYNC_IDENTITY_SOURCE": v}
		for k, x := range prod {
			m[k] = x
		}
		return m
	}
	for v, want := range map[string]string{"": "cluster_and_deployment_id", "cluster_and_deployment_id": "cluster_and_deployment_id", "deployment_id_only": "deployment_id_only", "\tdeployment_id_only\n": "deployment_id_only"} {
		c, err := Load(env(with(v)))
		if err != nil || c.SyncIdentitySource != want || c.SyncDeploymentIDDefaulted {
			t.Errorf("SYNC_IDENTITY_SOURCE=%q: %q, defaulted %v, %v", v, c.SyncIdentitySource, c.SyncDeploymentIDDefaulted, err)
		}
	}
	for _, bad := range []string{"cluster", "Deployment_ID_Only", "true", "system_identifier"} {
		if _, err := Load(env(with(bad))); err == nil || !strings.Contains(err.Error(), "SYNC_IDENTITY_SOURCE must be") {
			t.Errorf("SYNC_IDENTITY_SOURCE=%q: %v", bad, err)
		}
	}
	if c, err := Load(env(map[string]string{"ENV": "sandbox"})); err != nil || !c.SyncDeploymentIDDefaulted {
		t.Errorf("sandbox default id not flagged: %v, %v", c.SyncDeploymentIDDefaulted, err)
	}
}
