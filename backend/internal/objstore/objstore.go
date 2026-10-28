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
)

// ErrNotFound means no object has the key.
var ErrNotFound = errors.New("objstore: not found")

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
type Dir struct {
	root string
}

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
	return &Dir{root: abs}, nil
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
	if err := ctx.Err(); err != nil {
		return err
	}
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
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	b, err := os.ReadFile(p) //nolint:gosec // G304: p is d.path(key), and ValidateKey rejects empty, . and .. segments
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	return b, err
}

// Delete implements Store.
func (d *Dir) Delete(ctx context.Context, key string) error {
	p, err := d.path(key)
	if err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
