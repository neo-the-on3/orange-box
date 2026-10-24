package problem

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/neo-the-on3/orange-box/backend/internal/reqctx"
)

func TestWrite(t *testing.T) {
	t.Parallel()
	r := httptest.NewRequest(http.MethodPost, "/v1/auth/otp", nil)
	ctx := reqctx.WithRequestID(r.Context(), "0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a14")
	r = r.WithContext(ctx)
	rec := httptest.NewRecorder()

	Write(rec, r, New(http.StatusUnprocessableEntity, CodeTestAddressRejected, "Addresses in the reserved test domain are only accepted when test mode is on.").
		WithTitle("Test address not allowed").
		WithErrors(FieldError{Pointer: "/email", Detail: "reserved test domain"}).
		WithRetryAfter(7).
		WithHeader("X-Extra", "1"))

	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d", rec.Code)
	}
	for k, want := range map[string]string{
		"Content-Type": "application/problem+json", "Retry-After": "7", "X-Extra": "1", "Cache-Control": "no-store",
	} {
		if got := rec.Header().Get(k); got != want {
			t.Errorf("%s = %q, want %q", k, got, want)
		}
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{
		"type":      "https://api.example.invalid/problems/test-address-rejected",
		"title":     "Test address not allowed",
		"status":    float64(422),
		"code":      "test_address_rejected",
		"instance":  "/v1/auth/otp",
		"requestId": "0192f1b2-7c1e-7a55-9f0e-2b1d7d2c9a14",
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %v, want %v", k, got[k], v)
		}
	}
	if errs, _ := got["errors"].([]any); len(errs) != 1 {
		t.Errorf("errors = %v", got["errors"])
	}
}

func TestNewDefaults(t *testing.T) {
	t.Parallel()
	p := New(http.StatusNotFound, "", "")
	if p.Type != "about:blank" || p.Title != "Not Found" {
		t.Errorf("got %+v", p)
	}
	if Internal().Status != 500 || Internal().Code != CodeInternal {
		t.Error("Internal() wrong")
	}
	if p := New(400, CodeInvalidGrant, "x"); p.Error() != "invalid_grant: x" {
		t.Errorf("Error() = %q", p.Error())
	}
}
