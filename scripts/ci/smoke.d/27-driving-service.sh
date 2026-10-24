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

# Leave the app first: the live tiles animate, and uiautomator cannot dump a window that never goes idle
# (it then leaves the previous dump in place). From the launcher, the tap also tests a background open.
adb shell input keyevent KEYCODE_HOME
sleep 2
# A busy CI emulator sometimes ignores the first expand; retry, then fall back to a swipe down from the top.
shade_open=false
for i in 1 2 3; do
  if [ "$i" -lt 3 ]; then adb shell cmd statusbar expand-notifications; else adb shell input swipe 500 5 500 1400 400; fi
  sleep 2
  shot "27-shade-try-$i"
  if wait_for_text "Recording trip" shade 5; then shade_open=true; break; fi
done
$shade_open || { echo "notification shade did not show the driving notification" >&2; exit 1; }
shot 27-notification-shade
# "Start driving" (the action) if the notification is expanded, else its body: both open the dashboard.
if grep -qi 'text="Start driving"' "$OUT/shade.xml"; then
  tap_attr text "$(grep -oi 'text="Start driving"' "$OUT/shade.xml" | head -n1 | sed -E 's/text="(.*)"/\1/')" shade-tap
else
  tap_attr text "Recording trip" shade-tap
fi
trap - EXIT
wait_for_regex 'content-desc="End driving"' notif-dashboard 15
shot 28-dashboard-from-notification
adb shell input keyevent KEYCODE_BACK
wait_for_text "Live data" notif-home
echo "driving service smoke OK"
