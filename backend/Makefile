# Orange Box backend. Run `make help` for the list of targets.

SPEC ?= ../docs/backend/openapi-v2.yaml
BIN  ?= bin/server
IMAGE ?= orange-box-backend:dev

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "  %-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: build
build: ## Build the server binary into bin/
	CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o $(BIN) ./cmd/server

.PHONY: run
run: ## Run locally in test mode; needs AUTH_TEST_CODE in your environment (see README)
	@if [ -z "$$AUTH_TEST_CODE" ]; then echo "run: export AUTH_TEST_CODE (6 non-trivial digits; never commit it)"; exit 1; fi
	ENV=local AUTH_TEST_MODE=true go run ./cmd/server

.PHONY: test
test: ## Run unit tests with the race detector
	go test -race ./...

.PHONY: vet
vet: ## Run go vet
	go vet ./...

.PHONY: lint
lint: ## Run golangci-lint (v2)
	golangci-lint run

.PHONY: vuln
vuln: ## Scan dependencies with govulncheck
	govulncheck ./...

.PHONY: spec-lint
spec-lint: ## Lint the OpenAPI spec (needs Node and Python)
	@if [ ! -f "$(SPEC)" ]; then \
		echo "spec-lint: $(SPEC) not found (lands with PR #81); skipping"; \
	else \
		npx --yes @redocly/cli@2.71.0 lint $(SPEC) && openapi-spec-validator $(SPEC); \
	fi

.PHONY: generate
generate: ## Regenerate internal/api from the spec (oapi-codegen strict server + stubs)
	go generate ./internal/api/...

.PHONY: generate-check
generate-check: generate ## Fail if the committed generated code is out of date
	@git diff --exit-code -- internal/api || (echo "generated code is stale: run make generate and commit" && exit 1)

.PHONY: docker
docker: ## Build the container image
	docker build -t $(IMAGE) .

.PHONY: docker-if-available
docker-if-available: # Build the image when a Docker daemon is reachable
	@if docker info >/dev/null 2>&1; then $(MAKE) docker; else echo "docker: no daemon reachable, skipping the image build (CI still builds it)"; fi

.PHONY: check
check: generate-check vet test lint vuln build docker-if-available ## Run every local check CI runs (docker only if available)

.PHONY: clean
clean: ## Remove build output
	rm -rf bin
