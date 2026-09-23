/*
 * The screen contract.
 *
 * v2 briefs describe JOBS — what the person must be able to do and which facts
 * must be reachable — not layouts. Each direction decides how information is
 * organised, what the components are and where things live (a job may be met
 * by a sheet, a column, a different tab…). Keep the ids so directions can be
 * compared side by side.
 *
 * kind: 'app' (navigation shell) · 'landscape' (player) · 'portrait' (player)
 * orient is kept for the archived v1 round.
 *
 * Wording rules from docs/save-sync-spec.md §11 apply to every direction:
 *   - Actions: "Save state", "Load state", "Resume", "Start with game save".
 *   - Local outcomes: "Game-save data saved", "Save state saved", "Automatic resume updated".
 *   - Remote: Local only · Waiting to sync · Syncing · Synced · Sign-in needed · Conflict.
 *   - Never a bare "Saved"; never "Save game" for an app action.
 * Support tiers: Touch · Controller · Browse only (unsupported), never merged.
 *
 * iPhone Duo, open (951×669, or 669×951 in portrait): ONE continuous canvas.
 * The fold is a crease, not a partition — never build two walled-off halves.
 * Backgrounds, headers and art may span it; content reflows wider. Use a
 * list/detail split only where the relationship is real (narrow list, wide
 * detail, shared background). The game and its controls are NEVER split into
 * separate halves (no game-on-one-half / pad-on-the-other).
 *
 * iPhone Duo players (open and closed): controls NEVER overlap any game screen,
 * and they sit low, where thumbs rest. Use k.stage(c, …) for the geometry — it
 * shrinks the game to leave rails for the controls, places the clusters low,
 * puts Select · Menu · Start clear of the game and gives a free spot for notices.
 */
window.StudioScreens = [
  {
    id: 'welcome', flow: 'Onboarding', title: 'Welcome', kind: 'app', orient: 'portrait',
    brief: 'First launch. Establish what Rommio is (a native client for your own RomM server: browse, play offline, progress that follows you) and get the person moving: connect to a server, or scan a setup code. Say plainly that Rommio provides no games.',
  },
  {
    id: 'connect', flow: 'Onboarding', title: 'Connect', kind: 'app', orient: 'portrait',
    brief: 'Enter or pick a server (romm.homelab.dev is typed; saved and nearby servers exist) and see the live check result (RomM 5.2.0 · protected by Cloudflare Access). Access method is adjustable (Automatic, Direct, Cloudflare Access, Service token). Errors live beside the input.',
  },
  {
    id: 'signin', flow: 'Onboarding', title: 'Sign in', kind: 'app', orient: 'portrait',
    brief: 'Sign in to the verified server as ryan (password filled), or continue with single sign-on. The server identity stays visible and changeable.',
  },
  {
    id: 'hydrate', flow: 'Onboarding', title: 'First sync', kind: 'app', orient: 'portrait',
    brief: 'The library is being cached for offline use (22 platforms, 2,164 games, collections done, artwork 61%). Explain that game saves and resume states sync between devices. Offer keep-artwork-offline and cellular choices. Let the person start playing before caching finishes.',
  },
  {
    id: 'home', flow: 'Library', title: 'Home', kind: 'app', orient: 'portrait',
    brief: 'The first tab. Whatever this direction believes the app is *for*, this is where it shows it. It must make resuming Super Metroid one tap, surface other in-progress games, the EarthBound conflict that needs a decision, and queue/offline state — but the organising idea (a queue, a feed, a dashboard, an issue, a cabinet row…) is yours.',
  },
  {
    id: 'library', flow: 'Library', title: 'Library', kind: 'app', orient: 'portrait',
    brief: 'Browse the whole collection (22 platforms, 2,164 games, 91 installed). Platform support tiers stay distinct (Touch, Controller, Browse only with reason). The organising axis is yours — platform, era, genre, table, shelf — as long as tiers and install state stay legible. On Duo open, use the extra width as one canvas (more columns, wider rows, a detail that grows out of the list) rather than a second walled-off pane.',
  },
  {
    id: 'platform', flow: 'Library', title: 'Platform', kind: 'app', orient: 'portrait',
    brief: 'One slice of the library in depth: Super Nintendo (241 games, 14 installed, Snes9x, Touch). Filter and sort; distinguish installed from not downloaded. On Duo open it may sit beside a narrow library list, sharing one background.',
  },
  {
    id: 'collections', flow: 'Library', title: 'Collections', kind: 'app', orient: 'portrait',
    brief: 'Manual collections first (Couch co-op, Metroidvanias, Finish in 2026, Pocket classics), then smart (with their rule) and automatic ones. Present them however suits the direction.',
  },
  {
    id: 'game', flow: 'Library', title: 'Game', kind: 'app', orient: 'portrait',
    brief: 'Super Metroid. Resume (1 min ago, This iPhone) and Start with game save; progress state (game-save data Synced, automatic resume Waiting to sync, 3 save states); file (Super Metroid (USA).sfc · 3 MB · installed), core readiness (Snes9x · Touch), metadata and blurb; Sync now; Remove download (progress kept). Play belongs in the toolbar as the prominent action. On Duo open, one composition across the display (e.g. art spanning the fold, content reflowed into columns beneath) — not two separate halves.',
  },
  {
    id: 'downloads', flow: 'Library', title: 'Downloads', kind: 'app', orient: 'portrait',
    brief: 'Transfers: Final Fantasy VII 62% (1.2 of 1.9 GB, 18.4 MB/s, 39 s), queue (Wind Waker, Mario Kart DS), waiting for Wi-Fi (Advance Wars), failed (Crash Bandicoot · Retry), completed, and storage. It may be a tab, a sheet, part of an activity log — your call.',
  },
  {
    id: 'search', flow: 'Library', title: 'Search', kind: 'app', orient: 'portrait',
    brief: 'The Search tab (iOS 26 separates it). Query "metroid" typed: results across games, platforms and collections, plus suggestions/filters (D.search) and recent searches. Show how results are ranked and what can be played now.',
  },
  {
    id: 'signature', flow: 'Library', title: 'Signature', kind: 'app', orient: 'portrait',
    brief: "This direction's own organising surface — something the other directions don't have — built from real history: D.sessions (play sessions), D.moments (resume points and save states across games), D.activity (sync/download log), D.fleet (devices), D.stats (playtime, streaks). Examples: a play journal, a moments gallery, a device-fleet console, a stats board. It must be reachable from primary navigation.",
  },
  {
    id: 'settings', flow: 'Settings', title: 'Settings', kind: 'app', orient: 'portrait',
    brief: 'Account (ryan · Home server · romm.homelab.dev · online · synced 2 min ago · RomM 5.2.0); other profile (Sam’s library, offline-ready); sync choices (game saves, resume states, cellular); player & controls; storage breakdown (ROMs 18.4 GB, saves, states, cache, cores); cores & BIOS (1 needs BIOS, 1 needs setup); about.',
  },
  {
    id: 'controls', flow: 'Settings', title: 'Controls', kind: 'app', orient: 'portrait',
    brief: 'Tune the touch controls with a live preview: show touch controls, hide with a controller (DualSense connected), left-handed, opacity 72%, size 100%, stick deadzone 12%, haptics, OLED black, console colours, controller type Automatic. Tuning, not freeform editing. On Duo open, the preview is the real full-width player with the settings floating beside/over it, so changes are seen in place.',
  },
  {
    id: 'player', flow: 'Player', title: 'In game', kind: 'landscape', orient: 'landscape',
    brief: 'Super Metroid (SNES) running. d-pad, Y/X/B/A diamond, L/R, Select/Start and Menu. iPhone: centred 4:3 game with side rails. iPhone Duo (open and closed): geometry from k.stage(c, { aspect }) — the game centred between side rails (spanning the fold when open), the d-pad and face buttons low in the rails, L/R just above them, Select · Menu · Start under the game. No control overlaps the game. NEVER game on one half and controls on the other. Optional transient "Automatic resume updated".',
  },
  {
    id: 'player-portrait', flow: 'Player', title: 'In game · portrait', kind: 'portrait', orient: 'portrait',
    brief: "Link's Awakening DX (Game Boy Color): game above, controls below (d-pad, B/A, Select/Start), Menu reachable. iPhone Duo (open and closed): geometry from k.stage(c, { aspect }) — the game full width at the top (it may cross the fold), the controls in the band beneath it, low; nothing overlaps the game.",
  },
  {
    id: 'pause', flow: 'Player', title: 'Pause', kind: 'landscape', orient: 'landscape',
    brief: 'Paused Super Metroid, presented as a native sheet (on Duo open, a floating inspector over the full-screen paused game — the game stays whole behind it). Resume, Save state, Load state, Controls, Fast-forward, Reset, Leave game — Leave game always visible. Truthful status: Game-save data synced · Resume waiting to sync. Session 42 min.',
  },
  {
    id: 'states', flow: 'Player', title: 'Save states', kind: 'landscape', orient: 'landscape',
    brief: 'Saved progress for Super Metroid: automatic resume and game-save data as their own items; slots 1–3 with frames, names, device, time and sync state; slot 4 empty; recovery history. Game-save and state histories both reachable. On Duo open, a floating inspector over the full-screen paused game.',
  },
  {
    id: 'conflict', flow: 'Player', title: 'Conflict', kind: 'app', orient: 'portrait',
    brief: 'EarthBound resume conflict, as a native sheet: This iPhone (38 min ago, Threed, 14 h 12 m) vs Pixel 9 (3 h ago, Twoson, 13 h 51 m), each with its preview. Choose which continues; the other is kept as a recovery copy by default; cancel.',
  },
  {
    id: 'dual', flow: 'Player', title: 'Dual screen', kind: 'portrait', orient: 'portrait',
    brief: 'Pokémon Black (DS): top screen above the touch screen, always stacked. Controls: d-pad, A/B/X/Y, L/R, Start/Select, Menu; stylus goes to the lower screen. iPhone Duo (open and closed): geometry from k.stage(c, { dual: true }) — the two screens stacked (top screen above the fold when open), the controls in a band at the bottom; nothing overlaps either screen, so the stylus area stays clear.',
  },
];
