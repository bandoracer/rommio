#!/usr/bin/env python3
"""Build the pinned Azahar Vulkan core used by the iOS app.

The GPU build remains separate from its pinned software-source prerequisite.
"""

import argparse
import hashlib
import json
import pathlib
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[2]
PIN = pathlib.Path(__file__).with_name('azahar-candidate.json')
PATCH = pathlib.Path(__file__).parent / 'patches/azahar.patch'
MVD_PATCH = pathlib.Path(__file__).parent / 'patches/azahar-mvd.patch'
GPU_PATCH = pathlib.Path(__file__).parent / 'patches/azahar-vulkan-probe.patch'
EXTENSIONS = sorted((pathlib.Path(__file__).parent / 'extensions').glob('rommio_3ds_file_bundle.*'))


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def read(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True).strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(sdk, arch):
    target = f'{sdk}-{arch}'
    candidate = ROOT / 'build/azahar-candidate' / target
    source = candidate / 'source'
    provenance = candidate / 'output/provenance.json'
    if not provenance.is_file():
        raise RuntimeError('Build the pinned software candidate first with build_azahar_candidate.py')

    pin = json.loads(PIN.read_text())
    record = json.loads(provenance.read_text())
    extension_hash = hashlib.sha256(b''.join(path.read_bytes() for path in EXTENSIONS)).hexdigest()
    if (record.get('sourceRevision') != pin['revision'] or
            record.get('patchSha256') != sha256(PATCH) or
            record.get('mvdPatchSha256') != sha256(MVD_PATCH) or
            record.get('extensionSha256') != extension_hash or
            record.get('sha256') != sha256(candidate / 'output/azahar.dylib') or
            read('git', '-C', source, 'rev-parse', 'HEAD') != pin['revision']):
        raise RuntimeError('Azahar source or Rommio patch differs from the pinned candidate')

    root = ROOT / 'build/azahar-gpu-probe' / target
    cmake = root / 'cmake'
    output = root / 'output'
    # Vulkan snapshots need a shader-cache refresh. The iOS simulator also
    # requires GPU-private texel buffers with an explicit upload copy. Keep
    # both fixes out of the separately pinned software binary.
    run('git', '-C', source, 'apply', '--check', GPU_PATCH)
    run('git', '-C', source, 'apply', GPU_PATCH)
    try:
        run('cmake', '-S', source, '-B', cmake,
            '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_TESTING=OFF', '-DENABLE_QT=OFF',
            '-DENABLE_SDL2=OFF', '-DENABLE_LIBRETRO=ON', '-DENABLE_VULKAN=ON',
            '-DENABLE_WEB_SERVICE=OFF', f'-DCMAKE_OSX_ARCHITECTURES={arch}',
            f'-DCMAKE_OSX_SYSROOT={sdk}',
            f'-DCMAKE_OSX_DEPLOYMENT_TARGET={"14.0" if sdk == "macosx" else "17.0"}',
            *(['-DCMAKE_SYSTEM_NAME=iOS'] if sdk != 'macosx' else []))
        run('cmake', '--build', cmake, '--target', 'azahar_libretro', '-j8')
    finally:
        run('git', '-C', source, 'apply', '--reverse', GPU_PATCH)

    binary = cmake / 'bin/Release/azahar_libretro.dylib'
    if 'Using Vulkan hw renderer' not in read('strings', binary):
        raise RuntimeError('The probe binary does not contain Azahar Vulkan renderer code')
    exports = read('nm', '-gU', binary)
    for symbol in ('_retro_load_game', '_retro_rommio_persistence_descriptor',
                   '_retro_rommio_prepare_restore', '_retro_rommio_memory_restored'):
        if symbol not in exports:
            raise RuntimeError(f'The probe binary is missing {symbol}')
    output.mkdir(parents=True, exist_ok=True)
    result = output / 'azahar-vulkan-probe.dylib'
    result.write_bytes(binary.read_bytes())
    run('install_name_tool', '-id', '@rpath/azahar-vulkan-probe.dylib', result)
    run('codesign', '--force', '--sign', '-', '--timestamp=none', result)
    record = {
        'schemaVersion': 1,
        'status': 'gpu-host-integration-probe',
        'runtimeId': 'azahar',
        'graphicsApi': 'Vulkan',
        'sdk': sdk,
        'architecture': arch,
        'sourceRevision': pin['revision'],
        'patchSha256': sha256(PATCH),
        'mvdPatchSha256': sha256(MVD_PATCH),
        'gpuPatchSha256': sha256(GPU_PATCH),
        'extensionSha256': extension_hash,
        'persistenceExtension': pin['persistenceExtension'],
        'sha256': sha256(result),
        'bundledInApp': False,
    }
    (output / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', choices=['macosx', 'iphonesimulator', 'iphoneos'], required=True)
    parser.add_argument('--arch', default='arm64')
    args = parser.parse_args()
    print(build(args.sdk, args.arch))
