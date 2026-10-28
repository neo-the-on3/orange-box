package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/neo-the-on3/orange-box/backend/internal/db/dbtest"
)

// TestRunStoresTelemetryInDir: with TELEMETRY_STORE=dir the server writes
// a batch's samples under TELEMETRY_DIR and its metadata to Postgres.
func TestRunStoresTelemetryInDir(t *testing.T) {
	t.Parallel()
	url := dbtest.NewDatabase(t)
	dir := t.TempDir()
	base := startServer(t, dbConfig(t, url, map[string]string{"MIGRATE_ON_START": "true", "TELEMETRY_STORE": "dir", "TELEMETRY_DIR": dir}), io.Discard)
	token := accessToken(t, base, "tel@test.orangebox.invalid")
	call := func(method, path, body string) (int, map[string]any) {
		req, _ := http.NewRequestWithContext(context.Background(), method, base+path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Idempotency-Key", uuid.NewString())
		res, err := noKeepAlive.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(res.Body).Decode(&m)
		return res.StatusCode, m
	}
	status, v := call(http.MethodPost, "/v1/vehicles", `{"make":"Hyundai","model":"i20","fuelType":"gasoline"}`)
	if status != http.StatusCreated {
		t.Fatalf("vehicle: %d %v", status, v)
	}
	trip := uuid.NewString()
	status, ack := call(http.MethodPost, "/v1/telemetry/batches", `{"clientBatchId":"`+uuid.NewString()+`","clientTripId":"`+trip+
		`","vehicleId":"`+v["id"].(string)+`","samples":[{"t":"2026-10-23T14:00:00Z","speedKmh":42}]}`)
	if status != http.StatusAccepted || ack["status"] != "accepted" || ack["acceptedSamples"] != float64(1) {
		t.Fatalf("upload: %d %v", status, ack)
	}
	files, _ := filepath.Glob(filepath.Join(dir, "members", "*", "trips", trip, "*.json.gz"))
	if len(files) != 1 {
		t.Fatalf("files = %v", files)
	}
	if fi, err := os.Stat(files[0]); err != nil || fi.Size() == 0 {
		t.Fatalf("object: %v %v", fi, err)
	}
	var n int
	if err := dbtest.Open(t, url).QueryRow(context.Background(), `SELECT count(*) FROM telemetry_batches WHERE object_key IS NOT NULL`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("batches = %d, %v", n, err)
	}
}
