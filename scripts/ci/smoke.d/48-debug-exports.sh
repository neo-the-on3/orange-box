#!/usr/bin/env bash
# Debug Home overflow: Export trips / Export connection log write files and open the share sheet.
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"
PKG="${PKG:-io.github.neotheon3.orangebox.debug}"
DIR="/sdcard/Android/data/$PKG/files/Download/orange-box-exports"

focus() { adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -n1; }

# The share sheet (another package) must take focus; Back then returns to Home with the drive still running.
close_share_sheet() {
  local shown=0
  for _ in $(seq 1 15); do
    if ! focus | grep -q "$PKG/"; then shown=1; break; fi
    sleep 1
  done
  [ "$shown" = 1 ] || { shot "$1"; echo "share sheet did not open: $(focus)" >&2; return 1; }
  # Focus moves to the chooser before it is drawn and takes keys: let it settle, or Back can land on the app
  # and leave it for the launcher.
  sleep 2
  shot "$1"
  for _ in 1 2 3; do
    focus | grep -q "$PKG/" && return 0
    adb shell input keyevent KEYCODE_BACK
    # Wait for the app to come back before sending another Back (a second one would leave the app).
    for _ in $(seq 1 8); do
      sleep 1
      focus | grep -q "$PKG/" && return 0
    done
  done
  echo "app not back in front after closing the share sheet: $(focus)" >&2
  return 1
}

export_entry() {
  local label="$1" name="$2"
  for _ in 1 2 3; do
    tap_attr content-desc "More" "$name-more" || true
    sleep 1
    ui_dump "$name-menu"
    if grep -q "text=\"$label\"" "$OUT/$name-menu.xml"; then
      tap_attr text "$label" "$name-menu" && return 0
    fi
    adb shell input keyevent KEYCODE_ESCAPE || true
    sleep 1
  done
  echo "menu entry '$label' not found" >&2
  return 1
}

export_entry "Export connection log" export-log
close_share_sheet 48-export-log-share-sheet
export_entry "Export trips" export-trips
close_share_sheet 48-export-trips-share-sheet
adb shell ls "$DIR" | tee "$OUT/exports.txt"
grep -q '^connection-log-.*\.txt$' "$OUT/exports.txt" || { echo "connection log not exported" >&2; exit 1; }
grep -q '^trips-.*\.json$' "$OUT/exports.txt" || { echo "trips JSON not exported" >&2; exit 1; }
grep -q '^trips-.*\.gpx$' "$OUT/exports.txt" || { echo "trips GPX not exported" >&2; exit 1; }
log=$(grep '^connection-log-' "$OUT/exports.txt" | head -n1)
adb shell cat "$DIR/$log" > "$OUT/connection-log.txt"
# Earlier steps connected the simulated dongle: its traffic must be in the log.
grep -q ' CONNECT simulated dongle' "$OUT/connection-log.txt" || { echo "no CONNECT line in the log" >&2; exit 1; }
grep -Eq ' TR AT[A-Z0-9@]* > ' "$OUT/connection-log.txt" || { echo "no AT init request/reply lines in the log" >&2; exit 1; }
grep -Eq ' TR 01[0-9A-F]+ > .*\([0-9]+ms\)$' "$OUT/connection-log.txt" || { echo "no timed PID request/reply lines in the log" >&2; exit 1; }
# Session header (#200): kept outside the ring buffer, written first.
grep -Eq '^# session [0-9]+ started ' "$OUT/connection-log.txt" || { echo "no session header in the log" >&2; exit 1; }
for key in adapter protocol pid_bitmaps supported_pids poll_plan; do
  grep -Eq "^#   $key: " "$OUT/connection-log.txt" || { echo "session header has no $key" >&2; exit 1; }
done
wait_for_regex 'content-desc="More"' exports-home 10
echo "debug exports smoke OK"
