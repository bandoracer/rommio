#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST_PATH="$REPO_ROOT/scripts/ios/core-build-manifest.json"
RUNTIME_MATRIX_PATH="$REPO_ROOT/docs/runtime-matrix.json"
REQUIRE_FILES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --require-files)
      REQUIRE_FILES=1
      shift
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

/usr/bin/ruby - <<'RUBY' "$MANIFEST_PATH" "$RUNTIME_MATRIX_PATH" "$REPO_ROOT" "$REQUIRE_FILES"
require "json"

manifest_path, runtime_matrix_path, repo_root, require_files = ARGV
manifest = JSON.parse(File.read(manifest_path))
runtime_matrix = JSON.parse(File.read(runtime_matrix_path))
expected_runtime_ids = runtime_matrix.fetch("families").map { |family| family.fetch("default_runtime_id") }.sort

actual_runtime_ids = manifest.map { |entry| entry.fetch("runtime_id") }
errors = []

if actual_runtime_ids.uniq.length != actual_runtime_ids.length
  duplicates = actual_runtime_ids.group_by(&:itself).select { |_k, v| v.length > 1 }.keys
  errors << "duplicate runtime_id entries: #{duplicates.join(", ")}"
end

if actual_runtime_ids.sort != expected_runtime_ids
  missing = expected_runtime_ids - actual_runtime_ids
  extra = actual_runtime_ids - expected_runtime_ids
  errors << "missing runtime_ids: #{missing.join(", ")}" unless missing.empty?
  errors << "unexpected runtime_ids: #{extra.join(", ")}" unless extra.empty?
end

manifest.each do |entry|
  runtime_id = entry.fetch("runtime_id")
  required_keys = %w[recipe_id source_checkout device_artifact simulator_artifact bundle_relative_path license_file]
  missing_keys = required_keys.reject { |key| entry.key?(key) && !entry[key].to_s.empty? }
  errors << "#{runtime_id}: missing keys #{missing_keys.join(", ")}" unless missing_keys.empty?

  bundle_relative_path = entry["bundle_relative_path"]
  unless bundle_relative_path.start_with?("Cores/")
    errors << "#{runtime_id}: bundle_relative_path must start with Cores/"
  end

  if File.basename(bundle_relative_path) != "#{runtime_id}.dylib"
    errors << "#{runtime_id}: bundle_relative_path should end in #{runtime_id}.dylib"
  end

  if require_files == "1"
    %w[device_artifact simulator_artifact license_file].each do |path_key|
      absolute_path = File.join(repo_root, entry.fetch(path_key))
      errors << "#{runtime_id}: missing #{path_key} at #{absolute_path}" unless File.exist?(absolute_path)
    end
  end
end

unless errors.empty?
  warn errors.join("\n")
  exit 1
end

puts "Verified #{manifest.length} iOS core build manifest entries."
RUBY
