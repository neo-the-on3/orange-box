package api

import (
	"errors"
	"io"
	"strings"
	"testing"
)

func TestDecodeJSONBody(t *testing.T) {
	t.Parallel()
	var b TripBatch
	if err := decodeJSONBody(strings.NewReader(`{"TRIPS":[{"state":"completed"}]}`), &b); err != nil || len(b.Trips) != 0 {
		t.Errorf("case variant decoded into Trips: %v %+v", err, b)
	}
	if err := decodeJSONBody(strings.NewReader(`{"trips":[],"trips":[{}]}`), &b); err == nil {
		t.Error("duplicate name accepted")
	}
	for _, empty := range []string{"", "  \n"} {
		if err := decodeJSONBody(strings.NewReader(empty), &b); !errors.Is(err, io.EOF) {
			t.Errorf("empty body %q: %v, want io.EOF", empty, err)
		}
	}
	if err := decodeJSONBody(strings.NewReader(`{"trips":[]} {}`), &b); err == nil {
		t.Error("trailing data accepted")
	}
	if err := decodeJSONBody(strings.NewReader(`{"trips":[{"state":"completed"}]}`), &b); err != nil || len(b.Trips) != 1 {
		t.Errorf("valid body: %v %+v", err, b)
	}
}

// Merge patches must tell an explicit null (clear the field) from an absent
// one (leave it alone).
func TestDecodePatchNullVersusAbsent(t *testing.T) {
	t.Parallel()
	var absent, null, set ProfilePatch
	for body, v := range map[string]*ProfilePatch{
		`{"displayName":"A"}`:  &absent,
		`{"countryCode":null}`: &null,
		`{"countryCode":"IN"}`: &set,
	} {
		if err := decodeJSONBody(strings.NewReader(body), v); err != nil {
			t.Fatalf("%s: %v", body, err)
		}
	}
	if absent.CountryCode.IsSpecified() {
		t.Error("absent countryCode is specified")
	}
	if !null.CountryCode.IsSpecified() || !null.CountryCode.IsNull() {
		t.Error("null countryCode isn't an explicit null")
	}
	if v, err := set.CountryCode.Get(); err != nil || v != "IN" {
		t.Errorf("countryCode = %q, %v", v, err)
	}
	var vp VehiclePatch
	if err := decodeJSONBody(strings.NewReader(`{"plateNumber":null}`), &vp); err != nil || !vp.PlateNumber.IsNull() {
		t.Errorf("VehiclePatch plateNumber: %v %+v", err, vp)
	}
}
