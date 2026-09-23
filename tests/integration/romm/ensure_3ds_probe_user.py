"""Create a throwaway account only in the isolated RomM 5.2 integration stack."""
import json
import secrets
from pathlib import Path
from handler.auth.base_handler import AuthHandler
from handler.database.base_handler import sync_session
from models.user import User, Role

assert Path('/romm/library/rommio-progress-3ds').is_dir(), 'Not the 3DS test stack'
username = 'codex_3ds_' + secrets.token_hex(6)
password = secrets.token_urlsafe(32)
with sync_session() as session:
    user = User(username=username, email=None, role=Role.ADMIN)
    session.add(user)
    user.hashed_password = AuthHandler().get_password_hash(password)
    session.commit()
path = Path('/tmp/rommio-3ds-cred.json')
path.write_text(json.dumps({'username': username, 'password': password}))
path.chmod(0o600)
print('3DS test account ready')
