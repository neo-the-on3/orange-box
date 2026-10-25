package db_test

import (
	"context"
	"net"
	"net/url"
	"sync/atomic"
	"testing"
	"time"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

// freezingProxy forwards TCP to target until frozen, then stops moving
// bytes while keeping the connections open: a hung database.
func freezingProxy(t *testing.T, target string) (addr string, frozen *atomic.Bool) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	frozen = new(atomic.Bool)
	pipe := func(dst, src net.Conn) {
		buf := make([]byte, 32<<10)
		for {
			n, err := src.Read(buf)
			for frozen.Load() {
				time.Sleep(10 * time.Millisecond) // hold the bytes; never answer
			}
			if n > 0 {
				if _, werr := dst.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			s, err := net.Dial("tcp", target)
			if err != nil {
				_ = c.Close()
				continue
			}
			t.Cleanup(func() { _ = c.Close(); _ = s.Close() })
			go pipe(s, c)
			go pipe(c, s)
		}
	}()
	return ln.Addr().String(), frozen
}

// TestCloseWithinHungDatabase: closing a pool whose database hangs takes
// at most the given bound (pgx alone waits ~15 s per connection).
func TestCloseWithinHungDatabase(t *testing.T) {
	t.Parallel()
	u, err := url.Parse(dbtest.NewDatabase(t))
	if err != nil {
		t.Fatal(err)
	}
	addr, frozen := freezingProxy(t, u.Host)
	u.Host = addr
	pool, err := db.Open(t.Context(), db.Options{URL: u.String(), MaxConns: 2})
	if err != nil {
		t.Fatal(err)
	}
	frozen.Store(true)
	// A query that hangs and times out, as in the startup identity read.
	qctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	_, qerr := pool.Exec(qctx, "SELECT 1")
	cancel()
	if qerr == nil {
		t.Fatal("query through the frozen proxy succeeded")
	}
	start := time.Now()
	finished := db.CloseWithin(pool, 300*time.Millisecond)
	d := time.Since(start)
	t.Logf("CloseWithin: finished %v after %v", finished, d)
	if d > 2*time.Second {
		t.Fatalf("CloseWithin took %v", d)
	}
	// Measured: a plain pool.Close here takes ~15 s.
	t.Cleanup(func() { frozen.Store(false) })
}
