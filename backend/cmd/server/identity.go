package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/neo-the-on3/orange-box/backend/internal/config"
	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
)

const identityUsage = `usage: server identity

Prints the sync identity digest this configuration computes (the same
"sync identity" line the server logs at startup), for deploy checks:
run it with each instance's environment and compare. It prints only the
digest and source, never system_identifier or SYNC_DEPLOYMENT_ID.
`

// identityCommand implements `server identity`.
// Only the result goes to stdout; usage and errors go to stderr, so a
// deploy script parsing stdout can't mistake them for it.
func identityCommand(args []string, getenv func(string) string, stdout, stderr io.Writer) int {
	logger := logging.New(stderr, slog.LevelWarn)
	if len(args) != 0 {
		_, _ = fmt.Fprint(stderr, identityUsage)
		return 2
	}
	cfg, err := config.Load(getenv)
	if err != nil {
		logger.Error("identity: invalid configuration", slog.Any("err", err))
		return 1
	}
	if cfg.DatabaseURL == "" {
		logger.Error("identity: DATABASE_URL is not set")
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	pool, err := db.Open(ctx, db.Options{URL: cfg.DatabaseURL, MaxConns: 1})
	if err != nil {
		logger.Error("identity: cannot connect", slog.Any("err", err))
		return 1
	}
	defer db.CloseWithin(pool, poolCloseTimeout)
	trips, err := db.NewTripsWith(ctx, pool, db.TripsOptions{
		DeploymentID: cfg.SyncDeploymentID, IdentitySource: db.IdentitySource(cfg.SyncIdentitySource), Logger: logger,
	})
	if err != nil {
		logger.Error("identity: cannot compute", slog.Any("err", err))
		return 1
	}
	_, _ = fmt.Fprintf(stdout, "identity=%s source=%s\n", trips.Identity(), trips.IdentitySource())
	return 0
}
