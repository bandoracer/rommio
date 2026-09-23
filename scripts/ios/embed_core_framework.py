#!/usr/bin/env python3
"""Embed a libretro core as a signed iOS framework, returning its final digest."""
import hashlib
import pathlib
import plistlib
import shutil
import subprocess
import sys


def embed(core: pathlib.Path, app: pathlib.Path, runtime: str, identity: str) -> tuple[pathlib.Path, str]:
    if not runtime.replace('_', '').isalnum():
        raise ValueError('Invalid core runtime ID')
    framework = app / 'Frameworks' / f'{runtime}.framework'
    if framework.exists():
        shutil.rmtree(framework)
    framework.mkdir(parents=True)
    binary = framework / runtime
    shutil.copy2(core, binary)
    subprocess.run(['install_name_tool', '-id', f'@rpath/{runtime}.framework/{runtime}', str(binary)], check=True)
    info = {
        'CFBundleDevelopmentRegion': 'en',
        'CFBundleExecutable': runtime,
        'CFBundleIdentifier': f'io.github.mattsays.rommio.ios.core.{runtime.replace("_", "-")}',
        'CFBundleInfoDictionaryVersion': '6.0',
        'CFBundleName': runtime,
        'CFBundlePackageType': 'FMWK',
        'CFBundleShortVersionString': '1.0',
        'CFBundleVersion': '1',
        'MinimumOSVersion': '18.0',
    }
    with (framework / 'Info.plist').open('wb') as stream:
        plistlib.dump(info, stream)
    subprocess.run(['codesign', '--force', '--sign', identity, '--timestamp=none', str(framework)], check=True)
    subprocess.run(['codesign', '--verify', '--strict', str(framework)], check=True)
    return binary, hashlib.sha256(binary.read_bytes()).hexdigest()


if __name__ == '__main__':
    executable, digest = embed(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3], sys.argv[4])
    print(executable, digest)
