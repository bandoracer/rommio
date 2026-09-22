# Live RomM 5.2.0 upgrade

Executed 2026-09-21 (America/Los_Angeles), with explicit user authorization.

- TrueNAS app `romm` upgraded from catalog 1.2.23 / RomM 4.8.1 to catalog
  1.2.40 / RomM 5.2.0 using the supported app lifecycle.
- Before upgrade, saved the full app configuration, app instance metadata,
  PostgreSQL custom-format dump, and schema under
  `/mnt/HDD_pool/vault/backups/romm/pre-5.2.0-20260922T003149Z`.
  Backup files contain secrets and remain private on the server.
- Took recursive dataset snapshot
  `SSD Pool/ix-apps/app_mounts/romm@pre-5.2.0-20260922T003149Z`.
- Converted the former custom `HASHEOUS_API_ENABLED` environment setting to the
  new catalog's first-class setting. The new chart rejected the duplicate key.
- A partial nested configuration update replaced the old `romm` values rather
  than merging them. Startup checking caught missing database/auth settings;
  restored the full original configuration merged with the new chart defaults.
  Final readback matches the backup's original settings except the deliberate
  Hasheous representation change.

Final checks: app `RUNNING`; authenticated API reports 5.2.0; dedicated existing
QA account can log in; all 72 original library entry IDs remain; all 22 original
asset files match their pre-upgrade snapshot hashes. The public Cloudflare route
also returns a successful 5.2.0 heartbeat using the existing service credentials.

Client save/sync keeps its existing 5.2.0 minimum. The subsequent cartridge
handoff tests use the actual upgraded server through its LAN endpoint and a
separate QA account. Public heartbeat success is not public-route gameplay or
physical-device qualification. See [cartridge persistence](../cartridge-progress.md).
