#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--name <avd>] [--device <device-id>] [--api <level>] [--image-tag <tag>] [--arch <arch>]

Creates or replaces the default Android Virtual Device used for RomM Native.

$(usage_android_defaults)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)
      ANDROID_AVD_NAME="$2"
      shift 2
      ;;
    --device)
      ANDROID_DEVICE_NAME="$2"
      shift 2
      ;;
    --api)
      ANDROID_API_LEVEL="$2"
      shift 2
      ;;
    --image-tag)
      ANDROID_IMAGE_TAG="$2"
      shift 2
      ;;
    --arch)
      ANDROID_ARCH="$2"
      shift 2
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

ensure_system_image_installed

echo "Creating AVD $ANDROID_AVD_NAME..."
echo "no" | "$AVDMANAGER_BIN" create avd \
  --force \
  --name "$ANDROID_AVD_NAME" \
  --device "$ANDROID_DEVICE_NAME" \
  --package "$(system_image_package)"

AVD_DIR="$(avd_config_dir)"
CONFIG_INI="$AVD_DIR/config.ini"
if [[ -f "$CONFIG_INI" ]]; then
  grep -q '^hw.keyboard=' "$CONFIG_INI" || printf 'hw.keyboard=yes\n' >> "$CONFIG_INI"
  grep -q '^disk.dataPartition.size=' "$CONFIG_INI" || printf 'disk.dataPartition.size=6144M\n' >> "$CONFIG_INI"
fi

echo "AVD ready at $AVD_DIR"
