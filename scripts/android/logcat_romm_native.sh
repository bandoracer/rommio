#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

SERIAL=""
SHOW_ALL=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--serial <device>] [--all]

Streams logcat for the RomM Native process when it is running.
Without --all, the fallback stream only shows crash-oriented logs.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --all)
      SHOW_ALL=1
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
PID="$("$ADB_BIN" -s "$SERIAL" shell pidof -s "$APP_PACKAGE" 2>/dev/null | tr -d '\r')"

if [[ -n "$PID" ]]; then
  exec "$ADB_BIN" -s "$SERIAL" logcat --pid "$PID" -v color
fi

if [[ "$SHOW_ALL" -eq 1 ]]; then
  exec "$ADB_BIN" -s "$SERIAL" logcat -v color
fi

echo "App is not running. Launch it first, or re-run with --all for full device logs." >&2
exec "$ADB_BIN" -s "$SERIAL" logcat -v color AndroidRuntime:E ActivityManager:I DEBUG:E libc:F *:S
