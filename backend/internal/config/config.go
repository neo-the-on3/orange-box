// Package config loads the server configuration from environment variables.
//
// Loading fails closed: anything that could switch on test-only behaviour
// outside the allow-listed environments stops the server from starting.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"
)

// Environments in which test-only behaviour (AUTH_TEST_MODE, a generated
// JWT signing key) is allowed. The match is exact and case-sensitive.
var devEnvironments = map[string]bool{"local": true, "ci": true, "sandbox": true}

// Config is the full server configuration.
type Config struct {
	// Env is the deployment environment (ENV), e.g. local, ci, sandbox or production.
	Env string
	// Port is the TCP port to listen on (PORT, default 8080; 0 picks a free port).
	Port int

	// AuthTestMode enables test-mode sign-in for @test.orangebox.invalid addresses.
	AuthTestMode bool
	// AuthTestCode is the static code test addresses accept (AUTH_TEST_CODE).
	AuthTestCode string

	// JWTPrivateKeyPEM is the ES256 (P-256) signing key in PEM (JWT_PRIVATE_KEY).
	// Empty means "generate an ephemeral key", allowed only in dev environments.
	JWTPrivateKeyPEM string
	// JWTKeyID is the kid header for JWTPrivateKeyPEM (JWT_KEY_ID).
	JWTKeyID string
	// JWTIssuer is the iss claim (JWT_ISSUER, default "orange-box").
	JWTIssuer string

	// RequestTimeout bounds each request's context (REQUEST_TIMEOUT, default 30s).
	RequestTimeout time.Duration
	// ShutdownTimeout bounds graceful shutdown (SHUTDOWN_TIMEOUT, default 8s,
	// inside Docker's default 10s stop grace period).
	ShutdownTimeout time.Duration
	// UploadConcurrency caps upload bodies processed at once (UPLOAD_CONCURRENCY,
	// default 2, sized for a 512 MB host with GOMEMLIMIT=400MiB).
	UploadConcurrency int
	// UploadQueueWait is how long an upload waits for a slot before 503 (UPLOAD_QUEUE_WAIT, default 2s).
	UploadQueueWait time.Duration
	// BodyConcurrency caps signed-in non-upload bodies processed at once
	// (BODY_CONCURRENCY, default 16).
	BodyConcurrency int
	// AnonBodyConcurrency caps anonymous bodies processed at once, in a
	// separate pool (ANON_BODY_CONCURRENCY, default 8).
	AnonBodyConcurrency int
	// AnonReadConcurrency caps anonymous requests with a body in flight,
	// reading included (ANON_READ_CONCURRENCY, default 1024).
	AnonReadConcurrency int
	// UploadReadTimeout bounds how long an upload body may take to arrive
	// once it holds a slot (UPLOAD_READ_TIMEOUT, default 10s, then 408).
	UploadReadTimeout time.Duration
	// LogLevel is the minimum log level (LOG_LEVEL: debug, info, warn, error).
	LogLevel slog.Level
}

// IsDevEnvironment reports whether Env is one of local, ci or sandbox.
func (c Config) IsDevEnvironment() bool { return devEnvironments[c.Env] }

// Load reads the configuration through getenv (os.Getenv in production).
// It returns every problem it finds, joined, so one restart fixes them all.
func Load(getenv func(string) string) (Config, error) {
	c := Config{
		Env:              getenv("ENV"),
		AuthTestCode:     getenv("AUTH_TEST_CODE"),
		JWTPrivateKeyPEM: getenv("JWT_PRIVATE_KEY"),
		JWTKeyID:         getenv("JWT_KEY_ID"),
		JWTIssuer:        orDefault(getenv("JWT_ISSUER"), "orange-box"),
	}
	var errs []error

	port, err := strconv.Atoi(orDefault(getenv("PORT"), "8080"))
	if err != nil || port < 0 || port > 65535 {
		errs = append(errs, errors.New("PORT must be a number from 0 to 65535"))
	}
	c.Port = port

	switch v := getenv("AUTH_TEST_MODE"); v {
	case "", "false":
	case "true":
		c.AuthTestMode = true
	default:
		errs = append(errs, errors.New(`AUTH_TEST_MODE must be "true", "false" or unset`))
	}
	if c.AuthTestMode {
		if !c.IsDevEnvironment() {
			errs = append(errs, errors.New("AUTH_TEST_MODE=true is only allowed when ENV is exactly local, ci or sandbox"))
		}
		if err := ValidateTestCode(c.AuthTestCode); err != nil {
			errs = append(errs, fmt.Errorf("AUTH_TEST_CODE: %w", err))
		}
	}

	switch {
	case c.JWTPrivateKeyPEM == "" && !c.IsDevEnvironment():
		errs = append(errs, errors.New("JWT_PRIVATE_KEY is required unless ENV is local, ci or sandbox"))
	case c.JWTPrivateKeyPEM != "" && c.JWTKeyID == "":
		errs = append(errs, errors.New("JWT_KEY_ID is required with JWT_PRIVATE_KEY"))
	}

	if c.RequestTimeout, err = duration(getenv, "REQUEST_TIMEOUT", 30*time.Second); err != nil {
		errs = append(errs, err)
	}
	if c.ShutdownTimeout, err = duration(getenv, "SHUTDOWN_TIMEOUT", 8*time.Second); err != nil {
		errs = append(errs, err)
	}
	if c.UploadQueueWait, err = duration(getenv, "UPLOAD_QUEUE_WAIT", 2*time.Second); err != nil {
		errs = append(errs, err)
	}
	if c.UploadReadTimeout, err = duration(getenv, "UPLOAD_READ_TIMEOUT", 10*time.Second); err != nil {
		errs = append(errs, err)
	}
	c.UploadConcurrency, err = strconv.Atoi(orDefault(getenv("UPLOAD_CONCURRENCY"), "2"))
	if err != nil || c.UploadConcurrency < 1 || c.UploadConcurrency > 256 {
		errs = append(errs, errors.New("UPLOAD_CONCURRENCY must be a number from 1 to 256"))
	}
	c.BodyConcurrency, err = strconv.Atoi(orDefault(getenv("BODY_CONCURRENCY"), "16"))
	if err != nil || c.BodyConcurrency < 1 || c.BodyConcurrency > 1024 {
		errs = append(errs, errors.New("BODY_CONCURRENCY must be a number from 1 to 1024"))
	}
	c.AnonBodyConcurrency, err = strconv.Atoi(orDefault(getenv("ANON_BODY_CONCURRENCY"), "8"))
	if err != nil || c.AnonBodyConcurrency < 1 || c.AnonBodyConcurrency > 1024 {
		errs = append(errs, errors.New("ANON_BODY_CONCURRENCY must be a number from 1 to 1024"))
	}
	c.AnonReadConcurrency, err = strconv.Atoi(orDefault(getenv("ANON_READ_CONCURRENCY"), "1024"))
	if err != nil || c.AnonReadConcurrency < 1 || c.AnonReadConcurrency > 65536 {
		errs = append(errs, errors.New("ANON_READ_CONCURRENCY must be a number from 1 to 65536"))
	}
	if err := c.LogLevel.UnmarshalText([]byte(orDefault(getenv("LOG_LEVEL"), "info"))); err != nil {
		errs = append(errs, errors.New("LOG_LEVEL must be debug, info, warn or error"))
	}

	if len(errs) > 0 {
		return Config{}, fmt.Errorf("invalid configuration: %w", errors.Join(errs...))
	}
	return c, nil
}

// ValidateTestCode checks AUTH_TEST_CODE: exactly 6 ASCII digits, not all
// the same digit and not an ascending or descending run. The error never
// contains the code.
func ValidateTestCode(code string) error {
	if len(code) != 6 {
		return errors.New("must be exactly 6 digits")
	}
	for i := range len(code) {
		if code[i] < '0' || code[i] > '9' {
			return errors.New("must be exactly 6 digits")
		}
	}
	same, up, down := true, true, true
	for i := 1; i < len(code); i++ {
		d := int(code[i]) - int(code[i-1])
		same = same && d == 0
		up = up && d == 1
		down = down && d == -1
	}
	switch {
	case same:
		return errors.New("must not be the same digit repeated")
	case up, down:
		return errors.New("must not be an ascending or descending run")
	}
	return nil
}

func orDefault(v, def string) string {
	if strings.TrimSpace(v) == "" {
		return def
	}
	return v
}

func duration(getenv func(string) string, name string, def time.Duration) (time.Duration, error) {
	v := getenv(name)
	if v == "" {
		return def, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		return 0, fmt.Errorf("%s must be a positive duration such as 30s", name)
	}
	return d, nil
}
