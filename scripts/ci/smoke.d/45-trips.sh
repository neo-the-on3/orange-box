#!/usr/bin/env bash
# Home → Trips (month calendar + list) → trip detail (metrics, events).
# The emulator has no GPS fix, so debug demo trips are seeded first (`--es demo_trips seed`).
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

# tap_regex <ERE on the text attribute> <dump-name>: tap the first node whose text matches.
tap_regex() {
  local b
  for _ in $(seq 1 8); do
    ui_dump "$2"
    b=$(grep -oE "text=\"$1\"[^>]*bounds=\"[^\"]*\"" "$OUT/$2.xml" | head -n1 \
      | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true)
    if [ -n "$b" ]; then
      read -r x1 y1 x2 y2 <<< "$b"
      adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
      return 0
    fi
    adb shell input swipe 500 1500 500 900 300
    sleep 1
  done
  echo "no node with text matching '$1'" >&2
  return 1
}

adb shell am start -W -n "$PKG/$ACTIVITY" --es demo_trips seed > /dev/null
sleep 2
adb logcat -d | grep -q "demo_trips seeded" || { echo "demo_trips extra not applied" >&2; exit 1; }

tap_attr content-desc "Trips" trips-home
wait_for_text "Previous month" trips-calendar 15
wait_for_regex 'text="[A-Z][a-z]+ 20[0-9]{2}"' trips-month-title 10
# Seeded an hour ago: on the first of a month just after midnight it is in the previous month.
if grep -q 'text="No trips in' "$OUT/trips-month-title.xml"; then
  tap_attr content-desc "Previous month" trips-prev
fi
wait_for_regex 'text="[0-9.]+ km · 0:25:00' trips-list 15
shot 45-trips

# Trip detail: metrics and events (the route map comes with #44).
tap_regex '[0-9.]+ km · 0:25:00[^"]*' trips-open-detail
wait_for_text "Average speed" trip-detail 15
wait_for_regex 'text="[0-9]+ GPS points · [0-9]+ speed readings"' trip-detail-gps 10
shot 46-trip-detail
adb shell input keyevent KEYCODE_BACK
wait_for_text "Previous month" trips-back 10
adb shell input keyevent KEYCODE_BACK
wait_for_regex 'content-desc="Settings"' trips-back-home 10
echo "trips smoke OK"
