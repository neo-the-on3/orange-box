package httpapi

import (
	"errors"
	"fmt"

	"github.com/getkin/kin-openapi/openapi3"
)

// Server-side size limits on trip fields that the spec (openapi-v2.yaml)
// leaves unbounded. They are proposed for the spec on #161; until it adopts
// them the server adds them to its in-memory copy of the spec at startup,
// so prescan (maxItems, before decoding) and validation (maxLength) enforce
// them like any other schema limit: 422 validation_failed with a pointer.
// Without them one member could store 8 MiB trips and every page of
// GET /v1/trips would cost hundreds of MiB to serve.
const (
	maxTripVehicleIDLength    = 64  // server ids are 36-character UUIDs
	maxTripAppVersionLength   = 64  // e.g. 1.4.2 (1402)
	maxTripAppOSLength        = 32  // e.g. android
	maxTripAppOSVersionLength = 128 // e.g. Android 15 / build strings
	maxTripSpeedBands         = 32  // the legacy app has 12
	maxDtcCodesPerList        = 128 // per stored/pending/permanent list
)

// Limits on a trip as a whole. maxTripBytes is well above the largest trip
// the field limits allow (about 7 KiB, see TestTripSizeLimits), so it
// only matters if the schema limits are ever loosened.
var (
	// maxTripBytes caps one trip's JSON encoding (as stored and served).
	// A variable so tests can lower it.
	maxTripBytes = 16 << 10
)

const (
	// tripPageBudget caps the JSON of a page of trips: a page ends early,
	// with a cursor, before it would exceed it (it always has at least one
	// trip).
	tripPageBudget = 512 << 10
)

// applyServerLimits adds the limits above to the loaded spec. It fails if
// the spec no longer has the fields, so a spec change can't drop them
// silently.
func applyServerLimits(doc *openapi3.T) error {
	if doc.Components == nil {
		return errors.New("spec has no components")
	}
	schema := func(name string) (*openapi3.Schema, error) {
		ref := doc.Components.Schemas[name]
		if ref == nil || ref.Value == nil {
			return nil, fmt.Errorf("spec has no schema %s", name)
		}
		return ref.Value, nil
	}
	prop := func(s *openapi3.Schema, path, name string) (*openapi3.Schema, error) {
		ref := s.Properties[name]
		if ref == nil || ref.Value == nil {
			return nil, fmt.Errorf("spec has no %s.%s", path, name)
		}
		return ref.Value, nil
	}
	maxLength := func(s *openapi3.Schema, path, name string, n uint64) error {
		p, err := prop(s, path, name)
		if err != nil {
			return err
		}
		if p.MaxLength == nil || *p.MaxLength > n {
			p.MaxLength = &n
		}
		return nil
	}
	maxItems := func(s *openapi3.Schema, path, name string, n uint64) error {
		p, err := prop(s, path, name)
		if err != nil {
			return err
		}
		if p.MaxItems == nil || *p.MaxItems > n {
			p.MaxItems = &n
		}
		return nil
	}

	trip, err := schema("TripUpload")
	if err != nil {
		return err
	}
	app, err := prop(trip, "TripUpload", "app")
	if err != nil {
		return err
	}
	dtc, err := schema("DtcSet")
	if err != nil {
		return err
	}
	return errors.Join(
		maxLength(trip, "TripUpload", "vehicleId", maxTripVehicleIDLength),
		maxItems(trip, "TripUpload", "speedBands", maxTripSpeedBands),
		maxLength(app, "TripUpload.app", "version", maxTripAppVersionLength),
		maxLength(app, "TripUpload.app", "os", maxTripAppOSLength),
		maxLength(app, "TripUpload.app", "osVersion", maxTripAppOSVersionLength),
		maxItems(dtc, "DtcSet", "stored", maxDtcCodesPerList),
		maxItems(dtc, "DtcSet", "pending", maxDtcCodesPerList),
		maxItems(dtc, "DtcSet", "permanent", maxDtcCodesPerList),
	)
}
