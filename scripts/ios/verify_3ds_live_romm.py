#!/usr/bin/env python3
"""Boot one account-owned 3DS ZIP from RomM in the Debug iOS simulator app.

This downloads the title through Rommio into a disposable app data root. It
does not publish progress or include game content in the repository.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]


def output(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--simulator', required=True)
    parser.add_argument('--base-url', required=True, help='RomM origin reachable from the simulator')
    parser.add_argument('--credentials', type=Path, required=True, help='Local JSON with username and password')
    parser.add_argument('--cloudflare-credentials', type=Path, help='One-touch service credentials JSON for the public route')
    parser.add_argument('--rom-id', type=int, required=True)
    parser.add_argument('--file-id', type=int, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=1800)
    parser.add_argument('--screenshot', type=Path, help='Optional screenshot after the player starts')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    assert urllib.parse.urlparse(base).scheme in ('http', 'https')
    assert 0 < args.timeout_seconds <= 3600
    credentials = json.loads(args.credentials.read_text())
    assert credentials['username'].startswith('codex-'), 'Live probe requires a QA account'
    edge = json.loads(args.cloudflare_credentials.read_text()) if args.cloudflare_credentials else None
    request_headers = {'User-Agent': 'Rommio/1.0 (iOS)'}
    if edge:
        request_headers.update({'CF-Access-Client-Id': edge['client_id'],
                                'CF-Access-Client-Secret': edge['client_secret']})

    token_request = urllib.request.Request(
        base + '/api/token',
        data=urllib.parse.urlencode({
            'grant_type': 'password', 'username': credentials['username'],
            'password': credentials['password'], 'scope': 'roms.read',
        }).encode(),
        headers=request_headers | {'Content-Type': 'application/x-www-form-urlencoded'},
    )
    with urllib.request.urlopen(token_request, timeout=30) as response:
        token = json.load(response)['access_token']
    rom_request = urllib.request.Request(
        base + f'/api/roms/{args.rom_id}',
        headers=request_headers | {'Authorization': 'Bearer ' + token},
    )
    with urllib.request.urlopen(rom_request, timeout=30) as response:
        rom = json.load(response)
    assert rom['id'] == args.rom_id and rom['platform_slug'] in ('3ds', 'new-nintendo-3ds')
    assert rom['files'][0]['id'] == args.file_id, 'The selected file must be the primary ROM file'
    assert rom['files'][0]['file_name'].lower().endswith('.zip'), 'This probe qualifies RomM ZIP delivery'

    app = ROOT / 'ios/build/Debug-iphonesimulator/Rommio.app'
    assert (app / 'Frameworks/azahar.framework/azahar').is_file(), 'Build the opt-in Azahar simulator app first'
    bundle = output('/usr/libexec/PlistBuddy', '-c', 'Print CFBundleIdentifier', str(app / 'Info.plist'))
    subprocess.run(['xcrun', 'simctl', 'install', args.simulator, str(app)], check=True)
    container = Path(output('xcrun', 'simctl', 'get_app_container', args.simulator, bundle, 'data'))
    probe = container / 'Documents/progress-runs' / uuid.uuid4().hex / 'rommio-progress-fixture'
    probe.mkdir(parents=True)
    (probe / 'rom.json').write_text(json.dumps(rom))
    config = probe / 'config.json'
    edge_file = probe / 'edge.json'
    if edge:
        shutil.copyfile(args.cloudflare_credentials, edge_file)
        edge_file.chmod(0o600)
    config.write_text(json.dumps({
        'phase': 'boot', 'allowLiveServer': True, 'baseUrl': base,
        'romId': args.rom_id, 'fileId': args.file_id, 'romMetadata': 'rom.json',
        'username': credentials['username'], 'password': credentials['password'],
    } | ({'cloudflareCredentialsFile': edge_file.name} if edge else {})))
    config.chmod(0o600)
    env = os.environ.copy()
    env.update(SIMCTL_CHILD_ROMMIO_APP_ROOT=str(probe),
               SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_CONFIG=str(config),
               SIMCTL_CHILD_ROMMIO_ENABLE_AZAHAR_QA='1')
    try:
        output('xcrun', 'simctl', 'launch', '--terminate-running-process', args.simulator, bundle, env=env)
        result_file = probe / 'probe-result.json'
        deadline = time.monotonic() + args.timeout_seconds
        while time.monotonic() < deadline:
            if result_file.exists():
                result = json.loads(result_file.read_text())
                assert result.get('status') == 'passed' and result.get('phase') == 'boot', result
                print('PASS live RomM 3DS ZIP download, extraction, and player start')
                print('Disposable app root:', probe)
                if args.screenshot:
                    subprocess.run(['xcrun', 'simctl', 'io', args.simulator, 'screenshot', str(args.screenshot)], check=True)
                return
            time.sleep(1)
        raise RuntimeError(f'Timed out waiting for the player; inspect {probe}')
    finally:
        config.unlink(missing_ok=True)
        edge_file.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
