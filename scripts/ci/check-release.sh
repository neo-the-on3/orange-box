#!/usr/bin/env bash
# Fails if debug-only code (Simulator route/classes, simulated ELM327, launch-intent override)
# ends up in the release APK. Uses the R8 mapping (original class names of everything kept)
# plus a string scan of the dex files.
set -euo pipefail

MAPPING=app/build/outputs/mapping/release/mapping.txt
APK=$(ls app/build/outputs/apk/release/*.apk | head -n1)
[ -f "$MAPPING" ] || { echo "missing $MAPPING"; exit 1; }

status=0
if grep -E '^[^ ].*(core\.simulator\.|\.simulator\.Simulator|SimulatedElm327|DebugLaunchOverrides)' "$MAPPING"; then
  echo "::error::Debug-only classes found in release mapping"
  status=1
fi

# No Google services (Play Services / Firebase / Google Maps) in the app, by decision.
if grep -E '^(com\.google\.android\.gms|com\.google\.firebase|com\.google\.android\.libraries\.maps)\.' "$MAPPING"; then
  echo "::error::Google Play Services / Firebase classes found in release mapping"
  status=1
fi

tmp=$(mktemp -d)
unzip -q -o "$APK" 'classes*.dex' -d "$tmp"
for pattern in "SimulatorDestination" "backend_mode override applied" "onboarding override applied" "Use simulated dongle"; do
  if grep -a -q "$pattern" "$tmp"/classes*.dex; then
    echo "::error::Release dex contains '$pattern'"
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "release check OK: no Simulator, debug overrides or Google services in $APK"
exit "$status"
