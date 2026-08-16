#!/bin/bash
# Reproducible jank measurement for com.chizberg.rewind on a connected device.
#
# Usage: tools/perf/measure.sh <scenario> [label]
#   scenarios: pan | zoom | newarea | cold
#
# Output: framestats-<scenario>[-label].txt next to this script, plus a summary on stdout.
# Parse per-frame phases with: python3 tools/perf/parse.py framestats-*.txt
#
# Methodology (see .claude/perf-plan.md):
#  - RELEASE builds only — debug's Compose tooling skews every number.
#  - Scripted input, so runs are comparable across builds.
#  - Coordinates assume a ~1080x2392 portrait screen with the controls stack at the bottom
#    (map area kept above y≈1650); adjust for other devices.
set -e
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
PKG=com.chizberg.rewind
SCENARIO="${1:?pan|zoom|newarea|cold}"
LABEL="${2:+-$2}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/framestats-$SCENARIO$LABEL.txt"

case "$SCENARIO" in
  pan)
    adb shell dumpsys gfxinfo $PKG reset > /dev/null
    for i in 1 2 3 4; do
      adb shell input swipe 540 1300 540 700 600
      adb shell input swipe 540 700 540 1300 600
      adb shell input swipe 300 1000 800 1000 600
      adb shell input swipe 800 1000 300 1000 600
    done
    ;;
  zoom)
    # Restart first: repeated runs drift the camera to max zoom, where double-taps become no-ops
    # and the numbers stop being comparable. A fresh process restores the persisted region.
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 10
    adb shell dumpsys gfxinfo $PKG reset > /dev/null
    for i in 1 2 3 4 5 6; do
      adb shell input tap 540 1000; adb shell input tap 540 1000
      sleep 1
    done
    ;;
  newarea)
    # Fling to fresh territory, then idle: debounce -> fetch -> annotation wave lands.
    # Same restart-first reasoning as zoom: keep the starting region comparable across runs.
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 10
    adb shell dumpsys gfxinfo $PKG reset > /dev/null
    adb shell input swipe 900 1000 100 1000 300
    adb shell input swipe 900 1000 100 1000 300
    adb shell input swipe 900 1000 100 1000 300
    sleep 6
    ;;
  cold)
    # Fresh process; stats accumulate from zero, so no reset — 15s covers first load + wave.
    adb shell am force-stop $PKG
    sleep 2
    adb shell am start -n $PKG/.MainActivity > /dev/null
    sleep 15
    ;;
  *)
    echo "unknown scenario: $SCENARIO" >&2; exit 1;;
esac

adb shell dumpsys gfxinfo $PKG framestats > "$OUT"
echo "--- $SCENARIO$LABEL -> $OUT"
grep -E "Total frames|Janky frames:|percentile:|Number Missed|Number Slow" "$OUT" | head -12
