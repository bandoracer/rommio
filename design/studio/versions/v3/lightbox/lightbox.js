/*
 * AURORA · LIGHTBOX — round 3 exploration · "Your library is light."
 *
 * DESIGN PLAN
 * ===========
 * Thesis     A retro library is mostly pictures: the frame you left a game on, and a wall
 *            of box art. Lightbox treats the phone as a light table. The games' own images
 *            come first and run edge to edge, the chrome is glass laid over them, and colour
 *            is a way to browse: the Library is a wall of covers in hue order that you scan
 *            the way you scan a shelf ("the red one").
 *
 * IA         Tabs are the app's own (Play · Library · Stats · Settings · Search).
 *              Play      "Marquee". The current game's latest resume frame fills the top 61%
 *                        of the page, pixel-sharp, under the status bar and toolbar, and melts
 *                        into the page at its foot. The moment's name is the headline, and
 *                        Resume is the one loud action. Beneath it, "In play" is a row of
 *                        every game in progress as small lit screens. Tapping one swaps the
 *                        marquee, so there are no pages or dots and nothing is hidden behind a
 *                        swipe. Then "Saved lately" (a filmstrip of recent resume points,
 *                        states and saves) and "Waiting to be played" (lit covers, a nudge).
 *              Library   "Colour wall". The whole library as a dense wall of 3:4 covers (four
 *                        across), sorted by the colour each cover looks into one continuous
 *                        gradient. Each colour family (Embers … Bloom) opens with a paint chip
 *                        in the flow of covers, so the gradient never breaks into half-empty
 *                        rows. Two controls only: a lens (Colour · Console · Era · Genre · A–Z)
 *                        and density (pinch, or the grid capsule). A right-edge index rail
 *                        jumps anywhere: for Colour it is the wall's own gradient in miniature.
 *                        Touch and hold a cover to lift it into the loupe, a glass card with
 *                        the essentials and Play or Download, while the page takes that game's
 *                        light.
 *              Platform  The drill-in is the same wall, not a new page: lens = Console, pinched
 *                        to six across. Platforms become sections with sticky glass headers
 *                        (name, "241 games · 14 on this iPhone", tier) and the rail lists
 *                        platform short names.
 *
 * Components Marquee      frame under the bars → shade → progressive-blur melt → page.
 *            Slide        a small lit screen with a caption; the current one wears a ring
 *                         (EarthBound: two halves and a conflict mark; no preview: a lamp).
 *            Cover lamp   when a game has no resume preview (oot3d) its cover art is shown
 *                         as a backlit transparency: full-bleed, lit from above, labelled
 *                         "Cover" so it is never mistaken for a gameplay frame.
 *            Filmstrip    a dark strip with sprocket holes that show the page through them;
 *                         each frame carries its kind chip, name, game, time and sync state.
 *            Lens         glass capsule chips + a density capsule.
 *            Wall         covers with a tiny check when on this iPhone and a lit ring (with
 *                         glow) when in progress. Paint chip: a family's swatch (the gradient
 *                         of its covers), name and count, the size of a cover.
 *            Perceived    the wall sorts by the hue a cover looks (area-weighted palette),
 *            colour       not by its accent light: Star Fox is a navy sky, not a red dot.
 *            Rail         hue strip (Colour) or platform short names (Console), with a glass
 *                         thumb and a bubble naming the section and its count.
 *            Loupe        the held cover, raised and enlarged in a floating glass card.
 *
 * The owner's complaint
 *            Home: no more paged carousel and no cover in a grey box. Every game in play is
 *            visible at once in one row, and the marquee is the real frame, large. A game
 *            without a preview gets a deliberate cover lamp. Library: today three filters
 *            (era ruler, platform facets, tier chips) stand in front of a coverflow that shows
 *            one or two covers. Here a screen holds about twenty covers four across (about
 *            forty six across), a lens changes how the wall is cut, pinch changes how many
 *            fit, and the rail jumps anywhere. The model is the same at 72 games or 2,164:
 *            sections grow and the rail keeps its size, each family or platform a segment in
 *            proportion to its share.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;
  const AK = window.AuroraKit;
  const X = window.AuroraX;

  let C = null;
  const wideCanvas = () => C && C.size === 'regular';

  const FOCUS = 'smetroid'; // the marquee game on Home
  const HELD = 'dkc2'; // the cover in the loupe on Library

  // ================================================================ helpers
  const lit = (id) => AK.lit(id);
  const kind = (m) => `${i(AK.KIND[m.kind].icon, 11)}<span>${esc(AK.kindLabel(m))}</span>`;
  const inPlay = (id) => X.IN_PROGRESS.includes(id);

  // The wall sorts by the colour a cover *looks*, not by its accent (AuroraX.hue is the
  // key light, which for Star Fox is a red accent on a navy sky). A cover is mostly sky
  // (top and bottom), then far and near ground, then a little accent: the perceived hue is
  // the chroma-weighted circular mean of the palette over those areas.
  const AREA = [0.3, 0.3, 0.17, 0.17, 0.06];
  const TONE = new Map();
  function tone(id) {
    if (TONE.has(id)) return TONE.get(id);
    let x = 0;
    let y = 0;
    window.StudioArt.palette(id).forEach((hex, n) => {
      const [h, s, l] = AK.toHsl(hex);
      const c = s * (1 - Math.abs(2 * l - 1)) * AREA[n];
      x += Math.cos((h * Math.PI) / 180) * c;
      y += Math.sin((h * Math.PI) / 180) * c;
    });
    let h = (Math.atan2(y, x) * 180) / Math.PI;
    if (h < 0) h += 360;
    // Reds past 345° sort with the reds at the front.
    const out = { h, key: h >= 345 ? h - 360 : h };
    TONE.set(id, out);
    return out;
  }
  // A vivid swatch of that perceived colour, for the rail and the family kickers.
  const swatch = (id) => AK.toHex(tone(id).h, 0.76, 0.57);
  const FAMS = X.FAMILIES.filter((f) => f.id !== 'ember2');
  const famOf = (id) => {
    const h = tone(id).h;
    const f = X.FAMILIES.find((x) => h >= x.from && h < x.to) || X.FAMILIES[0];
    return f.id === 'ember2' ? FAMS[0] : f;
  };
  const byHue = (list) => list.slice().sort((a, b) => tone(a.id).key - tone(b.id).key);
  const shadeOf = (id) => {
    const [h, s] = AK.keyColour(id, true);
    return AK.toHex(h, Math.min(0.5, Math.max(0.28, s * 0.5)), 0.07);
  };
  const DL = (id) => D().downloads.find((d) => d.game === id);

  // ================================================================ PLAY · marquee
  // Three cases, one layout: a resume frame (the usual), two frames when a resume is in
  // conflict (EarthBound), and the cover lamp when there is no preview (Ocarina of Time 3D).
  function marquee(c, gid) {
    const g = k.game(gid);
    const p = AK.plat(g);
    const m = AK.lastMoment(gid);
    const cf = D().conflict.game === gid ? D().conflict.branches : null;
    const preview = X.hasPreview(gid);
    const H = Math.round(c.h * (c.device.duo ? 0.66 : 0.61));
    const src = cf ? k.frame(gid, cf[0].frame) : preview ? k.frame(gid, m.frame) : k.cover(gid, 'wide');
    const face = cf
      ? `<span class="lb-mq-px lb-mq-two">${cf.map((b) => `<img class="px" src="${k.frame(gid, b.frame)}" alt="">`).join('')}</span>`
      : `<img class="lb-mq-px${preview ? ' px' : ''}" src="${src}" alt="">`;
    let eye = `<b>${esc(g.title)}</b><span>${esc(p.short)}</span>`;
    let title = preview ? m.name : g.title;
    let meta = preview ? `${AK.kindLabel(m)} · ${k.ago(m.at)} · ${m.device}` : `Game-save data · ${k.ago(g.played)} · This iPhone`;
    let chip = preview ? AK.sync(m.remote, m.remote === 'waiting' ? 'Resume waiting to sync' : '') : AK.sync(g.sync);
    let verb = AK.btn(preview ? AK.KIND[m.kind].verb : 'Play', { icon: 'play', kind: 'primary', cls: 'lg grow' });
    let alt = preview ? `${g.title}: ${m.name}` : `${g.title} cover art`;
    if (cf) {
      title = 'Two moments';
      meta = cf.map((b) => `${b.where} on ${b.device}`).join(' · ');
      chip = AK.sync('conflict');
      verb = AK.btn('Choose a moment', { icon: 'split', kind: 'primary', cls: 'lg grow' });
      alt = `${g.title}: ${cf.map((b) => b.where).join(' and ')}`;
    } else if (!preview) {
      eye = `<b>${esc(p.name)}</b><span class="lb-mq-tag">${i('image', 12)}Cover art · no resume preview</span>`;
    }
    return `<section class="lb-mq${preview || cf ? '' : ' is-lamp'}" style="--mq-h:${H}px;--mq-shade:${shadeOf(gid)}" aria-label="${esc(g.title)}">
      <div class="lb-mq-art" aria-hidden="true">
        <img class="lb-mq-bias" src="${src}" alt="">
        ${face}
        <i class="lb-mq-top"></i><i class="lb-mq-shade"></i><i class="lb-mq-melt"></i><i class="lb-mq-foot"></i>
      </div>
      <span class="lb-sr" role="img" aria-label="${esc(alt)}"></span>
      <div class="lb-mq-cap">
        <p class="lb-mq-eye">${eye}</p>
        <h1 class="lb-mq-title">${esc(title)}</h1>
        <p class="lb-mq-meta">${esc(meta)}</p>
        <div class="lb-mq-sync">${chip}</div>
        <div class="lb-mq-act">${verb}${AK.btn('', { icon: 'info', cls: 'round lg', aria: `${g.title} details` })}</div>
      </div>
    </section>`;
  }

  // ================================================================ PLAY · in play (slides)
  function slide(gid, on) {
    const g = k.game(gid);
    const ar = k.aspect(gid);
    const SH = 66;
    const sw = Math.round(SH * ar);
    const w = Math.max(sw, 88);
    let face;
    let sub;
    let cls = '';
    if (gid === 'earthbound') {
      const [a, b] = D().conflict.branches;
      cls = ' is-two';
      face = `<img class="px" src="${k.frame(gid, a.frame)}" alt=""><img class="px" src="${k.frame(gid, b.frame)}" alt=""><span class="lb-slide-flag" aria-hidden="true">${i('split', 12)}</span>`;
      sub = `<small class="lb-bad">Two moments</small>`;
    } else if (!X.hasPreview(gid)) {
      cls = ' is-lamp';
      face = `<img class="lb-lamp-art" src="${k.cover(gid, 'wide')}" alt=""><i class="lb-lamp-light"></i><span class="lb-lamp-tag">${i('image', 10)}Cover</span>`;
      sub = `<small>${esc(k.ago(g.played))}</small>`;
    } else {
      const m = AK.lastMoment(gid);
      face = `<img class="px" src="${k.frame(gid, m.frame)}" alt="">`;
      sub = `<small>${esc(k.ago(m.at))}</small>`;
    }
    const glowSrc = gid === 'earthbound' ? k.frame(gid, 'a') : X.hasPreview(gid) ? k.frame(gid, AK.lastMoment(gid).frame) : k.cover(gid, 'wide');
    return `<button class="lb-slide${on ? ' on' : ''}${cls}" style="${lit(gid)};--sw:${sw}px;--w:${w}px" aria-label="${esc(g.title)}"${on ? ' aria-current="true"' : ''}>
      <span class="lb-slide-scr"><img class="lb-slide-glow" src="${glowSrc}" alt=""><span class="lb-slide-face">${face}</span></span>
      <span class="lb-slide-cap"><b>${esc(g.title)}</b>${sub}</span>
    </button>`;
  }

  // ================================================================ PLAY · saved lately (filmstrip)
  function filmstrip(focus) {
    const top = AK.lastMoment(focus);
    const list = D()
      .moments.filter((m) => m !== top)
      .sort((a, b) => a.at - b.at)
      .slice(0, 9);
    return `<div class="lb-film" role="list">${list
      .map((m) => {
        const g = k.game(m.game);
        return `<figure class="lb-fr" role="listitem" style="${lit(m.game)}">
          <div class="lb-fr-img"><img class="px" src="${k.frame(m.game, m.frame)}" alt="${esc(g.title)}: ${esc(m.name)}"><span class="lb-fr-kind">${kind(m)}</span></div>
          <figcaption><b>${esc(m.name)}</b><span class="lb-fr-sub">${esc(g.title)}</span><span class="lb-fr-sub">${esc(k.ago(m.at))} · ${esc(m.device)}</span>${AK.sync(m.remote)}</figcaption>
        </figure>`;
      })
      .join('')}</div>`;
  }

  // ================================================================ PLAY · waiting to be played
  // AuroraX.waiting() = on this iPhone, never started. When there are none, the nudge widens
  // to the games in the library nobody has started yet, arrivals first.
  function waitingList() {
    const here = X.waiting();
    if (here.length) return { games: here, here: true };
    const order = { running: 0, queued: 1, waiting: 2, failed: 8 };
    const rank = (g) => {
      const d = DL(g.id);
      return (d ? order[d.state] ?? 5 : 5) * 1000 - g.rating;
    };
    return { games: X.unplayed().sort((a, b) => rank(a) - rank(b)), here: false };
  }
  function waitingState(g) {
    if (g.installed) return `<small class="ok">${i('check', 12)}On this iPhone</small>`;
    const d = DL(g.id);
    if (d && d.state === 'running') return `<small class="live tab">${i('download', 12)}Arriving · ${k.pct(d.progress)}</small>`;
    if (d && d.state === 'queued') return `<small class="tab">${i('clock', 12)}Queued · ${esc(k.size(g.size))}</small>`;
    if (d && d.state === 'waiting') return `<small>${i('wifi-off', 12)}${esc(d.reason)}</small>`;
    if (d && d.state === 'failed') return `<small class="t-bad-text">${i('alert-circle', 12)}Download failed</small>`;
    return `<small class="tab">${i('cloud-download', 12)}${esc(k.size(g.size))}</small>`;
  }
  function waitingCover(g) {
    const d = DL(g.id);
    const arriving = d && d.state === 'running';
    return `<figure class="lb-wt${arriving ? ' is-arriving' : ''}" style="${lit(g.id)}${arriving ? `;--p:${Math.round(d.progress * 100)}%` : ''}">
      <div class="lb-wt-img"><img class="lb-wt-glow" src="${k.cover(g.id, 'box')}" alt=""><img class="lb-wt-cv" src="${k.cover(g.id, 'box')}" alt="${esc(g.title)}">${arriving ? `<i class="lb-wt-frost" aria-hidden="true"></i><i class="lb-wt-edge" aria-hidden="true"></i>` : ''}</div>
      <figcaption><b>${esc(g.title)}</b>${waitingState(g)}</figcaption>
    </figure>`;
  }

  function homeBody(c, focus) {
    const W = waitingList();
    const needs = X.IN_PROGRESS.filter((id) => k.game(id).sync === 'conflict').length;
    return `<div class="lb-home" style="--mq-shade:${shadeOf(focus)}">
      <div class="lb-edge" aria-hidden="true"><i></i></div>
      ${marquee(c, focus)}
      <section class="lb-sec lb-inplay">
        ${AK.kicker('In play', `<span class="tab">${X.IN_PROGRESS.length} games${needs ? ` · ${needs} needs a choice` : ''}</span>`)}
        <div class="lb-row lb-slides">${X.IN_PROGRESS.map((id) => slide(id, id === focus)).join('')}</div>
      </section>
      <section class="lb-sec lb-saved">
        ${AK.kicker('Saved lately', `<button class="lb-more">See all ${i('chevron-right', 14)}</button>`)}
        <div class="lb-row lb-filmrow">${filmstrip(focus)}</div>
      </section>
      <section class="lb-sec lb-waiting">
        ${AK.kicker('Waiting to be played', `<span class="tab">${W.here ? `${W.games.length} on this iPhone` : `${W.games.length} never started`}</span>`)}
        <div class="lb-row lb-wts">${W.games.slice(0, 8).map(waitingCover).join('')}</div>
      </section>
    </div>`;
  }

  function homeScreen(c) {
    const focus = FOCUS;
    const html = AK.scene(
      [focus],
      `${AK.field('lb-f-home')}${c.sys.shell({
        tab: 'play',
        titleMode: 'none',
        actions: [AK.ARRIVING, AK.ACCOUNT],
        heroUnderBar: true,
        edge: false,
        body: homeBody(c, focus),
      })}`,
      'lb-s-home',
    );
    // The frame is dark and runs under the status bar: light status text in both appearances.
    return c.device.os === 'ios' && !c.device.vbar ? { html, tone: 'light' } : html;
  }

  // ================================================================ LIBRARY · lens + wall
  const LENSES = [
    { id: 'colour', label: 'Colour', icon: 'palette' },
    { id: 'console', label: 'Console', icon: 'gamepad' },
    { id: 'era', label: 'Era', icon: 'hourglass' },
    { id: 'genre', label: 'Genre', icon: 'shapes' },
    { id: 'az', label: 'A–Z', icon: 'list-ordered' },
  ];
  const lens = (on, cols) =>
    `<div class="lb-lens">
      <div class="lb-lens-row" role="tablist" aria-label="Browse the library by">${LENSES.map(
        (l) => `<button class="lb-chip${l.id === on ? ' on' : ''}" role="tab" aria-selected="${l.id === on}">${i(l.icon, 16)}<span>${esc(l.label)}</span></button>`,
      ).join('')}</div>
      <button class="lb-density" aria-label="Grid density: ${cols} across. Pinch the wall to change it.">${i('grid', 17)}<b class="tab">${cols}</b></button>
    </div>`;

  // One cover on the wall.
  function cover(g, { held = false } = {}) {
    const play = inPlay(g.id);
    return `<figure class="lb-cv${play ? ' is-play' : ''}${held ? ' is-held' : ''}" style="${lit(g.id)}">
      <img src="${k.cover(g.id, 'box')}" alt="${esc(g.title)}${g.installed ? ', on this iPhone' : ''}${play ? ', in play' : ''}">
      ${g.installed ? `<span class="lb-cv-ok" aria-hidden="true">${i('check', 9)}</span>` : ''}
    </figure>`;
  }

  // One continuous grid: each colour family opens with a paint chip (its swatch, name and
  // count) in the flow of covers, so the gradient never breaks into half-empty rows.
  function colourWall(held) {
    const all = byHue(D().games);
    const cells = FAMS.map((f) => {
      const games = all.filter((g) => famOf(g.id).id === f.id);
      if (!games.length) return '';
      const stops = games.map((g) => swatch(g.id));
      const grad = stops.length > 1 ? stops.join(', ') : `${stops[0]}, ${stops[0]}`;
      const here = games.filter((g) => g.installed).length;
      return `<div class="lb-paint" id="lb-fam-${f.id}" role="heading" aria-level="2" aria-label="${esc(f.name)}, ${games.length} games, ${here} on this iPhone">
          <i class="lb-paint-c" style="background:linear-gradient(180deg, ${grad})"></i>
          <span class="lb-paint-l"><b>${esc(f.name)}</b><span class="tab">${games.length} games</span></span>
        </div>${games.map((g) => cover(g, { held: g.id === held })).join('')}`;
    }).join('');
    return `<div class="lb-grid">${cells}</div>`;
  }

  // The colour rail is the wall's own gradient, compressed: one segment per family, each as
  // tall as its share of the library, so a big family is an easy target at 2,164 games too.
  function colourRail(at) {
    const all = byHue(D().games);
    const fams = FAMS.map((f) => ({ f, games: all.filter((g) => famOf(g.id).id === f.id) })).filter((x) => x.games.length);
    const seg = ({ f, games }) =>
      `<i class="lb-rail-seg${f.id === at ? ' on' : ''}" style="flex:${games.length};background:linear-gradient(180deg, ${games.map((g) => swatch(g.id)).join(', ')}${games.length === 1 ? `, ${swatch(games[0].id)}` : ''})" title="${esc(f.name)}"></i>`;
    const cur = fams.find((x) => x.f.id === at);
    const before = fams.slice(0, fams.indexOf(cur)).reduce((n, x) => n + x.games.length, 0);
    const pos = (before / all.length) * 100;
    return `<nav class="lb-rail is-hue" aria-label="Jump to a colour">
      <span class="lb-rail-strip">${fams.map(seg).join('')}</span>
      <span class="lb-rail-thumb" style="--at:${pos}%;--sw:${swatch(cur.games[0].id)}" aria-label="${esc(cur.f.name)} · ${cur.games.length}"><i></i><span class="lb-rail-name">${esc(cur.f.name)}</span><b class="tab">${cur.games.length}</b></span>
    </nav>`;
  }

  function loupe(gid) {
    const g = k.game(gid);
    const p = AK.plat(g);
    const d = DL(gid);
    const here = g.installed
      ? `<p class="lb-lp-here ok">${i('check-circle', 14)}On this iPhone · ${esc(k.size(g.size))}</p>`
      : `<p class="lb-lp-here">${i('cloud-download', 14)}Not on this iPhone · ${esc(k.size(g.size))}${d ? ` · ${esc({ running: `Arriving · ${k.pct(d.progress)}`, queued: 'Queued', waiting: d.reason, failed: 'Download failed' }[d.state] || '')}` : ''}</p>`;
    const verb = g.installed ? AK.btn('Play', { icon: 'play', kind: 'primary', cls: 'md grow' }) : AK.btn('Download', { icon: 'download', kind: 'primary', cls: 'md grow' });
    return `<div class="lb-loupe" style="${lit(gid)}" role="dialog" aria-label="${esc(g.title)}">
      <div class="lb-lp-light" aria-hidden="true"></div>
      <div class="lb-lp-cv"><img class="lb-lp-glow" src="${k.cover(gid, 'box')}" alt=""><img class="lb-lp-img" src="${k.cover(gid, 'box')}" alt="${esc(g.title)} cover"></div>
      <div class="lb-lp-body">
        <h2>${esc(g.title)}</h2>
        <p class="lb-lp-meta tab">${esc(p.short)} · ${g.year} · ${esc(g.genre)}</p>
        ${here}
        <div class="lb-lp-act">${verb}${AK.btn('', { icon: 'info', cls: 'round md', aria: `${g.title} details` })}</div>
      </div>
    </div>`;
  }

  // The colour wall at rest (Library) and with a cover held (Quick look): one builder. At rest
  // the page is lit by the colours in view; while a cover is held it takes that game's light.
  function colourWallScreen(c, held) {
    const t = k.totals();
    const inView = byHue(D().games)
      .slice(0, 3)
      .map((g) => g.id);
    return AK.scene(
      held ? [held] : inView,
      `${AK.field('f-page lb-f-lib', held ? k.cover(held, 'tall') : undefined)}${c.sys.shell({
        tab: 'library',
        title: 'Library',
        subtitle: `${k.num(t.games)} games · ${t.platforms} platforms · ${t.installed} on this iPhone`,
        actions: [AK.ARRIVING, AK.ACCOUNT],
        body: `<div class="lb-lib">${lens('colour', wideCanvas() ? 8 : 4)}<div class="lb-wall${held ? ' is-holding' : ''}">${colourWall(held)}</div></div>`,
        overlay: `<div class="lb-over${wideCanvas() ? ' is-wide' : ''}">${colourRail('ember')}${held ? `<div class="lb-hold-dim" aria-hidden="true"></div>${loupe(held)}` : ''}</div>`,
      })}`,
      held ? 'lb-s-lib lb-s-held' : 'lb-s-lib',
    );
  }
  const libraryScreen = (c) => colourWallScreen(c, null);
  const quickLookScreen = (c) => colourWallScreen(c, HELD);

  // ================================================================ PLATFORM · console lens, dense
  const TIER_ICON = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  function consoleWall() {
    const plats = D().platforms.filter((p) => k.gamesOn(p.id).length);
    return plats
      .map((p) => {
        const games = byHue(k.gamesOn(p.id));
        return `<section class="lb-pl" id="lb-pl-${p.id}">
          <header class="lb-ph"><i class="lb-ph-dot" style="background:${p.color}"></i><span class="lb-ph-t"><b>${esc(p.name)}</b><small class="tab">${k.num(p.games)} games · ${p.installed} on this iPhone</small></span><span class="lb-ph-tier" title="${esc(k.tierLabel(p.tier))}" aria-label="${esc(k.tierLabel(p.tier))}">${i(TIER_ICON[p.tier], 15)}</span></header>
          <div class="lb-grid dense">${games.map((g) => cover(g)).join('')}</div>
        </section>`;
      })
      .join('');
  }
  function consoleRail(at) {
    const plats = D().platforms.filter((p) => k.gamesOn(p.id).length);
    return `<nav class="lb-rail is-names" aria-label="Jump to a platform"><ol>${plats
      .map((p) => `<li class="${p.id === at ? 'on' : ''}"${p.id === at ? ' aria-current="true"' : ''}>${esc(p.short)}</li>`)
      .join('')}</ol></nav>`;
  }
  function platformScreen(c) {
    const t = k.totals();
    return AK.scene(
      ['mm2', 'smetroid', 'kirbyadv'],
      `${AK.field('f-page lb-f-lib')}${c.sys.shell({
        tab: 'library',
        title: 'Library',
        subtitle: `${k.num(t.games)} games · ${t.platforms} platforms · ${t.installed} on this iPhone`,
        actions: [AK.ARRIVING, AK.ACCOUNT],
        body: `<div class="lb-lib is-dense">${lens('console', wideCanvas() ? 12 : 6)}<div class="lb-wall">${consoleWall()}</div></div>`,
        overlay: `<div class="lb-over">${consoleRail('nes')}</div>`,
      })}`,
      'lb-s-plat',
    );
  }

  // ================================================================ register
  const own = (fn) => (c) => {
    C = c;
    try {
      return fn(c);
    } finally {
      C = null;
    }
  };

  Studio.defineVersion({
    id: 'lightbox',
    round: 3,
    extends: 'aurora',
    name: 'Aurora · Lightbox',
    tagline: 'Your library is light: the games’ own images first, and colour as a way to browse.',
    thesis:
      'A retro library is mostly pictures: the frame you left a game on and a wall of box art. Lightbox treats the phone as a light table. Play opens on a marquee, the real resume frame edge to edge under the bars, with every game in play in one row beneath it (tap one to swap the marquee), a filmstrip of recent saves and a shelf of games waiting to be started. The Library is one dense wall of covers sorted by the colour they look and opened family by family with paint chips, with two controls (a lens and density), an index rail that jumps anywhere and a loupe that lifts a cover when you hold it.',
    swatch: ['#0D0C12', '#FF7A3D', '#7FD6D0'],
    tabs: X.TABS,
    fonts: [],
    notes: {
      home:
        'Marquee. Super Metroid’s latest resume frame fills the top 61% of the page, pixel-sharp, under the status bar and toolbar; a shade tinted with the game’s darkest colour carries white type, and a progressive blur melts the frame into the page, whose light comes from the same game. Eyebrow, the moment’s name as the headline, “Automatic resume · 1 min ago · This iPhone”, the sync chip, then Resume and a round Details button. “In play” is one row of every game in progress as small lit screens at their true aspect: the current one is ringed, tapping another swaps the marquee, so there are no pages or dots. EarthBound shows its two moments side by side with a conflict mark; Ocarina of Time 3D has no preview, so its cover art is shown as a backlit transparency labelled “Cover”. Then “Saved lately”, a filmstrip of recent resume points, states and saves (kind chip on every frame, sync state beneath, sprocket holes that let the page through), and “Waiting to be played” as lit covers. That shelf is meant for games on this iPhone that were never started; the mock data has none, so it widens to the 17 never-started games in the library, arrivals first (Final Fantasy VII developing from frost at 62%, then the queue).',
      library:
        'Colour wall, at rest. Title and totals, then the only two controls: the lens (glass capsules: Colour · Console · Era · Genre · A–Z) and density (pinch, or the grid capsule). Every game is on one wall, four across, sorted into a single gradient by the colour each cover looks (not its accent), and each colour family opens with a paint chip (its swatch, name and count) in the flow of covers, so the wall never breaks into half-empty rows; about twenty covers per screen. Covers on this iPhone carry a tiny check; games in play wear a lit ring and glow. The rail on the right edge is the wall’s own gradient in miniature, one segment per family; its thumb is a glass spine label in the rail’s own column (Embers · 6), so it never sits on a cover. Drag it to jump anywhere.',
      game:
        'Quick look (press and hold on the wall). The same colour wall with a cover held: the loupe lifts Diddy’s Kong Quest into a floating glass card above the tab bar with title, platform · year · genre, whether it’s on this iPhone (“Not on this iPhone · 4 MB”) and Download (Play when it is here) plus Details. The slot it came from keeps a dashed, lit outline; the rest of the wall dims slightly (darker, not paler, so Daylight stays saturated) and the page takes the held game’s light. Let go to dismiss; tap Details for the full game page.',
      platform:
        'The same wall after two gestures: the Console lens, then a pinch to six across. Platforms become sections with sticky glass headers (Super Nintendo · 241 games · 14 on this iPhone · Touch), covers keep their colour order inside each platform, and the rail switches to platform short names. Nothing else changes: lens and density are the only controls, so 72 games or 2,164 browse the same way.',
    },
    screens: X.wrap({ home: own(homeScreen), library: own(libraryScreen), platform: own(platformScreen), game: own(quickLookScreen) }),
  });
})();
