#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORES_DIR="$REPO_ROOT/ios/App/Resources/Cores"
MANIFEST_PATH="$CORES_DIR/CoreLicenses.json"
PROJECT_PATH="$REPO_ROOT/ios/Rommio.xcodeproj/project.pbxproj"
CATALOG_PATH="$REPO_ROOT/ios/Sources/RommioPlayerKit/BundledCoreCatalog.swift"

/usr/bin/ruby - <<'RUBY' "$MANIFEST_PATH" "$CORES_DIR" "$PROJECT_PATH" "$CATALOG_PATH"
require "json"
require "set"

manifest_path, cores_dir, project_path, catalog_path = ARGV
manifest = JSON.parse(File.read(manifest_path))
project = File.read(project_path)
catalog = File.read(catalog_path)

errors = []

required_manifest_keys = %w[id binary_path license_path imported_at]
manifest_ids = manifest.map { |entry| entry["id"] }

if manifest_ids.uniq.length != manifest_ids.length
  duplicates = manifest_ids.group_by(&:itself).select { |_key, values| values.length > 1 }.keys
  errors << "duplicate bundled core manifest ids: #{duplicates.join(", ")}"
end

shipped_binaries = Dir.glob(File.join(cores_dir, "*.dylib"))
  .map { |path| "Cores/#{File.basename(path)}" }
  .sort
shipped_licenses = Dir.glob(File.join(cores_dir, "licenses", "*.LICENSE.txt"))
  .map { |path| "Cores/licenses/#{File.basename(path)}" }
  .sort

catalog_paths = catalog.scan(/runtimeID:\s*"([^"]+)".*?bundleRelativePath:\s*"([^"]+)"/m).to_h

rommio_target_match = project.match(/\/\* Rommio \*\/ = \{\s*isa = PBXNativeTarget;.*?buildPhases = \((.*?)\);\s*buildRules = \(/m)
if rommio_target_match.nil?
  errors << "failed to locate the Rommio app target in #{project_path}"
  resource_phase_entries = ""
else
  rommio_build_phases = rommio_target_match[1]
  resource_phase_id = rommio_build_phases[/([A-F0-9]+) \/\* Resources \*\//, 1]
  if resource_phase_id.nil?
    errors << "failed to locate the Rommio app target resources build phase"
    resource_phase_entries = ""
  else
    resource_phase_match = project.match(/#{resource_phase_id} \/\* Resources \*\/ = \{\s*isa = PBXResourcesBuildPhase;.*?files = \((.*?)\);\s*runOnlyForDeploymentPostprocessing = 0;\s*\};/m)
    if resource_phase_match.nil?
      errors << "failed to read PBXResourcesBuildPhase #{resource_phase_id} for the Rommio app target"
      resource_phase_entries = ""
    else
      resource_phase_entries = resource_phase_match[1]
    end
  end
end

manifest.each do |entry|
  missing_keys = required_manifest_keys.reject { |key| entry[key].is_a?(String) && !entry[key].empty? }
  runtime_id = entry["id"] || "<missing id>"
  errors << "#{runtime_id}: missing manifest keys #{missing_keys.join(", ")}" unless missing_keys.empty?

  binary_path = entry["binary_path"]
  license_path = entry["license_path"]
  next if binary_path.nil? || license_path.nil?

  binary_file = File.join(File.dirname(manifest_path), binary_path.delete_prefix("Cores/"))
  license_file = File.join(File.dirname(manifest_path), license_path.delete_prefix("Cores/"))

  errors << "#{runtime_id}: missing bundled binary at #{binary_file}" unless File.exist?(binary_file)
  errors << "#{runtime_id}: missing bundled license at #{license_file}" unless File.exist?(license_file)

  catalog_path_for_runtime = catalog_paths[runtime_id]
  if catalog_path_for_runtime.nil?
    errors << "#{runtime_id}: BundledCoreCatalog does not define a bundled runtime entry"
  elsif catalog_path_for_runtime != binary_path
    errors << "#{runtime_id}: BundledCoreCatalog resolves #{catalog_path_for_runtime} instead of #{binary_path}"
  end

  [binary_path, license_path].each do |bundle_path|
    basename = File.basename(bundle_path)
    unless project.include?("path = #{bundle_path};")
      errors << "#{runtime_id}: #{bundle_path} is missing from ios/Rommio.xcodeproj/project.pbxproj"
    end
    unless resource_phase_entries.include?("/* #{basename} in Resources */")
      errors << "#{runtime_id}: #{basename} is not part of the Rommio app target resources phase"
    end
  end
end

manifest_binary_paths = manifest.map { |entry| entry["binary_path"] }.sort
manifest_license_paths = manifest.map { |entry| entry["license_path"] }.sort

extra_binaries = shipped_binaries - manifest_binary_paths
missing_binaries = manifest_binary_paths - shipped_binaries
extra_licenses = shipped_licenses - manifest_license_paths
missing_licenses = manifest_license_paths - shipped_licenses

errors << "untracked bundled dylibs missing from CoreLicenses.json: #{extra_binaries.join(", ")}" unless extra_binaries.empty?
errors << "manifest dylibs missing from ios/App/Resources/Cores: #{missing_binaries.join(", ")}" unless missing_binaries.empty?
errors << "untracked bundled licenses missing from CoreLicenses.json: #{extra_licenses.join(", ")}" unless extra_licenses.empty?
errors << "manifest licenses missing from ios/App/Resources/Cores/licenses: #{missing_licenses.join(", ")}" unless missing_licenses.empty?

unless project.include?("path = Cores/CoreLicenses.json;")
  errors << "CoreLicenses.json is missing from ios/Rommio.xcodeproj/project.pbxproj"
end
unless resource_phase_entries.include?("/* CoreLicenses.json in Resources */")
  errors << "CoreLicenses.json is not part of the Rommio app target resources phase"
end

unless errors.empty?
  warn errors.join("\n")
  exit 1
end

puts "Verified #{manifest.length} bundled cores, their licenses, and the Rommio app target resources phase."
RUBY
