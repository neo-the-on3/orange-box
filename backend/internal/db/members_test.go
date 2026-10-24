package db_test

import (
	"context"
	"sync"
	"testing"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/auth"
	"github.com/neo-the-on3/orange-box/backend/internal/db"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbgen"
	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

func TestMembersFindOrCreate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	var store auth.MemberStore = db.NewMembers(dbtest.New(t))

	m, created, err := store.FindOrCreateByEmail(ctx, "ana@test.orangebox.invalid")
	if err != nil || !created {
		t.Fatalf("first sign-in: created=%v err=%v", created, err)
	}
	id, err := uuid.Parse(m.ID)
	if err != nil || id.Version() != 7 {
		t.Errorf("id %q is not a UUIDv7", m.ID)
	}
	if m.Email != "ana@test.orangebox.invalid" || m.CreatedAt.IsZero() || m.CreatedAt.Location().String() != "UTC" {
		t.Errorf("member = %+v", m)
	}

	again, created, err := store.FindOrCreateByEmail(ctx, "ana@test.orangebox.invalid")
	if err != nil || created || again.ID != m.ID || !again.CreatedAt.Equal(m.CreatedAt) {
		t.Fatalf("second sign-in: %+v created=%v err=%v, want %+v", again, created, err, m)
	}

	other, created, err := store.FindOrCreateByEmail(ctx, "bo@test.orangebox.invalid")
	if err != nil || !created || other.ID == m.ID {
		t.Fatalf("other address: %+v created=%v err=%v", other, created, err)
	}

	if _, _, err := store.FindOrCreateByEmail(ctx, ""); err == nil {
		t.Error("empty address accepted")
	}
}

func TestMembersConcurrentFirstSignIn(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	store := db.NewMembers(dbtest.New(t))

	const n = 16
	var wg sync.WaitGroup
	ids := make([]string, n)
	createdCount := make([]bool, n)
	errs := make([]error, n)
	for i := range n {
		wg.Go(func() {
			m, created, err := store.FindOrCreateByEmail(ctx, "race@test.orangebox.invalid")
			ids[i], createdCount[i], errs[i] = m.ID, created, err
		})
	}
	wg.Wait()
	created := 0
	for i := range n {
		if errs[i] != nil {
			t.Fatalf("call %d: %v", i, errs[i])
		}
		if ids[i] != ids[0] {
			t.Fatalf("call %d got member %s, call 0 got %s", i, ids[i], ids[0])
		}
		if createdCount[i] {
			created++
		}
	}
	if created != 1 {
		t.Errorf("%d calls reported a new member, want 1", created)
	}
}

func TestDeletedMemberAddressSignsUpAgain(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	store := db.NewMembers(pool)
	q := dbgen.New(pool)

	old, _, err := store.FindOrCreateByEmail(ctx, "gone@test.orangebox.invalid")
	if err != nil {
		t.Fatal(err)
	}
	oldID := uuid.MustParse(old.ID)
	vehicleID := insertVehicle(t, q, oldID, nil)

	deleted, err := q.MarkMemberDeleted(ctx, oldID)
	if err != nil || deleted.DeletedAt == nil {
		t.Fatalf("MarkMemberDeleted: %+v, %v", deleted, err)
	}
	state, err := q.GetMemberAuthState(ctx, oldID)
	if err != nil || state.DeletedAt == nil || state.TokensValidAfter == nil {
		t.Fatalf("auth state after delete: %+v, %v", state, err)
	}
	if _, err := q.GetMember(ctx, oldID); err == nil {
		t.Error("GetMember returns a deleted member")
	}

	fresh, created, err := store.FindOrCreateByEmail(ctx, "gone@test.orangebox.invalid")
	if err != nil || !created || fresh.ID == old.ID {
		t.Fatalf("sign-up after deletion: %+v created=%v err=%v", fresh, created, err)
	}

	// Erasure removes the old member and, by cascade, what it owned.
	n, err := q.EraseMember(ctx, oldID)
	if err != nil || n != 1 {
		t.Fatalf("EraseMember = %d, %v", n, err)
	}
	if _, err := q.GetVehicle(ctx, dbgen.GetVehicleParams{MemberID: oldID, ID: vehicleID}); err == nil {
		t.Error("vehicle survived erasure")
	}
	// A live member is never erased.
	if n, err := q.EraseMember(ctx, uuid.MustParse(fresh.ID)); err != nil || n != 0 {
		t.Errorf("EraseMember(live) = %d, %v", n, err)
	}
}

func TestUpdateMemberProfile(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	pool := dbtest.New(t)
	q := dbgen.New(pool)
	id := newMember(t, q)

	name, country, region, optIn := "Ana", "IN", "IN-KA", true
	m, err := q.UpdateMemberProfile(ctx, dbgen.UpdateMemberProfileParams{
		ID: id, ExpectedVersion: 1,
		SetDisplayName: true, DisplayName: &name,
		SetCountryCode: true, CountryCode: &country,
		SetRegionCode: true, RegionCode: &region,
		RankingOptIn: &optIn,
	})
	if err != nil || m.Version != 2 || *m.DisplayName != "Ana" || *m.CountryCode != "IN" || !m.RankingOptIn {
		t.Fatalf("update: %+v, %v", m, err)
	}
	// Stale version: no row.
	if _, err := q.UpdateMemberProfile(ctx, dbgen.UpdateMemberProfileParams{ID: id, ExpectedVersion: 1, SetRegionCode: true}); err == nil {
		t.Error("stale version accepted")
	}
	// Clearing region only; everything else unchanged.
	m, err = q.UpdateMemberProfile(ctx, dbgen.UpdateMemberProfileParams{ID: id, ExpectedVersion: 2, SetRegionCode: true})
	if err != nil || m.RegionCode != nil || *m.CountryCode != "IN" || *m.DisplayName != "Ana" || !m.RankingOptIn {
		t.Fatalf("clear region: %+v, %v", m, err)
	}
	// The schema enforces the spec's own limits as a backstop.
	bad := "in"
	if _, err := q.UpdateMemberProfile(ctx, dbgen.UpdateMemberProfileParams{ID: id, ExpectedVersion: 3, SetCountryCode: true, CountryCode: &bad}); err == nil {
		t.Error("lower-case country code stored")
	}
}
