package objstore

import (
	"context"
	"errors"
	"testing"
	"time"
)

// A Dir operation that hangs in the file system returns when its context
// ends (or after dirOpTimeout), rather than holding the request forever.
func TestDirHungOperations(t *testing.T) {
	t.Parallel()
	d, err := NewDir(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := d.Put(t.Context(), "k", []byte("v")); err != nil {
		t.Fatal(err)
	}
	release := make(chan struct{})
	t.Cleanup(func() { close(release) })
	d.hook = func() { <-release } // every file system call hangs

	ops := map[string]func(ctx context.Context) error{
		"put":    func(ctx context.Context) error { return d.Put(ctx, "k2", []byte("v")) },
		"get":    func(ctx context.Context) error { _, err := d.Get(ctx, "k"); return err },
		"delete": func(ctx context.Context) error { return d.Delete(ctx, "k") },
	}
	for name, op := range ops {
		ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
		start := time.Now()
		err := op(ctx)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 2*time.Second {
			t.Errorf("%s: %v after %v", name, err, time.Since(start))
		}
	}
	// Without a deadline the store's own timeout applies.
	d.timeout = 50 * time.Millisecond
	for name, op := range ops {
		start := time.Now()
		if err := op(context.Background()); !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 2*time.Second {
			t.Errorf("%s without a deadline: %v after %v", name, err, time.Since(start))
		}
	}
}
