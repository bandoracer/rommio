"""Executed inside the isolated test container, never against a user library."""
import json
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select
from handler.database.base_handler import sync_session
from handler.database import db_platform_handler, db_rom_handler
from models.platform import Platform
from models.rom import Rom, RomFile

slug = "rommio-protocol-fixture"
platform = db_platform_handler.get_platform_by_fs_slug(slug)
if platform is None:
    platform = db_platform_handler.add_platform(Platform(name="Rommio synthetic fixture", slug=slug, fs_slug=slug))
with sync_session() as session:
    rom = session.scalar(select(Rom).where(Rom.platform_id == platform.id, Rom.fs_name == "synthetic.bin"))
if rom is None:
    rom = db_rom_handler.add_rom(Rom(
        platform_id=platform.id, name="Synthetic protocol bytes (not a game)", slug="synthetic",
        fs_name="synthetic.bin", fs_name_no_tags="synthetic", fs_name_no_ext="synthetic",
        fs_extension="bin", fs_path=f"{slug}/roms",
    ))
    db_rom_handler.add_rom_file(RomFile(rom_id=rom.id, file_name="synthetic.bin", file_path=rom.fs_path, file_size_bytes=16, last_modified=datetime.now(timezone.utc)))
# Repair only this synthetic fixture created by older probes. The catalog's
# with_files response requires last_modified to be a valid datetime.
with sync_session.begin() as session:
    for file in session.scalars(select(RomFile).where(RomFile.rom_id == rom.id)):
        if file.last_modified is None:
            file.last_modified = datetime.now(timezone.utc)
path = Path("/romm/library") / rom.fs_path / rom.fs_name
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(bytes(range(16)))
print(json.dumps({"rom_id": rom.id, "platform_id": platform.id}))
