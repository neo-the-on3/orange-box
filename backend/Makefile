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
run: ## Run the server locally (PORT defaults to 8080)
	go run ./cmd/server

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
		npx --yes @redocly/cli@2 lint $(SPEC) && openapi-spec-validator $(SPEC); \
	fi

.PHONY: generate
generate: ## Generate server code from the spec (placeholder until PR #81 lands)
	@echo "generate: oapi-codegen (strict server) and sqlc are not wired up yet"

.PHONY: docker
docker: ## Build the container image
	docker build -t $(IMAGE) .

.PHONY: check
check: vet test lint vuln build ## Run every local check CI runs

.PHONY: clean
clean: ## Remove build output
	rm -rf bin
