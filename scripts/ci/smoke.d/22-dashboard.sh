#!/usr/bin/env bash
# M4 smoke: with the Highway script playing (20-live-data.sh), open the driving dashboard from Home,
# screenshot it in portrait and landscape, flip to Detailed Info, then End driving + Undo (the link must
# stay up for the later scripts) and go back Home. Restores auto-rotation afterwards.
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

restore() {
  adb shell settings put system user_rotation 0 || true
  adb shell settings put system accelerometer_rotation 1 || true
}
trap restore EXIT
rotate() { adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation "$1"; sleep 3; }

tap_attr text "Open dashboard" dash-open
wait_for_regex 'content-desc="End driving"' dash-portrait
wait_for_regex 'content-desc="Speed [1-9][0-9]* km/h"' dash-portrait
grep -Eq 'content-desc="Engine speed [1-9][0-9]* rpm"' "$OUT/dash-portrait.xml"
shot 22-dashboard-portrait

rotate 1
wait_for_regex 'content-desc="Speed [1-9][0-9]* km/h"' dash-landscape
grep -q 'content-desc="End driving"' "$OUT/dash-landscape.xml"
shot 23-dashboard-landscape

tap_attr text "Detailed Info." dash-toggle
wait_for_regex 'content-desc="Accel Pedal Position, [0-9]+ %"' dash-detail
sleep 1 # 500 ms transition
shot 23-dashboard-landscape-detail
tap_attr text "Detailed Info." dash-toggle-back
wait_for_regex 'content-desc="Speed [0-9]+ km/h"' dash-landscape-back

rotate 0
wait_for_regex 'content-desc="End driving"' dash-portrait-back
# The undo window is 5 s and a CI dump takes 1-2 s: tap Undo from the dump that found it, no re-dump.
tap_attr content-desc "End driving" dash-end
wait_for_text 'text="Undo"' dash-ended 3
b=$(grep -o 'text="Undo"[^>]*bounds="[^"]*"' "$OUT/dash-ended.xml" | head -n1 \
  | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
read -r x1 y1 x2 y2 <<< "$b"
adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
shot 24-drive-ended-undone
wait_for_regex 'content-desc="End driving"' dash-resumed 10
sleep 6 # past the 5 s undo window: the link must still be up
adb shell input keyevent KEYCODE_BACK
wait_for_text "Live data" dash-home
wait_for_regex 'content-desc="Speed [0-9]+ km/h"' dash-home-live
grep -q 'text="Connected"' "$OUT/dash-home-live.xml" || { echo "drive ended despite Undo" >&2; exit 1; }
echo "dashboard smoke OK"
