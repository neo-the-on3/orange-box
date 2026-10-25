#!/usr/bin/env bash
# Settings → Driving dashboard: bottom-left button modes, app picker, overspeed and auto-show (screenshots for review).
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

tap_attr content-desc "Settings" dash-settings-home
wait_for_text "Car and dongle" dash-settings
# Compose merges a row's texts into one node ("Hazard, Opens ..."), so match by prefix.
tap_attr text 'Hazard[^"]*' dash-settings-hazard
wait_for_regex 'text="Default for your country \(([A-Z]{2}|unknown)\): [0-9]+"' dash-settings-number 10 || true
wait_for_regex 'text="Starts above 120 km/h, ends below 117 km/h"' dash-settings-overspeed 10 || true
shot 35-settings-dashboard
tap_until text 'Quick launcher[^"]*' "Quick launcher app" dash-settings-picker
sleep 2
shot 36-settings-app-picker
tap_attr text "Cancel" dash-settings-cancel
tap_attr text 'Hazard[^"]*' dash-settings-restore
# Toggle it on: show the state (and the full-screen row on API 34), then turn it off again.
tap_attr text 'Show dashboard automatically[^"]*' dash-settings-auto-show-on
sleep 1
ui_dump dash-settings-auto-show
shot 37-settings-auto-show
tap_attr text 'Show dashboard automatically[^"]*' dash-settings-auto-show-off
adb shell input keyevent KEYCODE_BACK
echo "dashboard settings smoke OK"
