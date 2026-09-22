#!/usr/bin/env python3
"""Build the audited FCEUmm source for Apple; no source or toolchain downloads."""
import argparse, concurrent.futures, hashlib, json, os, pathlib, shlex, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'third_party/fceumm'
def sha(data): return hashlib.sha256(data).hexdigest()
def command(args): return subprocess.check_output(args, text=True).strip()
def build(sdk, arch):
    pin_bytes = (SOURCE / 'sources.json').read_bytes(); pin = json.loads(pin_bytes)
    for item in pin['files']:
        path = SOURCE / item['path']
        if not path.resolve().is_relative_to(SOURCE) or sha(path.read_bytes()) != item['vendoredSha256']:
            raise RuntimeError('Pinned source changed: ' + item['path'])
    expected = {item['path'] for item in pin['files']} | {'sources.json', 'UPSTREAM.md', 'rommio.patch'}
    if {str(p.relative_to(SOURCE)) for p in SOURCE.rglob('*') if p.is_file()} - expected:
        raise RuntimeError('Unrecorded vendored source')
    target = {'macosx': f'{arch}-apple-macos14.0', 'iphonesimulator': f'{arch}-apple-ios17.0-simulator', 'iphoneos': f'{arch}-apple-ios17.0'}[sdk]
    clang = command(['xcrun', '--sdk', sdk, '--find', 'clang'])
    sysroot = command(['xcrun', '--sdk', sdk, '--show-sdk-path'])
    output = ROOT / 'ios/build/pinned-fceumm' / (sdk + '-' + arch)
    output.mkdir(parents=True, exist_ok=True)
    key = sha(pin_bytes + pathlib.Path(__file__).read_bytes() + command([clang, '--version']).encode() + target.encode() + sysroot.encode())
    manifest = output / 'FceummProvenance.json'; binary = output / 'fceumm.dylib'
    if manifest.exists() and binary.exists():
        previous = json.loads(manifest.read_text())
        if previous.get('buildKey') == key and previous['sha256'] == sha(binary.read_bytes()): return binary
    makefile = output / 'sources.mk'
    makefile.write_text(f'CORE_DIR := {SOURCE}/src\nHAVE_NTSC := 1\ninclude {SOURCE}/Makefile.common\nprint:\n\t@echo $(SOURCES_C)\n\t@echo $(COREDEFINES) $(INCFLAGS)\n')
    lines = command(['make', '-s', '-f', str(makefile), 'print']).splitlines()
    sources, flags = shlex.split(lines[0]), shlex.split(lines[1])
    common = [clang, '-target', target, '-isysroot', sysroot, '-O2', '-fPIC', '-g0', '-Werror=date-time', '-DPSS_STYLE=1', '-DGIT_VERSION="236ccdfc-rommio3"', f'-ffile-prefix-map={SOURCE}=fceumm', *flags]
    def compile_one(src):
        obj = output / (sha(src.encode())[:16] + '.o')
        subprocess.run([*common, '-c', src, '-o', str(obj)], check=True, stdout=subprocess.DEVNULL)
        return str(obj)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: objects = list(pool.map(compile_one, sources))
    subprocess.run([clang, '-target', target, '-isysroot', sysroot, '-dynamiclib', '-Wl,-install_name,@rpath/fceumm.dylib', *objects, '-o', str(binary)], check=True)
    subprocess.run(['codesign', '--force', '--sign', '-', '--timestamp=none', str(binary)], check=True)
    symbols = command(['nm', '-gU', str(binary)])
    for name in ('retro_rommio_persistence_descriptor', 'retro_rommio_memory_restored', 'retro_serialize', 'retro_get_memory_data'):
        if '_' + name not in symbols: raise RuntimeError('Missing export ' + name)
    manifest.write_text(json.dumps(dict(schemaVersion=1, runtimeId='fceumm', sourceRevision=pin['revision'], sourceManifestSha256=sha(pin_bytes), recipeSha256=sha(pathlib.Path(__file__).read_bytes()), persistenceExtension='rommio-fceumm-segments-v2', sdk=sdk, architecture=arch, sha256=sha(binary.read_bytes()), buildKey=key), indent=2) + '\n')
    return binary
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--sdk', choices=['macosx', 'iphonesimulator', 'iphoneos'], required=True); parser.add_argument('--arch', default='arm64', choices=['arm64', 'x86_64'])
    args = parser.parse_args(); print(build(args.sdk, args.arch))
