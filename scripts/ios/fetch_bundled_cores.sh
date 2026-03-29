#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
manifest_path="${repo_root}/scripts/ios/bundled-core-release-manifest.json"
resources_root="${repo_root}/ios/App/Resources"
cores_dir="${resources_root}/Cores"
if_missing=0
repo_slug=""

usage() {
    cat <<'EOF'
usage: scripts/ios/fetch_bundled_cores.sh [--if-missing] [--repo owner/repo]

Download the shipped iOS bundled cores declared in
scripts/ios/bundled-core-release-manifest.json, verify their checksums, install
them into ios/App/Resources/Cores/, regenerate CoreLicenses.json, and verify the
Rommio app bundle resource wiring.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --if-missing)
            if_missing=1
            shift
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

entry_lines="$(/usr/bin/ruby -rjson -e '
manifest = JSON.parse(File.read(ARGV[0]))
manifest.each do |entry|
  puts [
    entry.fetch("runtime_id"),
    entry.fetch("release_tag"),
    entry.fetch("asset_name"),
    entry.fetch("sha256"),
    entry.fetch("bundle_relative_path"),
    entry.fetch("license_file"),
    entry.fetch("imported_at")
  ].join("\t")
end
' "${manifest_path}")"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

download_count=0

download_with_gh() {
    local release_tag="$1"
    local asset_name="$2"
    local output_path="$3"

    if ! command -v gh >/dev/null 2>&1; then
        return 1
    fi

    local asset_dir
    asset_dir="$(dirname "${output_path}")"
    mkdir -p "${asset_dir}"
    rm -f "${output_path}"

    gh release download "${release_tag}" \
        --repo "${repo_slug}" \
        --pattern "${asset_name}" \
        --dir "${asset_dir}" \
        --clobber >/dev/null 2>&1 || return 1

    [[ -f "${output_path}" ]]
}

download_with_curl() {
    local release_tag="$1"
    local asset_name="$2"
    local output_path="$3"
    local url="https://github.com/${repo_slug}/releases/download/${release_tag}/${asset_name}"

    mkdir -p "$(dirname "${output_path}")"
    curl -fsSL "${url}" -o "${output_path}" >/dev/null 2>&1
}

verify_sha() {
    local expected_sha="$1"
    local file_path="$2"
    local actual_sha
    actual_sha="$(shasum -a 256 "${file_path}" | awk '{print $1}')"
    [[ "${actual_sha}" == "${expected_sha}" ]]
}

while IFS=$'\t' read -r runtime_id release_tag asset_name sha256 bundle_relative_path license_file imported_at; do
    [[ -n "${runtime_id}" ]] || continue

    target_path="${resources_root}/${bundle_relative_path}"
    install_required=1

    if [[ "${if_missing}" == "1" && -f "${target_path}" ]] && verify_sha "${sha256}" "${target_path}"; then
        install_required=0
    fi

    if [[ "${install_required}" == "0" ]]; then
        continue
    fi

    download_path="${tmp_dir}/${asset_name}"
    if ! download_with_gh "${release_tag}" "${asset_name}" "${download_path}"; then
        if ! download_with_curl "${release_tag}" "${asset_name}" "${download_path}"; then
            echo "Failed to download bundled core '${runtime_id}' from release '${release_tag}'." >&2
            exit 1
        fi
    fi

    if ! verify_sha "${sha256}" "${download_path}"; then
        echo "Checksum mismatch for bundled core '${runtime_id}' from release '${release_tag}'." >&2
        exit 1
    fi

    mkdir -p "$(dirname "${target_path}")"
    cp "${download_path}" "${target_path}"
    chmod 0644 "${target_path}"
    download_count=$((download_count + 1))
done <<< "${entry_lines}"

/usr/bin/ruby "${script_dir}/generate_core_licenses.rb" \
    --manifest "${manifest_path}" \
    --output "${cores_dir}/CoreLicenses.json"

"${script_dir}/verify_bundled_core_resources.sh"

if [[ "${download_count}" -gt 0 ]]; then
    echo "Fetched ${download_count} bundled iOS core artifact(s) from GitHub Releases."
else
    echo "Bundled iOS core artifacts already satisfied the shipped manifest."
fi
