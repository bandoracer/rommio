#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORES_DIR="$REPO_ROOT/ios/App/Resources/Cores"
RELEASE_MANIFEST_PATH="$REPO_ROOT/scripts/ios/bundled-core-release-manifest.json"
CORE_LICENSES_PATH="$CORES_DIR/CoreLicenses.json"
PROJECT_PATH="$REPO_ROOT/ios/Rommio.xcodeproj/project.pbxproj"
CATALOG_PATH="$REPO_ROOT/ios/Sources/RommioPlayerKit/BundledCoreCatalog.swift"

/usr/bin/ruby - <<'RUBY' "$RELEASE_MANIFEST_PATH" "$CORE_LICENSES_PATH" "$CORES_DIR" "$PROJECT_PATH" "$CATALOG_PATH"
require "json"

release_manifest_path, core_licenses_path, cores_dir, project_path, catalog_path = ARGV
release_manifest = JSON.parse(File.read(release_manifest_path))
core_licenses = JSON.parse(File.read(core_licenses_path))
project = File.read(project_path)
catalog = File.read(catalog_path)

errors = []

required_release_keys = %w[runtime_id release_tag asset_name sha256 bundle_relative_path license_file imported_at]
required_core_license_keys = %w[id binary_path license_path imported_at]
release_ids = release_manifest.map { |entry| entry["runtime_id"] }
core_license_ids = core_licenses.map { |entry| entry["id"] }

if release_ids.uniq.length != release_ids.length
  duplicates = release_ids.group_by(&:itself).select { |_key, values| values.length > 1 }.keys
  errors << "duplicate bundled core release manifest runtime ids: #{duplicates.join(", ")}"
end

if core_license_ids.uniq.length != core_license_ids.length
  duplicates = core_license_ids.group_by(&:itself).select { |_key, values| values.length > 1 }.keys
  errors << "duplicate CoreLicenses ids: #{duplicates.join(", ")}"
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

core_licenses_by_id = core_licenses.each_with_object({}) { |entry, memo| memo[entry["id"]] = entry }

release_manifest.each do |entry|
  missing_keys = required_release_keys.reject { |key| entry[key].is_a?(String) && !entry[key].empty? }
  runtime_id = entry["runtime_id"] || "<missing runtime_id>"
  errors << "#{runtime_id}: missing release manifest keys #{missing_keys.join(", ")}" unless missing_keys.empty?

  bundle_relative_path = entry["bundle_relative_path"]
  license_file_path = entry["license_file"]
  next if bundle_relative_path.nil? || license_file_path.nil?

  binary_file = File.join(File.dirname(core_licenses_path), bundle_relative_path.delete_prefix("Cores/"))
  license_file = File.join(File.dirname(core_licenses_path), license_file_path.delete_prefix("Cores/"))

  errors << "#{runtime_id}: missing bundled binary at #{binary_file}" unless File.exist?(binary_file)
  errors << "#{runtime_id}: missing bundled license at #{license_file}" unless File.exist?(license_file)

  core_license_entry = core_licenses_by_id[runtime_id]
  if core_license_entry.nil?
    errors << "#{runtime_id}: CoreLicenses.json is missing an entry for the shipped bundled core"
  else
    missing_core_license_keys = required_core_license_keys.reject { |key| core_license_entry[key].is_a?(String) && !core_license_entry[key].empty? }
    errors << "#{runtime_id}: CoreLicenses.json entry is missing keys #{missing_core_license_keys.join(", ")}" unless missing_core_license_keys.empty?

    if core_license_entry["binary_path"] != bundle_relative_path
      errors << "#{runtime_id}: CoreLicenses.json binary_path is #{core_license_entry["binary_path"]} instead of #{bundle_relative_path}"
    end
    if core_license_entry["license_path"] != license_file_path
      errors << "#{runtime_id}: CoreLicenses.json license_path is #{core_license_entry["license_path"]} instead of #{license_file_path}"
    end
    if core_license_entry["imported_at"] != entry["imported_at"]
      errors << "#{runtime_id}: CoreLicenses.json imported_at is #{core_license_entry["imported_at"]} instead of #{entry["imported_at"]}"
    end
  end

  catalog_path_for_runtime = catalog_paths[runtime_id]
  if catalog_path_for_runtime.nil?
    errors << "#{runtime_id}: BundledCoreCatalog does not define a bundled runtime entry"
  elsif catalog_path_for_runtime != bundle_relative_path
    errors << "#{runtime_id}: BundledCoreCatalog resolves #{catalog_path_for_runtime} instead of #{bundle_relative_path}"
  end

  [bundle_relative_path, license_file_path].each do |bundle_path|
    basename = File.basename(bundle_path)
    unless project.include?("path = #{bundle_path};")
      errors << "#{runtime_id}: #{bundle_path} is missing from ios/Rommio.xcodeproj/project.pbxproj"
    end
    unless resource_phase_entries.include?("/* #{basename} in Resources */")
      errors << "#{runtime_id}: #{basename} is not part of the Rommio app target resources phase"
    end
  end
end

manifest_binary_paths = release_manifest.map { |entry| entry["bundle_relative_path"] }.sort
manifest_license_paths = release_manifest.map { |entry| entry["license_file"] }.sort

extra_binaries = shipped_binaries - manifest_binary_paths
missing_binaries = manifest_binary_paths - shipped_binaries
missing_licenses = manifest_license_paths - shipped_licenses
extra_core_license_ids = core_license_ids - release_ids
missing_core_license_ids = release_ids - core_license_ids

errors << "unexpected bundled dylibs present outside the shipped core manifest: #{extra_binaries.join(", ")}" unless extra_binaries.empty?
errors << "manifest dylibs missing from ios/App/Resources/Cores: #{missing_binaries.join(", ")}" unless missing_binaries.empty?
errors << "shipped bundled licenses missing from ios/App/Resources/Cores/licenses: #{missing_licenses.join(", ")}" unless missing_licenses.empty?
errors << "CoreLicenses.json contains extra runtime ids not present in the shipped core manifest: #{extra_core_license_ids.join(", ")}" unless extra_core_license_ids.empty?
errors << "CoreLicenses.json is missing runtime ids from the shipped core manifest: #{missing_core_license_ids.join(", ")}" unless missing_core_license_ids.empty?

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

puts "Verified #{release_manifest.length} shipped bundled cores, CoreLicenses.json, and the Rommio app target resources phase."
RUBY
