#!/usr/bin/env python3
"""Qualify the opt-in Azahar candidate against 3DStris and disposable RomM 5.2.

Build the Debug simulator app with ROMMIO_INCLUDE_AZAHAR_CANDIDATE=1 first.
The 3DStris ROM is separately obtained test content and is never committed.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
CONTAINER = 'rommio-protocol-test-romm-1'
DOCKER = ['docker', '--context', 'colima-rommio-test']


def output(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def wait_result(root, timeout):
    path = root / 'probe-result.json'
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            result = json.loads(path.read_text())
            if result.get('status') not in ('passed', 'ready'):
                raise RuntimeError(f"{result.get('phase', 'native')}: {result.get('error', result)}")
            return result
        time.sleep(.3)
    raise RuntimeError(f'Timed out waiting for {root}')


def run_phase(device, bundle, root, phase, config, *, native=False, timeout=120):
    config_path = root / 'config.json'
    config_path.write_text(json.dumps(config | {'phase': phase}))
    config_path.chmod(0o600)
    env = os.environ.copy()
    env.update(SIMCTL_CHILD_ROMMIO_APP_ROOT=str(root),
               SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_CONFIG=str(config_path))
    if not native:
        env['SIMCTL_CHILD_ROMMIO_ENABLE_AZAHAR_QA'] = '1'
    if phase in ('peer', 'cold-peer'):
        env['SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_PAUSED'] = '1'
    (root / 'probe-result.json').unlink(missing_ok=True)
    output('xcrun', 'simctl', 'launch', '--terminate-running-process', device, bundle, env=env)
    result = wait_result(root, timeout)
    config_path.unlink(missing_ok=True)
    return result


def new_root(container):
    root = container / 'Documents/progress-runs' / uuid.uuid4().hex / 'rommio-progress-fixture'
    root.mkdir(parents=True)
    return root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--simulator', required=True)
    parser.add_argument('--rom', type=Path, required=True, help='3DStris 1.6.9 .3dsx test ROM')
    parser.add_argument('--audio-rom', type=Path, help='Opt-in ccleste 1.4.0 .3dsx audio fixture')
    parser.add_argument('--endurance-seconds', type=int, default=0)
    args = parser.parse_args()
    rom = args.rom.resolve(strict=True)
    assert rom.suffix == '.3dsx' and rom.stat().st_size > 0
    assert hashlib.sha256(rom.read_bytes()).hexdigest() == '09ef94c1eb759bd4cca0c38f932389ea0b6f4aa40a2a97b8b73c924b3f697280', 'Expected 3DStris 1.6.9 fixture'
    assert 0 <= args.endurance_seconds <= 600
    audio_rom = args.audio_rom.resolve(strict=True) if args.audio_rom else None
    if audio_rom:
        assert hashlib.sha256(audio_rom.read_bytes()).hexdigest() == '208233250d89cd7a23f5c2ed13ba6165a0e9882c4588ecb3097e2f5cd89c8edb', 'Expected ccleste 1.4.0 fixture'
    app = ROOT / 'ios/build/Debug-iphonesimulator/Rommio.app'
    assert (app / 'Frameworks/azahar.framework/azahar').is_file(), 'Build candidate-enabled app first'
    bundle = output('/usr/libexec/PlistBuddy', '-c', 'Print CFBundleIdentifier', str(app / 'Info.plist'))

    inspected = json.loads(output(*(DOCKER + ['inspect', CONTAINER])))[0]
    assert inspected['Config']['Labels']['com.docker.compose.project'] == 'rommio-protocol-test'
    assert inspected['NetworkSettings']['Ports']['8080/tcp'] == [{'HostIp': '127.0.0.1', 'HostPort': '18765'}]
    assert all(mount['Type'] == 'volume' for mount in inspected['Mounts'])
    probe_path = ROOT / 'tests/integration/romm/probe.py'
    spec = importlib.util.spec_from_file_location('romm_probe', probe_path)
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)
    assert probe.api('/api/heartbeat')['SYSTEM']['VERSION'] == '5.2.0'

    subprocess.run(DOCKER + ['cp', str(rom), f'{CONTAINER}:/tmp/rommio-3dstris.3dsx'], check=True)
    def container_script(name):
        return subprocess.check_output(DOCKER + ['exec', '-i', '-w', '/backend', CONTAINER,
                                                 '/src/.venv/bin/python', '-'],
                                       input=(ROOT / 'tests/integration/romm' / name).read_bytes()).decode()
    fixture = json.loads(container_script('seed_3ds_fixture.py').splitlines()[-1])
    container_script('ensure_3ds_probe_user.py')

    subprocess.run(['xcrun', 'simctl', 'bootstatus', args.simulator, '-b'], check=True,
                   stdout=subprocess.DEVNULL)
    subprocess.run(['xcrun', 'simctl', 'install', args.simulator, str(app)], check=True)
    container = Path(output('xcrun', 'simctl', 'get_app_container', args.simulator, bundle, 'data'))
    with tempfile.TemporaryDirectory(prefix='rommio-3ds-') as secret_dir:
        credentials_file = Path(secret_dir) / 'credentials.json'
        subprocess.run(DOCKER + ['cp', f'{CONTAINER}:/tmp/rommio-3ds-cred.json', str(credentials_file)], check=True)
        credentials = json.loads(credentials_file.read_text())
        base_config = dict(baseUrl='http://127.0.0.1:18765', romId=fixture['rom_id'],
                           fileId=fixture['file_id'], romMetadata='rom.json', **credentials)
        metadata = {'id': fixture['rom_id'], 'name': '3DStris', 'platform_slug': '3ds',
                    'platform_name': 'Nintendo 3DS', 'fs_name': 'rommio-3dstris.3dsx',
                    'files': [{'id': fixture['file_id'], 'rom_id': fixture['rom_id'],
                               'file_name': 'rommio-3dstris.3dsx', 'file_extension': '3dsx',
                               'file_size_bytes': fixture['bytes']}]}
        seed = new_root(container)
        (seed / 'rom.json').write_text(json.dumps(metadata))
        first = run_phase(args.simulator, bundle, seed, 'seed', base_config | {'azaharGameSave': True})
        assert first['gameSave'] and first['state'] and first['game']
        print('PASS publish: game save and independent snapshot', flush=True)
        expected = {key: first[key] for key in ('game', 'gameSave', 'state')}
        for phase in ('peer', 'cold-peer'):
            fresh = new_root(container)
            (fresh / 'rom.json').write_text(json.dumps(metadata))
            (fresh / 'expected.json').write_text(json.dumps(expected))
            run_phase(args.simulator, bundle, fresh, phase, base_config)
            print('PASS', phase, flush=True)

        token = probe.token_for(credentials['username'], credentials['password'])
        remote = probe.api(f"/api/states?rom_id={fixture['rom_id']}", token)
        names = [item.get('file_name', '') for item in remote if item.get('emulator') == 'rommio-progress-v3']
        assert any(f'_blob_{first["game"]}.bin' in name for name in names)
        assert any(f'_blob_{first["state"]}.bin' in name for name in names)
        print('PASS RomM readback: distinct game-save and snapshot blobs', flush=True)
        if args.endurance_seconds:
            native = new_root(container)
            shutil.copy2(rom, native / '3dstris.3dsx')
            (native / 'cases.json').write_text(json.dumps([{
                'runtime': 'azahar', 'system': '3ds', 'rom': '3dstris.3dsx',
                'variables': {'citra_graphics_api': 'Software', 'citra_use_cpu_jit': 'disabled',
                              'citra_use_shader_jit': 'disabled'},
                'enduranceSeconds': args.endurance_seconds}]))
            result = run_phase(args.simulator, bundle, native, 'expansion-native', base_config,
                               native=True, timeout=args.endurance_seconds + 120)
            print('PASS native:', result['fixtures'][0], flush=True)
        if audio_rom:
            native = new_root(container)
            shutil.copy2(audio_rom, native / 'ccleste.3dsx')
            (native / 'cases.json').write_text(json.dumps([{
                'runtime': 'azahar', 'system': '3ds', 'rom': 'ccleste.3dsx',
                'variables': {'citra_graphics_api': 'Software', 'citra_use_cpu_jit': 'disabled',
                              'citra_use_shader_jit': 'disabled'}, 'audioOnly': True}]))
            result = run_phase(args.simulator, bundle, native, 'expansion-native', base_config,
                               native=True, timeout=180)
            print('PASS audio:', result['fixtures'][0], flush=True)


if __name__ == '__main__':
    main()
