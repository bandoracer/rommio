#!/usr/bin/env python3
"""Run native sync and external process-death qualification on an explicitly disposable emulator."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.bandoracer.rommio'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--docker-context', default='colima-rommio-test')
    args = parser.parse_args()
    adb_path = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')) / 'platform-tools/adb'
    adb = [str(adb_path), '-s', args.serial]
    avd = subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True).splitlines()[0]
    assert avd.startswith('rommio-progress-test-'), 'Refusing a non-disposable emulator'
    spec = importlib.util.spec_from_file_location('probe', ROOT / 'tests/integration/romm/probe.py')
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)
    docker = ['docker', '--context', args.docker_context]
    server = json.loads(subprocess.check_output(docker + ['inspect', probe.CONTAINER]))[0]
    assert server['Config']['Labels']['com.docker.compose.project'] == 'rommio-protocol-test'
    assert server['NetworkSettings']['Ports']['8080/tcp'] == [{'HostIp': '127.0.0.1', 'HostPort': '18765'}]
    assert all(m['Type'] == 'volume' for m in server['Mounts'])
    assert probe.api('/api/heartbeat')['SYSTEM']['VERSION'] == '5.2.0'
    credentials = json.loads(args.credentials.read_text())
    token = probe.token_for('probe_admin_a', credentials['probe_admin_a'])
    user = probe.api('/api/users/me', token)
    config = json.dumps({'baseUrl': probe.BASE, 'accountId': str(user['id']), 'romId': 1, 'token': token}).encode()
    for path in ['app/build/outputs/apk/standard/debug/app-standard-debug.apk',
                 'app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk']:
        subprocess.run(adb + ['install', '-r', str(ROOT / path)], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(adb + ['reverse', 'tcp:18765', 'tcp:18765'], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'sh', '-c', "'umask 077; mkdir -p files; cat > files/isolated-progress-sync.json'"], input=config, check=True)
    def instrument(class_name, *extra):
        output = subprocess.check_output(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'io.github.bandoracer.rommio.domain.player.' + class_name, *extra, RUNNER], text=True)
        print(output, flush=True)
        assert 'OK (1 test)' in output and 'FAILURES' not in output, 'Instrumentation did not pass'
    try:
        instrument('LiveProgressSyncTest')
        seed_log = Path('/tmp/rommio-process-death-seed.log')
        with seed_log.open('w') as output:
            seed = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'io.github.bandoracer.rommio.domain.player.ProcessDeathProgressTest', '-e', 'progressKillPhase', 'seed', RUNNER], stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while True:
                    marker = subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'cat', 'files/process-death-progress/ready.json'], capture_output=True, text=True)
                    if marker.returncode == 0:
                        print('Background save durable in PID', json.loads(marker.stdout)['pid'], flush=True)
                        break
                    assert time.monotonic() < deadline and seed.poll() is None, seed_log.read_text()
                    time.sleep(.25)
                subprocess.run(adb + ['shell', 'am', 'force-stop', PACKAGE], check=True)
                seed.wait(timeout=15)
            finally:
                if seed.poll() is None:
                    subprocess.run(adb + ['shell', 'am', 'force-stop', PACKAGE], check=True)
                    seed.wait(timeout=15)
        subprocess.run(adb + ['shell', 'svc', 'wifi', 'disable'], check=True)
        subprocess.run(adb + ['shell', 'svc', 'data', 'disable'], check=True)
        instrument('ProcessDeathProgressTest', '-e', 'progressKillPhase', 'verify')
        print('PASS: real RomM two-client game-save/snapshot sync, confirmed rollback, force-stop and offline native recovery', flush=True)
    finally:
        subprocess.run(adb + ['shell', 'svc', 'wifi', 'enable'], check=False)
        subprocess.run(adb + ['shell', 'svc', 'data', 'enable'], check=False)
        subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'rm', '-f', 'files/isolated-progress-sync.json'], check=False)
        subprocess.run(adb + ['reverse', '--remove', 'tcp:18765'], check=False)


if __name__ == '__main__':
    main()
