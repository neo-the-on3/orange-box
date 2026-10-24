// Package api is generated from ../docs/backend/openapi-v2.yaml by
// oapi-codegen (strict server, std net/http). Do not edit *.gen.go; change
// the spec or oapi-codegen.yaml / oapi-overlay.yaml and run `make generate`.
package api

import "errors"

//go:generate sh -c "cd ../.. && go tool oapi-codegen -config oapi-codegen.yaml ../docs/backend/openapi-v2.yaml && go run ./internal/api/stubgen -in internal/api/api.gen.go -out internal/api/stubs.gen.go"

// ErrNotImplemented is returned by the Unimplemented stubs. The HTTP layer
// turns it into a 501 problem+json response.
var ErrNotImplemented = errors.New("operation not implemented yet")
