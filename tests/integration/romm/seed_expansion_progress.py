#!/usr/bin/env python3
"""Seed original Sega save-counter programs and a PSP demo in the isolated stack.

No personal library is mounted or modified. Each invocation gets unique ROM IDs.
The generated Sega programs write backup RAM on the emulated CPU; they are not
retail games. PSP uses a caller-supplied open-source demo.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import uuid


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--psp-demo', type=Path, required=True)
    p.add_argument('--credentials', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    docker = ['docker', '--context', 'colima-rommio-test']
    container = 'rommio-protocol-test-romm-1'
    info = json.loads(subprocess.check_output(docker + ['inspect', container]))[0]
    assert info['Config']['Labels']['com.docker.compose.project'] == 'rommio-protocol-test'
    assert all(m['Type'] == 'volume' for m in info['Mounts'])
    assert info['NetworkSettings']['Ports']['8080/tcp'] == [{'HostIp': '127.0.0.1', 'HostPort': '18765'}]
    tag = uuid.uuid4().hex[:12]
    if a.credentials.exists():
        credentials = json.loads(a.credentials.read_text())
    else:
        credentials = dict(username='codex-expansion-' + tag, password=secrets.token_hex(24))
        with os.fdopen(os.open(a.credentials, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as f:
            json.dump(credentials, f)
    assert credentials['username'].startswith('codex-expansion-')
    repo = Path(__file__).resolve().parents[3]
    with tempfile.TemporaryDirectory() as folder:
        subprocess.run(['python3', str(repo / 'scripts/cores/fixtures/generate_sega_psx.py'), '--output', folder], check=True, stdout=subprocess.DEVNULL)
        cases = []
        for slug, ext in [('genesis', 'md'), ('sms', 'sms'), ('gamegear', 'gg')]:
            data = bytearray((Path(folder) / ('fixture.' + ext)).read_bytes())
            if slug == 'genesis':
                # Replace the final spin with ADDQ.B #1,$200001 / BRA back.
                end = data.index(bytes.fromhex('60fe'), 0x200)
                data[end:end + 8] = bytes.fromhex('52390020000160f8')
            else:
                # Enable Sega mapper SRAM, increment its first byte continuously.
                end = data.index(bytes.fromhex('18fe'))
                program = bytes.fromhex('3e0832fcff2100803418fd')
                data[end:end + len(program)] = program
            data[0x400:0x40c] = tag.encode()
            cases.append(dict(slug=slug, extension=ext, data=base64.b64encode(data).decode()))
        cases.append(dict(slug='psp', extension=a.psp_demo.suffix[1:], data=base64.b64encode(a.psp_demo.read_bytes()).decode()))
    payload = dict(tag=tag, credentials=credentials, cases=cases)
    # JSON is read from stdin, not command arguments or environment listings.
    program = '''
import sys,json,base64
from pathlib import Path
from datetime import datetime,timezone
from handler.database import db_platform_handler,db_rom_handler,db_user_handler
from handler.auth import auth_handler
from models.platform import Platform
from models.rom import Rom,RomFile
from models.user import User,Role
d=json.loads(sys.stdin.readline());c=d['credentials']
user=db_user_handler.get_user_by_username(c['username'])
if user is None:
 db_user_handler.add_user(User(username=c['username'],hashed_password=auth_handler.get_password_hash(c['password']),role=Role.ADMIN,enabled=True))
result=[]
for item in d['cases']:
 slug=item['slug'];fs='rommio-progress-'+slug
 platform=db_platform_handler.get_platform_by_fs_slug(fs)
 if platform is None: platform=db_platform_handler.add_platform(Platform(name=slug+' QA',slug=slug,fs_slug=fs))
 name='progress-'+d['tag']+'.'+item['extension'];data=base64.b64decode(item['data'])
 rom=db_rom_handler.add_rom(Rom(platform_id=platform.id,name='Original progress fixture '+d['tag'],slug='progress-'+d['tag'],fs_name=name,fs_name_no_tags=name,fs_name_no_ext=name.rsplit('.',1)[0],fs_extension=item['extension'],fs_path=fs+'/roms',fs_size_bytes=len(data)))
 file=db_rom_handler.add_rom_file(RomFile(rom_id=rom.id,file_name=name,file_path=rom.fs_path,file_size_bytes=len(data),last_modified=datetime.now(timezone.utc)))
 path=Path('/romm/library')/rom.fs_path/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
 result.append(dict(platform=slug,romId=rom.id,fileId=file.id))
print(json.dumps(result))
'''
    # Feed program as -c and payload via stdin; neither contains logged secrets.
    output = subprocess.check_output(docker + ['exec', '-i', '-w', '/backend', container, '/src/.venv/bin/python', '-c', program], input=json.dumps(payload).encode())
    result = json.loads(output.decode().splitlines()[-1])
    a.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
