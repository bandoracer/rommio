#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

SERIAL=""
SKIP_BUILD=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--serial <device>] [--skip-build]

Builds the selected debug APK, installs it on the connected emulator/device,
and launches the main activity.

Set APP_VARIANT=legacyBridge to build and install the temporary
legacy bridge package instead of the renamed standard app.

Use ANDROID_SERIAL or --serial when more than one device is attached.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

if [[ -n "$SERIAL" ]]; then
  export ANDROID_SERIAL="$SERIAL"
fi

SERIAL="$(resolve_device_serial)"
wait_for_boot_complete "$SERIAL"

if [[ "$SKIP_BUILD" -ne 1 ]]; then
  run_gradle "$APP_INSTALL_TASK"
else
  "$ADB_BIN" -s "$SERIAL" install -r "$APP_APK_PATH"
fi

"$ADB_BIN" -s "$SERIAL" shell am start -n "$APP_PACKAGE/$APP_ACTIVITY" >/dev/null
echo "App launched on $SERIAL"
