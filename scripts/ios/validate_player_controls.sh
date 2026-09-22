#!/bin/bash
# Run against an already installed Debug app on a dedicated simulator.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEVICE="${1:?Usage: validate_player_controls.sh SIMULATOR_UDID}"
cp "$ROOT/docs/player-controls.json" /tmp/rommio-controls-contract.json
touch /tmp/rommio-controls-test
trap 'rm -f /tmp/rommio-controls-test /tmp/rommio-controls-contract.json' EXIT
"${RUBY:-ruby}" "$ROOT/scripts/ios/generate_player_ui_runner.rb"
xcodebuild test -project "$ROOT/ios/build/PlayerUIRunner.xcodeproj" \
  -scheme PlayerUIRunner -destination "platform=iOS Simulator,id=$DEVICE" \
  -derivedDataPath "$ROOT/ios/build/player-ui-runner" -parallel-testing-enabled NO \
  -only-testing:PlayerUIRunner/ExpansionControlsTests/testConsoleControls
