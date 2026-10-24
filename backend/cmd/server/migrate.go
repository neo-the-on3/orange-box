package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/logging"
)

const migrateUsage = `usage: server migrate <command>

Applies the embedded database migrations to DATABASE_URL.

commands:
  up       apply every pending migration
  down     roll back the most recent migration
  status   list migrations and whether each is applied
  version  print the current schema version
`

// migrateCommand runs `server migrate ...` and returns the exit code. It
// reads only DATABASE_URL (not the full server config), so it can run as a
// release step with nothing but the database credentials.
func migrateCommand(args []string, getenv func(string) string, stdout io.Writer) int {
	logger := logging.New(stdout, slog.LevelInfo)
	if len(args) != 1 {
		_, _ = fmt.Fprint(stdout, migrateUsage)
		return 2
	}
	url := getenv("DATABASE_URL")
	if url == "" {
		logger.Error("migrate: DATABASE_URL is not set")
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	pool, err := db.Open(ctx, db.Options{URL: url, MaxConns: 2, ForMigrations: true})
	if err != nil {
		logger.Error("migrate: cannot connect", slog.Any("err", err))
		return 1
	}
	defer pool.Close()
	if err := runMigrate(ctx, pool, args[0], stdout, logger); err != nil {
		logger.Error("migrate failed", slog.String("command", args[0]), slog.Any("err", err))
		return 1
	}
	return 0
}

var errUnknownMigrateCommand = errors.New("unknown migrate command")

func runMigrate(ctx context.Context, pool *pgxpool.Pool, command string, stdout io.Writer, logger *slog.Logger) error {
	if command == "up" {
		return migrateUp(ctx, pool, logger)
	}
	m, err := db.NewMigrator(pool)
	if err != nil {
		return err
	}
	defer func() { _ = m.Close() }()
	switch command {
	case "down":
		res, err := m.Down(ctx)
		if err != nil {
			return err
		}
		logger.Info("rolled back migration", slog.String("file", res.Source.Path), slog.String("took", res.Duration.Round(time.Millisecond).String()))
	case "status":
		statuses, err := m.Status(ctx)
		if err != nil {
			return err
		}
		for _, s := range statuses {
			applied := "pending"
			if !s.AppliedAt.IsZero() {
				applied = "applied " + s.AppliedAt.UTC().Format(time.RFC3339)
			}
			_, _ = fmt.Fprintf(stdout, "%05d  %-40s %s\n", s.Source.Version, s.Source.Path, applied)
		}
	case "version":
		v, err := m.GetDBVersion(ctx)
		if err != nil {
			return err
		}
		_, _ = fmt.Fprintln(stdout, v)
	default:
		_, _ = fmt.Fprint(stdout, migrateUsage)
		return fmt.Errorf("%w %q", errUnknownMigrateCommand, command)
	}
	return nil
}

// migrateUp applies every pending migration, logging each one.
func migrateUp(ctx context.Context, pool *pgxpool.Pool, logger *slog.Logger) error {
	m, err := db.NewMigrator(pool)
	if err != nil {
		return err
	}
	defer func() { _ = m.Close() }()
	results, err := m.Up(ctx)
	if err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	for _, r := range results {
		logger.Info("applied migration", slog.String("file", r.Source.Path), slog.String("took", r.Duration.Round(time.Millisecond).String()))
	}
	if len(results) == 0 {
		logger.Info("database schema is up to date")
	}
	return nil
}
