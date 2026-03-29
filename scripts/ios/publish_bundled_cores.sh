#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
manifest_path="${repo_root}/scripts/ios/bundled-core-release-manifest.json"
resources_root="${repo_root}/ios/App/Resources"
staging_dir=""
release_tag=""
repo_slug=""

usage() {
    cat <<'EOF'
usage: scripts/ios/publish_bundled_cores.sh --staging-dir DIR --release-tag TAG [--repo owner/repo]

Upload signed iOS core dylibs from a staging directory to a GitHub Release, then
update scripts/ios/bundled-core-release-manifest.json and regenerate
ios/App/Resources/Cores/CoreLicenses.json.

The staging directory may contain either:
  <runtime_id>.dylib
or
  bundled-core-<runtime_id>.dylib
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --staging-dir)
            staging_dir="$2"
            shift 2
            ;;
        --release-tag)
            release_tag="$2"
            shift 2
            ;;
        --repo)
            repo_slug="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "${staging_dir}" || -z "${release_tag}" ]]; then
    usage >&2
    exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI (gh) is required to publish bundled core artifacts." >&2
    exit 1
fi

derive_repo_slug() {
    local remote_url
    remote_url="$(git -C "${repo_root}" config --get remote.origin.url || true)"

    if [[ -n "${GITHUB_REPOSITORY:-}" ]]; then
        printf '%s\n' "${GITHUB_REPOSITORY}"
        return 0
    fi

    if [[ "${remote_url}" =~ github\.com[:/]([^/]+/[^/.]+)(\.git)?$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi

    return 1
}

if [[ -z "${repo_slug}" ]]; then
    repo_slug="$(derive_repo_slug)" || {
        echo "Failed to derive GitHub repository slug from remote.origin.url." >&2
        exit 1
    }
fi

staging_dir="$(cd "${staging_dir}" && pwd)"
shopt -s nullglob
staged_dylibs=("${staging_dir}"/*.dylib)
shopt -u nullglob

if [[ "${#staged_dylibs[@]}" -eq 0 ]]; then
    echo "No .dylib files found in ${staging_dir}." >&2
    exit 1
fi

if ! gh release view "${release_tag}" --repo "${repo_slug}" >/dev/null 2>&1; then
    gh release create "${release_tag}" \
        --repo "${repo_slug}" \
        --title "${release_tag}" \
        --notes "Bundled iOS core artifacts for Rommio." >/dev/null
fi

tmp_dir="$(mktemp -d)"
updates_path="${tmp_dir}/updates.json"
updates_tsv_path="${tmp_dir}/updates.tsv"
assets_dir="${tmp_dir}/assets"
mkdir -p "${assets_dir}"
trap 'rm -rf "${tmp_dir}"' EXIT

: > "${updates_tsv_path}"

for dylib_path in "${staged_dylibs[@]}"; do
    filename="$(basename "${dylib_path}")"
    runtime_id="${filename%.dylib}"
    runtime_id="${runtime_id#bundled-core-}"
    asset_name="bundled-core-${runtime_id}.dylib"
    asset_path="${assets_dir}/${asset_name}"
    license_bundle_path="Cores/licenses/${runtime_id}.LICENSE.txt"
    tracked_license_path="${resources_root}/${license_bundle_path}"

    if [[ ! -f "${tracked_license_path}" ]]; then
        echo "Missing tracked license file for '${runtime_id}' at ${tracked_license_path}." >&2
        exit 1
    fi

    cp "${dylib_path}" "${asset_path}"
    chmod 0644 "${asset_path}"
    gh release upload "${release_tag}" "${asset_path}" --repo "${repo_slug}" --clobber >/dev/null

    sha256="$(shasum -a 256 "${asset_path}" | awk '{print $1}')"
    imported_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${runtime_id}" \
        "${release_tag}" \
        "${asset_name}" \
        "${sha256}" \
        "Cores/${runtime_id}.dylib" \
        "${license_bundle_path}" \
        "${imported_at}" >> "${updates_tsv_path}"
done

/usr/bin/ruby -rjson -e '
updates_tsv_path = ARGV[0]
entries = File.readlines(updates_tsv_path, chomp: true).reject(&:empty?).map do |line|
  runtime_id, release_tag, asset_name, sha256, bundle_relative_path, license_file, imported_at =
    line.split("\t", 7)
  {
    "runtime_id" => runtime_id,
    "release_tag" => release_tag,
    "asset_name" => asset_name,
    "sha256" => sha256,
    "bundle_relative_path" => bundle_relative_path,
    "license_file" => license_file,
    "imported_at" => imported_at,
  }
end
File.write(ARGV[1], JSON.pretty_generate(entries) + "\n")
' "${updates_tsv_path}" "${updates_path}"

/usr/bin/ruby -rjson -e '
manifest_path, updates_path = ARGV
manifest = File.exist?(manifest_path) ? JSON.parse(File.read(manifest_path)) : []
updates = JSON.parse(File.read(updates_path))
updates.each do |entry|
  manifest.reject! { |candidate| candidate["runtime_id"] == entry["runtime_id"] }
  manifest << entry
end
manifest.sort_by! { |entry| entry.fetch("runtime_id") }
File.write(manifest_path, JSON.pretty_generate(manifest) + "\n")
' "${manifest_path}" "${updates_path}"

/usr/bin/ruby "${script_dir}/generate_core_licenses.rb" \
    --manifest "${manifest_path}" \
    --output "${resources_root}/Cores/CoreLicenses.json"

"${script_dir}/verify_core_build_manifest.sh"
"${script_dir}/fetch_bundled_cores.sh" --repo "${repo_slug}"

echo "Published ${#staged_dylibs[@]} bundled iOS core artifact(s) to ${repo_slug}@${release_tag}."
