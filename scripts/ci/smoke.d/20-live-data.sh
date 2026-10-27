#!/usr/bin/env bash
# M2 smoke: with the simulated dongle connected (10-simulator.sh), start the Highway drive script
# and check that the Home mini dashboard shows live decoded values (non-zero speed, RPM, coolant, fuel flow).
set -euo pipefail

# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

open_simulator live-more
tap_attr text "Highway" live-script
wait_for_regex 'Playing: Highway' live-playing
shot 20-simulator-highway-playing
adb shell input keyevent KEYCODE_BACK
wait_for_regex 'content-desc="Speed [1-9][0-9]* kilometres per hour"' live-home
wait_for_regex 'content-desc="Engine speed [1-9][0-9]* rpm"' live-home-rpm
# The Drive button (#192) moved the card down: the readings row can wrap below the fold, and uiautomator
# only dumps what is on screen. Scroll a little until it shows.
for i in 1 2 3 4; do
  if grep -Eq 'text="Coolant: 85 °C"' "$OUT/live-home-rpm.xml" && grep -Eq 'text="Fuel flow: [0-9]+\.[0-9]{2} L/h"' "$OUT/live-home-rpm.xml"; then
    break
  fi
  [ "$i" -eq 4 ] && { echo "coolant / fuel flow readings not shown" >&2; exit 1; }
  adb shell input swipe 500 1500 500 1100 300
  sleep 1
  ui_dump live-home-rpm
done
shot 21-home-live-data
grep -o '\(text\|content-desc\)="\(Speed\|Engine speed\|Coolant\|Fuel level\|Fuel flow\|Trip\)[^"]*"' "$OUT/live-home-rpm.xml" | tee "$OUT/live-values.txt"
echo "live data smoke OK"
