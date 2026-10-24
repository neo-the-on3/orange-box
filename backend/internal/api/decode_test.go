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
