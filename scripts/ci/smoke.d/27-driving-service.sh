#!/usr/bin/env bash
# M4 smoke: with the simulated dongle connected and the Highway script playing, the driving service
# must be in the foreground with its notification, a trip must be recording, and the notification's
# "Start driving" must open the dashboard. Screenshots the notification shade.
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

# The ongoing notification's chronometer ticks every second, so uiautomator never sees the shade idle
# and can't dump it. Open the shade with a swipe (`cmd statusbar expand-notifications` is ignored on the
# CI image), screenshot it, and tap the notification at its place in the layout (proportional to the screen).
# Leaving the app first also makes the tap a background open.
adb shell input keyevent KEYCODE_HOME
sleep 2
read -r W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n1 | tr 'x' ' ')
adb shell input swipe $(( W / 2 )) 5 $(( W / 2 )) $(( H * 6 / 10 )) 400
sleep 3
shot 27-notification-shade
# Ours is the first notification: its "Recording trip" row sits at about (30 %, 36 %) of the screen whether
# or not it is expanded (see 27-notification-shade.png). The body and "Start driving" both open the dashboard.
adb shell input tap $(( W * 30 / 100 )) $(( H * 36 / 100 ))
trap - EXIT
wait_for_regex 'content-desc="End driving"' notif-dashboard 15
shot 28-dashboard-from-notification
adb shell input keyevent KEYCODE_BACK
wait_for_text "Live data" notif-home
echo "driving service smoke OK"
