#!/usr/bin/env bash
# M1 smoke: Home overflow -> Simulator, enable the simulated dongle, back to Home, Connect,
# wait for "Connected" and screenshot both screens. Env: PKG, ACTIVITY, OUT (from emulator-smoke.sh).
set -euo pipefail

ui_dump() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml > "$OUT/$1.xml" 2>/dev/null || true
}
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
# Tap the centre of the first node whose attribute $1 equals $2.
tap_attr() {
  local name="$3" b
  for _ in $(seq 1 10); do
    ui_dump "$name"
    b=$(grep -o "$1=\"$2\"[^>]*bounds=\"[^\"]*\"" "$OUT/$name.xml" | head -n1 \
      | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true)
    if [ -n "$b" ]; then
      read -r x1 y1 x2 y2 <<< "$b"
      adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
      return 0
    fi
    sleep 1
  done
  echo "node $1=\"$2\" not found" >&2
  return 1
}
wait_for_text() {
  for _ in $(seq 1 30); do
    ui_dump "$2"
    grep -q "$1" "$OUT/$2.xml" && return 0
    sleep 1
  done
  echo "text '$1' not found" >&2
  return 1
}

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
