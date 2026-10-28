package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// DTC report kinds (DtcReportCreate.kind).
const (
	DtcKindRead    = "read"
	DtcKindCleared = "cleared"
)

// DtcReportFields are the stored fields of a DTC report (DtcReportCreate in
// the spec). A nil code list means the list was left out (that mode wasn't
// read); an empty one means it was read as empty.
type DtcReportFields struct {
	ClientReportID     uuid.UUID
	Kind               string
	ReadAt             time.Time
	Mil                *bool // nil: unknown
	Stored             []string
	Pending            []string
	Permanent          []string
	Vin                *string
	ClientTripID       *uuid.UUID
	OdometerKm         *int64
	DistanceSinceMilKm *int64
}

// DtcReport is a stored DTC report.
type DtcReport struct {
	ID        uuid.UUID
	VehicleID uuid.UUID
	DtcReportFields
	CreatedAt time.Time
}

// DtcReportPos is a position in a vehicle's report list (newest first by
// readAt, then id); a page continues strictly after it.
type DtcReportPos struct {
	ReadAt time.Time
	ID     uuid.UUID
}

// DtcOutcome says what CreateDtcReport did.
type DtcOutcome int

const (
	// DtcCreated means a new report was stored.
	DtcCreated DtcOutcome = iota + 1
	// DtcExisting means the member already has a report with this
	// clientReportId; it is returned unchanged (it may belong to another
	// of the member's vehicles).
	DtcExisting
)

// DtcReports stores DTC reports. Every method is scoped to member and to a
// live vehicle of the member: a missing, deleted or someone else's vehicle
// is ErrNotFound.
type DtcReports interface {
	// CreateDtcReport stores a report for vehicle, de-duplicated by
	// clientReportId per member. It share-locks the live vehicle, so a
	// concurrent cascade delete either waits for it (and then deletes the
	// report) or runs first (and the create gets ErrNotFound).
	CreateDtcReport(ctx context.Context, member, vehicle uuid.UUID, f DtcReportFields) (DtcReport, DtcOutcome, error)
	// ListDtcReports returns up to limit of vehicle's reports, newest first,
	// after after (nil for the first page).
	ListDtcReports(ctx context.Context, member, vehicle uuid.UUID, after *DtcReportPos, limit int) ([]DtcReport, error)
}
