// Package httpapi holds the HTTP handlers for the Orange Box REST API.
//
// Today it only serves the health check. Once the v2 spec lands, the
// oapi-codegen strict server will be generated next to it.
package httpapi

import (
	"encoding/json"
	"log/slog"
	"net/http"
)

// NewRouter returns the root handler with every route registered.
func NewRouter(logger *slog.Logger) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", health(logger))
	return mux
}

type healthResponse struct {
	Status string `json:"status"`
}

func health(logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		if err := json.NewEncoder(w).Encode(healthResponse{Status: "ok"}); err != nil {
			logger.ErrorContext(r.Context(), "write health response", slog.Any("err", err))
		}
	}
}
