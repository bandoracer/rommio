#!/usr/bin/env python3
"""Opt-in installed-app checks against an explicitly selected RomM QA account.

Does not stop the server, delete progress, install APKs, or modify personal app
roots. Android runs must use a dedicated rommio-progress-test-* emulator. Injected
credential configuration files are removed on exit. ROMs stay inside device test storage.
"""
import argparse,json,os,pathlib,subprocess,time,uuid,urllib.request,urllib.parse
BUNDLE='io.github.mattsays.rommio.ios';PACKAGE='io.github.bandoracer.rommio'
def run(args,**kw):return subprocess.check_output([str(a) for a in args],**kw)
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--server',required=True);p.add_argument('--credentials',type=pathlib.Path,required=True);p.add_argument('--simulator',required=True);p.add_argument('--android');p.add_argument('--roms',required=True,help='Comma separated RomM IDs');p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--synthetic-psp-save',action='store_true',help='Use explicitly synthetic multi-file PSP progress in disposable roots');p.add_argument('--prepared-root',type=pathlib.Path,help='Single-ROM disposable play-phase root after capture completes');a=p.parse_args()
 credentials=json.loads(a.credentials.read_text());assert credentials['username'].startswith('codex-'),'Use a dedicated QA account'
 base=a.server.rstrip('/');token=json.load(urllib.request.urlopen(urllib.request.Request(base+'/api/token',data=urllib.parse.urlencode(dict(grant_type='password',scope='me.read assets.read assets.write roms.read platforms.read',**credentials)).encode())))['access_token']
 headers={'Authorization':'Bearer '+token}
 def api(path):return json.load(urllib.request.urlopen(urllib.request.Request(base+path,headers=headers),timeout=30))
 assert api('/api/heartbeat')['SYSTEM']['VERSION']=='5.2.0';account=api('/api/users/me');assert account['username']==credentials['username']
 container=pathlib.Path(run(['xcrun','simctl','get_app_container',a.simulator,BUNDLE,'data'],text=True).strip())
 adb=[pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb','-s',a.android] if a.android else None
 if adb: assert run(adb+['emu','avd','name'],text=True).splitlines()[0].startswith('rommio-progress-test-')
 report={'server':base,'version':'5.2.0','account':credentials['username'],'games':[],'networkFailure':'application transport injection; server stays online'}
 a.output.parent.mkdir(parents=True,exist_ok=True)
 for rom_id in map(int,a.roms.split(',')):
  rom=api('/api/roms/'+str(rom_id));assert len(rom['files'])==1,'Single-file cartridge fixtures only';file=rom['files'][0];slug=rom['platform_slug'];runtime={'nes':'fceumm','gb':'gambatte','gbc':'gambatte','gba':'mgba','snes':'snes9x','nds':'melonds_ds','genesis':'genesis_plus_gx','sms':'genesis_plus_gx','gamegear':'genesis_plus_gx','segacd':'genesis_plus_gx','psx':'pcsx_rearmed','n64':'mupen64plus_next_gles3','psp':'ppsspp'}[slug]
  root=a.prepared_root or container/'Documents/progress-live'/uuid.uuid4().hex/'rommio-progress-fixture'
  if a.prepared_root:
   assert len(a.roms.split(',')) == 1 and root.name == 'rommio-progress-fixture' and root.resolve().is_relative_to(container.resolve())
   previous=json.loads((root/'config.json').read_text());assert previous['romId']==rom_id and previous['baseUrl']==base and previous['username']==credentials['username']
   assert json.loads((root/'probe-result.json').read_text())['status']=='passed'
  root.mkdir(parents=True,exist_ok=True);(root/'rom.json').write_text(json.dumps(rom));path=root/'config.json';path.touch(mode=0o600)
  config=dict(baseUrl=base,allowLiveServer=True,romId=rom_id,fileId=file['id'],romMetadata='rom.json',syntheticPSPSave=bool(a.synthetic_psp_save and slug=='psp'),**credentials)
  def launch(phase,offline=False,**extra):
   path.write_text(json.dumps(config|dict(phase=phase,**extra)));result_file=root/'probe-result.json';result_file.unlink(missing_ok=True)
   env=os.environ|dict(SIMCTL_CHILD_ROMMIO_APP_ROOT=str(root),SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_CONFIG=str(path),SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_PAUSED='1' if phase in ['verify','peer','receive'] else '0',SIMCTL_CHILD_ROMMIO_PROGRESS_PROBE_OFFLINE='1' if offline else '0')
   run(['xcrun','simctl','launch','--terminate-running-process',a.simulator,BUNDLE],env=env)
   for _ in range(600):
    if result_file.exists():
     result=json.loads(result_file.read_text());assert result['status']=='passed',result
     print('PASS iOS',slug,phase,flush=True);return result
    time.sleep(1)
   raise RuntimeError(f'{slug} {phase} timed out')
  try:
   seed=json.loads((root/'probe-result.json').read_text()) if a.prepared_root else launch('seed')
   run(['xcrun','simctl','terminate',a.simulator,BUNDLE]);verify=launch('verify',True);assert seed['pid']!=verify['pid']
   offline=launch('offline-play',True);run(['xcrun','simctl','terminate',a.simulator,BUNDLE]);recovered=launch('verify',True);assert offline['pid']!=recovered['pid'];launch('reconnect')
   if adb:
    installed=list((root/'Library/roms/installs').rglob(file['file_name']))
    assert len(installed)==1,installed
    extension=pathlib.Path(file['file_name']).suffix.lower().lstrip('.');fixture=f'fixture.{extension}'
    run(adb+['shell','run-as',PACKAGE,'mkdir','-p','files/cartridge-fixtures'])
    with installed[0].open('rb') as stream:run(adb+['shell','run-as',PACKAGE,'sh','-c',f"'cat > files/cartridge-fixtures/{fixture}'"],stdin=stream)
    expected=json.loads((root/'expected.json').read_text())
    android=dict(baseUrl=base,allowLiveServer=True,accountName=credentials['username'],accountId=str(account['id']),token=token,romId=rom_id,fileId=file['id'],runtimeId=runtime,romFixtureFile=fixture,n64FileMenu=bool(a.prepared_root and slug=='n64'),expectedGame=expected['game'],expectedClock=expected['clock'])
    run(adb+['shell','run-as',PACKAGE,'sh','-c',"'umask 077; cat > files/isolated-cross-platform.json'"],input=json.dumps(android).encode())
    text=run(adb+['shell','am','instrument','-w','-r','-e','class',PACKAGE+'.domain.player.CrossPlatformProgressTest',PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'],text=True)
    assert 'OK (1 test)' in text and 'FAILURES' not in text,text
    result=json.loads(run(adb+['shell','run-as',PACKAGE,'cat','files/cross-platform-result.json'],text=True));assert result['freshAndroidSnapshotRestored'] and result['lostUploadResponseRecovered'] and result['gamePublishedDuringSnapshotFailure'];launch('receive',expectedGame=result['game'],expectedClock=result['clock']);print('PASS Android',slug,'receive, cold boot, publish, retain both snapshot streams',flush=True)
   expected=json.loads((root/'expected.json').read_text());first=root;path.unlink();root=first.parent.parent/uuid.uuid4().hex/'rommio-progress-fixture';root.mkdir(parents=True);(root/'rom.json').write_text(json.dumps(rom));(root/'expected.json').write_text(json.dumps(expected));path=root/'config.json';path.touch(mode=0o600);launch('peer')
   report['games'].append({'romId':rom_id,'platform':slug,'runtime':runtime,'iosPublication':True,'iosProcessRestart':True,'iosReconnect':True,'iosOfflineCaptureSurvivedTermination':True,'androidLostUploadResponseRecovered':bool(adb),'gamePublishedDuringSnapshotFailure':bool(adb),'manualGameSaveSession':bool(a.prepared_root),'syntheticPSPSave':bool(a.synthetic_psp_save and slug=='psp'),'iosFreshRootSnapshotRestore':True,'androidHandoff':bool(adb),'androidFreshStoreSnapshotRestore':bool(adb),'gameHash':expected['game'],'clockHash':expected['clock'],'snapshotHash':expected['state']});a.output.write_text(json.dumps(report,indent=2)+'\n')
  finally:
   path.unlink(missing_ok=True)
   if adb:run(adb+['shell','run-as',PACKAGE,'rm','-f','files/isolated-cross-platform.json'])
 print('PASS live qualification',len(report['games']),'games',flush=True)
if __name__=='__main__':main()
