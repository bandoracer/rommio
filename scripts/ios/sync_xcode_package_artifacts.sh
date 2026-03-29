#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
ios_root="${repo_root}/ios"

configuration="${CONFIGURATION:-Debug}"
sdk="${SDK:-iphonesimulator}"
derived_data="${DERIVED_DATA:-${ios_root}/.derivedData}"
build_products_dir="${BUILD_PRODUCTS_DIR:-${ios_root}/build/${configuration}-${sdk}}"
package_build_dir_name="${configuration}-${sdk}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --configuration)
            configuration="$2"
            package_build_dir_name="${configuration}-${sdk}"
            shift 2
            ;;
        --sdk)
            sdk="$2"
            package_build_dir_name="${configuration}-${sdk}"
            shift 2
            ;;
        --derived-data)
            derived_data="$2"
            shift 2
            ;;
        --build-products-dir)
            build_products_dir="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

mkdir -p "${build_products_dir}" "${build_products_dir}/PackageFrameworks"

shopt -s nullglob

copied_any=0
for package_build in "${derived_data}"/SourcePackages/checkouts/*/build/"${package_build_dir_name}"; do
    [[ -d "${package_build}" ]] || continue

    for artifact in "${package_build}"/*.swiftmodule; do
        rsync -a --delete "${artifact}" "${build_products_dir}/"
        copied_any=1
    done

    for artifact in "${package_build}"/*.bundle "${package_build}"/*.framework; do
        rsync -a --delete "${artifact}" "${build_products_dir}/"
        copied_any=1
    done

    if [[ -d "${package_build}/PackageFrameworks" ]]; then
        rsync -a "${package_build}/PackageFrameworks/" "${build_products_dir}/PackageFrameworks/"
        copied_any=1
    fi
done

if [[ "${copied_any}" -eq 0 ]]; then
    echo "No remote Swift package build artifacts found under ${derived_data}/SourcePackages/checkouts/*/build/${package_build_dir_name}" >&2
fi
