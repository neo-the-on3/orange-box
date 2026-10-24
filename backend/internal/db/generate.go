package db

// sqlc is pinned here by version rather than as a go.mod tool: it needs
// cgo and a large dependency tree that the server never links.
//go:generate sh -c "cd ../.. && go run github.com/sqlc-dev/sqlc/cmd/sqlc@v1.31.1 generate"
