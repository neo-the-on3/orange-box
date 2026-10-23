#!/usr/bin/env bash
# M2 smoke: with the simulated dongle connected (10-simulator.sh), start the Highway drive script
# and check that Home shows live decoded values (non-zero speed, RPM, coolant, fuel flow).
set -euo pipefail

# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

open_simulator live-more
tap_attr text "Highway" live-script
wait_for_regex 'Playing: Highway' live-playing
shot 20-simulator-highway-playing
adb shell input keyevent KEYCODE_BACK
wait_for_regex 'text="Speed: [1-9][0-9]* km/h"' live-home
wait_for_regex 'text="RPM: [1-9][0-9]*"' live-home-rpm
grep -Eq 'text="Coolant: 85 °C"' "$OUT/live-home-rpm.xml"
grep -Eq 'text="Fuel flow: [0-9]+\.[0-9]{2} L/h"' "$OUT/live-home-rpm.xml"
shot 21-home-live-data
grep -o 'text="\(Speed\|RPM\|Coolant\|Fuel level\|Fuel flow\|Economy\|Distance this session\)[^"]*"' "$OUT/live-home-rpm.xml" | tee "$OUT/live-values.txt"
echo "live data smoke OK"
