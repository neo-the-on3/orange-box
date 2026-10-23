#!/usr/bin/env bash
# M1 smoke: Home overflow -> Simulator, enable the simulated dongle, back to Home, Connect,
# wait for "Connected" and screenshot both screens. Env: PKG, ACTIVITY, OUT (from emulator-smoke.sh).
set -euo pipefail

# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" --es backend_mode off > /dev/null
wait_for_text "Backend: Off" sim-home
tap_attr content-desc "More" sim-more
tap_attr text "Simulator" sim-menu
wait_for_text "Use simulated dongle" sim-screen
tap_attr text "Use simulated dongle" sim-toggle
sleep 1
shot 10-simulator
adb shell input keyevent KEYCODE_BACK
wait_for_text "Not connected" sim-home-idle
tap_attr text "Connect" sim-connect
wait_for_text 'text="Connected"' sim-home-connected
shot 11-home-connected-simulated
grep -q "CAN 11/500" "$OUT/sim-home-connected.xml"
echo "simulator smoke OK"
