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

fail() { echo "SMOKE FAIL: $*" >&2; shot zz-failure; dump; exit 1; }
dump() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT/activities.txt" 2>/dev/null || true
}
# shellcheck source=scripts/ci/smoke-lib.sh
source "$(dirname "$0")/smoke-lib.sh"

APK=$(ls "$APK_DIR"/*.apk | head -n1)
# Keep system "isn't responding" dialogs out of the way where the image allows it.
adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1 || true
echo "Installing $APK"
adb install -r -g "$APK"
adb logcat -c

# 0) First launch shows onboarding: walk it (accept the notice, skip car and dongle) to reach Home.
adb shell am start -W -n "$PKG/$ACTIVITY" > /dev/null
wait_for_text "Welcome to Orange Box" onboarding-welcome || fail "First launch did not show onboarding"
shot 00-onboarding-welcome
tap_until text "Get started" "Before you drive" onboarding-disclaimer || fail "Disclaimer step missing"
tap_until text "I understand and accept" 'checked="true"' onboarding-accept || fail "Accept checkbox not found"
tap_until text "Continue" "Permissions" onboarding-permissions || fail "Permissions step missing"
shot 00-onboarding-permissions
tap_until text "Continue" "Your car" onboarding-vehicle || fail "Vehicle step missing"
tap_until text "Skip for now" "Pair the ELM327" onboarding-dongle || fail "Dongle step missing"
tap_until text "Skip for now" "Start using Orange Box" onboarding-finish-step || fail "Finish step missing"
tap_until text "Start using Orange Box" "Backend:" onboarding-home || fail "Onboarding did not end on Home"
adb logcat -d | grep -q "onboarding_complete" || fail "onboarding_complete log line missing"
adb shell am force-stop "$PKG"

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

# 5) Home -> Settings, light then dark theme (Server is the last section since #62, so check the first one)
tap_attr content-desc "Settings" home-for-settings || fail "Settings button not found on Home"
wait_for_text "Car and dongle" settings-light || fail "Settings screen did not open"
shot 05-settings-light
adb shell cmd uimode night yes > /dev/null 2>&1 || true
sleep 2
wait_for_text "Car and dongle" settings-dark || fail "Settings did not survive dark-mode switch"
shot 06-settings-dark
adb shell input keyevent KEYCODE_BACK
wait_for_text "Backend: Off" home-dark || fail "Back from Settings did not return to Home"
shot 07-home-dark
adb shell cmd uimode night no > /dev/null 2>&1 || true

# Extra smoke steps added by later milestones live in scripts/ci/smoke.d/*.sh
if compgen -G "scripts/ci/smoke.d/*.sh" > /dev/null; then
  for s in scripts/ci/smoke.d/*.sh; do
    echo "Running $s"
    PKG="$PKG" ACTIVITY="$ACTIVITY" OUT="$OUT" SMOKE_LIB="$(dirname "$0")/smoke-lib.sh" bash "$s" || fail "$s failed"
  done
fi

# No crashes for our package
adb logcat -d > "$OUT/logcat.txt"
# Only our process counts: system apps on the Google APIs image (e.g. Gmail) sometimes crash at boot.
grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/all-crashes.txt" || true
if grep -q "AndroidRuntime: Process: $PKG," "$OUT/all-crashes.txt"; then
  grep -B1 -A40 "AndroidRuntime: Process: $PKG," "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
  fail "crash detected in $PKG (see crashes.txt)"
fi
adb shell pidof "$PKG" > /dev/null || fail "app process is not running"
dump
echo "SMOKE OK"
