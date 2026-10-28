// Package objstore stores opaque objects (raw telemetry batches) by key.
//
// Production uses Cloudflare R2 through its S3-compatible API (NewS3).
// Tests and local development use Memory or Dir, so neither needs R2
// credentials.
package objstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ErrNotFound means no object has the key.
var ErrNotFound = errors.New("objstore: not found")

// ErrBusy means a Dir store already has as many file system calls in
// flight as it allows (they are hung or slow); retry later.
var ErrBusy = errors.New("objstore: too many operations in flight")

// ErrInvalidKey means a key is empty, too long, or has a character or
// path segment Store implementations don't accept.
var ErrInvalidKey = errors.New("objstore: invalid key")

// Store is an object store. Put is durable when it returns nil.
type Store interface {
	// Put stores body under key, replacing any object with that key.
	Put(ctx context.Context, key string, body []byte) error
	// Get returns the object, or ErrNotFound.
	Get(ctx context.Context, key string) ([]byte, error)
	// Delete removes the object; a missing one is not an error.
	Delete(ctx context.Context, key string) error
}

// maxKeyLen is S3's (and R2's) key limit in bytes.
const maxKeyLen = 1024

// ValidateKey accepts keys of ASCII letters, digits and "._-" in
// "/"-separated segments: no empty, "." or ".." segment, so a key is also
// a safe relative file path.
func ValidateKey(key string) error {
	if key == "" || len(key) > maxKeyLen {
		return ErrInvalidKey
	}
	for seg := range strings.SplitSeq(key, "/") {
		if seg == "" || seg == "." || seg == ".." {
			return ErrInvalidKey
		}
		for _, r := range seg {
			ok := r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '.' || r == '_' || r == '-'
			if !ok {
				return ErrInvalidKey
			}
		}
	}
	return nil
}

// Memory is an in-memory Store for tests and local development. Objects
// are lost on restart.
type Memory struct {
	mu      sync.Mutex
	objects map[string][]byte
}

// NewMemory returns an empty Memory store.
func NewMemory() *Memory { return &Memory{objects: map[string][]byte{}} }

// Put implements Store.
func (m *Memory) Put(ctx context.Context, key string, body []byte) error {
	if err := ValidateKey(key); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.objects[key] = bytes.Clone(body)
	return nil
}

// Get implements Store.
func (m *Memory) Get(ctx context.Context, key string) ([]byte, error) {
	if err := ValidateKey(key); err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	b, ok := m.objects[key]
	if !ok {
		return nil, ErrNotFound
	}
	return bytes.Clone(b), nil
}

// Delete implements Store.
func (m *Memory) Delete(ctx context.Context, key string) error {
	if err := ValidateKey(key); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.objects, key)
	return nil
}

// Keys returns the stored keys in order (tests).
func (m *Memory) Keys() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return slices.Sorted(maps.Keys(m.objects))
}

// Dir is a Store in a local directory, one file per object, for local
// development without R2. Writes are atomic (temp file, fsync, rename).
//
// A file system call can't be interrupted, so each operation runs in its
// own goroutine and Dir stops waiting for it when the context ends, or
// after dirOpTimeout without a deadline: a hung disk or network mount
// then answers like a slow R2 (the caller's 503), instead of holding the
// request and its upload slot.
//
// An abandoned call keeps running, and keeps an OS thread while it is
// blocked in the kernel, so at most maxInflight calls run at once; past
// that, operations fail at once with ErrBusy until calls return. A Put
// whose caller gave up was never acknowledged (no batch row refers to
// it), so if it completes later its object is removed again.
type Dir struct {
	root     string
	timeout  time.Duration
	sem      chan struct{} // one slot per file system call in flight
	inflight sync.WaitGroup
	hook     func() // tests: runs before each file system operation
}

const (
	// dirOpTimeout bounds a Dir operation whose context has no deadline
	// (or a later one).
	dirOpTimeout = 30 * time.Second
	// maxInflight bounds a Dir's file system calls in flight, hung ones
	// included.
	maxInflight = 64
)

// Call states, for the race between the caller giving up and the call
// returning.
const (
	callRunning int32 = iota
	callReturned
	callAbandoned
)

// run runs op in a goroutine and returns its error, or the context's once
// it ends first. If the caller gave up and op then succeeds, abandoned
// (if not nil) runs to undo it.
func (d *Dir) run(ctx context.Context, op func() error, abandoned func()) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case d.sem <- struct{}{}:
	default:
		return ErrBusy
	}
	ctx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	var state atomic.Int32
	done := make(chan error, 1)
	d.inflight.Add(1)
	go func() {
		defer d.inflight.Done()
		defer func() { <-d.sem }()
		if d.hook != nil {
			d.hook()
		}
		err := op()
		if !state.CompareAndSwap(callRunning, callReturned) && err == nil && abandoned != nil {
			abandoned()
		}
		done <- err
	}()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		if state.CompareAndSwap(callRunning, callAbandoned) {
			return ctx.Err()
		}
		return <-done // it returned just as the context ended
	}
}

// wait waits for every file system call in flight, abandoned ones
// included (tests).
func (d *Dir) wait() { d.inflight.Wait() }

// NewDir returns a Store rooted at dir, creating it if needed.
func NewDir(dir string) (*Dir, error) {
	if dir == "" {
		return nil, errors.New("objstore: empty directory")
	}
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0o700); err != nil {
		return nil, fmt.Errorf("objstore: create %s: %w", abs, err)
	}
	return &Dir{root: abs, timeout: dirOpTimeout, sem: make(chan struct{}, maxInflight)}, nil
}

func (d *Dir) path(key string) (string, error) {
	if err := ValidateKey(key); err != nil {
		return "", err
	}
	return filepath.Join(d.root, filepath.FromSlash(key)), nil
}

// Put implements Store.
func (d *Dir) Put(ctx context.Context, key string, body []byte) error {
	p, err := d.path(key)
	if err != nil {
		return err
	}
	return d.run(ctx, func() error { return put(p, body) }, func() { _ = os.Remove(p) })
}

func put(p string, body []byte) error {
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(p), ".put-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer func() { _ = os.Remove(tmp) }() // a no-op after the rename
	if _, err := f.Write(body); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

// Get implements Store.
func (d *Dir) Get(ctx context.Context, key string) ([]byte, error) {
	p, err := d.path(key)
	if err != nil {
		return nil, err
	}
	var b []byte
	err = d.run(ctx, func() error {
		var err error
		b, err = os.ReadFile(p) //nolint:gosec // G304: p is d.path(key), and ValidateKey rejects empty, . and .. segments
		return err
	}, nil)
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return b, nil
}

// Delete implements Store.
func (d *Dir) Delete(ctx context.Context, key string) error {
	p, err := d.path(key)
	if err != nil {
		return err
	}
	return d.run(ctx, func() error {
		if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}, nil)
}
