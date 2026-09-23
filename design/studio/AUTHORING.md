# Authoring a version (round 2)

A *version* is one complete direction for Rommio: every screen in
`core/screens.js`, drawn from `core/data.js`. Round-2 versions live in
`versions/<id>/<id>.js` + `<id>.css`; the first round is archived under
`versions/v1/` (ids `void1`…) and is read-only.

Round 2 has three rules the first round didn't:

1. **Native navigation.** Tab bars, navigation bars, toolbar buttons, back and
   close buttons, split columns and sheets come from `c.sys` (see
   `core/system.js`). They render as iOS 26 Liquid Glass on iPhone, as the
   iOS 27.1 **vertical bar** on iPhone Duo (tabs and toolbar items on the
   trailing edge, status bar included), and as Material 3 on Android. You tint
   them; you never draw your own. Content, components and layout inside them
   are yours.
2. **Light and dark.** Every screen works in both appearances.
3. **Divergence.** A direction is a point of view about what the app *is*, not
   a skin. Its tabs, organising axes, components and information hierarchy
   should differ from the other directions. Briefs in `core/screens.js`
   describe jobs, not layouts.

## Registering

```js
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;

  const screens = {
    home: (c) =>
      c.sys.shell({
        tab: 'now',
        title: 'Now',
        actions: [{ avatar: 'R', label: 'Account' }],
        body: `<section class="…">…</section>`,
      }),
    player: { tone: 'light', render: (c) => `…full-screen game and controls…` },
  };

  Studio.defineVersion({
    id: 'void',
    name: 'Void',
    tagline: 'One line for the toolbar',
    thesis: 'What this direction believes the app is for, and how that shapes its structure.',
    swatch: ['#000', '#b9c6ff', '#f4f4f5'],
    fonts: ['Geist:wght@300..700'],
    tabs: [                                     // native tab bar / vertical bar / nav bar items
      { id: 'now', label: 'Now', icon: 'play' },
      { id: 'library', label: 'Library', icon: 'library' },
      { id: 'timeline', label: 'Timeline', icon: 'history', badge: '1' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },  // iOS 26 separates Search
    ],
    palette: [...], type: [...], tweaks: [...], notes: { home: '…' },
    screens,
  });
})();
```

## Devices, kinds and the content box

The studio renders every screen on the selected device. Each screen has a
`kind` (`app`, `landscape`, `portrait`); the device picks the size:

| device | app | landscape (player, pause, states) | portrait (player-portrait, dual) | bars |
| --- | --- | --- | --- | --- |
| `iphone` | 402×874 | 874×402 | 402×874 | top bar + floating tab bar |
| `android` | 402×874 | 874×402 | 402×874 | M3 top app bar + navigation bar |
| `duo-closed` | 466×678 | 678×466 | 466×678 | vertical bar, trailing edge (72pt) |
| `duo-open` | 951×669 | 951×669 | 669×951 | vertical bar; fold at x=475 (or y=475 in portrait) |

The context `c` carries `c.device` (`id`, `os`, `duo`, `vbar`, `fold`),
`c.size` (`compact` < 600pt wide ≤ `regular`), `c.scheme` (`light`/`dark`),
`c.kind`, `c.orient`, `c.w`, `c.h`, `c.safe`, plus `c.sys`.

`c.sys.shell()` lays out the bars and gives you `.sys-content`, a padded,
size-queryable container (`container: content`). Inside split columns each
column is its own container (`col`). Adapt with container queries rather than
device checks where you can:

```css
.v-void {
  .grid { display: grid; grid-template-columns: repeat(2, 1fr); }
  @container content (min-width: 600px) { .grid { grid-template-columns: repeat(4, 1fr); } }
}
```

Use `c.size === 'regular'` when the *structure* changes.

### iPhone Duo open: one canvas, not two halves

The inner display is a single continuous surface; the fold is a crease, not a
partition. Design it the way you would a small tablet in landscape:

- Let backgrounds, headers, heroes and art run across the fold. Reflow content
  wider (more columns, wider rows, multi-column text) instead of duplicating
  phone layouts side by side.
- Use `shell({ split })` only for a real list → detail relationship. The list
  is narrow (default 340pt, not fold-aligned) and shares the page background;
  the detail is the wide part. Never mirror two equal "apps" across the fold.
- Avoid a visual wall at the fold: no tinted half, no divider line on the fold,
  no pair of equal cards meeting at it.
- Players (open and closed): the game is never on one half with the controls
  on the other, and **no control ever overlaps a game screen**. Controls sit
  low, where thumbs rest. Take the geometry from `k.stage(c, { aspect })` /
  `k.stage(c, { dual: true })` (see `core/kit.js`): it returns the game rect(s),
  the rails, the d-pad and face-button centres and size, the shoulder rects,
  the Select · Menu · Start row and a free spot for notices. Draw your own
  controls at those positions; restyle freely, but don't move them onto the
  game. `#shot-sysdemo-player-duo-open` (and `-player-portrait`, `-dual`,
  `-duo-closed`) shows the geometry as a wireframe.
- Sheets (`c.sys.sheet`) become a floating inspector over the full-screen
  backdrop; draw the backdrop across the whole display (`box`), and use
  `box.clear` only if something must stay uncovered.

## `c.sys` in brief (full reference at the top of `core/system.js`)

- `shell({ tab, title, titleMode, subtitle, back, close, actions, primary, search, bottomBar, body, split, heroUnderBar, edge, tabBar })`
  — `primary` is the prominent action (Play/Resume/Download): a tinted glass
  button on iPhone, pinned at the top of the Duo vertical bar, an extended FAB
  on Android. `actions` accept `{ avatar: 'R' }` for the account button.
  `tabBar: false` for full-screen flows (onboarding). Large titles use
  `--sys-title-font`, so they can carry your display face.
- `sheet({ detent: 'medium' | 'tall' | 'large' | 'pane', title, close, done, body, backdrop: (box) => html, scheme })`
  — modal sheet; on Duo open it becomes a floating inspector over the
  full-screen backdrop (`box` is the whole display, `box.clear` the part the
  inspector leaves uncovered).
  `scheme: 'dark'` keeps a sheet dark in light appearance (player sheets). `width` widens the sheet on
  landscape phones (default 480). `split.divider` adds a hairline between list
  and detail (off by default). `subtitle` shows under large titles on every platform.
- `list(sections)`, `toggle(on, label, inert)`, `segmented(items, i)`,
  `search({ value, focused })`, `button(label, { style, icon, size })`,
  `menu(items)`, `layout(opts)` — native controls when you want the stock
  feel; you may design your own content components instead.

Full-bleed art: pass `heroUnderBar: true, edge: false` and let your first
element sit at the top of `.sys-content`. On the vertical bar, content keeps
clear of the bar (`--sys-right`); to bleed art underneath it use
`margin-right: calc(-1 * var(--sys-right))`.

Player screens (`player`, `player-portrait`, `dual`) are full-screen and draw
their own game and touch controls — game controls are not navigation. Pause,
Save states and Conflict are sheets (`c.sys.sheet`) over the game or app.

## CSS rules

- Nest every rule inside `.v-<id> { … }`. Declare the light palette on
  `.v-<id>` and the dark palette on `&.scheme-dark` (tokens only), e.g.
  `--sys-tint`, `--sys-bg` and your own variables.
- Set `--sys-tint` (selected tabs, prominent buttons, switches) and
  `--sys-bg` (page background, used by the scroll-edge effect) for both
  appearances. Optional: `--sys-title-font`, `--sys-title-color`, `--sys-switch`.
- `@keyframes` are global: prefix them with your id.
- Never restyle `.sys-*` chrome beyond those variables (no custom tab bars,
  nav bars, back buttons or sheet frames). Style everything inside them freely.
- Fonts via the `fonts` array (Google Fonts) with fallbacks. No Inter, Roboto,
  Arial or Space Grotesk as a design face (the Android chrome uses Roboto Flex).
- Motion must start from a complete first frame.

## Data

Everything comes from `core/data.js`: games, platforms, collections,
downloads, progress, conflict, profiles, storage, cores, controls, plus round-2
history: `D.sessions` (play sessions), `D.moments` (resume points and states
across games), `D.activity` (sync and download log), `D.fleet` (devices),
`D.stats` (playtime, streak, 28-day minutes), `D.search`. Helpers are in
`core/kit.js` (`k.game`, `k.ago`, `k.size`, `k.syncLabel`, `k.cover`, `k.frame`,
`k.dual`, `k.aspect`, `k.onColor`, `k.shade`, `k.consoleColors`, `k.icon`…).

Save wording follows `docs/save-sync-spec.md` §11 (see the header of
`core/screens.js`). No lorem, no emoji. Touch targets ≥ 44pt, text contrast
≥ 4.5:1 in both appearances.

## Checking your work

```sh
design/studio/tools/shoot.sh shot-<id>-<screen>-both /tmp/x.png           # iPhone, light + dark
design/studio/tools/shoot.sh shot-<id>-<screen>-duo-open-dark /tmp/x.png  # Duo open
design/studio/tools/shoot.sh shot-<id>-<screen>-duo-closed /tmp/x.png
design/studio/tools/shoot.sh shot-<id>-<screen>-android-both /tmp/x.png
design/studio/tools/shoot-all.sh <id> /tmp/<id> both                       # every screen
design/studio/tools/sheets.sh <id> /tmp/<id> +both                         # contact sheet per flow
design/studio/tools/probe.sh '<selector>' shot-<id>-<screen>               # computed boxes
design/studio/tools/shoot.sh 'shot-<id>-<screen>^400' /tmp/x.png          # content scrolled 400pt (below the fold)
```

`#shot-sysdemo-<screen>[-device][-dark]` renders a stock reference of the
chrome (`versions/_system/`).
