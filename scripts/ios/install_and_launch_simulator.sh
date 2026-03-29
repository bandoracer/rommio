#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
ios_root="${repo_root}/ios"
bundle_id="io.github.mattsays.rommio.ios"

configuration="${CONFIGURATION:-Debug}"
device_name="${SIMULATOR_NAME:-iPhone Air}"
device_udid="${SIMULATOR_UDID:-}"
skip_build=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --configuration)
            configuration="$2"
            shift 2
            ;;
        --device-name)
            device_name="$2"
            shift 2
            ;;
        --udid)
            device_udid="$2"
            shift 2
            ;;
        --skip-build)
            skip_build=1
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ "${skip_build}" -eq 0 ]]; then
    "${script_dir}/build_simulator_app.sh" --configuration "${configuration}"
fi

app_path="${ios_root}/build/${configuration}-iphonesimulator/Rommio.app"
if [[ ! -d "${app_path}" ]]; then
    echo "Missing app bundle at ${app_path}" >&2
    exit 1
fi

if [[ -z "${device_udid}" ]]; then
    device_udid="$(SIMULATOR_TARGET_NAME="${device_name}" python3 - <<'PY'
import json
import os
import subprocess

payload = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))
for runtime, devices in payload["devices"].items():
    for device in devices:
        if device.get("state") == "Booted":
            print(device["udid"])
            raise SystemExit(0)

target_name = os.environ["SIMULATOR_TARGET_NAME"]
for runtime, devices in payload["devices"].items():
    for device in devices:
        if device.get("name") == target_name and device.get("isAvailable", False):
            print(device["udid"])
            raise SystemExit(0)

for runtime, devices in payload["devices"].items():
    for device in devices:
        if device.get("isAvailable", False):
            print(device["udid"])
            raise SystemExit(0)

raise SystemExit(1)
PY
)"
fi

xcrun simctl boot "${device_udid}" >/dev/null 2>&1 || true
xcrun simctl bootstatus "${device_udid}" -b
xcrun simctl install "${device_udid}" "${app_path}"
xcrun simctl terminate "${device_udid}" "${bundle_id}" >/dev/null 2>&1 || true
launch_output="$(xcrun simctl launch "${device_udid}" "${bundle_id}")"

if command -v open >/dev/null 2>&1; then
    open -a Simulator >/dev/null 2>&1 || true
fi

echo "Installed ${app_path} on ${device_udid}"
echo "${launch_output}"
