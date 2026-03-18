#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

BACKGROUND=1
WAIT_FOR_BOOT=1
HEADLESS=0
WIPE_DATA=0
EXTRA_ARGS=()

usage() {
  cat <<EOF
Usage: $(basename "$0") [--name <avd>] [--foreground] [--headless] [--no-wait] [--wipe-data] [-- <emulator args>]

Starts the configured Android emulator and waits for Android to finish booting.

$(usage_android_defaults)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)
      ANDROID_AVD_NAME="$2"
      shift 2
      ;;
    --foreground)
      BACKGROUND=0
      shift
      ;;
    --headless)
      HEADLESS=1
      shift
      ;;
    --no-wait)
      WAIT_FOR_BOOT=0
      shift
      ;;
    --wipe-data)
      WIPE_DATA=1
      shift
      ;;
    --)
      shift
      EXTRA_ARGS+=("$@")
      break
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

ensure_emulator_installed
[[ -d "$(avd_config_dir)" ]] || die "AVD $ANDROID_AVD_NAME is missing. Run create_avd.sh first."

EMULATOR_ARGS=(
  -avd "$ANDROID_AVD_NAME"
  -netdelay none
  -netspeed full
  -no-snapshot-save
)

if [[ "$HEADLESS" -eq 1 ]]; then
  EMULATOR_ARGS+=(-no-window -gpu swiftshader_indirect)
else
  EMULATOR_ARGS+=(-gpu host)
fi

if [[ "$WIPE_DATA" -eq 1 ]]; then
  EMULATOR_ARGS+=(-wipe-data)
fi

if [[ "${#EXTRA_ARGS[@]}" -gt 0 ]]; then
  EMULATOR_ARGS+=("${EXTRA_ARGS[@]}")
fi

if [[ "$BACKGROUND" -eq 1 ]]; then
  LOG_DIR="$NATIVE_APP_DIR/build/emulator-logs"
  mkdir -p "$LOG_DIR"
  LOG_PATH="$LOG_DIR/$ANDROID_AVD_NAME.log"

  nohup "$EMULATOR_BIN" "${EMULATOR_ARGS[@]}" </dev/null >"$LOG_PATH" 2>&1 &
  EMULATOR_PID=$!
  echo "Emulator started in background (pid=$EMULATOR_PID)"
  echo "Log: $LOG_PATH"
else
  "$EMULATOR_BIN" "${EMULATOR_ARGS[@]}" &
  EMULATOR_PID=$!
fi

sleep 5

if [[ "$WAIT_FOR_BOOT" -eq 1 ]]; then
  wait_for_boot_complete
  echo "Android boot completed."
fi

if [[ "$BACKGROUND" -eq 0 ]]; then
  wait "$EMULATOR_PID"
fi
