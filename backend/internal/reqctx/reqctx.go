// Package reqctx carries per-request values (request id, member id, route)
// through a request's context.
package reqctx

import "context"

type key int

const (
	requestIDKey key = iota
	memberIDKey
	infoKey
)

// WithRequestID returns ctx carrying the request id.
func WithRequestID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, requestIDKey, id)
}

// RequestID returns the request id, or "" outside a request.
func RequestID(ctx context.Context) string {
	id, _ := ctx.Value(requestIDKey).(string)
	return id
}

// WithMemberID returns ctx carrying the authenticated member id.
func WithMemberID(ctx context.Context, id string) context.Context {
	if info := InfoFrom(ctx); info != nil {
		info.MemberID = id
	}
	return context.WithValue(ctx, memberIDKey, id)
}

// MemberID returns the authenticated member id (the access token's sub).
// ok is false on operations that don't require auth.
func MemberID(ctx context.Context) (id string, ok bool) {
	id, ok = ctx.Value(memberIDKey).(string)
	return id, ok && id != ""
}

// Info is filled in by inner middleware so the outer access log can report
// it. It is only touched by the goroutine serving the request.
type Info struct {
	// Route is the OpenAPI path template, e.g. /v1/vehicles/{vehicleId}.
	Route string
	// OperationID is the OpenAPI operationId.
	OperationID string
	// MemberID is the authenticated member, if any.
	MemberID string
	// BodyBytes is the size of the request body after decompression, once
	// it has been read and validated.
	BodyBytes int
}

// WithInfo returns ctx carrying a fresh *Info.
func WithInfo(ctx context.Context) (context.Context, *Info) {
	info := &Info{}
	return context.WithValue(ctx, infoKey, info), info
}

// InfoFrom returns the request's *Info, or nil.
func InfoFrom(ctx context.Context) *Info {
	info, _ := ctx.Value(infoKey).(*Info)
	return info
}
