/*
 * ROUND 3 — Aurora Home & Library explorations (2026-09-26).
 *
 * Why      On the phone, Aurora's Play page is one game at a time (a paged carousel whose
 *          hero is a cover in a grey box whenever there is no resume preview) and its
 *          Library shows one cover at a time behind three stacked filters (era ruler,
 *          platform facets, tier chips). The owner isn't in love with Home and finds the
 *          Library hard to browse and explore.
 *
 * Rules    Every exploration keeps Aurora's material — per-game light, glass, Bricolage
 *          Grotesque + Figtree, Daylight and Night — and rethinks structure. Each sets
 *          `round: 3, extends: 'aurora'`: its screens carry `.v-aurora .v-<id>`, so Aurora's
 *          tokens and component classes apply and window.AuroraKit's helpers work. Each
 *          designs Home and Library plus one drill-in of its browsing model. Tabs are the
 *          app's real ones (Play · Library · Stats · Settings · Search).
 *
 * Shared   window.AuroraX (below): tabs, screen wrapping, the games in progress, series,
 *          makers, colour families, and the "Aurora today" baseline for Compare.
 */
(function () {
  const k = window.StudioKit;
  const D = () => window.StudioData;
  const AK = window.AuroraKit;

  // The app's tabs (iOS and Android builds).
  const TABS = [
    { id: 'play', label: 'Play', icon: 'circle-play' },
    { id: 'library', label: 'Library', icon: 'library' },
    { id: 'stats', label: 'Stats', icon: 'chart' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
    { id: 'search', label: 'Search', icon: 'search', role: 'search' },
  ];

  // Screen renderers run with AuroraKit reading their context (scheme, size).
  function wrap(screens) {
    const out = {};
    for (const [id, fn] of Object.entries(screens)) {
      out[id] = (c) => {
        AK.use(c);
        try {
          return fn(c);
        } finally {
          AK.done();
        }
      };
    }
    return out;
  }

  // Games in progress, most recent first. EarthBound's resume point is in conflict
  // (two devices); Ocarina of Time 3D has been played but has no resume preview — the
  // case the app shows today as a cover in a grey box.
  const IN_PROGRESS = ['smetroid', 'earthbound', 'linksawak', 'oot3d', 'chrono', 'kirbyadv', 'metfusion'];
  const NO_PREVIEW = ['oot3d', 'mm2', 'sonic2', 'twewy', 'sf2'];
  const hasPreview = (id) => !NO_PREVIEW.includes(id) && !!AK.lastMoment(id);

  // Metadata RomM carries (franchises, companies) that the mock data doesn't: series and makers.
  const SERIES = {
    smetroid: 'Metroid', metfusion: 'Metroid',
    alttp: 'Zelda', linksawak: 'Zelda', oot3d: 'Zelda', windwaker: 'Zelda',
    smw: 'Mario', yoshi: 'Mario', sm64: 'Mario', mk64: 'Mario Kart', mkds: 'Mario Kart',
    kirbyadv: 'Kirby', kss: 'Kirby',
    chrono: 'Chrono', earthbound: 'Mother',
    ff6: 'Final Fantasy', ff7: 'Final Fantasy',
    pkcrystal: 'Pokémon', pkblack: 'Pokémon',
    mm2: 'Mega Man', mmx: 'Mega Man',
    aria: 'Castlevania', sotn: 'Castlevania', castle4: 'Castlevania',
    dkc2: 'Donkey Kong', sonic2: 'Sonic', sor2: 'Streets of Rage', contra3: 'Contra',
    starfox: 'Star Fox', fzero: 'F-Zero', som: 'Mana', goldensun: 'Golden Sun',
    advwars: 'Advance Wars', sf2: 'Street Fighter', pacman: 'Pac-Man', galaga: 'Galaxian',
  };
  const MAKER = (g) => (typeof g === 'string' ? k.game(g) : g).publisher;

  // Colour families for browsing by look: the hue of each game's key colour.
  const FAMILIES = [
    { id: 'ember', name: 'Embers', from: 0, to: 28 },
    { id: 'sun', name: 'Sunlight', from: 28, to: 62 },
    { id: 'moss', name: 'Moss', from: 62, to: 160 },
    { id: 'sea', name: 'Sea glass', from: 160, to: 215 },
    { id: 'dusk', name: 'Dusk', from: 215, to: 285 },
    { id: 'bloom', name: 'Bloom', from: 285, to: 345 },
    { id: 'ember2', name: 'Embers', from: 345, to: 361 },
  ];
  const hue = (id) => AK.keyColour(id, false)[0];
  const family = (id) => {
    const h = hue(id);
    const f = FAMILIES.find((x) => h >= x.from && h < x.to) || FAMILIES[0];
    return f.id === 'ember2' ? FAMILIES[0] : f;
  };

  const played = () => D().games.filter((g) => g.played != null).sort((a, b) => a.played - b.played);
  const unplayed = () => D().games.filter((g) => g.played == null);
  const waiting = () => D().games.filter((g) => g.installed && g.played == null);

  window.AuroraX = { TABS, wrap, IN_PROGRESS, NO_PREVIEW, hasPreview, SERIES, MAKER, FAMILIES, hue, family, played, unplayed, waiting };

  // Baseline: today's Aurora (round 2), so Compare shows each exploration against it.
  const A = window.Studio.byId.aurora;
  window.Studio.defineVersion({
    id: 'auroranow',
    round: 3,
    extends: 'aurora',
    name: 'Aurora today',
    tagline: 'The current Aurora Play and Library (round 2 reference).',
    thesis:
      'Today’s Aurora: Play is a paged carousel of rooms, one game at a time; the Library is an era ruler over a coverflow with platform facets and tier chips beneath. On the phone, a game without a resume preview shows its cover in a grey box, and the coverflow shows one or two covers at a time.',
    swatch: ['#07070C', '#FF7A3D', '#F6F4F1'],
    tabs: A.tabs,
    screens: { home: A.screens.home, library: A.screens.library, platform: A.screens.platform },
    notes: { home: A.notes.home, library: A.notes.library, platform: A.notes.platform },
  });
})();
