#!/usr/bin/env bash
# #137 smoke (runs last: it ends the drive and drops the simulated link): End driving on the dashboard
# must remove the ongoing "OBD dongle connected" / "Recording trip" notification (id 1001) once the
# 5 s undo window commits, instead of leaving an orphaned, non-dismissible notification behind.
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

PKG="${PKG:-io.github.neotheon3.orangebox.debug}"
ongoing_posted() { grep -Eq "pkg=$PKG[^)]* id=1001( |$)" "$OUT/$1.txt"; }

adb shell dumpsys notification --noredact > "$OUT/end-notif-before.txt" 2>/dev/null || true
ongoing_posted end-notif-before || { echo "notification 1001 not posted before End driving" >&2; exit 1; }

tap_attr text "Open dashboard" end-open
wait_for_regex 'content-desc="End driving"' end-dashboard 20
tap_attr content-desc "End driving" end-tap
for _ in $(seq 1 20); do
  adb logcat -d -s OrangeBox/App:I 2>/dev/null | grep -q "drive_ended" && break
  sleep 1
done
adb logcat -d -s OrangeBox/App:I 2>/dev/null | grep -q "drive_ended" || { echo "drive did not end" >&2; exit 1; }

gone=0
for _ in $(seq 1 15); do
  adb shell dumpsys notification --noredact > "$OUT/end-notif-after.txt" 2>/dev/null || true
  if ! ongoing_posted end-notif-after; then gone=1; break; fi
  sleep 1
done
shot 90-after-end-driving
[ "$gone" = 1 ] || { echo "notification 1001 still posted after End driving (#137)" >&2; exit 1; }
echo "end drive notification smoke OK"
