package objstore

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// hangingDir returns a Dir whose file system calls block until release is
// called. Cleanup releases them and waits for them before t.TempDir is
// removed (cleanups run last-registered first), so no abandoned call
// writes into a directory being removed.
func hangingDir(t *testing.T) (d *Dir, release func()) {
	t.Helper()
	root := t.TempDir()
	d, err := NewDir(root)
	if err != nil {
		t.Fatal(err)
	}
	gate := make(chan struct{})
	released := false
	release = func() {
		if !released {
			released = true
			close(gate)
		}
	}
	t.Cleanup(func() {
		release()
		d.wait()
	})
	d.hook = func() { <-gate }
	return d, release
}

// A Dir operation that hangs in the file system returns when its context
// ends (or after the store's timeout), rather than holding the request.
func TestDirHungOperations(t *testing.T) {
	t.Parallel()
	d, _ := hangingDir(t)
	ops := map[string]func(ctx context.Context) error{
		"put":    func(ctx context.Context) error { return d.Put(ctx, "k2", []byte("v")) },
		"get":    func(ctx context.Context) error { _, err := d.Get(ctx, "k"); return err },
		"delete": func(ctx context.Context) error { return d.Delete(ctx, "k") },
	}
	for name, op := range ops {
		ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
		start := time.Now()
		err := op(ctx)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 2*time.Second {
			t.Errorf("%s: %v after %v", name, err, time.Since(start))
		}
	}
	// Without a deadline the store's own timeout applies.
	d.timeout = 20 * time.Millisecond
	for name, op := range ops {
		start := time.Now()
		if err := op(context.Background()); !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 2*time.Second {
			t.Errorf("%s without a deadline: %v after %v", name, err, time.Since(start))
		}
	}
}

// A Put whose caller gave up was never acknowledged, so when it finishes
// late its object is removed rather than left behind (and no temp file
// stays either).
func TestDirAbandonedPutLeavesNothing(t *testing.T) {
	t.Parallel()
	d, release := hangingDir(t)
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	if err := d.Put(ctx, "a/b/late", []byte("v")); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("put: %v", err)
	}
	release()
	d.wait()
	var left []string
	_ = filepath.WalkDir(d.root, func(p string, e os.DirEntry, err error) error {
		if err == nil && !e.IsDir() {
			left = append(left, p)
		}
		return nil
	})
	if len(left) != 0 {
		t.Errorf("files left after an abandoned put: %v", left)
	}
	// A Put that returns in time is kept.
	d.hook = nil
	if err := d.Put(t.Context(), "a/b/kept", []byte("v")); err != nil {
		t.Fatal(err)
	}
	if b, err := d.Get(t.Context(), "a/b/kept"); err != nil || string(b) != "v" {
		t.Errorf("kept: %q %v", b, err)
	}
}

// At most maxInflight file system calls run at once: when hung calls hold
// every slot, new operations fail at once with ErrBusy instead of starting
// more (each hung call holds an OS thread), and work again once the calls
// return.
func TestDirInflightLimit(t *testing.T) {
	t.Parallel()
	d, release := hangingDir(t)
	d.sem = make(chan struct{}, 3)
	for range 3 {
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Millisecond)
		_, _ = d.Get(ctx, "k")
		cancel()
	}
	start := time.Now()
	for name, err := range map[string]error{
		"put":    d.Put(t.Context(), "k", []byte("v")),
		"get":    func() error { _, err := d.Get(t.Context(), "k"); return err }(),
		"delete": d.Delete(t.Context(), "k"),
	} {
		if !errors.Is(err, ErrBusy) {
			t.Errorf("%s with every slot hung: %v", name, err)
		}
	}
	if time.Since(start) > time.Second {
		t.Errorf("busy operations took %v; they should fail at once", time.Since(start))
	}
	release()
	d.wait()
	d.hook = nil
	if err := d.Put(t.Context(), "k", []byte("v")); err != nil {
		t.Errorf("after the hung calls returned: %v", err)
	}
}
