#!/usr/bin/env python3
"""Rebuild the blocked Azahar 3DS integration candidate from pinned source.

This does not add Azahar to the app's playable or distribution inventory.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PIN = pathlib.Path(__file__).with_name('azahar-candidate.json')
PATCH = pathlib.Path(__file__).parent / 'patches/azahar.patch'
EXTENSIONS = sorted((pathlib.Path(__file__).parent / 'extensions').glob('rommio_file_bundle.*'))


def run(args):
    subprocess.run([str(part) for part in args], check=True)


def read(args):
    return subprocess.check_output([str(part) for part in args], text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def populate_submodules(repo, cache_repo):
    modules = repo / '.gitmodules'
    if not modules.exists():
        return
    paths = read(['git', '-C', repo, 'config', '-f', '.gitmodules', '--get-regexp', 'path'])
    config = ['-c', 'protocol.file.allow=always'] if cache_repo else []
    children = []
    for line in paths.splitlines():
        setting, relative = line.split(maxsplit=1)
        name = setting.removeprefix('submodule.').removesuffix('.path')
        local = cache_repo / relative if cache_repo else None
        if local and local.exists() and (local / '.git').exists():
            config += ['-c', f'submodule.{name}.url={local}']
        children.append((repo / relative, local))
    run(['git', *config, '-C', repo, 'submodule', 'update', '--init', '--jobs', '8'])
    for child, local in children:
        if child.exists() and (child / '.gitmodules').exists():
            populate_submodules(child, local)


def build(sdk, arch, source_cache):
    pin = json.loads(PIN.read_text())
    target = f'{sdk}-{arch}'
    root = ROOT / 'build/azahar-candidate' / target
    source = root / 'source'
    output = root / 'output'
    binary = output / 'azahar.dylib'
    manifest = output / 'provenance.json'
    toolchain = read(['xcrun', 'clang', '--version'])
    extension_hash = hashlib.sha256(b''.join(path.read_bytes() for path in EXTENSIONS)).hexdigest()
    key = hashlib.sha256(PIN.read_bytes() + PATCH.read_bytes() +
                         extension_hash.encode() + pathlib.Path(__file__).read_bytes() +
                         target.encode() + toolchain.encode()).hexdigest()
    if binary.exists() and manifest.exists():
        previous = json.loads(manifest.read_text())
        if previous.get('buildKey') == key and previous.get('sha256') == digest(binary):
            return binary
    if source.exists():
        shutil.rmtree(source)
    origin = source_cache if source_cache else pin['repository']
    run(['git', 'clone', '--quiet', '--no-checkout', origin, source])
    run(['git', '-C', source, 'checkout', '--quiet', '--detach', pin['revision']])
    if read(['git', '-C', source, 'rev-parse', 'HEAD']) != pin['revision']:
        raise RuntimeError('Azahar source revision differs from the pin')
    populate_submodules(source, source_cache)
    for extension in EXTENSIONS:
        shutil.copy2(extension, source / 'src/citra_libretro' / extension.name)
    run(['git', '-C', source, 'apply', '--check', PATCH])
    run(['git', '-C', source, 'apply', PATCH])
    options = ['-DCMAKE_BUILD_TYPE=Release', '-DBUILD_TESTING=OFF', '-DENABLE_QT=OFF',
               '-DENABLE_SDL2=OFF', '-DENABLE_LIBRETRO=ON', '-DENABLE_VULKAN=OFF',
               '-DENABLE_WEB_SERVICE=OFF', f'-DCMAKE_OSX_ARCHITECTURES={arch}',
               f'-DCMAKE_OSX_SYSROOT={sdk}',
               f'-DCMAKE_OSX_DEPLOYMENT_TARGET={"14.0" if sdk == "macosx" else "17.0"}']
    if sdk != 'macosx':
        options.append('-DCMAKE_SYSTEM_NAME=iOS')
    run(['cmake', '-S', source, '-B', root / 'cmake', *options])
    run(['cmake', '--build', root / 'cmake', '--target', 'azahar_libretro', '-j8'])
    built = root / 'cmake/bin/Release/azahar_libretro.dylib'
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built, binary)
    run(['install_name_tool', '-id', '@rpath/azahar.dylib', binary])
    run(['codesign', '--force', '--sign', '-', '--timestamp=none', binary])
    exports = read(['nm', '-gU', binary])
    for symbol in ['retro_rommio_persistence_descriptor', 'retro_rommio_prepare_restore',
                   'retro_rommio_memory_restored', 'retro_get_memory_data']:
        if symbol not in exports:
            raise RuntimeError(f'Missing Azahar persistence export: {symbol}')
    shutil.copy2(source / 'license.txt', output / 'LICENSE.txt')
    record = dict(schemaVersion=1, runtimeId='azahar', status='integration-candidate',
                  sdk=sdk, architecture=arch, sourceRevision=pin['revision'],
                  patchSha256=digest(PATCH), extensionSha256=extension_hash,
                  recipeSha256=digest(pathlib.Path(__file__)), buildKey=key,
                  sha256=digest(binary), persistenceExtension=pin['persistenceExtension'])
    manifest.write_text(json.dumps(record, indent=2) + '\n')
    return binary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', choices=['macosx', 'iphonesimulator', 'iphoneos'], required=True)
    parser.add_argument('--arch', default='arm64')
    parser.add_argument('--source-cache', type=pathlib.Path,
                        default=os.environ.get('ROMMIO_AZAHAR_SOURCE_CACHE'))
    args = parser.parse_args()
    print(build(args.sdk, args.arch, args.source_cache))
