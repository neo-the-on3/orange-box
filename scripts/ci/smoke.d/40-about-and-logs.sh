#!/usr/bin/env bash
# Settings → About shows the build identity; structured OrangeBox logs are present.
set -euo pipefail
# shellcheck source=scripts/ci/smoke-lib.sh
source "${SMOKE_LIB:-scripts/ci/smoke-lib.sh}"

tap_attr content-desc "Settings" about-home
wait_for_text "Car and dongle" about-settings
# M8 added sections above About, so scroll until the version row shows instead of a fixed number of swipes.
version_re='text="Version 0\.1\.0\.[0-9]+-[0-9a-f]{7}(-ci[0-9]+)?-debug \(code [0-9]+\)"'
for _ in $(seq 1 12); do
  ui_dump about-version
  grep -Eq "$version_re" "$OUT/about-version.xml" && break
  adb shell input swipe 500 1600 500 600 300; sleep 1
done
wait_for_regex "$version_re" about-version 5
grep -o 'text="Version [^"]*"' "$OUT/about-version.xml" | tee "$OUT/version.txt"
shot 40-settings-about
adb shell input keyevent KEYCODE_BACK

adb logcat -d -s 'OrangeBox/OBD:*' 'OrangeBox/Backend:*' 'OrangeBox/App:*' 'OrangeBox/Telemetry:*' > "$OUT/orangebox-logs.txt" || true
for pat in 'OrangeBox/App.*start version=' 'OrangeBox/Backend.*mode mode=' 'OrangeBox/OBD.*state ' 'OrangeBox/OBD.*protocol_detected protocol=CAN_11_500' 'OrangeBox/OBD.*link_down' 'OrangeBox/Telemetry.*polling_started'; do
  grep -Eq "$pat" "$OUT/orangebox-logs.txt" || { echo "missing log line: $pat" >&2; exit 1; }
done
echo "about + logs smoke OK"
