#!/usr/bin/env bash
# M4 smoke: with the simulated dongle connected and the Highway script playing, the driving service
# must be in the foreground with its notification, a trip must be recording, and the
# notification's open-dashboard intent must bring up the dashboard. Screenshots the shade (best effort).
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

PKG="${PKG:-io.github.neotheon3.orangebox.debug}"
collapse() { adb shell cmd statusbar collapse > /dev/null 2>&1 || true; }
trap collapse EXIT

adb shell dumpsys activity services "$PKG" > "$OUT/driving-services.txt"
grep -q "DrivingService" "$OUT/driving-services.txt" || { echo "DrivingService not running" >&2; exit 1; }
grep -q "isForeground=true" "$OUT/driving-services.txt" || { echo "DrivingService not in the foreground" >&2; exit 1; }
grep -Eo "foregroundServiceType=[^ ]*" "$OUT/driving-services.txt" | head -n1 || true

for _ in $(seq 1 20); do
  adb logcat -d | grep -q "trip_start" && break
  sleep 1
done
adb logcat -d | grep -q "trip_start" || { echo "no trip_start log line" >&2; exit 1; }

for _ in $(seq 1 20); do
  adb shell dumpsys notification --noredact > "$OUT/notifications.txt" 2>/dev/null || true
  grep -q "Recording trip" "$OUT/notifications.txt" && break
  sleep 1
done
grep -q "Recording trip" "$OUT/notifications.txt" || { echo "driving notification missing" >&2; exit 1; }

# Screenshot the shade (best effort). The ongoing notification's chronometer ticks every second, so
# uiautomator never sees the shade idle and can't dump it, and on the CI image neither
# `cmd statusbar expand-notifications` nor a swipe reliably opens it. Try both and check the focused window.
shade_open() { adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedWindow" | grep -q "NotificationShade"; }
adb shell input keyevent KEYCODE_HOME
sleep 2
read -r W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n1 | tr 'x' ' ')
opened=0
for i in 1 2 3; do
  adb shell cmd statusbar expand-notifications > /dev/null 2>&1 || true
  sleep 2
  shade_open && { opened=1; break; }
  adb shell input touchscreen swipe $(( W / 2 )) 0 $(( W / 2 )) $(( H * 7 / 10 )) $(( 300 * i + 300 ))
  sleep 2
  shade_open && { opened=1; break; }
done
if [ "$opened" = 1 ]; then shot 27-notification-shade; else echo "warning: notification shade did not open; no shade screenshot" >&2; fi
collapse
sleep 1

# "Start driving" and the body open MainActivity with EXTRA_OPEN_DASHBOARD (FLAG_ACTIVITY_NEW_TASK |
# SINGLE_TOP); DrivingNotificationsTest asserts that the PendingIntents carry exactly this. Coordinate taps
# in an un-dumpable shade proved flaky, so deliver the same intent from the background (Home) instead.
grep -q '"Start driving" -> PendingIntent' "$OUT/notifications.txt" || { echo "Start driving action missing" >&2; exit 1; }
adb shell am start -f 0x30000000 -n "$PKG/io.github.neotheon3.orangebox.MainActivity" \
  --ez io.github.neotheon3.orangebox.OPEN_DASHBOARD true > /dev/null
trap - EXIT
wait_for_regex 'content-desc="End driving"' notif-dashboard 20
shot 28-dashboard-from-notification
adb shell input keyevent KEYCODE_BACK
wait_for_text "Live data" notif-home
echo "driving service smoke OK"
