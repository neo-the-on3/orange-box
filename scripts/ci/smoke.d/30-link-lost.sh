#!/usr/bin/env bash
# Link-down smoke: Simulator "BT disconnected" must keep the app disconnected (no auto-retry) until
# "BT connected" is triggered.
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

open_simulator ll-more
tap_attr text "BT disconnected" ll-disc
adb shell input keyevent KEYCODE_BACK
wait_for_text "Adapter disconnected" ll-home
sleep 8 # longer than the first 2 s retry wait
ui_dump ll-home-later
grep -q "Adapter disconnected" "$OUT/ll-home-later.xml" || { echo "app reconnected on its own" >&2; exit 1; }
shot 30-home-link-lost
open_simulator ll-more2
tap_attr text "BT connected" ll-conn
adb shell input keyevent KEYCODE_BACK
wait_for_text 'text="Connected"' ll-home-back
shot 31-home-link-restored
echo "link lost smoke OK"
