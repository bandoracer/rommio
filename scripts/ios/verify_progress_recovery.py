#!/usr/bin/env python3
"""Kill the actual simulator app at every durable progress-import boundary, then verify recovery."""
import argparse, json, os, pathlib, subprocess, time, uuid
ROOT = pathlib.Path(__file__).resolve().parents[2]
def output(args): return subprocess.check_output(args, text=True).strip()
def run(args, **kw): return subprocess.run(args, check=True, **kw)
def wait_file(path, seconds=90):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if path.exists(): return json.loads(path.read_text())
        time.sleep(.2)
    raise RuntimeError('Fixture did not complete: ' + str(path))
def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--simulator', required=True); a=parser.parse_args()
    devices=json.loads(output(['xcrun','simctl','list','devices','available','--json']))['devices']
    device=next(d for group in devices.values() for d in group if d['udid']==a.simulator)
    assert device['name'].startswith('rommio-progress-'), 'Refusing a personal simulator'
    app=ROOT/'ios/build/Debug-iphonesimulator/Rommio.app'
    bundle=output(['/usr/libexec/PlistBuddy','-c','Print CFBundleIdentifier',str(app/'Info.plist')])
    run(['xcrun','simctl','bootstatus',a.simulator,'-b'],stdout=subprocess.DEVNULL)
    run(['xcrun','simctl','install',a.simulator,str(app)])
    container=pathlib.Path(output(['xcrun','simctl','get_app_container',a.simulator,bundle,'data']))
    for boundary in ['journalReady','originalMoved','replacementMoved','metadataCommitted','completed']:
        root=container/'Documents/recovery-runs'/uuid.uuid4().hex/'rommio-progress-fixture'; root.mkdir(parents=True)
        config=root/'config.json'
        def launch(phase, fault=None):
            (root/'probe-result.json').unlink(missing_ok=True)
            config.write_text(json.dumps(dict(baseUrl='http://127.0.0.1:18765',romId=1,fileId=1,phase=phase)))
            env=dict(os.environ,SIMCTL_CHILD_ROMMIO_APP_ROOT=str(root),SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_CONFIG=str(config))
            if fault: env['SIMCTL_CHILD_ROMMIO_IMPORT_PROBE_BOUNDARY']=fault
            launched=output_env(['xcrun','simctl','launch','--terminate-running-process',a.simulator,bundle],env)
            pid=int(launched.split(':')[-1].strip())
            if fault: return pid
            result=wait_file(root/'probe-result.json')
            assert result['status']=='passed',result
            return int(result['pid'])
        try:
            launch('backup-seed')
            pid=launch('backup-import',boundary)
            reached=wait_file(root/'import-boundary.json')
            assert int(reached['pid'])==pid and reached['boundary']==boundary
            # SIGKILL, rather than an injected Swift error, prevents all cleanup/defer handlers.
            run(['/bin/kill','-KILL',str(pid)])
            new_pid=launch('backup-verify')
            assert new_pid != pid
            print('PASS external process kill and exact recovery at',boundary,flush=True)
        finally:
            config.unlink(missing_ok=True)
    print('PASS all five activation boundaries on the installed iOS simulator app',flush=True)
def output_env(args, env): return subprocess.check_output(args, text=True, env=env).strip()
if __name__=='__main__': main()
