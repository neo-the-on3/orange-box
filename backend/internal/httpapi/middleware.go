package httpapi

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"runtime/debug"
	"time"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/problem"
	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
)

// requestID echoes a valid client X-Request-Id (a UUID, per the spec) or
// generates a UUIDv7, sets it on the response and puts it in the context.
func requestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get("X-Request-Id")
		if u, err := uuid.Parse(id); err != nil || len(id) != 36 {
			id = newRequestID()
		} else {
			id = u.String()
		}
		w.Header().Set("X-Request-Id", id)
		next.ServeHTTP(w, r.WithContext(reqctx.WithRequestID(r.Context(), id)))
	})
}

// statusRecorder remembers the status and size of a response.
type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (s *statusRecorder) WriteHeader(code int) {
	if s.status == 0 {
		s.status = code
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	n, err := s.ResponseWriter.Write(b)
	s.bytes += n
	return n, err
}

func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

// accessLog writes one JSON line per request. It logs the OpenAPI route
// template, never the raw path or query string, and no headers or bodies.
func accessLog(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			ctx, info := reqctx.WithInfo(r.Context())
			rec := &statusRecorder{ResponseWriter: w}
			next.ServeHTTP(rec, r.WithContext(ctx))

			status := rec.status
			if status == 0 {
				status = http.StatusOK
			}
			route := info.Route
			if route == "" {
				route = "unmatched"
			}
			level := slog.LevelInfo
			switch status {
			case http.StatusNotImplemented, http.StatusServiceUnavailable:
				level = slog.LevelWarn // expected while the API is being built
			default:
				if status >= http.StatusInternalServerError {
					level = slog.LevelError
				}
			}
			attrs := []slog.Attr{
				slog.String("method", r.Method),
				slog.String("route", route),
				slog.Int("status", status),
				slog.Int("bytes", rec.bytes),
				slog.Float64("duration_ms", float64(time.Since(start).Microseconds())/1000),
				slog.String("request_id", reqctx.RequestID(ctx)),
			}
			if info.OperationID != "" {
				attrs = append(attrs, slog.String("operation", info.OperationID))
			}
			if info.MemberID != "" {
				attrs = append(attrs, slog.String("member_id", info.MemberID))
			}
			logger.LogAttrs(ctx, level, "request", attrs...)
		})
	}
}

// recoverer turns a panic into a 500 problem (if nothing was written yet)
// and logs the stack. http.ErrAbortHandler is re-raised, as net/http expects.
func recoverer(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			rec := &statusRecorder{ResponseWriter: w}
			defer func() {
				if v := recover(); v != nil {
					handlePanic(logger, rec, r, v)
				}
			}()
			next.ServeHTTP(rec, r)
		})
	}
}

func handlePanic(logger *slog.Logger, rec *statusRecorder, r *http.Request, v any) {
	if err, ok := v.(error); ok && errors.Is(err, http.ErrAbortHandler) {
		panic(v)
	}
	logger.ErrorContext(r.Context(), "panic serving request",
		slog.String("request_id", reqctx.RequestID(r.Context())),
		slog.String("stack", string(debug.Stack())))
	if rec.status == 0 {
		problem.Write(rec, r, problem.Internal())
	}
}

// timeout bounds each request's context. Handlers and stores must honour it.
func timeout(d time.Duration) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ctx, cancel := context.WithTimeout(r.Context(), d)
			defer cancel()
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// defaultHeaders sets safe defaults that individual operations may override
// (e.g. catalogue responses will be cacheable).
func defaultHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Cache-Control", "no-store")
		h.Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(w, r)
	})
}
