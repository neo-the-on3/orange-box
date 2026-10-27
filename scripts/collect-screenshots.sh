#!/usr/bin/env bash
# Copies the Robolectric screen gallery (#71/#72/#73) out of the module build dirs into one folder.
# Run after `./gradlew testDebugUnitTest`. Usage: scripts/collect-screenshots.sh [dest] (default build/screenshots).
set -euo pipefail
dest="${1:-build/screenshots}"
mkdir -p "$dest"
count=0
while IFS= read -r -d '' f; do
  cp "$f" "$dest/"
  count=$((count + 1))
done < <(find . -path ./backend -prune -o -path '*/build/outputs/gallery/*.png' -print0)
echo "copied $count screenshots to $dest"
