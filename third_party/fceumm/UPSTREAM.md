# Pinned FCEUmm source

Upstream: https://github.com/libretro/libretro-fceumm

Revision: `236ccdfc911e84c60fea6b9d0699c2d440a8de14`.

This subtree contains the upstream `src/`, `jni/`, `Makefile.common`, `Copying`, `Authors`, and `README.md`. `sources.json` records all 659 files with upstream and vendored SHA-256 digests. `rommio.patch` contains every source difference. Generated libraries remain under `app/build/`.

The local patch freezes the compiled version string, removes absolute source paths from compiler output, uses the app's expected library filename, propagates serialization failure, and exposes additional cartridge battery segments through memory IDs `0x100`, `0x200`, and `0x300`. It also exports a restore-completion hook that rebuilds mapper-19 audio caches and callbacks without resetting oscillator phase. The optional `retro_rommio_persistence_descriptor` export reports the active iNES loader's effective mapper, submapper, battery flag, header version, and declared nonvolatile sizes after core corrections; other loaders return no descriptor. The compiled patch tag is `236ccdfc-rommio3`. Standard region zero and the upstream SRAM file format remain unchanged. These extension IDs must only be queried for a build that declares `rommio-fceumm-segments-v2`; the frontend verifies the trusted APK manifest and exact library bytes before including them in coherent capture and restore. This interface approval alone does not establish a complete per-content persistence qualification.

`scripts/android/build_pinned_cores.py` verifies source digests before invoking NDK `27.2.12479018` at Android API 26 for arm64-v8a, armeabi-v7a, x86, and x86_64. It uses upstream's Android RGB565/NTSC recipe without HD packs, sets the pinned source epoch, and emits a manifest of source, recipe, and exact library digests. `--rebuild` forces recompilation for repeatability checks. Building requires no source download.

License and attribution: retain [Copying](Copying), [Authors](Authors), and individual source headers. The upstream README identifies GNU GPL v2; bundled libretro-common files retain their own notices. Source inclusion and successful compilation do not establish full gameplay or persistence qualification. See [the app's qualification boundary](../../docs/android-pinned-core.md).
