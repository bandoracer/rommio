#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

print_doctor_report
echo

if [[ -x "$EMULATOR_BIN" ]]; then
  echo "Emulator package: installed"
else
  echo "Emulator package: missing"
fi

if [[ -d "$(system_image_dir)" ]]; then
  echo "System image: installed"
else
  echo "System image: missing"
fi

if [[ -d "$(avd_config_dir)" ]]; then
  echo "AVD: present ($ANDROID_AVD_NAME)"
else
  echo "AVD: missing ($ANDROID_AVD_NAME)"
fi

echo
"$ADB_BIN" devices -l || true

echo
"$AVDMANAGER_BIN" list avd || true
