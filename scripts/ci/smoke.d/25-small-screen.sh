#!/usr/bin/env bash
# Small-screen smoke: emulate a 360x640 dp phone (720x1280 px at 320 dpi) and check that Home scrolls
# far enough to show the bottom of the Live data (mini dashboard) card. Restores the display afterwards.
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

restore() { adb shell wm size reset || true; adb shell wm density reset || true; }
trap restore EXIT

adb shell wm size 720x1280
adb shell wm density 320
sleep 3
wait_for_text "Live data" small-home
shot 25-home-360x640-top
for _ in 1 2 3; do adb shell input swipe 360 1000 360 300 300; sleep 1; done
wait_for_regex 'text="Fuel flow: [0-9.]+ L/h"' small-home-scrolled 10
grep -Eq 'text="Trip [-0-9.]+ km/L' "$OUT/small-home-scrolled.xml"
shot 26-home-360x640-scrolled
restore
trap - EXIT
sleep 3
wait_for_text "Live data" small-home-restored
echo "small screen smoke OK"
