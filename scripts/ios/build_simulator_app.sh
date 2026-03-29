#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
ios_root="${repo_root}/ios"

configuration="${CONFIGURATION:-Debug}"
sdk="iphonesimulator"
build_products_dir="${ios_root}/build/${configuration}-${sdk}"
log_dir="${ios_root}/build-logs"
derived_data="${DERIVED_DATA:-}"
adhoc_sign_simulator_bundle="${ADHOC_SIGN_SIMULATOR_BUNDLE:-1}"
regenerate_xcodeproj="${REGENERATE_XCODEPROJ:-0}"

host_arch="$(uname -m)"
archs="${ARCHS:-$([[ "${host_arch}" == "arm64" ]] && echo "arm64" || echo "x86_64")}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --configuration)
            configuration="$2"
            build_products_dir="${ios_root}/build/${configuration}-${sdk}"
            shift 2
            ;;
        --derived-data)
            derived_data="$2"
            shift 2
            ;;
        --archs)
            archs="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

mkdir -p "${log_dir}" "${build_products_dir}"

if [[ "${regenerate_xcodeproj}" == "1" || ! -d "${ios_root}/Rommio.xcodeproj" ]]; then
    ruby "${script_dir}/generate_xcodeproj.rb"
fi

"${script_dir}/fetch_bundled_cores.sh" --if-missing

xcodebuild_args=(
    xcodebuild
    -project "${ios_root}/Rommio.xcodeproj"
    -target Rommio
    -sdk "${sdk}"
    -configuration "${configuration}"
    "ARCHS=${archs}"
    ONLY_ACTIVE_ARCH=YES
    SWIFT_ENABLE_EXPLICIT_MODULES=NO
    build
)

resolve_derived_data() {
    if [[ -n "${derived_data}" && -d "${derived_data}" ]]; then
        printf '%s\n' "${derived_data}"
        return 0
    fi

    local derived_data_root="${HOME}/Library/Developer/Xcode/DerivedData"
    local newest=""
    local newest_mtime=0
    shopt -s nullglob
    for candidate in "${derived_data_root}"/Rommio-*; do
        [[ -d "${candidate}" ]] || continue
        local mtime
        mtime="$(stat -f '%m' "${candidate}")"
        if (( mtime > newest_mtime )); then
            newest="${candidate}"
            newest_mtime="${mtime}"
        fi
    done
    shopt -u nullglob

    if [[ -n "${newest}" ]]; then
        printf '%s\n' "${newest}"
        return 0
    fi

    return 1
}

sync_artifacts() {
    local resolved_derived_data
    resolved_derived_data="$(resolve_derived_data)" || return 0
    "${script_dir}/sync_xcode_package_artifacts.sh" \
        --configuration "${configuration}" \
        --sdk "${sdk}" \
        --derived-data "${resolved_derived_data}" \
        --build-products-dir "${build_products_dir}"
}

timestamp="$(date +%Y%m%d-%H%M%S)"
first_log="${log_dir}/xcodebuild-simulator-${timestamp}-warmup.log"
retry_log="${log_dir}/xcodebuild-simulator-${timestamp}-retry.log"

sync_artifacts || true

if ! "${xcodebuild_args[@]}" >"${first_log}" 2>&1; then
    sync_artifacts
    if ! "${xcodebuild_args[@]}" >"${retry_log}" 2>&1; then
        echo "Simulator build failed. Recent log output:" >&2
        tail -n 200 "${retry_log}" >&2
        exit 1
    fi
fi

app_path="${build_products_dir}/Rommio.app"
if [[ ! -d "${app_path}" ]]; then
    echo "Build completed without producing ${app_path}" >&2
    exit 1
fi

if [[ "${sdk}" == "iphonesimulator" && "${adhoc_sign_simulator_bundle}" == "1" ]]; then
    # Xcode leaves simulator builds as linker-signed binaries when code signing is
    # disabled in project settings. Re-sign the finished app bundle ad hoc so the
    # shipped dylibs, license files, and other resources satisfy strict verification.
    codesign --force --sign - --timestamp=none --deep "${app_path}"
    codesign --verify --deep --strict "${app_path}"
fi

echo "Built ${app_path}"
