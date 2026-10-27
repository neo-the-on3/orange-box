#!/usr/bin/env bash
# Release smoke (#74): installs the minified (R8) release APK on the CI emulator, walks onboarding to Home
# and checks the process did not crash. This catches missing keep rules (Hilt, Room, kotlinx.serialization,
# OkHttp), which only fail at runtime. Run inside android-emulator-runner after emulator-smoke.sh.
# Never run this against an x86/KVM emulator on the shared box.
#
# Usage: release-smoke.sh <apk-dir> <out-dir>
set -euo pipefail

APK_DIR="${1:?apk dir}"
OUT="${2:?out dir}/release"
PKG="io.github.neotheon3.orangebox"
ACTIVITY="io.github.neotheon3.orangebox.MainActivity"
mkdir -p "$OUT"

dump() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT/activities.txt" 2>/dev/null || true
}
fail() { echo "RELEASE SMOKE FAIL: $*" >&2; shot zz-failure; dump; exit 1; }
# shellcheck source=scripts/ci/smoke-lib.sh
source "$(dirname "$0")/smoke-lib.sh"

APK=$(ls "$APK_DIR"/*.apk | head -n1)
echo "Installing $APK"
adb install -r -g "$APK" || fail "release APK did not install (is it signed?)"
adb logcat -c

adb shell am start -W -n "$PKG/$ACTIVITY" > /dev/null
wait_for_text "Welcome to Orange Box" r-onboarding-welcome || fail "Release build did not show onboarding"
shot r00-onboarding
tap_until text "Get started" "Before you drive" r-onboarding-disclaimer || fail "Disclaimer step missing"
tap_until text "I understand and accept" 'checked="true"' r-onboarding-accept || fail "Accept checkbox not found"
tap_until text "Continue" "Permissions" r-onboarding-permissions || fail "Permissions step missing"
tap_until text "Continue" "Your car" r-onboarding-vehicle || fail "Vehicle step missing"
tap_until text "Skip for now" "Pair the ELM327" r-onboarding-dongle || fail "Dongle step missing"
tap_until text "Skip for now" "Start using Orange Box" r-onboarding-finish || fail "Finish step missing"
tap_until text "Start using Orange Box" "Start driving" r-home || fail "Onboarding did not end on Home"
shot r01-home
# Relaunch, so the saved onboarding state (DataStore) and the trip database (Room) are read back.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" > /dev/null
wait_for_text "Start driving" r-home-relaunch || fail "Relaunch did not go straight to Home"
shot r02-home-relaunch

adb logcat -d > "$OUT/logcat.txt"
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "AndroidRuntime: Process: $PKG,"; then
  grep -B1 -A40 "AndroidRuntime: Process: $PKG," "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
  fail "crash detected in release $PKG (see release/crashes.txt)"
fi
adb shell pidof "$PKG" > /dev/null || fail "release app process is not running"
dump
echo "RELEASE SMOKE OK"
