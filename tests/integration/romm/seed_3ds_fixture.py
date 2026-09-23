"""Register the separately obtained 3DStris homebrew in the disposable RomM stack.

Copy the legal test ROM to /tmp/rommio-3dstris.3dsx in the RomM container first.
"""
import json
from datetime import datetime, timezone
from pathlib import Path
from sqlalchemy import select
from handler.database.base_handler import sync_session
from handler.database import db_platform_handler, db_rom_handler
from models.platform import Platform
from models.rom import Rom, RomFile

source = Path('/tmp/rommio-3dstris.3dsx')
assert source.is_file() and source.stat().st_size > 0, '3DStris fixture is missing'
slug = 'rommio-progress-3ds'
platform = db_platform_handler.get_platform_by_fs_slug(slug)
if platform is None:
    platform = db_platform_handler.add_platform(Platform(name='Nintendo 3DS test fixture', slug='3ds', fs_slug=slug))
with sync_session() as session:
    rom = session.scalar(select(Rom).where(Rom.platform_id == platform.id, Rom.fs_name == source.name))
if rom is None:
    rom = db_rom_handler.add_rom(Rom(platform_id=platform.id, name='3DStris homebrew test fixture', slug='3dstris-fixture', fs_name=source.name, fs_name_no_tags='3dstris', fs_name_no_ext='3dstris', fs_extension='3dsx', fs_path=f'{slug}/roms'))
    db_rom_handler.add_rom_file(RomFile(rom_id=rom.id, file_name=source.name, file_path=rom.fs_path, file_size_bytes=source.stat().st_size, last_modified=datetime.now(timezone.utc)))
destination = Path('/romm/library') / rom.fs_path / rom.fs_name
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_bytes(source.read_bytes())
with sync_session() as session:
    file = session.scalar(select(RomFile).where(RomFile.rom_id == rom.id))
    print(json.dumps({'rom_id': rom.id, 'file_id': file.id, 'platform_id': platform.id, 'bytes': destination.stat().st_size}))
