"""Original 6502 counter cartridge, for the disposable RomM integration stack only."""
import json, os
from datetime import datetime, timezone
from pathlib import Path
from sqlalchemy import select
from handler.database.base_handler import sync_session
from handler.database import db_platform_handler, db_rom_handler
from models.platform import Platform
from models.rom import Rom, RomFile
slug = 'rommio-progress-nes'
platform = db_platform_handler.get_platform_by_fs_slug(slug)
if platform is None:
    platform = db_platform_handler.add_platform(Platform(name='NES test fixture', slug='nes', fs_slug=slug))
with sync_session() as session:
    rom = session.scalar(select(Rom).where(Rom.platform_id == platform.id, Rom.fs_name == 'counter.nes'))
if rom is None:
    rom = db_rom_handler.add_rom(Rom(platform_id=platform.id, name='Rommio original counter fixture', slug='counter-fixture', fs_name='counter.nes', fs_name_no_tags='counter', fs_name_no_ext='counter', fs_extension='nes', fs_path=f'{slug}/roms'))
    db_rom_handler.add_rom_file(RomFile(rom_id=rom.id, file_name='counter.nes', file_path=rom.fs_path, file_size_bytes=16400, last_modified=datetime.now(timezone.utc)))
data = bytearray(16400); data[:7] = bytes([0x4e,0x45,0x53,0x1a,1,0,2])
program = bytes([0x78,0xee,0,0x60,0xd0,8,0xee,1,0x60,0xd0,3,0xee,2,0x60,0x4c,1,0x80]); data[16:16+len(program)] = program
for offset in [0x3ffa,0x3ffc,0x3ffe]: data[16+offset:18+offset] = bytes([0,0x80])
padding = os.environ.get('ROMMIO_PROBE_TAG', 'apple1')[:6].encode()
data[200:200+len(padding)] = padding
path = Path('/romm/library') / rom.fs_path / rom.fs_name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
with sync_session() as session:
    file = session.scalar(select(RomFile).where(RomFile.rom_id == rom.id))
    print(json.dumps(dict(rom_id=rom.id, file_id=file.id, platform_id=platform.id, padding=padding.decode())))
