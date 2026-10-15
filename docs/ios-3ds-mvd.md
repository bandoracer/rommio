# New 3DS movie decoder (MVD)

The New 3DS has a hardware video decoder, a Hantro G1 H.264 decoder and postprocessor, behind
the `mvd:STD` service. Upstream Azahar only registers the service's command names: every
command has no handler, and it registers the service as `mvd:std`. The real name is `mvd:STD`
and lookups are case-sensitive, so a client waits for it forever. Rommio's iOS Azahar build
emulates the service with `scripts/cores/patches/azahar-mvd.patch`, applied after
`azahar.patch`.

## Who uses it

- The New 3DS Internet Browser, and homebrew video players: Video player for 3DS, ThirdTube,
  3Danmu, Kasumi, topos, Moonlight-N3DS, and libctru's `mvd` example.
- No commercial game is known to use it. Game cutscenes are normally Mobiclip (`.moflex`)
  video that the game decodes itself. Pokémon Ultra Sun doesn't list `mvd:STD` among its
  services. Its blank intro video call on iPhone was a presentation bug: the player drew
  the framebuffer's alpha, which the game leaves at zero there (see
  [ios-3ds-gpu-renderer.md](ios-3ds-gpu-renderer.md)).

## What is emulated

The protocol follows TuxSH's reverse engineering of the MVD sysmodule
([mvd-investigation](https://github.com/TuxSH/mvd-investigation-cleaned-up)), 3dbrew's
[MVD Services](https://www.3dbrew.org/wiki/MVD_Services) and libctru's `mvd.c`.

- **Initialization:** Initialize, Shutdown, CalculateWorkBufSize (the level and
  reference-frame estimates; 854×480 at level 3.2 gives the browser's `0x9006C8`),
  CalculateImageSize, H264Initialize/Release, PpInitialize/Release and
  PpEnableCombinedMode/PpDisableCombinedMode. H264EnableMvc fails as on hardware.
- **Decoding:**
  - ProcessNALUnit takes one NAL unit or a whole access unit per call (Annex B).
  - Results: `0x17001` for consumed input, `0x17003` when a picture was decoded, and
    `0x17004` (headers ready) at the first slice of a new sequence. That slice is left
    unconsumed, so the client reads GetStatus, sets its output and passes the rest again.
  - Non-reference pictures are skipped with `0x17007` when asked.
- **Pictures:** ControlFrameRendering dequeues a picture, runs the postprocessor in combined
  mode, and returns `0x17002` with the 0x40-byte picture record, or `0x17000` when none is
  ready. Its end-of-stream flag drains the decoder. GetStatusOther peeks. Each decoded picture
  is also copied into the client's work buffer, where the record says it is.
- **GetStatus:** reports the stream from its sequence parameter set: coded size, crop window,
  range, matrix, aspect ratio, monochrome and interlaced flags.
- **Postprocessor:** GetConfig/SetConfig with the 0x11C-byte Hantro `PPConfig`, validated the
  way the sysmodule does, with rejections returning the matching `0xD9617108 + n` code.
  - Crop, the six rotations and flips, and bilinear scaling (3× up, 1/70 down).
  - Frame-buffer placement with clipping.
  - YCbCr→RGB with BT.601 or BT.709 (limited or full range) or custom coefficients, with
    saturation and brightness.
  - Output formats: YUYV and its byte orders, NV12, RGB/BGR 555 and 565, 32-bit RGB/BGR and
    custom masks.
  - PpGetResult runs standalone color conversion of YUYV and 4:2:0 inputs.
  - SetupOutputBuffers, GetNextOutput and OverrideOutputBuffers rotate output across up to 17
    buffers.

Decoding uses the host's H.264 decoder through `CreateH264Decoder()`. Apple platforms use
VideoToolbox, in hardware where the device has a decoder. If iOS invalidates the session (for
example after the app was in the background), it is rebuilt and decoding resumes at the next
IDR picture. Other platforms have no backend yet: the service still answers, logs that video
won't be shown, and produces no pictures.

## Not emulated

- Contrast (the hardware uses undocumented piecewise registers), dithering, the two overlay
  masks, deinterlacing, and the tiled 4:2:2 output formats.
- The VP8, WebP and VP6 decoders (commands `0x0C`–`0x17`). Nothing known uses them.
- Pictures with several slices: decoded one picture late, after a warning. Known clients use
  one slice per picture.
- Savestates keep the configuration, output buffers and stream headers, but not the host
  decoder. Queued pictures are dropped and decoding resumes at the next IDR picture.
  Savestates from before this patch (class version 0) still load.

## Build and pinning

`build_azahar_candidate.py` applies the patch after `azahar.patch` and records
`mvdPatchSha256` in the provenance. `build_azahar_vulkan_probe.py` checks that value and
copies it. `PinnedAppleCore.verify` refuses an Azahar build without the pinned digest, so
changing the patch means updating that digest too.

A changed 3DS core is a new core identity for snapshot compatibility
(`docs/save-sync-spec.md`). Resume snapshots made with the previous core aren't loaded
automatically; in-game saves are unaffected.

To work on the patch, check out the pinned revision, apply `azahar.patch` and the
`rommio_3ds_file_bundle` extensions as a base commit, commit the MVD changes on top, and
regenerate the patch from that commit. Keep the patch's header.

## Tests

The patch adds Azahar unit tests in `src/tests/core/hle/service/mvd/`, tagged `[mvd]`:
- **NAL and SPS parsing**, including emulation-prevention bytes and an 854×480 crop.
- **Bit-exact decoding** of a three-picture 48×32 stream generated with ffmpeg, against
  ffmpeg's own decode.
- **Every postprocessor path**, with BT.601 colours worked out by hand.
- **The whole IPC flow, driven like libctru's client:** headers-ready and resend, picture
  records, the work-buffer copy, RGB565 output, whole-access-unit input, skipping,
  multibuffer output, configuration rejection, and libctru's init and exit commands.

Build them for macOS with `-DENABLE_LIBRETRO=ON -DBUILD_TESTING=ON`, then run `tests "[mvd]"`.

## Qualification

| Platform | Status |
|---|---|
| macOS (development build) | 17 test cases, 235 assertions pass. The full Azahar suite passes. 3DStris boots, plays, and survives a savestate round trip |
| iOS Simulator | `verify_3ds_simulator.py` passes with this core. 3DStris publishes a game save and snapshot, two fresh peers restore them, and a 60 s Vulkan/interpreter session passes cold boot, fresh snapshot and snapshot replay. No MVD client has run on iOS yet; decoding is covered by the macOS tests |
| iPhone | Pending |
| Android | Not covered: Android ships the libretro Citra core, not this Azahar build |

## Upstream

This patch was written with Claude, an AI model. Azahar's `AI-POLICY.md` (effective
2026-03-20) prohibits AI-written contributions of this size, with or without disclosure, so it
must not be submitted to Azahar. Facts in this document and the sources it links may help a
human contributor; the maintainers decide whether work informed by it is acceptable.
