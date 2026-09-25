#!/bin/bash
# Exercise the already-installed app using explicit JSON actions (ios/AppUITests/UIActionRunner.swift).
# The actions are embedded in the runner bundle, so the same pass runs on a simulator or a device.
#
#   run_player_ui_pass.sh SIMULATOR_UDID ACTIONS_JSON
#   run_player_ui_pass.sh --device DEVICE_UDID ACTIONS_JSON   (needs ROMMIO_DEVELOPMENT_TEAM for signing)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLATFORM="iOS Simulator"
if [[ "${1:-}" == "--device" ]]; then
  PLATFORM="iOS"
  shift
fi
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 [--device] UDID ACTIONS_JSON" >&2
  exit 2
fi
DEVICE="$1"
ACTIONS="$2"
ruby "$ROOT/scripts/ios/generate_player_ui_runner.rb" --actions "$ACTIONS"
xcodebuild test -project "$ROOT/ios/build/PlayerUIRunner.xcodeproj" \
  -scheme PlayerUIRunner -destination "platform=$PLATFORM,id=$DEVICE" \
  -derivedDataPath "$ROOT/ios/build/player-ui-runner" -parallel-testing-enabled NO \
  -only-testing:PlayerUIRunner/PlayerExplorationTests
