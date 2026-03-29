#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "usage: scripts/ios/import_bundled_core.sh <source-dylib> <core-id> <license-file>" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_DYLIB="$1"
CORE_ID="$2"
LICENSE_FILE="$3"
CORES_DIR="$REPO_ROOT/ios/App/Resources/Cores"
LICENSES_DIR="$CORES_DIR/licenses"
MANIFEST_PATH="$CORES_DIR/CoreLicenses.json"
TARGET_DYLIB="$CORES_DIR/${CORE_ID}.dylib"
TARGET_LICENSE="$LICENSES_DIR/${CORE_ID}.LICENSE.txt"

mkdir -p "$CORES_DIR" "$LICENSES_DIR"
cp "$SOURCE_DYLIB" "$TARGET_DYLIB"
chmod 0644 "$TARGET_DYLIB"
cp "$LICENSE_FILE" "$TARGET_LICENSE"
chmod 0644 "$TARGET_LICENSE"

IMPORTED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

/usr/bin/ruby - <<'RUBY' "$MANIFEST_PATH" "$CORE_ID" "$TARGET_DYLIB" "$TARGET_LICENSE" "$IMPORTED_AT"
require "json"

manifest_path, core_id, dylib_path, license_path, imported_at = ARGV
entries =
  if File.exist?(manifest_path) && !File.zero?(manifest_path)
    JSON.parse(File.read(manifest_path))
  else
    []
  end

entries.reject! { |entry| entry["id"] == core_id }
entries << {
  "id" => core_id,
  "binary_path" => dylib_path.sub(%r{.*/ios/App/Resources/}, ""),
  "license_path" => license_path.sub(%r{.*/ios/App/Resources/}, ""),
  "imported_at" => imported_at,
}

File.write(manifest_path, JSON.pretty_generate(entries) + "\n")
RUBY

echo "Imported $CORE_ID into ios/App/Resources/Cores"
