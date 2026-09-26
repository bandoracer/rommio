#!/bin/bash
# Touch controls pass in the controls QA harness (ios/AppUITests/PlayerHarnessButtonTests.swift
# and PlayerStickControlsTests.swift) against the installed Debug app. The harness plays no
# game and writes no saves, so it is safe on a signed-in phone. Theme and appearance apply to
# this run only (launch arguments).
#
#   run_controls_pass.sh --simulator UDID --theme aurora|riso --scheme light|dark [--out DIR]
#   run_controls_pass.sh --device UDID    --theme aurora|riso --scheme light|dark --team TEAM_ID [--out DIR]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLATFORM="" UDID="" THEME="" SCHEME="" TEAM="${ROMMIO_DEVELOPMENT_TEAM:-}" OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --simulator) PLATFORM="iOS Simulator"; UDID="$2"; shift 2 ;;
    --device) PLATFORM="iOS"; UDID="$2"; shift 2 ;;
    --theme) THEME="$2"; shift 2 ;;
    --scheme) SCHEME="$2"; shift 2 ;;
    --team) TEAM="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -z "$PLATFORM" || -z "$THEME" || -z "$SCHEME" ]]; then
  sed -n '2,9p' "$0" >&2
  exit 2
fi
if [[ "$PLATFORM" == "iOS" && -z "$TEAM" ]]; then
  echo "A device run needs --team TEAM_ID (or ROMMIO_DEVELOPMENT_TEAM) to sign the runner." >&2
  exit 2
fi
OUT="${OUT:-$ROOT/ios/build/controls-pass/$THEME-$SCHEME}"
mkdir -p "$OUT"
RESULT="$OUT/ControlsPass.xcresult"
rm -rf "$RESULT"

args=(--controls --theme "$THEME" --scheme "$SCHEME")
[[ -n "$TEAM" ]] && args+=(--team "$TEAM")
ruby "$ROOT/scripts/ios/generate_player_ui_runner.rb" "${args[@]}"

set +e
xcodebuild test -project "$ROOT/ios/build/PlayerUIRunner.xcodeproj" -scheme PlayerUIRunner \
  -destination "platform=$PLATFORM,id=$UDID" -derivedDataPath "$ROOT/ios/build/player-ui-runner" \
  -parallel-testing-enabled NO -resultBundlePath "$RESULT" \
  -only-testing:PlayerUIRunner/PlayerHarnessButtonTests \
  -only-testing:PlayerUIRunner/PlayerStickControlsTests 2>&1 | tee "$OUT/xcodebuild.log"
status=${PIPESTATUS[0]}
set -e

# A fresh folder: an export into an earlier run's folder can keep that run's manifest.
rm -rf "$OUT/attachments"
xcrun xcresulttool export attachments --path "$RESULT" --output-path "$OUT/attachments" >/dev/null 2>&1 || true
grep -E "Test Case .*(passed|failed)|error:|crashed" "$OUT/xcodebuild.log" > "$OUT/summary.txt" || true
cat "$OUT/summary.txt"
echo "Result bundle: $RESULT"
echo "Attachments: $OUT/attachments"
exit "$status"
