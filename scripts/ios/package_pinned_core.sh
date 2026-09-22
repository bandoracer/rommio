#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
sdk="${PLATFORM_NAME:?PLATFORM_NAME is required}"
arch="${CURRENT_ARCH:-${NATIVE_ARCH_ACTUAL:-arm64}}"
[[ "$arch" == "undefined_arch" ]] && arch="${NATIVE_ARCH_ACTUAL:-arm64}"
[[ "$sdk" == "iphoneos" ]] && arch=arm64
binary="$(python3 "${script_dir}/build_pinned_core.py" --sdk "$sdk" --arch "$arch")"
resources="${TARGET_BUILD_DIR:?}/${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}"
mkdir -p "$resources"
cp "$binary" "$resources/fceumm.dylib"
codesign --force --sign "${EXPANDED_CODE_SIGN_IDENTITY:--}" --timestamp=none "$resources/fceumm.dylib"
python3 - "$binary" "$resources" <<'PY'
import hashlib,json,pathlib,sys
source=pathlib.Path(sys.argv[1]); root=pathlib.Path(sys.argv[2])
manifest=json.loads(source.with_name('FceummProvenance.json').read_text())
manifest['sha256']=hashlib.sha256((root/'fceumm.dylib').read_bytes()).hexdigest()
(root/'FceummProvenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY

python3 "${repo_root}/scripts/cores/package_apple.py"
