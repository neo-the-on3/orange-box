#!/usr/bin/env bash
# OpenAPI lint of the backend contract (#69). Runs inside the app CI build job, so it costs seconds, not a
# separate job. Same pinned tool and version as backend.yml's spec job; errors fail the build, warnings don't.
set -euo pipefail
SPEC="${1:-docs/backend/openapi-v2.yaml}"
if [[ ! -f "$SPEC" ]]; then
  echo "::notice::$SPEC not found; skipping spec lint"
  exit 0
fi
npx --yes @redocly/cli@2.71.0 lint "$SPEC" --format=stylish
