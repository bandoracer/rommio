# Isolated RomM qualification environment

This disposable server is for R11/R12 in the [remediation plan](../../../docs/remediation-plan.md). It uses named volumes with synthetic data and exposes HTTP only on localhost port 18765. It must never be pointed at a personal library or used as a production deployment.

The setup follows RomM's [example deployment](https://github.com/rommapp/romm/blob/5.2.0/examples/docker-compose.example.yml) and [environment documentation](https://docs.romm.app/latest/Getting-Started/Environment-Variables/). The pinned server version is 5.2.0; record the resolved image digest in the capability report.

Generate three random test-only values in an untracked, mode-0600 environment file:

```text
ROMMIO_TEST_DB_PASSWORD=<random>
ROMMIO_TEST_DB_ROOT_PASSWORD=<random>
ROMMIO_TEST_AUTH_KEY=<random 32-byte hex>
```

Run from the repo root with an explicit Docker context and environment file, for example:

```bash
docker --context colima-rommio-test compose --env-file /tmp/rommio-protocol-test.env -f tests/integration/romm/compose.yml up -d
```

Keep the personal/default Docker context unchanged. On this development machine a separate Colima profile can be started with `colima start rommio-test --activate=false --cpu 2 --memory 4 --disk 20 --mount none`.

Run the capability probe after the server's heartbeat reports ready:

```bash
python3 tests/integration/romm/probe.py --docker-context colima-rommio-test --credential-file /tmp/rommio-probe-credentials.json --output docs/romm-capabilities-5.2.0.json
```

The probe verifies the Compose project, localhost binding, named volumes, and exact server version before creating synthetic users/content and uploading test records. Its credential file is created with mode 0600. Re-runs reuse that isolated account setup and generate distinct record names. The [decision and limits](../../../docs/romm-transport-decision.md) explain the recorded results. A successfully started container alone does not qualify synchronization.


The Android native two-client and process-death tests use this same isolated stack. After building `assembleStandardDebug assembleStandardDebugAndroidTest`, run `python3 scripts/android/verify_progress_sync.py --serial emulator-5580 --credentials /tmp/rommio-probe-credentials.json`. The target must be a disposable `rommio-progress-test-*` AVD. This exercises the production schema-3 transport with real FCEUmm memory/state, a response lost after actual server acceptance, explicit restore confirmation and exact offline recovery after an external force-stop. See [the protocol and limits](../../../docs/progress-protocol-v3.md).

## Expanded save/snapshot handoff

`seed_expansion_progress.py --psp-demo /path/to/cubevfpu.prx --credentials /tmp/expansion-credentials.json --output /tmp/expansion-cases.json`
creates a separate QA account and new original Genesis, SMS and Game Gear save-counter
ROMs plus the supplied open-source PSP demo. It checks the isolated Compose project,
localhost port and named-volume boundary before writing anything. Credentials are
mode 0600; existing accounts and ROMs are not replaced. The Sega programs write SRAM
on the emulated CPU. These programs are transport fixtures, not retail-game qualification.

Install the current Debug app and instrumentation APK, reverse Android's port 18765,
then run `scripts/ios/verify_live_cartridge_sync.py` with `--server http://127.0.0.1:18765`,
the generated credentials, an iOS simulator, a dedicated `rommio-progress-test-*`
Android emulator, the generated comma-separated ROM IDs and an output report path.
`--synthetic-psp-save` seeds a clearly synthetic multi-file memory-stick fixture in
the disposable iOS workspace; it also changes the file while offline. It does not
claim a PSP game's own save menu was exercised.

The runner checks local snapshot recovery after external process termination,
offline capture followed by another termination, reconnect, iOS-to-Android game-save
cold boot, Android snapshot upload response loss after server acceptance, independent
game-save publication, duplicate-free retry, fresh Android cloud snapshot recovery,
Android-to-iOS game-save handoff and fresh iOS cloud snapshot recovery. The server
stays online; offline failure is injected at the application transport boundary.

For manual gameplay, the DEBUG-only `play` probe accepts the usual disposable-root
configuration, launches a real game and writes `status: ready`. Exercise the game's
save menu, then create `capture-request` inside that root. After the checkpoint has
published, `--prepared-root` runs the same handoff checks against that single ROM.
The runner removes injected device credentials at exit and retains QA history.
