#!/usr/bin/env bash
# Shared helpers for emulator smoke scripts. Needs OUT (artifact dir).

ui_dump() {
  # One adb round trip (the Undo snackbar only lives 5 s). The previous dump is removed first so a failed
  # dump (a window that never goes idle) can't be read as the current screen.
  adb shell 'rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; cat /sdcard/ui.xml 2>/dev/null' > "$OUT/$1.xml" 2>/dev/null || true
  dismiss_system_dialogs "$1"
}

# CI emulators sometimes show "Process system isn't responding" / "<app> isn't responding" for other
# processes while busy. Tap "Wait" so the dialog does not hide our UI; our own crashes are checked from logcat.
dismiss_system_dialogs() {
  local f="$OUT/$1.xml" b
  grep -q "isn't responding" "$f" 2>/dev/null || return 0
  echo "dismissing system ANR dialog seen in $1" >&2
  b=$(grep -o 'text="Wait"[^>]*bounds="[^"]*"' "$f" | head -n1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true)
  if [ -n "$b" ]; then
    read -r x1 y1 x2 y2 <<< "$b"
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
  else
    adb shell input keyevent KEYCODE_BACK
  fi
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml > "$f" 2>/dev/null || true
}

shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

# wait_for_regex <ERE> <dump-name> [tries]
wait_for_regex() {
  for _ in $(seq 1 "${3:-40}"); do
    ui_dump "$2"
    grep -Eq "$1" "$OUT/$2.xml" && return 0
    sleep 1
  done
  echo "pattern '$1' not found" >&2
  return 1
}
# wait_for_text <fixed string> <dump-name> [tries]
wait_for_text() {
  for _ in $(seq 1 "${3:-40}"); do
    ui_dump "$2"
    grep -Fq "$1" "$OUT/$2.xml" && return 0
    sleep 1
  done
  echo "text '$1' not found" >&2
  return 1
}

# tap_attr <attr> <value> <dump-name>: tap the first node whose attribute equals value; swipes up if not visible.
tap_attr() {
  local name="$3" b
  for i in $(seq 1 10); do
    ui_dump "$name"
    b=$(grep -o "$1=\"$2\"[^>]*bounds=\"[^\"]*\"" "$OUT/$name.xml" | head -n1 \
      | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true)
    if [ -n "$b" ]; then
      read -r x1 y1 x2 y2 <<< "$b"
      adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
      return 0
    fi
    [ "$i" -gt 3 ] && adb shell input swipe 500 1600 500 900 300
    sleep 1
  done
  echo "node $1=\"$2\" not found" >&2
  return 1
}

# tap_until <attr> <value> <expected fixed text> <dump-name>: tap, and tap again (up to 3 times) if the
# expected text has not appeared. A busy CI emulator (boot-time ANRs elsewhere) sometimes drops a tap.
tap_until() {
  for _ in 1 2 3; do
    tap_attr "$1" "$2" "$4-tap" || true
    wait_for_text "$3" "$4" 8 && return 0
  done
  return 1
}

# Home overflow -> Simulator, retrying the menu tap (it can be lost during a configuration change).
open_simulator() {
  local name="${1:-open-sim}"
  for _ in 1 2 3; do
    tap_attr content-desc "More" "$name-more" || true
    sleep 1
    ui_dump "$name-menu"
    if grep -q 'text="Simulator"' "$OUT/$name-menu.xml"; then
      tap_attr text "Simulator" "$name-menu" && return 0
    fi
    adb shell input keyevent KEYCODE_ESCAPE || true
    sleep 2
  done
  echo "could not open the Simulator from Home" >&2
  return 1
}
