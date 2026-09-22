#!/bin/bash
# Exercise an already-installed simulator app using explicit JSON actions.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 SIMULATOR_UDID ACTIONS_JSON" >&2
  exit 2
fi
DEVICE="$1"
ACTIONS="$2"
# XCTest's simulator runner shares the host /tmp. Remove the opt-in file on exit
# so ordinary test runs cannot accidentally repeat an exploratory pass.
cp "$ACTIONS" /tmp/rommio-player-ui-actions.json
trap 'rm -f /tmp/rommio-player-ui-actions.json' EXIT
ruby "$ROOT/scripts/ios/generate_player_ui_runner.rb"
xcodebuild test -project "$ROOT/ios/build/PlayerUIRunner.xcodeproj" \
  -scheme PlayerUIRunner -destination "platform=iOS Simulator,id=$DEVICE" \
  -derivedDataPath "$ROOT/ios/build/player-ui-runner" -parallel-testing-enabled NO \
  -only-testing:PlayerUIRunner/PlayerExplorationTests
