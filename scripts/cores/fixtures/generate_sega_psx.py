#!/usr/bin/env python3
"""Generate original minimal CPU/video fixtures; no commercial ROMs or BIOS.

These test native loading and persistence transport, not game compatibility.
SPDX-License-Identifier: CC0-1.0
"""
import argparse,json,pathlib,struct
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
md=bytearray(0x10000)
struct.pack_into('>II',md,0,0xffff00,0x200)
md[0x100:0x110]=b'SEGA MEGA DRIVE '
md[0x120:0x150]=b'ROMMIO PERSISTENCE FIXTURE'.ljust(48,b' ')
struct.pack_into('>II',md,0x1a0,0,0xffff)
md[0x1b0:0x1b4]=bytes.fromhex('5241f820')
struct.pack_into('>II',md,0x1b4,0x200001,0x203fff)
md[0x1f0:0x1f3]=b'USA'
code=bytes.fromhex('46fc2700 23fc5345474100a14000 33fc800400c00004 33fc814400c00004 23fcc000000000c00004 33fc000e00c00000 60fe')
md[0x200:0x200+len(code)]=code
(a.output/'fixture.md').write_bytes(md)
for suffix,region in [('sms',0x4f),('gg',0x6f)]:
 rom=bytearray(0x8000)
 # Mode 4, display on; backdrop palette entry 16, then halt in a tight loop.
 code=bytes.fromhex('f3 3100df 3e04d3bf3e80d3bf 3e40d3bf3e81d3bf 3e00d3bf3e87d3bf 3e10d3bf3ec0d3bf 3e03d3be 18fe')
 rom[:len(code)]=code;rom[0x7ff0:0x7ff8]=b'TMR SEGA';rom[0x7fff]=region
 (a.output/f'fixture.{suffix}').write_bytes(rom)
exe=bytearray(4096);exe[:8]=b'PS-X EXE'
struct.pack_into('<II',exe,0x10,0x80010000,0)
struct.pack_into('<II',exe,0x18,0x80010000,2048)
struct.pack_into('<II',exe,0x30,0x801fff00,0)
# GPU display enable + fill VRAM rectangle red; spin without BIOS calls.
words=[0x3c081f80,0x3c090300,0xad091814,0x3c090200,0x352900ff,0xad091810,0xad001810,0x3c0900f0,0x35290140,0xad091810,0x0800400a,0]
struct.pack_into('<'+'I'*len(words),exe,2048,*words)
(a.output/'fixture.exe').write_bytes(exe)
cases=[dict(runtime='genesis_plus_gx',system=s,rom=str((a.output/f'fixture.{ext}').resolve()),variables={}) for s,ext in [('genesis','md'),('sms','sms'),('gamegear','gg')]]
cases.append(dict(runtime='pcsx_rearmed',system='psx',rom=str((a.output/'fixture.exe').resolve()),variables={'pcsx_rearmed_bios':'HLE','pcsx_rearmed_drc':'disabled'}))
(a.output/'cases.json').write_text(json.dumps(cases,indent=2)+'\n')
print(a.output/'cases.json')
