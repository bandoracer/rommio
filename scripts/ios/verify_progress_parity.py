#!/usr/bin/env python3
"""Run the real iOS player, Android native owner, and process-restart checks on disposable devices."""
import argparse, importlib.util, json, os, pathlib, subprocess, time, uuid
ROOT = pathlib.Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.bandoracer.rommio'
def run(args, **kw): return subprocess.run(args, check=True, **kw)
def output(args): return subprocess.check_output(args, text=True).strip()
def wait_for(check, timeout=90):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        result=check()
        if result: return result
        time.sleep(.3)
    raise RuntimeError('Timed out waiting for fixture completion')
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--simulator',required=True);p.add_argument('--android',required=True);p.add_argument('--credentials',type=pathlib.Path,required=True);a=p.parse_args()
    devices=json.loads(output(['xcrun','simctl','list','devices','available','--json']))['devices']
    device=next(d for values in devices.values() for d in values if d['udid']==a.simulator)
    assert device['name'].startswith('rommio-progress-'), 'Refusing a personal simulator'
    adb=[str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',a.android]
    assert output(adb+['emu','avd','name']).splitlines()[0].startswith('rommio-progress-test-'), 'Refusing a personal emulator'
    spec=importlib.util.spec_from_file_location('probe', ROOT/'tests/integration/romm/probe.py');probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
    docker=['docker','--context','colima-rommio-test']
    server=json.loads(output(docker+['inspect',probe.CONTAINER]))[0]
    assert server['Config']['Labels']['com.docker.compose.project']=='rommio-protocol-test'
    assert all(m['Type']=='volume' for m in server['Mounts'])
    assert server['NetworkSettings']['Ports']['8080/tcp']==[{'HostIp':'127.0.0.1','HostPort':'18765'}]
    assert probe.api('/api/heartbeat')['SYSTEM']['VERSION']=='5.2.0'
    padding=uuid.uuid4().hex[:6]
    fixture=json.loads(subprocess.check_output(docker+['exec','-i','-e','ROMMIO_PROBE_TAG='+padding,'-w','/backend',probe.CONTAINER,'/src/.venv/bin/python','-'],input=(ROOT/'tests/integration/romm/seed_game_fixture.py').read_bytes()).decode().splitlines()[-1])
    secrets=json.loads(a.credentials.read_text());token=probe.token_for('probe_admin_a', secrets['probe_admin_a']);user=probe.api('/api/users/me',token)
    app=ROOT/'ios/build/Debug-iphonesimulator/Rommio.app'
    bundle=output(['/usr/libexec/PlistBuddy','-c','Print CFBundleIdentifier',str(app/'Info.plist')])
    run(['xcrun','simctl','bootstatus',a.simulator,'-b'],stdout=subprocess.DEVNULL)
    run(['xcrun','simctl','install',a.simulator,str(app)])
    container=pathlib.Path(output(['xcrun','simctl','get_app_container',a.simulator,bundle,'data']))
    root=container/'Documents/progress-runs'/uuid.uuid4().hex/'rommio-progress-fixture'
    assert not root.exists(), 'Use a fresh disposable simulator for a new parity run'
    root.mkdir(parents=True)
    config=dict(baseUrl=probe.BASE,romId=fixture['rom_id'],fileId=fixture['file_id'],username='probe_admin_a',password=secrets['probe_admin_a'])
    path=root/'config.json'; path.touch(mode=0o600)
    env=dict(os.environ,SIMCTL_CHILD_ROMMIO_APP_ROOT=str(root),SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_CONFIG=str(path))
    def launch(phase, **extra):
        (root/'probe-result.json').unlink(missing_ok=True)
        path.write_text(json.dumps(dict(config,phase=phase,**extra)))
        probe_env=dict(env,SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_PAUSED='1' if phase in ('verify','receive','peer') else '0')
        run(['xcrun','simctl','launch','--terminate-running-process',a.simulator,bundle],env=probe_env,stdout=subprocess.DEVNULL)
        def result():
            file=root/'probe-result.json'
            return json.loads(file.read_text()) if file.exists() else None
        data=wait_for(result,120)
        assert data['status']=='passed', data
        print('PASS iOS',phase,{k:v for k,v in data.items() if k not in ('error',)},flush=True)
        return data
    server_stopped=False
    try:
        seed=launch('seed')
        for apk in ['app/build/outputs/apk/standard/debug/app-standard-debug.apk','app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk']:
            run(adb+['install','-r',str(ROOT/apk)],stdout=subprocess.DEVNULL)
        run(adb+['reverse','tcp:18765','tcp:18765'],stdout=subprocess.DEVNULL)
        android=dict(baseUrl=probe.BASE,romId=fixture['rom_id'],fileId=fixture['file_id'],accountId=str(user['id']),token=token,padding=padding,expectedGame=seed['game'])
        run(adb+['shell','run-as',PACKAGE,'sh','-c',"'umask 077; mkdir -p files; cat > files/isolated-cross-platform.json'"],input=json.dumps(android).encode())
        result=output(adb+['shell','am','instrument','-w','-r','-e','class','io.github.bandoracer.rommio.domain.player.CrossPlatformProgressTest',PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'])
        assert 'OK (1 test)' in result and 'FAILURES' not in result,result
        android_result=json.loads(output(adb+['shell','run-as',PACKAGE,'cat','files/cross-platform-result.json']))
        print('PASS Android receive, cold boot, game-save successor, and separate snapshot stream',flush=True)
        receive=launch('receive',expectedGame=android_result['game'])
        # A fresh app root has its own database, device ID and vault. It must obtain
        # both streams from RomM, not the first installation's local checkpoint.
        original_root, original_path, original_env = root, path, env
        root=container/'Documents/progress-runs'/uuid.uuid4().hex/'rommio-progress-fixture'
        root.mkdir(parents=True)
        path=root/'config.json'; path.touch(mode=0o600)
        (root/'expected.json').write_text(json.dumps(dict(game=receive['game'],state=receive['state'])))
        env=dict(os.environ,SIMCTL_CHILD_ROMMIO_APP_ROOT=str(root),SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_CONFIG=str(path))
        try:
            launch('peer')
            print('PASS fresh iOS installation downloads and resumes exact remote game/state pair',flush=True)
        finally:
            path.unlink(missing_ok=True)
            root,path,env=original_root,original_path,original_env
        run(['xcrun','simctl','terminate',a.simulator,bundle])
        run(docker+['stop',probe.CONTAINER],stdout=subprocess.DEVNULL);server_stopped=True
        verify=launch('verify')
        assert verify['pid'] != receive['pid']
        run(docker+['start',probe.CONTAINER],stdout=subprocess.DEVNULL);server_stopped=False
        def ready():
            try:return probe.api('/api/heartbeat')['SYSTEM']['VERSION']=='5.2.0'
            except Exception:return False
        wait_for(ready)
        launch('reconnect')
        print('PASS: actual iOS gameplay, iOS/Android bidirectional game saves, both snapshot streams, external termination, offline exact resume, reconnect',flush=True)
    finally:
        path.unlink(missing_ok=True)
        if server_stopped: subprocess.run(docker+['start',probe.CONTAINER],stdout=subprocess.DEVNULL)
        subprocess.run(adb+['shell','run-as',PACKAGE,'rm','-f','files/isolated-cross-platform.json'],stdout=subprocess.DEVNULL)
        subprocess.run(adb+['reverse','--remove','tcp:18765'],stdout=subprocess.DEVNULL)
if __name__=='__main__':main()
