// Command server runs the Orange Box REST API.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/config"
	"github.com/neo-the-on3/orange-box/backend/internal/httpapi"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
)

func main() {
	os.Exit(serve())
}

// serve loads the config, binds the listener and runs the server. It
// returns the process exit code.
func serve() int {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		logging.New(os.Stderr, slog.LevelInfo).Error("refusing to start", slog.Any("err", err))
		return 1
	}
	logger := logging.New(os.Stdout, cfg.LogLevel)
	slog.SetDefault(logger)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		// After the first signal, restore default handling so a second
		// Ctrl-C (or SIGTERM) kills the process instead of waiting for
		// in-flight requests to drain.
		<-ctx.Done()
		stop()
	}()

	var lc net.ListenConfig
	ln, err := lc.Listen(ctx, "tcp", net.JoinHostPort("", strconv.Itoa(cfg.Port)))
	if err != nil {
		logger.Error("listen failed", slog.Any("err", err))
		return 1
	}
	if err := run(ctx, ln, cfg, logger); err != nil {
		logger.Error("server exited", slog.Any("err", err))
		return 1
	}
	return 0
}

// run serves on ln until ctx is cancelled, then shuts down gracefully within
// cfg.ShutdownTimeout. It owns ln.
func run(ctx context.Context, ln net.Listener, cfg config.Config, logger *slog.Logger) error {
	handler, err := newHandler(cfg, logger)
	if err != nil {
		_ = ln.Close()
		return err
	}
	srv := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      cfg.RequestTimeout + 5*time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    64 << 10,
		ErrorLog:          slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
		BaseContext:       func(net.Listener) context.Context { return context.WithoutCancel(ctx) },
	}

	// The listener is already bound, so this is the real address (PORT=0 too)
	// and connections made from now on queue until Serve accepts them.
	logger.Info("listening", slog.String("addr", ln.Addr().String()))
	errCh := make(chan error, 1)
	go func() {
		errCh <- srv.Serve(ln)
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}

	logger.Info("shutting down", slog.Duration("timeout", cfg.ShutdownTimeout))
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), cfg.ShutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		_ = srv.Close()
		return fmt.Errorf("graceful shutdown: %w", err)
	}
	if err := <-errCh; !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	logger.Info("stopped")
	return nil
}

func newHandler(cfg config.Config, logger *slog.Logger) (http.Handler, error) {
	tokens, err := newTokens(cfg, logger)
	if err != nil {
		return nil, err
	}
	if cfg.AuthTestMode {
		logger.Warn("AUTH_TEST_MODE is on: @test.orangebox.invalid addresses sign in with the static test code",
			slog.String("env", cfg.Env))
	}
	return httpapi.NewRouter(httpapi.Deps{
		Logger:         logger,
		Tokens:         tokens,
		Members:        auth.NewMemoryMembers(), // Postgres store arrives with #105
		Codes:          auth.NewPendingCodes(),
		TestMode:       cfg.AuthTestMode,
		TestCode:       cfg.AuthTestCode,
		RequestTimeout: cfg.RequestTimeout,
	})
}

func newTokens(cfg config.Config, logger *slog.Logger) (*auth.Tokens, error) {
	if cfg.JWTPrivateKeyPEM == "" {
		// config.Load only allows this in local, ci and sandbox.
		key, kid, err := auth.GenerateDevKey()
		if err != nil {
			return nil, err
		}
		logger.Warn("JWT_PRIVATE_KEY not set: using an ephemeral signing key; tokens stop working on restart",
			slog.String("kid", kid))
		return auth.NewTokens(cfg.JWTIssuer, kid, key)
	}
	key, err := auth.ParsePrivateKeyPEM(cfg.JWTPrivateKeyPEM)
	if err != nil {
		return nil, err
	}
	return auth.NewTokens(cfg.JWTIssuer, cfg.JWTKeyID, key)
}
