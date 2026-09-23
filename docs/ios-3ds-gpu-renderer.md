# 3DS GPU renderer integration

Status: **core build proved; app host not implemented**. Keep Azahar on the
blocked software-rendering path until the hardware path passes gameplay,
save/snapshot, audio, and performance qualification.

## Current path

`BundledCoreCatalog` selects `citra_graphics_api=Software`. The pinned Azahar
recipe builds with `ENABLE_VULKAN=OFF`; upstream disables OpenGL on Apple.
Azahar rasterizes 3DS graphics on the CPU and sends an XRGB8888 bitmap through
the libretro video callback. `RMLLibretroBridge` copies that bitmap into
`RMLVideoFrame`, and `PlayerSurfaceController` uses Metal/Core Image to place it
on the display. Metal in this path only presents a completed CPU image.

The iPhone Duo simulator Pokémon X sample ran at about 23–24 emulated fps,
producing audio at less than half the core's required sample rate. The sample
was dominated by the interpreted 3DS CPU and software rasterizer. This is a
simulator result, not a physical-device speed estimate.

## Pinned Vulkan probe

Run the existing software candidate recipe first, then:

```sh
python3 scripts/cores/build_azahar_vulkan_probe.py --sdk iphonesimulator
```

The probe builds the same pinned source and Rommio save extension with
`ENABLE_VULKAN=ON` into `build/azahar-gpu-probe/`, separate from the currently
bundled software artifact. It checks the source pin and patch, signs the
development binary, and records its digest. It does **not** bundle or activate
the binary. A successful compile proves that Azahar's Vulkan libretro branch
can be built for this target; it does not prove that a Vulkan device, renderer,
or display frame works in Rommio.

## Host contract to implement

1. Supply a pinned Vulkan-on-Metal implementation (such as MoltenVK) for both
   simulator and iPhone arm64. Verify its license and distribution packaging.
2. Add a libretro Vulkan frontend to `RMLLibretroBridge`: accept
   `RETRO_ENVIRONMENT_SET_HW_RENDER`, provide the context negotiation and
   `RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE` callbacks, create the Vulkan
   instance/device/queue, and honor the core's semaphores and image ownership.
   Treat a GPU-context failure as a boot failure, never a blank playable frame.
3. Present the Vulkan image on the existing Metal stage without readback to a
   CPU bitmap. Keep the DS/3DS screen layout, touch mapping, and fold behavior.
4. Keep software as a selectable fallback during qualification. Add a distinct
   GPU runtime profile and include the effective graphics option in resume
   snapshot compatibility; do not silently resume a software snapshot under
   Vulkan or vice versa. Portable in-game saves remain renderer-independent.
5. Test Pokémon X gameplay, audio production, touch, pause/resume, cold
   in-game save restore, and synced snapshot restore on the simulator, then on
   physical iPhone when device qualification resumes. Capture GPU and CPU
   timings, not just displayed frames. Promote the runtime only after those
   gates and distribution signing pass.

The existing software-renderer worker cap and Release/LTO build already remove
two simple CPU-side tuning opportunities. Frame-loop threading and framebuffer
copy reduction remain worthwhile but cannot substitute for the GPU renderer.
