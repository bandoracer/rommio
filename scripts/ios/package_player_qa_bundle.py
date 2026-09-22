#!/usr/bin/env python3
"""Augment a simulator build with locally built, pinned QA cores.

Never modifies release resources or the production core manifest. See
 docs/ios-player-ui-validation.md for the matching source build recipes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

CORES = {
    'gambatte': ('gambatte/gambatte_libretro_ios.dylib', 'gambatte/COPYING', 'd9d6cd06382d1ced30de34d56d3609452323dab1'),
    'snes9x': ('snes9x/libretro/snes9x_libretro.dylib', 'snes9x/LICENSE', 'fae2fea08f74180759ef540ee94259213f503480'),
    'mgba': ('mgba-build/mgba_libretro.dylib', 'mgba/LICENSE', '7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6'),
    'melonds_ds': ('melonds-build/src/libretro/melondsds_libretro.dylib', 'melonds-build/melondsds-LICENSE.txt', 'bc4e4b67d2d470d7c682810a1e892cafd6f9082b'),
}

def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core-builds', type=Path, required=True)
    parser.add_argument('--base-app', type=Path, default=Path('ios/build/Debug-iphonesimulator/Rommio.app'))
    parser.add_argument('--output', type=Path, default=Path('ios/build/PlayerQA/Rommio.app'))
    args = parser.parse_args()
    base, output = args.base_app.resolve(), args.output.resolve()
    if output.suffix != '.app':
        parser.error('QA output must be an .app bundle')
    if output == base or base in output.parents or output in base.parents:
        parser.error('QA output must be separate from the base app')
    sources = []
    for core_id, (binary, license_file, revision) in CORES.items():
        source_dir = args.core_builds / core_id
        if run('git', '-C', str(source_dir), 'rev-parse', 'HEAD') != revision:
            parser.error(f'{core_id}: source revision differs from the QA pin')
        if run('git', '-C', str(source_dir), 'diff', 'HEAD', '--'):
            parser.error(f'{core_id}: tracked source changes require a new provenance record')
        binary = args.core_builds / binary
        if 'IOSSIMULATOR' not in run('xcrun', 'vtool', '-show-build', str(binary)):
            parser.error(f'{core_id}: expected an iOS simulator binary')
        license_file = args.core_builds / license_file
        if not license_file.is_file():
            parser.error(f'{core_id}: license file missing')
        sources.append((core_id, binary, license_file, revision))
    # Copy a clean base into a dedicated bundle; never reuse stale dylibs.
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(base, output)
    licenses = output / 'Cores/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    manifest_path = output / 'CoreLicenses.json'
    manifest = json.loads(manifest_path.read_text())
    provenance = []
    for core_id, binary, license_file, revision in sources:
        dest = output / 'Cores' / f'{core_id}.dylib'
        shutil.copy2(binary, dest)
        shutil.copy2(license_file, licenses / f'{core_id}.LICENSE.txt')
        run('codesign', '--force', '--sign', '-', '--timestamp=none', str(dest))
        manifest = [entry for entry in manifest if entry['id'] != core_id]
        manifest.append({'id': core_id, 'binary_path': f'Cores/{core_id}.dylib', 'license_path': f'Cores/licenses/{core_id}.LICENSE.txt'})
        provenance.append({'id': core_id, 'source_commit': revision, 'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(), 'qualification': 'simulator QA only'})
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'PlayerQAProvenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    pinned = json.loads((output / 'FceummProvenance.json').read_text())
    if hashlib.sha256((output / 'fceumm.dylib').read_bytes()).hexdigest() != pinned['sha256']:
        raise RuntimeError('Pinned FCEUmm provenance was changed')
    run('codesign', '--force', '--sign', '-', '--timestamp=none', str(output))
    run('codesign', '--verify', '--deep', '--strict', str(output))
    print(output)

if __name__ == '__main__':
    main()
