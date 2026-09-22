# Android firmware provisioning and runtime layout

Status: partial R08 implementation, with an R07 firmware identity input. Settings import, content validation, legacy-copy preservation, readiness, and selected runtime layouts are implemented. Real BIOS/core gameplay, other runtime asset layouts, full file-backed progress capture, and iOS parity remain unqualified.

## User flow and validation

Settings → **Manage BIOS files** lists recognized firmware and its verified/missing state. The user chooses an entry and supplies a document. The app never downloads BIOS images. A staged import must match the catalog's identification hash before atomic installation. Wrong content, a truncated read, a linked path, or an oversized document leaves the prior file unchanged.

Verified firmware lives in `files/library/system/`, with core-required subdirectories such as `dc/dc_boot.bin`. Readiness uses the same `FirmwareStore` as session preparation. File existence alone no longer satisfies BIOS readiness. Game detail observes firmware changes and reevaluates readiness after import.

A differing old file is copied to `firmware-backups/<old SHA-256>/<relative path>` before replacement. Valid older copies in `bios/`, `bios/dc/`, or the former flat `system/dc_boot.bin` location can be explicitly copied through **Use existing BIOS**; originals remain intact. Active player/browser sessions and pending library recovery prevent import. This shares the existing storage barrier with downloads and migration.

The default catalog covers PlayStation regional BIOS files, Dreamcast boot BIOS, documented Opera BIOS/font alternatives, optional Lynx boot firmware, and Sega CD regional BIOS files. The existing PlayStation policy still requires `scph5501.bin`. Opera accepts any recognized boot BIOS. Sega CD currently requires all three regional files because content-region selection is not implemented; cartridge Sega games do not require them. This conservative regional policy remains an R07/R08 limitation, not a claim of complete region-aware readiness.

Dreamcast no longer requires a globally supplied `dc_flash.bin`. Mutable flash/VMU files are progress, not an immutable BIOS bundle. The Android helper routes recognized Dreamcast/NAOMI BIOS names under `system/dc/` and rejects `dc_flash.bin` as a BIOS import. The helper remains a development transfer mechanism; normal imports use the validating UI.

## Runtime ownership and firmware identity

PCSX ReARMed, SwanStation, Dreamcast Flycast, Opera, Handy, Genesis Plus GX, and PicoDrive receive a runtime system directory under:

`runtime-system/<verified content-binding digest>/<runtime ID>/`

An unowned legacy install uses a visibly separate `legacy-<rom ID>-<file ID>` bucket; that does not establish historical account ownership. NAOMI retains the prior system path pending a ROM-set-specific firmware contract. Cores with other unmodeled assets, including PPSSPP and Dolphin, also retain their existing path until their asset descriptors and migration are implemented. This batch does not claim universal system-directory isolation.

Preparation copies only recognized, validated immutable firmware. It does not copy global VMUs, NVRAM, flash, or arbitrary directory contents into a new account/game. Existing global mutable files remain untouched for explicit recovery; they are not silently assigned to the active account.

A durable `.rommio-firmware` manifest freezes the initial relative paths and SHA-256 hashes before the prepared directory is handed to the player. Later imports cannot add a different BIOS to an already initialized game's selection. Existing selected optional firmware remains recorded even if its shared provisioning copy is removed. Removing required shared firmware makes readiness fail; mismatched or unrecorded firmware in a runtime directory also fails closed without deleting files. Explicit runtime-firmware repair/update remains required follow-up work.

Generated per-game system files persist across launches and are separate for colliding accounts and changed ROM bytes. `PlayerSession.firmwareSha256` carries the actual firmware snapshot for the forthcoming compatibility descriptor. The native bridge receives this prepared system directory. Launch also creates the game's save/state directories before core initialization.

This does not yet include file-backed system data in immutable checkpoints, prove save-state compatibility, or provide migration of old global VMU/flash data. Those remain R05–R10 work. The old mutable player/save path and local-only cloud behavior otherwise remain as recorded in the remediation status.

## Catalog sources

Identification metadata was checked against [Libretro core information at commit 5a74858ab2f7a50cebb5a6330895bc38899531c0](https://github.com/libretro/libretro-core-info/tree/5a74858ab2f7a50cebb5a6330895bc38899531c0), including its PCSX ReARMed, Flycast, Opera, and Handy records. Sega CD identifiers and region requirements come from the [Genesis Plus GX documentation](https://docs.libretro.com/library/genesis_plus_gx/). MD5 is used to recognize those documented dumps; the runtime snapshot uses SHA-256 for exact byte identity. No BIOS payloads were obtained or added to the repository.

The [Flycast documentation](https://docs.libretro.com/library/flycast/) specifies the nested `dc/` layout and documents mutable system-directory files. The [Opera documentation](https://docs.libretro.com/library/opera/) describes alternative boot BIOSes and file-backed NVRAM. Actual behavior still needs qualification against the pinned core builds selected by R07.

## Evidence and remaining gates

`FirmwareStoreTest` has 13 device cases using synthetic firmware and injected identification hashes. Tests exercise real managed files, core resolver readiness, normal import, interrupted/invalid import preserving old bytes, backup retention, older flat/nested layouts, exact runtime snapshots, account/content separation, mutable-file isolation, unchanged firmware selection after new imports, optional cached identities, missing/replaced files, alternative Opera BIOS, Sega CD conditions, symlinks, and storage/session exclusion.

The full Android check run is recorded in `docs/remediation-status.md`. Tests do not use real copyrighted firmware and do not establish boot/playback on hardware or visual interaction qualification of the document picker. R08 remains open until those gates and the remaining platform/runtime layouts pass.

R07 investigation also confirmed that the current Android downloader still resolves floating `nightly/android/latest/<ABI>/…` URLs. The inspected buildbot stable Android directory supplies RetroArch APKs; no durable versioned individual-core source has been selected. A checked-in approved artifact set with durable hosting or reproducible builds is still needed. Merely hashing today's moving URL, accepting any nonempty `.so`, or recording a BIOS hash does not close core pinning or compatibility.
