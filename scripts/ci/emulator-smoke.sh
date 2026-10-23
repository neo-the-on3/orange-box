#!/usr/bin/env bash
# Emulator smoke test, run inside reactivecircus/android-emulator-runner in CI.
# Never run this against an x86/KVM emulator on the shared box.
#
# Usage: emulator-smoke.sh <apk-dir> <out-dir>
set -euo pipefail

APK_DIR="${1:?apk dir}"
OUT="${2:?out dir}"
PKG="io.github.neotheon3.orangebox.debug"
ACTIVITY="io.github.neotheon3.orangebox.MainActivity"
mkdir -p "$OUT"

fail() { echo "SMOKE FAIL: $*" >&2; dump; exit 1; }
dump() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT/activities.txt" 2>/dev/null || true
}
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
ui_dump() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml > "$OUT/$1.xml" 2>/dev/null || true
}
wait_for_text() { # $1 = text, $2 = dump name
  for _ in $(seq 1 20); do
    ui_dump "$2"
    grep -q "$1" "$OUT/$2.xml" && return 0
    sleep 1
  done
  return 1
}

APK=$(ls "$APK_DIR"/*.apk | head -n1)
echo "Installing $APK"
adb install -r -g "$APK"
adb logcat -c

# 1) Launch with backend_mode=off
adb shell am start -W -n "$PKG/$ACTIVITY" --es backend_mode off
wait_for_text "Backend: Off" home-off || fail "Home did not show 'Backend: Off'"
shot 01-home-backend-off
adb logcat -d | grep -q "backend_mode override applied: off" || fail "override log line missing"

# 2) Relaunch with fake, check the override takes effect
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" --es backend_mode fake
wait_for_text "Backend: Fake" home-fake || fail "Home did not show 'Backend: Fake'"
shot 02-home-backend-fake

# 3) Invalid value keeps saved mode (fake) and does not crash
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" --es backend_mode bogus
wait_for_text "Backend: Fake" home-bogus || fail "Invalid extra changed the mode or crashed"
shot 03-home-invalid-extra

# 4) Back to off for subsequent checks
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" --es backend_mode off
wait_for_text "Backend: Off" home-off-2 || fail "Could not return to Off"
shot 04-home-off-again

# Extra smoke steps added by later milestones live in scripts/ci/smoke.d/*.sh
if compgen -G "scripts/ci/smoke.d/*.sh" > /dev/null; then
  for s in scripts/ci/smoke.d/*.sh; do
    echo "Running $s"
    PKG="$PKG" ACTIVITY="$ACTIVITY" OUT="$OUT" bash "$s" || fail "$s failed"
  done
fi

# No crashes for our package
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" "$OUT/logcat.txt" > "$OUT/crashes.txt"; then
  fail "crash detected (see crashes.txt)"
fi
adb shell pidof "$PKG" > /dev/null || fail "app process is not running"
dump
echo "SMOKE OK"
