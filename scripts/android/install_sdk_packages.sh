#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--api <level>] [--image-tag <tag>] [--arch <arch>]

Installs the Android emulator package, platform-tools, target platform,
and the arm64 system image needed by the native RomM app emulator workflow.

$(usage_android_defaults)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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

echo "Accepting Android SDK licenses where possible..."
yes | "$SDKMANAGER_BIN" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true

echo "Installing Android SDK packages..."
"$SDKMANAGER_BIN" \
  --sdk_root="$SDK_ROOT" \
  "emulator" \
  "platform-tools" \
  "platforms;android-$ANDROID_API_LEVEL" \
  "$(system_image_package)"

echo
print_doctor_report

if [[ -d "$SDK_ROOT/emulator" ]]; then
  cat <<EOF

If the emulator binary is not already on your PATH, use:
  export PATH="$SDK_ROOT/emulator:\$PATH"
EOF
fi
