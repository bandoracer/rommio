# Rommio UI Studio

A mockup workbench for exploring Rommio's interface end to end — onboarding,
library, game detail, downloads, search, settings, controls and every player
surface — across **iPhone, iPhone Duo (closed and open) and Android**, in
**light and dark**. It renders complete design directions against the same data
and the same 20-screen contract, so ideas can be compared honestly and iterated
fast.

## Round 2 (current)

Navigation is always native: `core/system.js` draws iOS 26 Liquid Glass bars
and tab bars on iPhone, the iOS 27.1 vertical bar on iPhone Duo, and Material 3
on Android. Each direction tints that chrome and designs everything inside it —
including its own tabs and information architecture.

On the open iPhone Duo the inner display is one continuous canvas: the fold is
a crease, not a partition. Content spans and reflows across it, list/detail is
a narrow list beside a wide detail, sheets float over the full-screen view, and
the game and its controls are never separated into regions.

| Version | Thesis |
| --- | --- |
| **Void** | One thing at a time: a quiet, text-first tool for getting back into a game. Now · Library · Timeline. The minimal player. |
| **Aurora** | Moments, not files: play organised around the scenes you remember, lit by the game in focus. Play · Moments · Library. |
| **Instrument** | A control surface for your library: tables, readouts, device fleet and transfer console. Console · Library · Devices · Transfers. |
| **Riso** | Your library, edited: a weekly issue, a back-of-book index and a play journal. Issue · Index · Journal. |
| **Marquee** | Pick up and play: hardware-first browsing, streaks and high scores. Arcade · Consoles · Stats. |

Round 1 (visual directions over a shared layout) is archived in
`versions/v1/` and selectable with the v1/v2 switch.

## Round 3 (Aurora Home & Library explorations)

The shipped Aurora Play page shows one game at a time, and its Library shows
one cover at a time behind three stacked filters. Round 3 (`versions/v3/`,
the v3 switch, `#compare-home+v3`) keeps Aurora's material (per-game light,
glass, Bricolage + Figtree, both appearances) and rethinks the structure. Each
exploration sets `extends: 'aurora'`, so it inherits Aurora's tokens and
components (`window.AuroraKit`, `window.AuroraX` in `versions/v3/v3.js`), and
designs Home, Library and one drill-in, using the app's real tabs.

| Version | Home | Library |
| --- | --- | --- |
| **Aurora today** | the round-2 reference, for comparison | |
| **Lightbox** | Marquee: the resume frame edge to edge, every game in play in one row | Colour wall: a dense cover wall with lenses (Colour · Console · Era · Genre · A–Z), an index rail and a press-and-hold loupe |
| **Shelf** | The stack: in-progress games as a Wallet-style pile | Spines: a shelf per console, spines sized by the game |
| **Tonight** | Concierge: "How long have you got?" and a hand of three picks with reasons | Threads: pull a thread from game to game through series, makers, genres and players |
| **Timeline** | The river: a play graph whose lanes fork on a save conflict | The year river: the library along release years, console lifespans as lanes |

## Open it

Open `design/studio/index.html` in a browser — no build step or server.

| View | What it shows | Deep link |
| --- | --- | --- |
| Canvas | every screen of one version, grouped by flow | `#void` |
| Compare | one screen across every version in the round | `#compare-home` |
| Matrix | one flow (columns) across every version (rows) | `#matrix-player` |
| Focus | one screen, large, with its brief, thesis, tabs, palette and type | `#void-home` |

The toolbar switches round, version, view, device (iPhone · Android · Duo
closed · Duo open), appearance (light · dark · both side by side) and zoom;
the sliders button opens live **Tweaks** for the current version.

Keys: `1`–`5` version · `←`/`→` screen · `C` `V` `X` `F` view · `D` device ·
`A` appearance · `T` tweaks · `M` pause motion. Locally, hash flags such as
`#void~Player@0.4+duo-open+dark` filter the canvas to one flow, zoom, device
and appearance.

## Iterate

- **Change a direction:** edit `versions/<id>/<id>.js` (screens) and
  `<id>.css` (rules nested under `.v-<id>`, light tokens on the scope, dark
  tokens on `&.scheme-dark`).
- **Change the native chrome:** `core/system.js` + `core/system.css` — every
  direction picks it up. `#shot-sysdemo-<screen>` shows a stock reference.
- **Change content everywhere:** `core/data.js` (games, saves, sessions,
  moments, devices, stats…).
- **Change what a screen must do:** its brief in `core/screens.js`.
- **Add a device:** `DEVICES` in `core/studio.js` (sizes per screen kind, safe
  areas, vertical bar, fold).

[AUTHORING.md](AUTHORING.md) is the full contract for version authors.

## Tools

```sh
design/studio/tools/shoot.sh shot-void-home-both /tmp/home.png          # iPhone, light + dark
design/studio/tools/shoot.sh shot-void-library-duo-open-dark /tmp/l.png # iPhone Duo open
design/studio/tools/shoot-all.sh void /tmp/void both                    # every screen
design/studio/tools/sheets.sh void /tmp/void +duo-open                  # one contact sheet per flow
design/studio/tools/probe.sh '.sys-vbar' shot-void-home-duo-closed      # computed layout boxes
python3 design/studio/tools/bundle.py                                   # single-file dist/rommio-ui-studio.html
```

The shell tools drive headless Google Chrome (`CHROME=/path` to override).
Covers and gameplay frames are procedural pixel art from `core/art.js`
(`tools/art-sheet.html#box` / `#frame` shows them all). Icons are a Lucide
subset regenerated by `tools/extract-icons.js`.
