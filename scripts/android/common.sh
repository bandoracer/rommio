#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NATIVE_APP_DIR="$REPO_ROOT"
APP_PACKAGE="io.github.mattsays.rommnative"
APP_ACTIVITY=".MainActivity"

ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-36}"
ANDROID_IMAGE_TAG="${ANDROID_IMAGE_TAG:-google_apis}"
ANDROID_ARCH="${ANDROID_ARCH:-arm64-v8a}"
ANDROID_DEVICE_NAME="${ANDROID_DEVICE_NAME:-pixel_8}"
ANDROID_AVD_NAME="${ANDROID_AVD_NAME:-romm-native-api36}"

usage_android_defaults() {
  cat <<EOF
Defaults:
  API level:   $ANDROID_API_LEVEL
  Image tag:   $ANDROID_IMAGE_TAG
  CPU arch:    $ANDROID_ARCH
  AVD device:  $ANDROID_DEVICE_NAME
  AVD name:    $ANDROID_AVD_NAME

Environment overrides:
  ANDROID_HOME or ANDROID_SDK_ROOT
  ANDROID_API_LEVEL
  ANDROID_IMAGE_TAG
  ANDROID_ARCH
  ANDROID_DEVICE_NAME
  ANDROID_AVD_NAME
  ANDROID_SERIAL
EOF
}

die() {
  echo "$*" >&2
  exit 1
}

detect_android_sdk_root() {
  local candidates=()

  if [[ -n "${ANDROID_HOME:-}" ]]; then
    candidates+=("$ANDROID_HOME")
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && "${ANDROID_SDK_ROOT:-}" != "${ANDROID_HOME:-}" ]]; then
    candidates+=("$ANDROID_SDK_ROOT")
  fi
  candidates+=(
    "$HOME/Library/Android/sdk"
    "$HOME/Android/Sdk"
  )

  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -d "$candidate/platform-tools" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  die "Unable to locate Android SDK. Set ANDROID_HOME or ANDROID_SDK_ROOT."
}

SDK_ROOT="$(detect_android_sdk_root)"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

find_sdk_cli_tool() {
  local tool_name="$1"
  local candidate

  for candidate in \
    "$SDK_ROOT/cmdline-tools/latest/bin/$tool_name" \
    "$SDK_ROOT/cmdline-tools/bin/$tool_name"
  do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  local newest
  newest="$(
    find "$SDK_ROOT/cmdline-tools" -maxdepth 2 -type f -name "$tool_name" 2>/dev/null \
      | sort \
      | tail -n 1
  )"
  if [[ -n "$newest" ]]; then
    printf '%s\n' "$newest"
    return 0
  fi

  if command -v "$tool_name" >/dev/null 2>&1; then
    command -v "$tool_name"
    return 0
  fi

  die "Unable to locate $tool_name. Install Android command-line tools first."
}

ADB_BIN="${ADB_BIN:-$SDK_ROOT/platform-tools/adb}"
if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="$(find_sdk_cli_tool adb)"
fi

SDKMANAGER_BIN="${SDKMANAGER_BIN:-$(find_sdk_cli_tool sdkmanager)}"
AVDMANAGER_BIN="${AVDMANAGER_BIN:-$(find_sdk_cli_tool avdmanager)}"
EMULATOR_BIN="${EMULATOR_BIN:-$SDK_ROOT/emulator/emulator}"

system_image_package() {
  printf 'system-images;android-%s;%s;%s\n' "$ANDROID_API_LEVEL" "$ANDROID_IMAGE_TAG" "$ANDROID_ARCH"
}

system_image_dir() {
  printf '%s/system-images/android-%s/%s/%s\n' "$SDK_ROOT" "$ANDROID_API_LEVEL" "$ANDROID_IMAGE_TAG" "$ANDROID_ARCH"
}

avd_config_dir() {
  printf '%s/.android/avd/%s.avd\n' "$HOME" "$ANDROID_AVD_NAME"
}

ensure_native_app_dir() {
  [[ -d "$NATIVE_APP_DIR" ]] || die "Native app not found at $NATIVE_APP_DIR"
}

ensure_emulator_installed() {
  [[ -x "$EMULATOR_BIN" ]] || die "Android emulator binary not found at $EMULATOR_BIN. Run install_sdk_packages.sh first."
}

ensure_system_image_installed() {
  [[ -d "$(system_image_dir)" ]] || die "System image $(system_image_package) is not installed. Run install_sdk_packages.sh first."
}

resolve_device_serial() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    printf '%s\n' "$ANDROID_SERIAL"
    return 0
  fi

  local devices=()
  local emulators=()
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && devices+=("$line")
  done < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }')

  while IFS= read -r line; do
    [[ -n "$line" ]] && emulators+=("$line")
  done < <(printf '%s\n' "${devices[@]:-}" | awk '/^emulator-/ { print $1 }')

  case "${#devices[@]}" in
    0) die "No connected Android device or emulator found." ;;
    1) printf '%s\n' "${devices[0]}" ;;
    *)
      if [[ "${#emulators[@]}" -eq 1 ]]; then
        printf '%s\n' "${emulators[0]}"
        return 0
      fi
      die "Multiple Android devices found. Set ANDROID_SERIAL to choose one."
      ;;
  esac
}

wait_for_boot_complete() {
  local serial="${1:-$(resolve_device_serial)}"

  "$ADB_BIN" -s "$serial" wait-for-device >/dev/null

  until [[ "$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done

  "$ADB_BIN" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
}

run_gradle() {
  ensure_native_app_dir
  (
    cd "$NATIVE_APP_DIR"
    ./gradlew "$@"
  )
}

push_file_into_app() {
  local serial="$1"
  local source_path="$2"
  local relative_target="$3"
  local target_dir
  target_dir="$(dirname "$relative_target")"

  [[ -f "$source_path" ]] || die "File not found: $source_path"

  "$ADB_BIN" -s "$serial" shell run-as "$APP_PACKAGE" sh -c "mkdir -p \"$target_dir\""
  "$ADB_BIN" -s "$serial" exec-out run-as "$APP_PACKAGE" sh -c "cat > \"$relative_target\"" < "$source_path"
}

print_doctor_report() {
  cat <<EOF
Repository:     $REPO_ROOT
Native app:     $NATIVE_APP_DIR
SDK root:       $SDK_ROOT
adb:            $ADB_BIN
sdkmanager:     $SDKMANAGER_BIN
avdmanager:     $AVDMANAGER_BIN
emulator:       $EMULATOR_BIN
System image:   $(system_image_package)
System image dir:
  $(system_image_dir)
AVD config dir:
  $(avd_config_dir)
EOF
}
