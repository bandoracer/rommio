#!/bin/bash
# Navigation pass and push-transition metrics against the installed, signed-in app
# (ios/AppUITests/NavigationPassTests.swift). Read-only: it plays, downloads and
# changes nothing. Theme and appearance apply to this run only (launch arguments).
#
#   run_navigation_pass.sh --simulator UDID --theme aurora|riso --scheme light|dark [options]
#   run_navigation_pass.sh --device UDID    --theme aurora|riso --scheme light|dark --team TEAM_ID [options]
#
# Options:
#   --tests pass|transitions|all   what to run (default all)
#   --query TEXT                   the Search query (default "a")
#   --iterations N                 measure iterations per transition (default 5)
#   --out DIR                      where the result bundle and captures go
#                                  (default ios/build/navigation-pass/<theme>-<scheme>)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLATFORM="" UDID="" THEME="" SCHEME="" TEAM="${ROMMIO_DEVELOPMENT_TEAM:-}" TESTS="all" QUERY="a" ITERATIONS="5" OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --simulator) PLATFORM="iOS Simulator"; UDID="$2"; shift 2 ;;
    --device) PLATFORM="iOS"; UDID="$2"; shift 2 ;;
    --theme) THEME="$2"; shift 2 ;;
    --scheme) SCHEME="$2"; shift 2 ;;
    --team) TEAM="$2"; shift 2 ;;
    --tests) TESTS="$2"; shift 2 ;;
    --query) QUERY="$2"; shift 2 ;;
    --iterations) ITERATIONS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -z "$PLATFORM" || -z "$THEME" || -z "$SCHEME" ]]; then
  sed -n '2,15p' "$0" >&2
  exit 2
fi
if [[ "$PLATFORM" == "iOS" && -z "$TEAM" ]]; then
  echo "A device run needs --team TEAM_ID (or ROMMIO_DEVELOPMENT_TEAM) to sign the runner." >&2
  exit 2
fi
OUT="${OUT:-$ROOT/ios/build/navigation-pass/$THEME-$SCHEME}"
mkdir -p "$OUT"
RESULT="$OUT/NavigationPass.xcresult"
rm -rf "$RESULT"

args=(--theme "$THEME" --scheme "$SCHEME" --query "$QUERY" --iterations "$ITERATIONS")
[[ -n "$TEAM" ]] && args+=(--team "$TEAM")
ruby "$ROOT/scripts/ios/generate_player_ui_runner.rb" "${args[@]}"

only=()
case "$TESTS" in
  pass) only=(-only-testing:PlayerUIRunner/NavigationPassTests/testNavigationPass) ;;
  transitions) only=(-only-testing:PlayerUIRunner/NavigationPassTests/testPlatformPushTransition
                     -only-testing:PlayerUIRunner/NavigationPassTests/testGamePushTransition
                     -only-testing:PlayerUIRunner/NavigationPassTests/testSettingsPushTransition) ;;
  all) only=(-only-testing:PlayerUIRunner/NavigationPassTests) ;;
  *) echo "--tests must be pass, transitions or all" >&2; exit 2 ;;
esac

set +e
xcodebuild test -project "$ROOT/ios/build/PlayerUIRunner.xcodeproj" -scheme PlayerUIRunner \
  -destination "platform=$PLATFORM,id=$UDID" -derivedDataPath "$ROOT/ios/build/player-ui-runner" \
  -parallel-testing-enabled NO -resultBundlePath "$RESULT" "${only[@]}" 2>&1 | tee "$OUT/xcodebuild.log"
status=${PIPESTATUS[0]}
set -e

# Captures (screenshots and accessibility trees) and the measured transitions.
# A fresh folder: an export into an earlier run's folder can keep that run's manifest.
rm -rf "$OUT/attachments"
xcrun xcresulttool export attachments --path "$RESULT" --output-path "$OUT/attachments" >/dev/null 2>&1 || true
grep -E "measured \[|error:|crash|not running|stopped at" "$OUT/xcodebuild.log" > "$OUT/summary.txt" || true
cat "$OUT/summary.txt"
echo "Result bundle: $RESULT"
echo "Attachments: $OUT/attachments"
exit "$status"
