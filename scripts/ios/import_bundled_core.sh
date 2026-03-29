#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
usage: scripts/ios/import_bundled_core.sh <source-dylib> <core-id> <license-file> [--staging-dir DIR]

Stage a signed bundled iOS core dylib and its license into a temporary packaging
directory for later publication with scripts/ios/publish_bundled_cores.sh.
EOF
}

if [[ $# -lt 3 ]]; then
  usage
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_DYLIB="$1"
CORE_ID="$2"
LICENSE_FILE="$3"
shift 3

STAGING_DIR="${REPO_ROOT}/build/ios/bundled-core-staging"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --staging-dir)
      STAGING_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

STAGING_DIR="$(mkdir -p "${STAGING_DIR}" && cd "${STAGING_DIR}" && pwd)"
LICENSES_DIR="${STAGING_DIR}/licenses"
TARGET_DYLIB="${STAGING_DIR}/bundled-core-${CORE_ID}.dylib"
TARGET_LICENSE="${LICENSES_DIR}/${CORE_ID}.LICENSE.txt"

mkdir -p "${LICENSES_DIR}"
cp "${SOURCE_DYLIB}" "${TARGET_DYLIB}"
chmod 0644 "${TARGET_DYLIB}"
cp "${LICENSE_FILE}" "${TARGET_LICENSE}"
chmod 0644 "${TARGET_LICENSE}"

echo "Staged ${CORE_ID} into ${STAGING_DIR}"
