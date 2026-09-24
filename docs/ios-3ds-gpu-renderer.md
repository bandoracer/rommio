# 3DS GPU renderer integration

Status: **Azahar Vulkan is enabled by default in iOS Debug and Release builds.**
The app is experimental; the remaining qualification work below still applies.

## Current path

`BundledCoreCatalog` selects `citra_graphics_api=Vulkan`. The iOS app bundles
the pinned Vulkan build of Azahar and MoltenVK. `RMLLibretroBridge` provides
the libretro Vulkan context, and `PlayerSurfaceController` presents its Metal
texture. The separate software build remains a source prerequisite for the
pinned GPU recipe; it is not the bundled 3DS core.

In a 1,200-frame iPhone Duo simulator Pokémon X probe, the software renderer
ran at 23.7 emulated fps and produced audio at 14,722 sample frames per wall
second. The same GPU-capable core, ROM, and probe with Vulkan ran at 93.3
emulated fps and produced audio at 57,881 sample frames per wall second. Both
produced non-silent audio. These are unthrottled native-probe measurements of
one scene, not displayed player frame rates or a physical-device estimate.

## Pinned Vulkan build

Run the existing software candidate recipe first, then:

```sh
python3 scripts/cores/build_azahar_vulkan_probe.py --sdk iphonesimulator
```

The recipe builds the same pinned source and Rommio save extension with
`ENABLE_VULKAN=ON` into `build/azahar-gpu-probe/`. The normal iOS app build
runs this recipe, embeds the resulting framework, and records its digest.

`RMLVulkanHost` now supplies the libretro Vulkan context using pinned MoltenVK
1.4.2, accepts Azahar's zero-semaphore image submission, waits for the Vulkan
queue, and exports the output image as a Metal texture. The player stage uses
that texture without CPU readback. Metal completion is serialized before the
core reuses the image. The host rejects unsupported queue or semaphore
submissions instead of treating them as rendered frames.

The GPU-only Azahar patch rebuilds its Vulkan shader cache after loading a
resume snapshot. Without that step, the next rendered frame dereferenced an
empty cache. On the simulator, it also stages texture-buffer writes into
GPU-private memory before shader reads; MoltenVK cannot create the required
linear texture view of a shared buffer there. The bridge reboots a retained
Vulkan context after applying the portable game-save tree, before applying a
snapshot. The normal software core and its save format are unchanged.

Build the simulator app with the GPU core:

```sh
scripts/ios/build_simulator_app.sh
```

The in-app save and resume compatibility contract records the graphics option and
core digest, so a Vulkan snapshot cannot silently load as a software snapshot;
the game-authored save tree is separate and renderer-independent. Existing
software-renderer snapshots therefore remain stored but are not resumed by the
new default core. Game saves remain a separate progress stream.

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
types after cold reopen on an Apple M5 Max. The simulator app builds
and signs with the pinned GPU core, and an unsigned `iphoneos` Debug app
compiles. These results do not qualify physical-device gameplay or
distribution signing.

## Remaining qualification

1. Finish visible-player qualification for touch mapping, fold behavior, and
   teardown/restart. Measure whether the synchronous image-ownership wait
   needs a frame ring under demanding gameplay.
2. Test Pokémon X in the visible player with audio playback, touch,
   pause/resume, cold in-game save restore, and synced snapshot restore on the
   simulator, then on physical iPhone when device qualification resumes.
   Capture GPU and CPU timings, not just displayed frames. Report simulator and
   device results separately.
3. Verify the MoltenVK license notice and distribution signing in a final
   release artifact, and measure its size.

The existing software-renderer worker cap and Release/LTO build already remove
two simple CPU-side tuning opportunities. Frame-loop threading and framebuffer
copy reduction remain worthwhile but cannot substitute for the GPU renderer.
