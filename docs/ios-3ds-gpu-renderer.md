# 3DS GPU renderer integration

Status: **experimental Vulkan renderer works on the iPhone Duo simulator; GPU
runtime remains blocked for general use**. Keep Azahar on the software path
until demanding gameplay, audio, device, and distribution qualification pass.

## Current path

`BundledCoreCatalog` selects `citra_graphics_api=Software`. The pinned Azahar
recipe builds with `ENABLE_VULKAN=OFF`; upstream disables OpenGL on Apple.
Azahar rasterizes 3DS graphics on the CPU and sends an XRGB8888 bitmap through
the libretro video callback. `RMLLibretroBridge` copies that bitmap into
`RMLVideoFrame`, and `PlayerSurfaceController` uses Metal/Core Image to place it
on the display. Metal in this path only presents a completed CPU image.

In a 1,200-frame iPhone Duo simulator Pokémon X probe, the software renderer
ran at 23.7 emulated fps and produced audio at 14,722 sample frames per wall
second. The same GPU-capable core, ROM, and probe with Vulkan ran at 93.3
emulated fps and produced audio at 57,881 sample frames per wall second. Both
produced non-silent audio. These are unthrottled native-probe measurements of
one scene, not displayed player frame rates or a physical-device estimate.

## Pinned Vulkan probe

Run the existing software candidate recipe first, then:

```sh
python3 scripts/cores/build_azahar_vulkan_probe.py --sdk iphonesimulator
```

The probe builds the same pinned source and Rommio save extension with
`ENABLE_VULKAN=ON` into `build/azahar-gpu-probe/`, separate from the normally
bundled software artifact. It checks the source pin and patch, signs the
development binary, and records its digest. A successful compile does not prove
that a Vulkan device, renderer, or display frame works in Rommio.

`RMLVulkanHost` now supplies the libretro Vulkan context using pinned MoltenVK
1.4.2, accepts Azahar's zero-semaphore image submission, waits for the Vulkan
queue, and exports the output image as a Metal texture. The player stage uses
that texture without CPU readback. Metal completion is serialized before the
core reuses the image. These are candidate integration paths, not qualified
gameplay. The host rejects unsupported queue or semaphore submissions instead
of treating them as rendered frames.

The GPU-only Azahar patch rebuilds its Vulkan shader cache after loading a
resume snapshot. Without that step, the next rendered frame dereferenced an
empty cache. On the simulator, it also stages texture-buffer writes into
GPU-private memory before shader reads; MoltenVK cannot create the required
linear texture view of a shared buffer there. The bridge reboots a retained
Vulkan context after applying the portable game-save tree, before applying a
snapshot. The normal software core and its save format are unchanged.

The GPU core is included only in an explicitly opted-in **Debug** app build:

```sh
ROMMIO_INCLUDE_AZAHAR_CANDIDATE=1 \
ROMMIO_INCLUDE_AZAHAR_GPU_CANDIDATE=1 \
scripts/ios/build_simulator_app.sh
```

Set `ROMMIO_AZAHAR_GPU_QA=1` in the app process to select Vulkan. Without those
flags, 3DS continues to use the software candidate. The in-app save and resume
compatibility contract already records the selected graphics option and the
core digest, so a Vulkan snapshot cannot silently load as a software snapshot;
the game-authored save tree is separate and renderer-independent.

The pinned GPU core produced a real Metal frame on the iPhone Duo simulator.
The simulator's native 3DStris probe passed a game-authored save restore,
independent Vulkan snapshot replay, and cold core reopens for each. A
30-second 3DStris endurance run passed at 374.8 emulated frames per second;
this light homebrew game produces no audio and is not representative of
Pokémon X. The actual iPhone Duo player displayed the upper and lower screens
upright on the proper halves of the Duo after a Metal image-orientation fix;
the 3DStris title screen's frame loop held 60 core steps per second in the
Debug player log. Its save menu also accepted synthetic touch input in the
native probe, but interactive finger input in the player is still to be
checked. The native macOS GPU probe also passed a Metal frame and both save
types after cold reopen on an Apple M5 Max. The opted-in simulator app builds
and signs with the pinned GPU core, and an unsigned `iphoneos` Debug app
compiles. These results do not qualify physical-device gameplay or
distribution signing.

## Remaining promotion gates

1. Finish visible-player qualification for touch mapping, fold behavior, and
   teardown/restart. Measure whether the synchronous image-ownership wait
   needs a frame ring under demanding gameplay.
2. Test Pokémon X in the visible player with audio playback, touch,
   pause/resume, cold in-game save restore, and synced snapshot restore on the
   simulator, then on physical iPhone when device qualification resumes.
   Capture GPU and CPU timings, not just displayed frames. Promote the runtime
   only after these checks and distribution signing pass. Report simulator and
   device results separately.
3. Verify the bundled MoltenVK license notice in the final distribution and
   measure the release app size before deciding whether to ship the GPU
   dependency in production.

The existing software-renderer worker cap and Release/LTO build already remove
two simple CPU-side tuning opportunities. Frame-loop threading and framebuffer
copy reduction remain worthwhile but cannot substitute for the GPU renderer.
