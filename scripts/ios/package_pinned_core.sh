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
python3 - "$binary" "$resources" "${EXPANDED_CODE_SIGN_IDENTITY:--}" "$script_dir" <<'PY'
import importlib.util,json,pathlib,sys
source=pathlib.Path(sys.argv[1]); root=pathlib.Path(sys.argv[2])
(root/'fceumm.dylib').unlink(missing_ok=True)
(root/'Cores/fceumm.dylib').unlink(missing_ok=True)
license_target=root/'Cores/licenses/fceumm.LICENSE.txt'
license_target.parent.mkdir(parents=True,exist_ok=True)
license_target.write_bytes((pathlib.Path(sys.argv[4]).parents[1]/'ios/App/Resources/Cores/licenses/fceumm.LICENSE.txt').read_bytes())
spec=importlib.util.spec_from_file_location('embed_core_framework',pathlib.Path(sys.argv[4])/'embed_core_framework.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
_,digest=module.embed(source,root,'fceumm',sys.argv[3])
manifest=json.loads(source.with_name('FceummProvenance.json').read_text())
manifest['sha256']=digest
(root/'FceummProvenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
licenses=root/'CoreLicenses.json'
entries=json.loads(licenses.read_text())
entries=[{**entry,'binary_path':'Frameworks/fceumm.framework/fceumm','license_path':'Cores/licenses/fceumm.LICENSE.txt'} if entry['id']=='fceumm' else entry for entry in entries]
licenses.write_text(json.dumps(entries,indent=2)+'\n')
PY

python3 "${repo_root}/scripts/cores/package_apple.py"
