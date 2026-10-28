package db_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
	"github.com/neo-the-on3/orange-box/backend/internal/store"
)

func newProfileMember(t *testing.T, s *db.Members, email string) uuid.UUID {
	t.Helper()
	m, _, err := s.FindOrCreateByEmail(context.Background(), email)
	if err != nil {
		t.Fatal(err)
	}
	return uuid.MustParse(m.ID)
}

func anyVersion(store.Profile) error { return nil }

func TestProfileStoreGetAndUpdate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	s := db.NewMembers(dbtest.New(t))
	member := newProfileMember(t, s, "profile@test.orangebox.invalid")

	p, err := s.GetProfile(ctx, member)
	if err != nil || p.ID != member || p.Email != "profile@test.orangebox.invalid" || p.AuthProvider != "email_otp" ||
		p.DisplayName != nil || p.CountryCode != nil || p.RankingOptIn || p.Version != 1 || p.CreatedAt.IsZero() {
		t.Fatalf("new member's profile: %+v %v", p, err)
	}
	up, err := s.UpdateProfile(ctx, member, anyVersion, func(f store.ProfileFields) store.ProfileFields {
		f.DisplayName, f.CountryCode, f.RegionCode, f.RankingOptIn = ptr("Kiran"), ptr("IN"), ptr("IN-KA"), true
		return f
	})
	if err != nil || up.Version != 2 || *up.DisplayName != "Kiran" || *up.CountryCode != "IN" || *up.RegionCode != "IN-KA" ||
		!up.RankingOptIn || up.UpdatedAt.Before(p.UpdatedAt) {
		t.Fatalf("update: %+v %v", up, err)
	}
	// A patch that changes nothing keeps the version (and so the ETag).
	same, err := s.UpdateProfile(ctx, member, anyVersion, func(f store.ProfileFields) store.ProfileFields { return f })
	if err != nil || same.Version != 2 || !same.UpdatedAt.Equal(up.UpdatedAt) {
		t.Fatalf("no-op: %+v %v", same, err)
	}
	// Clearing nullable fields.
	cleared, err := s.UpdateProfile(ctx, member, anyVersion, func(f store.ProfileFields) store.ProfileFields {
		f.CountryCode, f.RegionCode = nil, nil
		return f
	})
	if err != nil || cleared.Version != 3 || cleared.CountryCode != nil || cleared.RegionCode != nil || *cleared.DisplayName != "Kiran" {
		t.Fatalf("clear: %+v %v", cleared, err)
	}
	// check sees the current version and its error is returned as is.
	errStaleProfile := errors.New("stale")
	if _, err := s.UpdateProfile(ctx, member, func(cur store.Profile) error {
		if cur.Version != 3 {
			t.Errorf("check saw version %d", cur.Version)
		}
		return errStaleProfile
	}, func(f store.ProfileFields) store.ProfileFields { return f }); !errors.Is(err, errStaleProfile) {
		t.Fatalf("check error: %v", err)
	}
	if _, err := s.GetProfile(ctx, newID()); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("unknown member: %v", err)
	}
}

// Concurrent patches take turns under the row lock: none is lost and each
// bumps the version once.
func TestProfileStoreConcurrentUpdates(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	s := db.NewMembers(dbtest.New(t))
	member := newProfileMember(t, s, "busy@test.orangebox.invalid")
	var wg sync.WaitGroup
	for range 10 {
		wg.Go(func() {
			if _, err := s.UpdateProfile(ctx, member, anyVersion, func(f store.ProfileFields) store.ProfileFields {
				f.RankingOptIn = !f.RankingOptIn
				return f
			}); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	if p, err := s.GetProfile(ctx, member); err != nil || p.Version != 11 || p.RankingOptIn {
		t.Fatalf("after 10 toggles: %+v %v", p, err)
	}
}

func TestProfileStoreDeleteAccount(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	s := db.NewMembers(dbtest.New(t))
	member := newProfileMember(t, s, "leaving@test.orangebox.invalid")
	issued := time.Now().Add(-time.Minute)

	if revoked, err := s.TokenRevoked(ctx, member, issued); err != nil || revoked {
		t.Fatalf("live member's token: %v %v", revoked, err)
	}
	before := time.Now()
	at, err := s.DeleteAccount(ctx, member)
	if err != nil || at.Before(before.Add(-time.Second)) || at.After(time.Now().Add(time.Second)) {
		t.Fatalf("delete: %v %v", at, err)
	}
	// Every token is rejected from now on, even one issued later (a
	// deleted member can't sign in again: the address is detached).
	for _, iat := range []time.Time{issued, time.Now().Add(time.Minute)} {
		if revoked, err := s.TokenRevoked(ctx, member, iat); err != nil || !revoked {
			t.Fatalf("token issued %v after delete: %v %v", iat, revoked, err)
		}
	}
	if _, err := s.GetProfile(ctx, member); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("profile after delete: %v", err)
	}
	if _, err := s.UpdateProfile(ctx, member, anyVersion, func(f store.ProfileFields) store.ProfileFields { return f }); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("update after delete: %v", err)
	}
	if _, err := s.DeleteAccount(ctx, member); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
	// The address signs up again as a new, empty member.
	fresh := newProfileMember(t, s, "leaving@test.orangebox.invalid")
	if fresh == member {
		t.Fatal("the deleted member came back")
	}
	if p, err := s.GetProfile(ctx, fresh); err != nil || p.DisplayName != nil || p.Version != 1 {
		t.Fatalf("new member: %+v %v", p, err)
	}
	// A member that doesn't exist (erased) has no valid tokens.
	if revoked, err := s.TokenRevoked(ctx, newID(), issued); err != nil || !revoked {
		t.Fatalf("unknown member: %v %v", revoked, err)
	}
}

// tokens_valid_after (set by DELETE /v1/me today, by "log out everywhere"
// with #106) rejects tokens issued before it. JWT iat has whole seconds, so
// a token from the same second as the cut-off is rejected too.
func TestProfileStoreTokensValidAfter(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	s := db.NewMembers(pool)
	member := newProfileMember(t, s, "cutoff@test.orangebox.invalid")
	cut := time.Date(2026, 10, 28, 12, 0, 0, 500_000_000, time.UTC)
	if _, err := pool.Exec(ctx, `UPDATE members SET tokens_valid_after = $1 WHERE id = $2`, cut, member); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		iat     time.Time
		revoked bool
	}{
		{cut.Add(-time.Hour), true},
		{cut.Truncate(time.Second), true},
		{cut.Truncate(time.Second).Add(time.Second), false},
	} {
		if revoked, err := s.TokenRevoked(ctx, member, c.iat); err != nil || revoked != c.revoked {
			t.Errorf("iat %v: revoked=%v %v, want %v", c.iat, revoked, err, c.revoked)
		}
	}
}

// A patch racing DELETE /v1/me either lands before it or finds the member
// gone; it never resurrects or edits a deleted member.
func TestProfileStoreDeleteRacesUpdate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	s := db.NewMembers(dbtest.New(t))
	for i := range 20 {
		member := newProfileMember(t, s, "race"+uuid.NewString()[:8]+"@test.orangebox.invalid")
		var wg sync.WaitGroup
		var upErr, delErr error
		wg.Go(func() {
			_, upErr = s.UpdateProfile(ctx, member, anyVersion, func(f store.ProfileFields) store.ProfileFields {
				f.DisplayName = ptr("late")
				return f
			})
		})
		wg.Go(func() { _, delErr = s.DeleteAccount(ctx, member) })
		wg.Wait()
		if delErr != nil || (upErr != nil && !errors.Is(upErr, store.ErrNotFound)) {
			t.Fatalf("round %d: update %v, delete %v", i, upErr, delErr)
		}
		if revoked, err := s.TokenRevoked(ctx, member, time.Now()); err != nil || !revoked {
			t.Fatalf("round %d: member live after delete: %v %v", i, revoked, err)
		}
	}
}
