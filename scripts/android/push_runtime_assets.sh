#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

SERIAL=""
PLATFORM=""
CORES=()
BIOS_FILES=()
ROMS=()

usage() {
  cat <<EOF
Usage: $(basename "$0") [--serial <device>] [--platform <slug>] [--core <file>]... [--bios <file>]... [--rom <file>]...

Copies libretro cores, BIOS files, or ROMs into the debug app's internal
storage using run-as. ROM uploads require --platform.

Examples:
  $(basename "$0") --core ~/Downloads/snes9x_libretro_android.so
  $(basename "$0") --bios ~/Downloads/scph5501.bin
  $(basename "$0") --platform nintendo-snes --rom ~/Downloads/smw.sfc
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --platform)
      PLATFORM="$2"
      shift 2
      ;;
    --core)
      CORES+=("$2")
      shift 2
      ;;
    --bios)
      BIOS_FILES+=("$2")
      shift 2
      ;;
    --rom)
      ROMS+=("$2")
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

if [[ -n "$SERIAL" ]]; then
  export ANDROID_SERIAL="$SERIAL"
fi

if [[ "${#CORES[@]}" -eq 0 && "${#BIOS_FILES[@]}" -eq 0 && "${#ROMS[@]}" -eq 0 ]]; then
  usage
  exit 1
fi

if [[ "${#ROMS[@]}" -gt 0 && -z "$PLATFORM" ]]; then
  die "--platform is required when uploading ROM files."
fi

SERIAL="$(resolve_device_serial)"

"$ADB_BIN" -s "$SERIAL" shell run-as "$APP_PACKAGE" sh -c 'mkdir -p files/library/cores files/library/bios files/library/system'

for core_path in "${CORES[@]}"; do
  core_name="$(basename "$core_path")"
  [[ "$core_name" =~ ^[A-Za-z0-9_.-]+\.so$ ]] || die "Invalid core library filename."
  case "$core_name" in
    lib*_libretro_android.so) ;;
    *_libretro_android.so) core_name="lib$core_name" ;;
  esac
  echo "Pushing core: $core_name"
  push_file_into_app "$SERIAL" "$core_path" "files/library/cores/$core_name"
  echo "Legacy bootstrap copy staged; an existing versioned core selection is retained."
done

for bios_path in "${BIOS_FILES[@]}"; do
  bios_name="$(basename "$bios_path")"
  [[ "$bios_name" =~ ^[A-Za-z0-9_.-]+$ && "$bios_name" != "." && "$bios_name" != ".." ]] || die "Invalid BIOS filename."
  bios_relative="$bios_name"
  case "$bios_name" in
    dc_boot.bin|naomi.zip|naomi2.zip|hod2bios.zip|f355dlx.zip|f355bios.zip|airlbios.zip|awbios.zip|segasp.zip)
      bios_relative="dc/$bios_name"
      ;;
    dc_flash.bin)
      die "dc_flash.bin is mutable runtime data, not a BIOS import. Preserve it for explicit progress recovery."
      ;;
  esac
  echo "Pushing BIOS: $bios_name"
  push_file_into_app "$SERIAL" "$bios_path" "files/library/system/$bios_relative"
done

for rom_path in "${ROMS[@]}"; do
  rom_name="$(basename "$rom_path")"
  echo "Pushing ROM: $rom_name -> $PLATFORM"
  push_file_into_app "$SERIAL" "$rom_path" "files/library/roms/$PLATFORM/$rom_name"
done
