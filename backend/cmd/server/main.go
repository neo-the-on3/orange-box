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
	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/httpapi"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
	storage "github.com/neo-the-on3/orange-box/backend/internal/store"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "migrate" {
		os.Exit(migrateCommand(os.Args[2:], os.Getenv, os.Stdout))
	}
	if len(os.Args) > 1 && os.Args[1] == "identity" {
		os.Exit(identityCommand(os.Args[2:], os.Getenv, os.Stdout, os.Stderr))
	}
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
	store, err := openStore(ctx, cfg, logger)
	if err != nil {
		_ = ln.Close()
		return err
	}
	defer store.close()
	handler, err := newHandler(cfg, logger, store)
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

	logger.Info("shutting down", slog.String("timeout", cfg.ShutdownTimeout.String()))
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

// poolCloseTimeout bounds closing the database pool, at shutdown (after
// the HTTP drain, within SHUTDOWN_TIMEOUT + 1 s < Docker's 10 s grace) and
// when startup fails: a hung database otherwise holds each close ~15 s.
const poolCloseTimeout = time.Second

// store is the storage the handlers use.
type store struct {
	members     auth.MemberStore
	vehicles    storage.Vehicles            // nil without a database
	trips       *db.Trips                   // nil without a database
	idempotency storage.Idempotency         // nil without a database
	classify    func(error) storage.Failure // nil without a database
	ready       func(context.Context) error // nil when there is nothing to check
	close       func()
}

// openStore picks the storage: Postgres when DATABASE_URL is set
// (config.Load requires it outside dev environments), in-memory otherwise.
func openStore(ctx context.Context, cfg config.Config, logger *slog.Logger) (store, error) {
	if cfg.DatabaseURL == "" {
		logger.Warn("DATABASE_URL not set: members are kept in memory and lost on restart, and the vehicle and trip operations answer 501")
		return store{members: auth.NewMemoryMembers(), close: func() {}}, nil
	}
	// Pins the pool to the cluster the sync identity binds (blue/green or
	// failover behind the same address); not possible without
	// system_identifier (deployment_id_only).
	var guard *db.ClusterGuard
	if db.IdentitySource(cfg.SyncIdentitySource) == db.IdentityClusterAndDeploymentID {
		guard = db.NewClusterGuard(db.ClusterGuardOptions{Logger: logger, DeploymentID: cfg.SyncDeploymentID})
	}
	pool, err := db.Open(ctx, db.Options{URL: cfg.DatabaseURL, MaxConns: cfg.DBMaxConns, Guard: guard})
	if err != nil {
		return store{}, err
	}
	if cfg.MigrateOnStart {
		// Its own pool without the statement timeout (#178).
		mpool, err := db.Open(ctx, db.Options{URL: cfg.DatabaseURL, MaxConns: 2, ForMigrations: true})
		if err != nil {
			db.CloseWithin(pool, poolCloseTimeout)
			return store{}, err
		}
		err = migrateUp(ctx, mpool, logger)
		db.CloseWithin(mpool, poolCloseTimeout)
		if err != nil {
			db.CloseWithin(pool, poolCloseTimeout)
			return store{}, err
		}
	}
	if err := db.CheckMigrated(ctx, pool); err != nil {
		db.CloseWithin(pool, poolCloseTimeout)
		return store{}, err
	}
	trips, err := db.NewTripsWith(ctx, pool, db.TripsOptions{
		DeploymentID: cfg.SyncDeploymentID, IdentitySource: db.IdentitySource(cfg.SyncIdentitySource),
		Guard: guard, Logger: logger,
	})
	if err != nil {
		db.CloseWithin(pool, poolCloseTimeout)
		return store{}, err
	}
	// The digest only (no system_identifier or deployment id): every
	// instance of a deployment must log the same one.
	logger.Info("sync identity", slog.String("identity", trips.Identity()), slog.String("source", string(trips.IdentitySource())))
	if cfg.SyncDeploymentIDDefaulted {
		logger.Warn("SYNC_DEPLOYMENT_ID is unset: using the dev-only default, which never changes; " +
			"set a new SYNC_DEPLOYMENT_ID after restoring this database, or old sync tokens stay valid")
	}
	guardCtx, stopGuard := context.WithCancel(context.WithoutCancel(ctx))
	if guard != nil {
		go guard.Run(guardCtx, cfg.SyncClusterCheckInterval)
		logger.Info("checking the database cluster", slog.String("interval", cfg.SyncClusterCheckInterval.String()))
	}
	logger.Info("using Postgres", slog.Int("max_conns", cfg.DBMaxConns))
	return store{
		trips:       trips,
		members:     db.NewMembers(pool),
		vehicles:    db.NewVehicles(pool),
		idempotency: db.NewIdempotency(pool),
		classify:    db.Classify,
		ready: func(ctx context.Context) error {
			if err := guard.Err(); err != nil {
				return err
			}
			return pool.Ping(ctx)
		},
		close: func() {
			stopGuard()
			if !db.CloseWithin(pool, poolCloseTimeout) {
				logger.Warn("database pool did not close in time; exiting anyway", slog.String("timeout", poolCloseTimeout.String()))
			}
		},
	}, nil
}

func newHandler(cfg config.Config, logger *slog.Logger, st store) (http.Handler, error) {
	tokens, err := newTokens(cfg, logger)
	if err != nil {
		return nil, err
	}
	if cfg.SyncTokenKey == nil {
		// config.Load only allows this in local, ci and sandbox.
		logger.Warn("SYNC_TOKEN_KEY not set: using an ephemeral key; sync tokens expire on restart")
	}
	if cfg.AuthTestMode {
		logger.Warn("AUTH_TEST_MODE is on: @test.orangebox.invalid addresses sign in with the static test code",
			slog.String("env", cfg.Env))
	}
	return httpapi.NewRouter(httpapi.Deps{
		Logger:              logger,
		Tokens:              tokens,
		Members:             st.members,
		Vehicles:            st.vehicles,
		Trips:               tripStore(st.trips),
		SyncTokenKey:        cfg.SyncTokenKey,
		Idempotency:         st.idempotency,
		ClassifyError:       st.classify,
		Ready:               st.ready,
		Codes:               auth.NewPendingCodes(),
		TestMode:            cfg.AuthTestMode,
		TestCode:            cfg.AuthTestCode,
		RequestTimeout:      cfg.RequestTimeout,
		UploadConcurrency:   cfg.UploadConcurrency,
		UploadQueueWait:     cfg.UploadQueueWait,
		UploadReadTimeout:   cfg.UploadReadTimeout,
		BodyConcurrency:     cfg.BodyConcurrency,
		AnonBodyConcurrency: cfg.AnonBodyConcurrency,
		AnonReadConcurrency: cfg.AnonReadConcurrency,
	})
}

// tripStore keeps a nil *db.Trips a nil interface.
func tripStore(t *db.Trips) storage.Trips {
	if t == nil {
		return nil
	}
	return t
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
