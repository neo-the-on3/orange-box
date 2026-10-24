#!/usr/bin/env bash
# Home → Trips: the month calendar and the trip list (trips recorded by the earlier driving steps).
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

tap_attr content-desc "Trips" trips-home
wait_for_text "Previous month" trips-calendar 15
wait_for_regex 'text="[A-Z][a-z]+ 20[0-9]{2}"' trips-month-title 10
shot 45-trips
adb shell input keyevent KEYCODE_BACK
wait_for_regex 'content-desc="Settings"' trips-back-home 10
echo "trips smoke OK"
