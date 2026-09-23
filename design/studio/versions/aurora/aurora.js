/*
 * AURORA — round 2 · "Moments, not files."
 *
 * DESIGN PLAN
 * ===========
 * Thesis     People remember games as moments — where they were, what they saw —
 *            not as ROM files sorted by console. Aurora organises Rommio around
 *            moments of play: every resume point, save state and game-save is a
 *            captured scene you can step back into. The interface is cinematic and
 *            lit by the game in focus; native Liquid Glass chrome floats on that light.
 *
 * IA         Tabs: Play · Moments · Library · Search. Account avatar → Settings.
 *              Play      home. One game per "room", paged. The room is full-bleed
 *                        light from the game's palette; its resume frame is a glowing
 *                        screen; the moment's name is the headline; Resume is the only
 *                        loud action. Other rooms peek below. EarthBound's conflict is
 *                        a room of "two moments". Sync/offline state is one quiet line.
 *              Moments   signature. Every moment across every game as a luminous
 *                        gallery grouped by day (Photos-memories grammar). Each day
 *                        carries a light strip made of the games played that day.
 *                        Tapping a moment performs its verb (Resume / Load state /
 *                        Start with game save).
 *              Library   browse by era, not by grid: an era ruler (lit by each era's
 *                        games) above a coverflow; platforms and support tiers are
 *                        secondary facets; collections are "reels".
 *            Pushed: Game (from anywhere), Platform + Reels (from Library), Settings →
 *            Controls (from the avatar). Sheets: Arriving (downloads, from the
 *            toolbar arrow), Conflict, Pause, Save states.
 *
 * Palette    Daylight (light)                 Night (dark)
 *              Daylight   #F6F4F1  base          Night      #07070C  base
 *              Paper      #FFFFFF  raised        Night 2    #0E0E16  raised
 *              Ink        #16131D  text          Ink        #F6F5FA  text
 *              Frost      #FFFFFF 58% glass      Glass      #FFFFFF 8% glass
 *              Synced     #0B8457                Synced     #7CF2C4
 *              Waiting    #A35F00                Waiting    #FFC66B
 *              Syncing    #0A6F98                Syncing    #6FE3FF
 *              Conflict   #C8352A                Conflict   #FF7A6B
 *            Light is never generic: --l1/--l2/--l3 come from StudioArt.palette of the
 *            game (or games) in focus — lifted to glowing mid-tones at night, softened
 *            to pastels (L≈83%) by day. --sys-tint is the same key colour deepened so
 *            the native chrome (selected tab, prominent button, switches) is lit too.
 *            Super Metroid = ember + violet · Link's Awakening = sea + sun ·
 *            EarthBound = sky + bubblegum · Chrono Trigger = dusk orange.
 *
 * Type       Display  Bricolage Grotesque, condensed (wdth 80–86), 600–800: moment
 *                     names, titles, big numerals. Large titles carry it via
 *                     --sys-title-font.
 *            UI       Figtree 500–700, tabular figures for times, sizes, counts.
 *            Kicker   Figtree 700, 11px, +0.12em caps — chapter labels ("TODAY").
 *
 * Components (content only — all navigation is c.sys)
 *            Field     ambient light: three palette radials + a blurred copy of the
 *                      art + film grain, veiled toward the base.
 *            Screen    a frame as a glowing display: blurred copy spills beneath it,
 *                      a faint floor reflection under hero screens.
 *            Moment    tile: frame, kind (Automatic resume / Save state · Slot n /
 *                      Game-save data), name, game · time · device, sync chip.
 *            Room      screen + moment headline + Resume; room peeks and page lights.
 *            Diptych   two moments side by side (conflict).
 *            Era ruler, Coverflow, Reel (collection as a strip of lit covers),
 *            Memories  a game's moments on a timeline (game page), Arriving scene
 *                      (a download "developing" from frost to colour), glass rows,
 *                      storage spectrum, sliders.
 *
 * Duo        Closed (466×678): the phone layouts, tightened for the short screen;
 *            vertical bar on the trailing edge. Open (951×669): ONE lit canvas. The
 *            light field runs edge to edge (f-canvas) and the fold is only a crease:
 *            heroes span it (the Play room's resume screen, the coverflow, the key
 *            art, the route to the server, the cover wall) and content reflows wider
 *            (day rows of moments, 4–5 column grids, three-column settings). A split
 *            is used only where a list really selects a detail (Platform: eras →
 *            Super Nintendo; Reels: reel list → the reel) — a narrow list on the
 *            same light, never two halves. Sheets float as an inspector over the
 *            whole-display backdrop.
 *
 * Player     Bias lighting: the live frame, blurred, spills into the rails; frosted
 *            glass controls sit in that light (daylight: pale frost + ink legends;
 *            night: smoked glass + white legends). iPhone Duo (open and closed): every
 *            player surface takes its geometry from k.stage — the game shrinks to leave
 *            wide rails, nothing is ever drawn over a game screen, and the clusters sit
 *            low; the game's light fills the rails and pools under the controls. Pause
 *            and Save states are native sheets whose content treats the current
 *            position and each slot as moments.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;
  const Art = () => window.StudioArt;

  // Screens render synchronously, so the current context can be held here for helpers.
  let C = null;
  const reg = () => C && C.size === 'regular';
  // Sheets become side panes only on the open Duo (iPhone landscape is also "regular").
  const pane = () => C && C.device.id === 'duo-open';

  // ================================================================ colour engine
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  function toHsl(hex) {
    const [r, g, b] = k.rgb(hex).map((v) => v / 255);
    const mx = Math.max(r, g, b);
    const mn = Math.min(r, g, b);
    const l = (mx + mn) / 2;
    let h = 0;
    let s = 0;
    if (mx !== mn) {
      const d = mx - mn;
      s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
      h *= 60;
    }
    return [h, s, l];
  }
  function toHex(h, s, l) {
    const a = s * Math.min(l, 1 - l);
    const f = (n) => {
      const q = (n + h / 30) % 12;
      const c = l - a * Math.max(-1, Math.min(q - 3, 9 - q, 1));
      return Math.round(c * 255)
        .toString(16)
        .padStart(2, '0');
    };
    return `#${f(0)}${f(8)}${f(4)}`;
  }
  const chroma = ([, s, l]) => s * (1 - Math.abs(2 * l - 1));
  const yellowish = (h) => h > 40 && h < 76;

  // A game's most characterful colour (accent preferred; yellow avoided for UI tint).
  function keyColour(id, forTint) {
    const p = Art().palette(id).map(toHsl);
    return p
      .map((x, n) => ({ x, v: chroma(x) * (n === 4 ? 1.15 : 1) * (forTint && yellowish(x[0]) ? 0.3 : 1) * (x[1] < 0.12 ? 0.1 : 1) }))
      .sort((a, b) => b.v - a.v)[0].x;
  }
  function sources(ids) {
    if (ids.length === 1) {
      const p = Art().palette(ids[0]).map(toHsl);
      const acc = p[4];
      const rest = p.slice(0, 4).sort((a, b) => chroma(b) - chroma(a));
      // A yellow accent glows olive at night and vanishes by day: it becomes the third light.
      if (yellowish(acc[0]) && chroma(rest[0]) > 0.12) return [rest[0], rest[1], acc];
      return [acc, rest[0], rest[1]];
    }
    const out = ids.slice(0, 3).map((id) => keyColour(id, true));
    while (out.length < 3) out.push(out[out.length - 1]);
    return out;
  }
  const glow = ([h, s, l], scheme) =>
    scheme === 'dark'
      ? toHex(h, s < 0.1 ? s : Math.max(s, 0.55), clamp(l, 0.5, 0.64))
      : toHex(h, s < 0.1 ? s * 0.5 : clamp(s, 0.52, 0.9), clamp(0.82 + (l - 0.5) * 0.08, 0.78, 0.87));
  const LCACHE = new Map();
  function lights(ids, scheme = C ? C.scheme : 'dark') {
    ids = [].concat(ids);
    const key = ids.join(',') + scheme;
    if (LCACHE.has(key)) return LCACHE.get(key);
    const src = sources(ids);
    const [l1, l2, l3] = src.map((x) => glow(x, scheme));
    // Vivid mid-tones for small marks (dots, rules, strips) that must read on either base.
    const [k1, k2, k3] = src.map(([h, s, l]) => (scheme === 'dark' ? glow([h, s, l], scheme) : toHex(h, s < 0.1 ? s : clamp(s, 0.55, 0.95), clamp(l, 0.46, 0.54))));
    const [th, ts] = keyColour(ids[0], true);
    const tint = scheme === 'dark' ? toHex(th, Math.max(ts, 0.62), 0.55) : toHex(th, Math.max(ts, 0.6), 0.42);
    const bg = scheme === 'dark' ? toHex(src[1][0], 0.3, 0.048) : toHex(src[0][0], 0.32, 0.963);
    const out = { l1, l2, l3, k1, k2, k3, tint, bg };
    LCACHE.set(key, out);
    return out;
  }
  const lit = (ids) => {
    const L = lights(ids);
    return `--l1:${L.l1};--l2:${L.l2};--l3:${L.l3};--k1:${L.k1};--k2:${L.k2};--tint:${L.tint}`;
  };

  // ================================================================ data helpers
  const plat = (g) => k.platformOf(typeof g === 'string' ? k.game(g) : g);
  const momentsOf = (gid) => D().moments.filter((m) => m.game === gid).sort((a, b) => a.at - b.at);
  const lastMoment = (gid) => momentsOf(gid)[0];
  const hm = (min) => {
    const h = Math.floor(min / 60);
    const m = Math.round(min % 60);
    return h ? `${h} h ${m} m` : `${m} min`;
  };
  const KIND = {
    resume: { label: 'Automatic resume', icon: 'history', verb: 'Resume', verbIcon: 'play' },
    state: { label: 'Save state', icon: 'layers', verb: 'Load state', verbIcon: 'layers' },
    save: { label: 'Game-save data', icon: 'save', verb: 'Start with game save', verbIcon: 'save' },
  };
  const kindLabel = (m) => (m.kind === 'state' ? `Save state · Slot ${m.slot}` : KIND[m.kind].label);
  const ROOMS = ['smetroid', 'earthbound', 'linksawak', 'chrono', 'kirbyadv'];
  const ERAS = [
    { id: 'e80', name: 'Arcades & cartridges', short: '1978–89', years: '1978–1989', plats: ['arcade', 'nes', 'sms', 'gb'], light: ['mm2', 'galaga', 'kirbyadv'] },
    { id: 'e16', name: 'The 16-bit years', short: '1989–95', years: '1989–1995', plats: ['snes', 'genesis', 'pce', 'gg', 'lynx'], light: ['smetroid', 'chrono', 'sor2'] },
    { id: 'e3d', name: 'Polygons & pockets', short: '1995–2000', years: '1995–2000', plats: ['psx', 'n64', 'saturn', 'dc', 'vb', 'gbc', 'ngp'], light: ['sotn', 'sm64', 'jetset'] },
    { id: 'e00', name: 'The 2000s', short: '2001–11', years: '2001–2011', plats: ['gba', 'nds', 'psp', '3ds', 'gc', 'ps2'], light: ['metfusion', 'twewy', 'oot3d'] },
  ];
  const eraCount = (e) => e.plats.reduce((n, p) => n + k.platform(p).games, 0);
  const eraInstalled = (e) => e.plats.reduce((n, p) => n + k.platform(p).installed, 0);
  const eraGames = (e) => D().games.filter((g) => e.plats.includes(g.platform));

  // ================================================================ primitives
  const ACCOUNT = { avatar: 'R', label: 'Account and settings' };
  const ARRIVING = { icon: 'download', label: 'Arriving · 4 transfers', badge: '4' };

  const scene = (ids, html, cls = '') => {
    const L = lights(ids);
    return `<div class="a-scene ${cls}" style="--l1:${L.l1};--l2:${L.l2};--l3:${L.l3};--k1:${L.k1};--k2:${L.k2};--k3:${L.k3};--tint:${L.tint};--sys-tint:${L.tint};--sys-bg:${L.bg}">${html}</div>`;
  };
  // Ambient light field. `art` = image url blurred into the field.
  const field = (cls = '', art) =>
    `<div class="a-field ${cls}" aria-hidden="true">${art ? `<img class="a-field-art" src="${art}" alt="">` : ''}<i class="a-field-light"></i><i class="a-field-veil"></i><i class="a-field-grain" style="background-image:url(${Art().grain(0.08)})"></i></div>`;

  // A frame presented as a glowing screen.
  const gs = (src, { ar = 4 / 3, cls = '', alt = '' } = {}) =>
    `<div class="a-gs ${cls}" style="--ar:${ar}"><img class="a-gs-glow" src="${src}" alt=""><div class="a-gs-face"><img class="px" src="${src}" alt="${esc(alt)}"></div></div>`;

  const TONE = { synced: 'ok', pending: 'wait', waiting: 'wait', syncing: 'live', conflict: 'bad', local: 'mute', signin: 'wait', none: 'mute', failed: 'bad', running: 'live', ok: 'ok' };
  const sync = (state, label, cls = '') =>
    `<span class="a-sync t-${TONE[state] || 'mute'} ${cls}">${state === 'syncing' || state === 'running' ? `<span class="a-spin">${i('sync', 12)}</span>` : '<i class="a-dot"></i>'}<span>${esc(label || k.syncLabel(state))}</span></span>`;

  const btn = (label, { icon, kind = 'glass', cls = '', size = 18, aria } = {}) =>
    `<button class="a-btn ${kind} ${cls}${icon === 'play' ? ' is-play' : ''}"${aria ? ` aria-label="${esc(aria)}"` : ''}>${icon ? i(icon, size) : ''}${label ? `<span>${esc(label)}</span>` : ''}</button>`;

  const kicker = (text, extra = '') => `<p class="a-kicker"><span>${text}</span>${extra}</p>`;

  const TIER_ICON = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tier = (t, extra = '') => `<span class="a-tier tier-${t}">${i(TIER_ICON[t], 13)}${esc(k.tierLabel(t))}${extra}</span>`;

  // Glass list rows (Settings, Controls, Game facts).
  const row = ({ icon, tone = '', title, sub, value, acc = 'chev', cls = '' }) =>
    `<div class="a-row ${cls}">${icon ? `<span class="a-row-ic ${tone}">${i(icon, 17)}</span>` : ''}<span class="a-row-main"><b>${title}</b>${sub ? `<small>${sub}</small>` : ''}</span>${value ? `<span class="a-row-val">${value}</span>` : ''}${
      acc === 'chev' ? i('chevron-right', 16, 'a-chev') : acc || ''
    }</div>`;
  const group = (title, rows, foot = '', cls = '') =>
    `<section class="a-group ${cls}">${title ? `<h3>${title}</h3>` : ''}<div class="a-card">${rows.join('')}</div>${foot ? `<p class="a-foot">${foot}</p>` : ''}</section>`;

  // A moment as a captured scene.
  function momentTile(m, { size = 'sm', cls = '', device = size !== 'sm' } = {}) {
    const g = k.game(m.game);
    const L = lights(m.game);
    const big = size === 'lg';
    return `<figure class="a-mo a-mo-${size} ${cls}" style="--g:${L.k1};--g2:${L.k2}">
      <div class="a-mo-img"><img class="px" src="${k.frame(m.game, m.frame)}" alt=""></div>
      <span class="a-mo-kind">${i(KIND[m.kind].icon, 12)}${esc(kindLabel(m))}</span>
      ${big ? `<span class="a-mo-sync">${sync(m.remote)}</span>` : ''}
      <figcaption><b>${esc(m.name)}</b><span>${esc(g.title)} · ${esc(size === 'xs' ? k.agoShort(m.at) : k.ago(m.at))}${device ? ` · ${esc(m.device)}` : ''}</span>${big ? '' : sync(m.remote)}</figcaption>
    </figure>`;
  }

  // ================================================================ PLAY (rooms)
  const statusLine = () =>
    `<p class="a-status">${sync('synced', 'Home server · synced 2 min ago')}<span class="a-status-sep" aria-hidden="true"></span><span class="a-status-off">${i('smartphone', 13)}91 games offline</span></p>`;

  // The room: glowing resume screen, moment headline, one loud action.
  function room(gid, { peek = true, action = true } = {}) {
    if (gid === 'earthbound') return twoMomentsRoom({ action });
    const g = k.game(gid);
    const m = lastMoment(gid);
    const next = ROOMS[ROOMS.indexOf(gid) + 1];
    const nm = next && lastMoment(next);
    return `<div class="a-room">
      <div class="a-stage">
        ${gs(k.frame(gid, m.frame), { cls: 'a-room-gs', alt: `${g.title} — ${m.name}` })}
        <span class="a-stage-chip">${sync(m.remote, m.remote === 'waiting' ? 'Resume waiting to sync' : '')}</span>
        ${peek && nm ? `<div class="a-peek" style="${lit(next)}" aria-hidden="true"><img class="px" src="${k.frame(next, nm.frame)}" alt=""></div>` : ''}
      </div>
      <div class="a-cap">
        <p class="a-eyebrow"><b>${esc(g.title)}</b><span>${esc(plat(g).short)}</span></p>
        <h2 class="a-title-xl">${esc(m.name)}</h2>
        <p class="a-meta">${esc(kindLabel(m))} · ${esc(k.ago(m.at))} · ${esc(m.device)}</p>
        ${reg() || C.device.duo ? `<div class="a-cap-row">${sync('synced', 'Game-save data synced')}<span class="a-cap-note">3 min ago · session 42 min</span></div>` : ''}
      </div>
      ${action ? `<div class="a-act">${btn(KIND[m.kind].verb, { icon: 'play', kind: 'primary', cls: 'lg grow' })}${btn('', { icon: 'info', cls: 'round lg', aria: `${g.title} details` })}</div>` : ''}
    </div>`;
  }
  // EarthBound: two moments that need a choice.
  function twoMomentsRoom({ action = true } = {}) {
    const cf = D().conflict;
    const [a, b] = cf.branches;
    return `<div class="a-room a-room-two">
      <div class="a-stage two">
        <div class="a-dip">
          ${gs(k.frame('earthbound', a.frame), { alt: `EarthBound at ${a.where}` })}<span class="a-dip-l">${esc(a.where)}</span>
        </div>
        <div class="a-dip">
          ${gs(k.frame('earthbound', b.frame), { alt: `EarthBound at ${b.where}` })}<span class="a-dip-l">${esc(b.where)}</span>
        </div>
      </div>
      <div class="a-cap">
        <p class="a-eyebrow"><b>EarthBound</b><span>SNES</span></p>
        <h2 class="a-title-xl">Two moments</h2>
        <p class="a-meta">${esc(a.where)} on ${esc(a.device)} · ${esc(b.where)} on ${esc(b.device)}</p>
        <div class="a-cap-row">${sync('conflict')}<span class="a-cap-note">Choose which one continues</span></div>
      </div>
      ${action ? `<div class="a-act">${btn('Choose a moment', { icon: 'split', kind: 'primary', cls: 'lg grow' })}</div>` : ''}
    </div>`;
  }
  // Small card for another room.
  function roomCard(gid) {
    const g = k.game(gid);
    if (gid === 'earthbound') {
      const [a, b] = D().conflict.branches;
      return `<article class="a-rc two" style="${lit(gid)}">
        <div class="a-rc-img"><img class="px" src="${k.frame(gid, a.frame)}" alt=""><img class="px" src="${k.frame(gid, b.frame)}" alt=""></div>
        <span class="a-rc-flag">${sync('conflict')}</span>
        <div class="a-rc-cap"><span>EarthBound</span><b>Two moments</b><small>${esc(a.where)} or ${esc(b.where)}</small></div>
      </article>`;
    }
    const m = lastMoment(gid);
    return `<article class="a-rc" style="${lit(gid)}">
      <div class="a-rc-img"><img class="px" src="${k.frame(gid, m.frame)}" alt=""></div>
      <div class="a-rc-cap"><span>${esc(g.title)}</span><b>${esc(m.name)}</b><small>${esc(k.ago(m.at))} · ${esc(m.device)}</small></div>
    </article>`;
  }
  const pager = (active = 'smetroid') =>
    `<span class="a-pager" aria-label="Room ${ROOMS.indexOf(active) + 1} of ${ROOMS.length}">${ROOMS.map((id) => `<i class="${id === active ? 'on' : ''}" style="--c:${lights(id).tint}"></i>`).join('')}</span>`;

  // The running download as a room-sized tile: the art develops from frost to colour.
  function arrivingTile() {
    const d = D().downloads[0];
    return `<article class="a-rc a-arrtile" style="${lit(d.game)};--p:${d.progress * 100}%">
      <div class="a-rc-img"><img class="a-arrtile-frost" src="${k.cover(d.game, 'wide')}" alt=""><img class="a-arrtile-sharp" src="${k.cover(d.game, 'wide')}" alt=""><i class="a-arrtile-edge" aria-hidden="true"></i></div>
      <span class="a-rc-flag"><span class="a-sync t-live">${i('download', 12)}<span class="tab">Arriving · ${k.pct(d.progress)}</span></span></span>
      <div class="a-rc-cap"><span>Final Fantasy VII</span><b class="tab">${k.size(d.done)} of ${k.size(d.total)}</b><small class="tab">${d.rate} MB/s · ${esc(d.eta)} left · 2 queued</small></div>
    </article>`;
  }

  // Duo open: the room is the whole canvas. The resume screen spans the fold, the
  // moment reads beside it, and the other rooms run the full width beneath it.
  function playCanvas(focus = 'smetroid', { action = true } = {}) {
    const g = k.game(focus);
    const m = lastMoment(focus);
    const others = ROOMS.filter((id) => id !== focus);
    return `<div class="a-pc">
      <div class="a-pc-top">${statusLine()}${pager(focus)}</div>
      <div class="a-pc-hero">
        <div class="a-stage">${gs(k.frame(focus, m.frame), { cls: 'a-room-gs', alt: `${g.title} — ${m.name}` })}<span class="a-stage-chip">${sync(m.remote, m.remote === 'waiting' ? 'Resume waiting to sync' : '')}</span></div>
        <div class="a-cap">
          <p class="a-eyebrow"><b>${esc(g.title)}</b><span>${esc(plat(g).short)}</span></p>
          <h2 class="a-title-xl">${esc(m.name)}</h2>
          <p class="a-meta">${esc(kindLabel(m))} · ${esc(k.ago(m.at))} · ${esc(m.device)}</p>
          <div class="a-cap-row">${sync('synced', 'Game-save data synced')}<span class="a-cap-note">3 min ago · session 42 min</span></div>
          ${action ? `<div class="a-act">${btn(KIND[m.kind].verb, { icon: 'play', kind: 'primary', cls: 'lg grow' })}${btn('', { icon: 'info', cls: 'round lg', aria: `${g.title} details` })}</div>` : ''}
        </div>
      </div>
      <section class="a-pc-rooms">
        ${kicker('Also in play', `<span class="tab">${others.length} rooms · 1 needs a choice</span>`)}
        <div class="a-pc-row">${others.map(roomCard).join('')}${arrivingTile()}</div>
      </section>
    </div>`;
  }

  function playBody(c, focus = 'smetroid') {
    if (reg()) return playCanvas(focus);
    const rest = ROOMS.filter((id) => id !== focus);
    return `<div class="a-play">
      ${statusLine()}
      ${room(focus)}
      <section class="a-rooms">
        <header>${kicker('Also in play')}${pager(focus)}</header>
        <div class="a-rooms-row">${rest.map(roomCard).join('')}</div>
      </section>
    </div>`;
  }
  // The Play canvas without chrome, across a sheet's whole backdrop (Duo open inspector).
  const playBackdrop = (c, focus, box, opts = {}) =>
    scene(
      [focus],
      `${field('f-room f-canvas', k.cover(focus, 'tall'))}<div class="a-bd" style="width:${box.w}px;height:${box.h}px">${playCanvas(focus, { action: opts.action !== false })}</div>`,
      'a-s-play',
    );
  const playScreen = (c, focus = 'smetroid') =>
    scene(
      focus === 'earthbound' ? ['earthbound'] : [focus],
      `${field('f-room' + (reg() ? ' f-canvas' : ''), k.cover(focus, 'tall'))}${c.sys.shell({
        tab: 'play',
        titleMode: 'none',
        actions: [ARRIVING, ACCOUNT],
        edge: false,
        body: playBody(c, focus),
      })}`,
      'a-s-play',
    );

  // ================================================================ MOMENTS (signature)
  const DAYS = [
    { title: 'Today', date: 'Wednesday 23 September', from: 0, to: 600, days: [0] },
    { title: 'Yesterday', date: 'Tuesday 22 September', from: 600, to: 2040, days: [1] },
    { title: 'Earlier this week', date: '17–21 September', from: 2040, to: 10080, days: [2, 3, 4, 5, 6] },
    { title: 'Earlier in September', date: '11–12 September', from: 10080, to: 1e9, days: [11, 12] },
  ];
  function dayOf(d) {
    const ms = D()
      .moments.filter((m) => m.at >= d.from && m.at < d.to)
      .sort((a, b) => a.at - b.at);
    const ss = D().sessions.filter((s) => d.days.includes(s.day));
    const mins = ss.reduce((n, s) => n + s.minutes, 0);
    const games = [...new Set(ms.map((m) => m.game))];
    return { ms, sessions: ss.length, mins, games };
  }
  const dayLight = (games, dir = 'h') =>
    `<i class="a-daylight ${dir}" style="background:linear-gradient(${dir === 'v' ? 180 : 90}deg, ${games
      .map((id, n) => `${lights(id).k1} ${Math.round((n / Math.max(1, games.length - 1)) * 100)}%`)
      .join(', ')})" aria-hidden="true"></i>`;
  function dayGroup(d, { heroFirst = true, focus = false } = {}) {
    const x = dayOf(d);
    const ms = x.ms;
    const tiles = ms
      .map((m, n) => momentTile(m, { size: heroFirst && n === 0 && ms.length > 2 ? 'lg' : 'sm', cls: focus && n === 0 ? 'on' : '' }))
      .join('');
    return `<section class="a-day">
      <header class="a-day-h">
        <h2>${esc(d.title)}</h2>
        <p><span>${esc(d.date)}</span><span class="tab">${x.sessions} ${x.sessions === 1 ? 'session' : 'sessions'} · ${hm(x.mins)}</span></p>
        ${dayLight(x.games)}
      </header>
      <div class="a-mosaic">${tiles}</div>
    </section>`;
  }
  function momentsScreen(c) {
    const games = new Set(D().moments.map((m) => m.game)).size;
    const sub = `${D().moments.length} moments · ${games} games · ${hm(D().stats.month.minutes)} this month`;
    if (reg()) {
      // Duo open: each day is one row across the canvas — its name, sessions and light
      // strip in a narrow margin, its moments in a four-column mosaic that spans the fold.
      const dayRow = (d, n) => {
        const x = dayOf(d);
        const tiles = x.ms.map((m, j) => momentTile(m, { size: n === 0 && j === 0 ? 'lg' : 'sm', cls: n === 0 && j === 0 ? 'on' : '' })).join('');
        return `<section class="a-dayrow">
          <header class="a-dayrow-h">
            ${dayLight(x.games, 'v')}
            <h2>${esc(d.title)}</h2>
            <p>${esc(d.date)}</p>
            <p class="tab">${x.sessions} ${x.sessions === 1 ? 'session' : 'sessions'} · ${hm(x.mins)}</p>
            <p class="tab">${x.ms.length} ${x.ms.length === 1 ? 'moment' : 'moments'} · ${x.games.length} ${x.games.length === 1 ? 'game' : 'games'}</p>
          </header>
          <div class="a-dayrow-grid${n === 0 ? ' hero' : ''}">${tiles}</div>
        </section>`;
      };
      return scene(
        ['smetroid', 'earthbound', 'linksawak'],
        `${field('f-page f-canvas')}${c.sys.shell({
          tab: 'moments',
          title: 'Moments',
          subtitle: sub,
          actions: [{ icon: 'filter', label: 'Filter moments' }, ACCOUNT],
          body: `<div class="a-moments open">${DAYS.map(dayRow).join('')}</div>`,
        })}`,
        'a-s-moments',
      );
    }
    return scene(
      ['smetroid', 'earthbound', 'linksawak'],
      `${field('f-page')}${c.sys.shell({
        tab: 'moments',
        title: 'Moments',
        subtitle: sub,
        actions: [{ icon: 'filter', label: 'Filter moments' }, ACCOUNT],
        body: `<div class="a-moments">${DAYS.map((d) => dayGroup(d)).join('')}</div>`,
      })}`,
      'a-s-moments',
    );
  }

  // ================================================================ LIBRARY
  const eraRuler = (active = 'e16') =>
    `<div class="a-eras" role="tablist" aria-label="Eras">${ERAS.map((e) => {
      const L = e.light.map((id) => lights(id).k1);
      return `<button role="tab" class="a-era${e.id === active ? ' on' : ''}" aria-selected="${e.id === active}" style="flex:${eraCount(e)}"><i style="background:linear-gradient(90deg, ${L.join(', ')})"></i><b class="tab">${esc(e.short)}</b><small class="tab">${k.num(eraCount(e))}</small></button>`;
    }).join('')}</div>`;
  const FLOW = ['alttp', 'chrono', 'smetroid', 'earthbound', 'sor2'];
  // Duo open: the coverflow runs the width of the canvas, its centre cover on the crease.
  const FLOW_WIDE = ['mmx', 'alttp', 'chrono', 'smetroid', 'earthbound', 'sor2', 'ff6'];
  const coverflow = (ids = FLOW) =>
    `<div class="a-flow${ids.length > 5 ? ' wide' : ''}" role="listbox" aria-label="The 16-bit years">${ids.map((id, n) => {
      const g = k.game(id);
      const p = n - (ids.length - 1) / 2;
      return `<figure class="a-flow-i p${p < 0 ? 'm' + -p : p}${g.installed ? '' : ' unlit'}" style="${lit(id)}" role="option" aria-selected="${p === 0}"><img class="a-flow-glow" src="${k.cover(id, 'box')}" alt=""><img class="a-flow-img" src="${k.cover(id, 'box')}" alt="${esc(g.title)}"></figure>`;
    }).join('')}</div>`;
  const facets = (e) =>
    `<div class="a-facets">${e.plats
      .map((pid) => {
        const p = k.platform(pid);
        return `<button class="a-facet${pid === 'snes' ? ' on' : ''}"><i style="background:${p.color}"></i><b>${esc(p.short)}</b><span class="tab">${p.games}</span><small class="tab">${p.installed ? `${p.installed} here` : 'none here'}</small></button>`;
      })
      .join('')}</div>`;
  const tiersLine = () => {
    const t = (x) => k.tier(x).length;
    return `<div class="a-tiers" aria-label="How your platforms play">${tier('touch', ` <b class="tab">${t('touch')}</b>`)}${tier('controller', ` <b class="tab">${t('controller')}</b>`)}${tier('unsupported', ` <b class="tab">${t('unsupported')}</b>`)}</div>`;
  };
  const reelStrip = (col, n = 5) =>
    `<div class="a-reel-strip">${col.games
      .slice(0, n)
      .map((id, x) => `<img class="a-reel-f${k.game(id).installed ? '' : ' unlit'}" style="--n:${x}" src="${k.cover(id, 'box')}" alt="">`)
      .join('')}</div>`;
  function reelCard(col, { size = 'md' } = {}) {
    const games = col.games.map(k.game);
    const here = games.filter((g) => g.installed).length;
    const withMoments = games.filter((g) => momentsOf(g.id).length).length;
    return `<article class="a-reel a-reel-${size}" style="${lit(col.games.slice(0, 3))};--r1:${lights(col.games[0]).l1};--r2:${lights(col.games[1]).l1};--r3:${lights(col.games[2] || col.games[0]).l1}">
      <div class="a-reel-light" aria-hidden="true"></div>
      ${reelStrip(col, size === 'sm' ? 4 : 5)}
      <div class="a-reel-cap">
        <b>${esc(col.name)}</b>
        ${col.note ? `<span class="a-reel-note">${esc(col.note)}</span>` : ''}
        <small class="tab">${games.length} games · ${withMoments ? `${withMoments} with moments` : `${here} on this iPhone`}</small>
      </div>
    </article>`;
  }

  function libraryScreen(c) {
    const e = ERAS[1];
    const t = k.totals();
    if (reg()) {
      // Duo open: one canvas. The era ruler and the era's platforms run full width, the
      // coverflow spans the fold with Super Metroid on the crease, reels close the page.
      return scene(
        e.light,
        `${field('f-page f-canvas')}${c.sys.shell({
          tab: 'library',
          title: 'Library',
          subtitle: `${k.num(t.games)} games · ${t.platforms} platforms · ${t.installed} on this iPhone`,
          actions: [ARRIVING, ACCOUNT],
          body: `<div class="a-lib open">
            ${eraRuler('e16')}
            <div class="a-lib-bar"><div class="a-lib-era"><h2>${esc(e.name)}</h2><span class="tab">${k.num(eraCount(e))} games · ${eraInstalled(e)} here</span></div>${facets(e)}</div>
            ${coverflow(FLOW_WIDE)}
            <div class="a-flow-cap"><b>Super Metroid</b><span>SNES · 1994 · ${i('check', 12)} On this iPhone · 4 moments · Resume waiting to sync</span></div>
            <div class="a-lib-foot">
              <div class="a-lib-tiers">${kicker('How they play')}${tiersLine()}</div>
              ${reelCard(k.collection('metroidvania'))}${reelCard(k.collection('couch'))}
            </div>
          </div>`,
        })}`,
        'a-s-library',
      );
    }
    return scene(
      e.light,
      `${field('f-page')}${c.sys.shell({
        tab: 'library',
        title: 'Library',
        subtitle: `${k.num(t.games)} games · ${t.platforms} platforms · ${t.installed} on this iPhone`,
        actions: [ARRIVING, ACCOUNT],
        body: `<div class="a-lib">
          ${eraRuler('e16')}
          <div class="a-lib-era"><h2>${esc(e.name)}</h2><span class="tab">${k.num(eraCount(e))} games · ${eraInstalled(e)} here</span></div>
          ${coverflow(FLOW)}
          <div class="a-flow-cap"><b>Super Metroid</b><span>SNES · 1994 · ${i('check', 12)} On this iPhone · 4 moments</span></div>
          ${facets(e)}
          ${tiersLine()}
          <section class="a-lib-reels">
            <header>${kicker('Reels', '<button class="a-link">See all</button>')}</header>
            <div class="a-lib-reels-row">${reelCard(k.collection('metroidvania'), { size: 'sm' })}${reelCard(k.collection('couch'), { size: 'sm' })}</div>
          </section>
        </div>`,
      })}`,
      'a-s-library',
    );
  }

  // ================================================================ PLATFORM
  function platformDetail(c) {
    const p = k.platform('snes');
    const games = k.gamesOn('snes');
    const lit_ = games.filter((g) => g.installed).sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));
    const unlit = games.filter((g) => !g.installed);
    const litTile = (g) => {
      const m = lastMoment(g.id);
      return `<figure class="a-pt" style="${lit(g.id)}"><div class="a-pt-img"><img class="a-pt-glow" src="${k.cover(g.id, 'box')}" alt=""><img src="${k.cover(g.id, 'box')}" alt=""></div><figcaption><b>${esc(g.title)}</b><span>${m ? esc(m.name) : g.played != null ? esc(k.ago(g.played)) : 'Not started'}</span></figcaption></figure>`;
    };
    const unlitTile = (g) =>
      `<figure class="a-pt unlit"><div class="a-pt-img"><img src="${k.cover(g.id, 'box')}" alt=""><span class="a-pt-dl">${i('cloud-download', 14)}</span></div><figcaption><b>${esc(g.title)}</b><span class="tab">${k.size(g.size)}</span></figcaption></figure>`;
    return `<div class="a-plat">
      <div class="a-plat-hero gl">
        <div class="a-plat-n"><b class="tab">${p.games}</b><span>games</span></div>
        <div class="a-plat-n"><b class="tab">${p.installed}</b><span>on this iPhone</span></div>
        <div class="a-plat-core"><span>${i('cpu', 14)}${esc(p.core)}</span>${tier('touch')}<span class="a-sync t-ok"><i class="a-dot"></i><span>Ready</span></span></div>
      </div>
      <div class="a-plat-tools">${c.sys.segmented(['All', 'On this iPhone', 'Not downloaded'], 0)}<button class="a-sortbtn" aria-label="Sort">${i('sort', 16)}Recently played</button></div>
      <section class="a-plat-sec">${kicker(`Lit · on this iPhone`, `<span class="tab">${p.installed}</span>`)}<div class="a-pgrid">${lit_.map(litTile).join('')}</div></section>
      <section class="a-plat-sec">${kicker('Unlit · in your library', `<span class="tab">${p.games - p.installed}</span>`)}<div class="a-pgrid">${unlit.map(unlitTile).join('')}</div></section>
    </div>`;
  }
  function libraryIndex() {
    return `<div class="a-lindex">${ERAS.map(
      (e) => `<section class="a-lindex-era" style="${lit(e.light)}">
        <header><i style="background:linear-gradient(90deg, ${e.light.map((id) => lights(id).k1).join(', ')})"></i><b>${esc(e.name)}</b><span class="tab">${esc(e.years)}</span></header>
        <ul>${e.plats
          .slice(0, e.id === 'e16' ? 5 : 3)
          .map((pid) => {
            const p = k.platform(pid);
            return `<li class="${pid === 'snes' ? 'on' : ''}"><i style="background:${p.color}"></i><b>${esc(p.name)}</b><span class="tab">${p.games}</span></li>`;
          })
          .join('')}${e.plats.length > (e.id === 'e16' ? 5 : 3) ? `<li class="more"><span>${e.plats.length - (e.id === 'e16' ? 5 : 3)} more</span></li>` : ''}</ul>
      </section>`,
    ).join('')}</div>`;
  }
  function platformScreen(c) {
    const lightIds = ['smetroid', 'chrono', 'earthbound'];
    if (reg()) {
      // A real list → detail: the narrow era index selects the platform; both share one light.
      return scene(
        lightIds,
        `${field('f-page f-canvas')}${c.sys.shell({
          tab: 'library',
          title: 'Super Nintendo',
          back: 'Library',
          actions: [{ icon: 'sort', label: 'Sort' }, { icon: 'filter', label: 'Filter' }],
          body: '',
          split: {
            primary: `<div class="a-lindex-h">${kicker('Library · by era', `<span class="tab">${k.totals().platforms} platforms</span>`)}</div>${libraryIndex()}`,
            primaryWidth: 300,
            secondary: platformDetail(c),
            secondaryTitle: 'Super Nintendo',
          },
        })}`,
        'a-s-platform',
      );
    }
    return scene(
      lightIds,
      `${field('f-page')}${c.sys.shell({
        tab: 'library',
        title: 'Super Nintendo',
        back: 'Library',
        actions: [{ icon: 'filter', label: 'Filter' }, { icon: 'more', label: 'More' }],
        body: platformDetail(c),
      })}`,
      'a-s-platform',
    );
  }

  // ================================================================ COLLECTIONS (reels)
  function reelsList(sel) {
    const cols = D().collections;
    const manual = cols.filter((x) => x.kind === 'manual');
    const smart = cols.filter((x) => x.kind === 'smart');
    const auto = cols.filter((x) => x.kind === 'auto');
    const ruleRow = (x) =>
      `<div class="a-rule"><span class="a-rule-covers">${x.games
        .slice(0, 3)
        .map((id) => `<img src="${k.cover(id, 'box')}" alt="">`)
        .join('')}</span><span class="a-rule-t"><b>${esc(x.name)}</b><small class="a-rule-chip">${i(x.kind === 'smart' ? 'filter' : 'sparkles', 12)}${esc(x.rule)}</small></span><span class="a-rule-n tab">${x.games.length}</span></div>`;
    return `<div class="a-reels">
      ${kicker('Made by you', `<span class="tab">${manual.length}</span>`)}
      <div class="a-reels-manual">${manual.map((x) => reelCard(x, { size: sel === x.id ? 'md on' : 'md' })).join('')}</div>
      ${kicker('Smart', `<span class="tab">${smart.length}</span>`)}
      <div class="a-card">${smart.map(ruleRow).join('')}</div>
      ${kicker('Automatic', `<span class="tab">${auto.length}</span>`)}
      <div class="a-card">${auto.map(ruleRow).join('')}</div>
    </div>`;
  }
  // Duo open list: every reel as a row, the selected one lit by its own games.
  function reelIndex(sel) {
    const cols = D().collections;
    const kinds = [
      ['manual', 'Made by you'],
      ['smart', 'Smart'],
      ['auto', 'Automatic'],
    ];
    const rowOf = (x) =>
      `<li class="a-rule${x.id === sel ? ' on' : ''}" style="${lit(x.games.slice(0, 3))}"><span class="a-rule-covers">${x.games
        .slice(0, 3)
        .map((gid) => `<img src="${k.cover(gid, 'box')}" alt="">`)
        .join('')}</span><span class="a-rule-t"><b>${esc(x.name)}</b>${
        x.kind === 'manual' ? `<small class="a-rule-note">${esc(x.note || 'Made by you')}</small>` : `<small class="a-rule-chip">${i(x.kind === 'smart' ? 'filter' : 'sparkles', 12)}<span>${esc(x.rule)}</span></small>`
      }</span><span class="a-rule-n tab">${x.games.length}</span></li>`;
    return `<div class="a-rindex">${kinds
      .map(([kind, label]) => {
        const xs = cols.filter((x) => x.kind === kind);
        return `${kicker(label, `<span class="tab">${xs.length}</span>`)}<ul class="a-card">${xs.map(rowOf).join('')}</ul>`;
      })
      .join('')}</div>`;
  }
  // The selected reel as the wide detail (Duo open).
  function reelFocus(id) {
    const col = k.collection(id);
    const games = col.games.map(k.game);
    const withMoments = games.filter((g) => momentsOf(g.id).length).length;
    return `<div class="a-rfocus wide" style="${lit(col.games.slice(0, 3))}">
      <div class="a-rfocus-top">
        <div class="a-rfocus-hero"><div class="a-rfocus-fan">${col.games
          .slice(0, 4)
          .map((gid, n) => `<img style="--n:${n}" src="${k.cover(gid, 'box')}" alt="">`)
          .join('')}</div></div>
        <div class="a-rfocus-t">
          ${kicker('Reel · made by you')}
          <h2 class="a-title-xl">${esc(col.name)}</h2>
          <p class="a-meta">${esc(col.note)} · ${games.length} games · ${withMoments} with moments</p>
          <div class="a-act">${btn('Continue the reel', { icon: 'play', kind: 'primary', cls: 'lg grow' })}${btn('', { icon: 'more', cls: 'round lg', aria: 'Reel options' })}</div>
        </div>
      </div>
      ${kicker('In this reel', '<span>Ordered by last moment</span>')}
      <ul class="a-rfocus-list">${games
        .map((g) => {
          const m = lastMoment(g.id);
          return `<li style="${lit(g.id)}"><img src="${k.cover(g.id, 'box')}" alt=""><span><b>${esc(g.title)}</b><small>${esc(plat(g).short)} · ${m ? `${esc(m.name)} · ${esc(k.ago(m.at))}` : g.installed ? 'On this iPhone' : 'Not downloaded'}</small></span>${m ? sync(m.remote) : g.installed ? '' : i('cloud-download', 16, 'a-chev')}</li>`;
        })
        .join('')}</ul>
    </div>`;
  }
  function collectionsScreen(c) {
    if (reg()) {
      // A real list → detail: the narrow reel list selects a reel; the reel is the wide part.
      return scene(
        k.collection('metroidvania').games.slice(0, 3),
        `${field('f-page f-canvas')}${c.sys.shell({
          tab: 'library',
          title: 'Reels',
          back: 'Library',
          actions: [{ icon: 'plus', label: 'New reel' }],
          body: '',
          split: { primary: reelIndex('metroidvania'), primaryTitle: 'Reels', primaryWidth: 300, secondary: reelFocus('metroidvania') },
        })}`,
        'a-s-reels',
      );
    }
    return scene(
      ['sor2', 'smetroid', 'linksawak'],
      `${field('f-page')}${c.sys.shell({
        tab: 'library',
        title: 'Reels',
        subtitle: 'Collections, played like a sequence',
        back: 'Library',
        actions: [{ icon: 'plus', label: 'New reel' }],
        body: reelsList(),
      })}`,
      'a-s-reels',
    );
  }

  // ================================================================ GAME
  function memories(gid, { vertical = false } = {}) {
    const P = D().progress;
    const save = P.gameSave;
    // One timeline, newest first: the resume point, save states and game-save data interleave by time.
    const items = [
      { kind: 'resume', name: 'Maridia glass tube', at: P.resume.at, device: P.resume.device, remote: P.resume.remote, frame: P.resume.frame },
      { kind: 'save', name: 'In-game save', at: save.at, device: save.device, remote: save.remote },
      ...P.slots.filter((s) => !s.empty).map((s) => ({ kind: 'state', slot: s.n, name: s.name, at: s.at, device: s.device, remote: s.remote, frame: s.frame })),
    ].sort((a, b) => a.at - b.at);
    return `<div class="a-mem${vertical ? ' v' : ''}">
      <i class="a-mem-line" aria-hidden="true"></i>
      ${items
        .map((m) =>
          m.kind === 'save'
            ? `<figure class="a-mem-i save">
          <span class="a-mem-knot" aria-hidden="true"></span>
          <span class="a-mem-t tab">${k.agoShort(m.at)}</span>
          <div class="a-mem-img save">${i('save', 26)}<small>${esc(save.detail)}</small></div>
          <figcaption><small>Game-save data</small><b>${esc(m.name)}</b><span>${esc(k.ago(m.at))} · ${esc(m.device)}</span>${sync(m.remote)}</figcaption>
        </figure>`
            : `<figure class="a-mem-i${m.kind === 'resume' ? ' now' : ''}">
          <span class="a-mem-knot" aria-hidden="true"></span>
          <span class="a-mem-t tab">${esc(m.kind === 'resume' ? 'Now' : k.agoShort(m.at))}</span>
          <div class="a-mem-img"><img class="px" src="${k.frame(gid, m.frame)}" alt=""></div>
          <figcaption><small>${esc(kindLabel(m))}</small><b>${esc(m.name)}</b><span>${esc(k.ago(m.at))} · ${esc(m.device)}</span>${sync(m.remote)}</figcaption>
        </figure>`,
        )
        .join('')}
    </div>`;
  }
  function gameFacts(c, { withActions = true } = {}) {
    const g = k.game('smetroid');
    return `${withActions ? `<div class="a-game-acts">${btn('Start with game save', { icon: 'save', cls: 'md grow' })}${btn('Sync now', { icon: 'sync', cls: 'md grow' })}</div>` : ''}
      ${group('', [
        row({ icon: 'file', tone: 'violet', title: 'Super Metroid (USA).sfc', sub: '3 MB · On this iPhone', acc: '' }),
        row({ icon: 'cpu', tone: 'ember', title: 'Snes9x 1.63', sub: 'Touch controls · Ready', acc: sync('synced', 'Ready') }),
        row({ icon: 'info', tone: 'sky', title: '1994 · Nintendo', sub: 'Action · Exploration · 1 player · Rated 96', acc: '' }),
      ])}
      <p class="a-blurb">${esc(g.blurb)}</p>
      <button class="a-remove">${i('trash', 16)}Remove download<small>Progress and moments are kept</small></button>`;
  }
  function gameScreen(c) {
    const g = k.game('smetroid');
    const status = `<div class="a-game-status">${sync('synced', 'Game-save data synced')}${sync('waiting', 'Resume waiting to sync')}<span class="a-sync t-mute"><i class="a-dot"></i><span>3 save states</span></span></div>`;
    const hero = `<div class="a-game-hero">
      <div class="a-game-art"><img src="${k.cover('smetroid', 'wide')}" alt="Super Metroid key art"></div>
      <div class="a-game-t">
        <p class="a-eyebrow"><b>SNES</b><span>1994 · Nintendo</span></p>
        <h1 class="a-title-xxl">Super Metroid</h1>
        ${status}
      </div>
    </div>`;
    const shellBase = {
      tab: 'library',
      titleMode: 'none',
      back: 'Super Nintendo',
      primary: { icon: 'play', label: 'Resume' },
      actions: [{ icon: 'heart', label: 'Favourite' }, { icon: 'more', label: 'More' }],
      edge: false,
    };
    if (reg()) {
      // Duo open: one composition. The key art runs the full width (under the bar), the
      // title and a glass progress card sit on its lower edge, the memories timeline
      // spans the fold beneath, and facts reflow into columns below that.
      const body = `<div class="a-game open">
        <div class="a-game-art"><img src="${k.cover('smetroid', 'wide')}" alt="Super Metroid key art"></div>
        <div class="a-game-band">
          <div class="a-game-t">
            <p class="a-eyebrow"><b>SNES</b><span>1994 · Nintendo · Snes9x · Touch</span></p>
            <h1 class="a-title-xxl">Super Metroid</h1>
            <p class="a-blurb">${esc(g.blurb)}</p>
            <div class="a-game-acts">${btn('Start with game save', { icon: 'save', cls: 'md' })}${btn('Sync now', { icon: 'sync', cls: 'md' })}</div>
          </div>
          <div class="a-game-prog gl">
            ${kicker('Progress', '<span class="tab">3 kinds · synced separately</span>')}
            ${[
              row({ icon: 'save', tone: 'mint', title: 'Game-save data', sub: 'SRAM · 8 KB · 3 min ago', acc: sync('synced') }),
              row({ icon: 'history', tone: 'amber', title: 'Automatic resume', sub: '1 min ago · This iPhone', acc: sync('waiting') }),
              row({ icon: 'layers', tone: 'sky', title: 'Save states', sub: 'Slots 1–3 · slot 3 uploading', acc: sync('syncing') }),
            ].join('')}
          </div>
        </div>
        <section class="a-game-mem">${kicker('Memories', '<span class="tab">5 · newest first</span>')}${memories('smetroid')}</section>
        <div class="a-game-more">
          ${group('File & core', [
            row({ icon: 'file', tone: 'violet', title: 'Super Metroid (USA).sfc', sub: '3 MB · On this iPhone', acc: '' }),
            row({ icon: 'cpu', tone: 'ember', title: 'Snes9x 1.63', sub: 'Touch controls · Ready', acc: sync('synced', 'Ready') }),
          ])}
          ${group('About', [row({ icon: 'info', tone: 'sky', title: '1994 · Nintendo', sub: 'Action · Exploration · 1 player · Rated 96', acc: '' })])}
          <button class="a-remove">${i('trash', 16)}Remove download<small>Progress and moments are kept</small></button>
        </div>
      </div>`;
      // The key art runs under the vertical bar's status, so the status is light.
      return { html: scene(['smetroid'], `${field('f-room f-canvas', k.cover('smetroid', 'tall'))}${c.sys.shell({ ...shellBase, heroUnderBar: true, body })}`, 'a-s-game'), tone: 'light' };
    }
    const html = scene(
      ['smetroid'],
      `${field('f-room', k.cover('smetroid', 'tall'))}${c.sys.shell({
        ...shellBase,
        heroUnderBar: true,
        body: `<div class="a-game">${hero}
          <section class="a-game-mem">${kicker('Memories', '<span class="tab">5</span>')}${memories('smetroid')}</section>
          ${gameFacts(c)}
        </div>`,
      })}`,
      'a-s-game',
    );
    // The key art under the status bar is dark in both appearances.
    return c.os === 'ios' && !c.device.vbar ? { html, tone: 'light' } : html;
  }

  // ================================================================ DOWNLOADS (Arriving sheet)
  function arrivingBody(c) {
    const dl = D().downloads;
    const run = dl[0];
    const g = k.game(run.game);
    const q = dl.filter((d) => d.state === 'queued');
    const wait = dl.find((d) => d.state === 'waiting');
    const fail = dl.find((d) => d.state === 'failed');
    const done = dl.filter((d) => d.state === 'done');
    const S = D().storage;
    const used = S.roms + S.saves + S.states + S.cache + S.cores + S.bios;
    const line = (d, right, sub) => {
      const x = k.game(d.game);
      return `<li class="a-dl"><img src="${k.cover(d.game, 'box')}" alt=""><span><b>${esc(x.title)}</b><small>${sub}</small></span>${right}</li>`;
    };
    return `<div class="a-arr">
      <div class="a-develop" style="${lit(run.game)};--p:${run.progress * 100}%">
        <div class="a-develop-img">
          <img class="a-develop-frost" src="${k.cover(run.game, 'wide')}" alt="">
          <img class="a-develop-sharp" src="${k.cover(run.game, 'wide')}" alt="${esc(g.title)}">
          <i class="a-develop-edge" aria-hidden="true"></i>
        </div>
        <div class="a-develop-cap">
          <div><p class="a-eyebrow"><b>${esc(plat(g).short)}</b><span>Downloading</span></p><h3>${esc(g.title)}</h3>
          <p class="a-meta tab">${k.size(run.done)} of ${k.size(run.total)} · ${run.rate} MB/s · ${esc(run.eta)} left</p></div>
          <span class="a-develop-pct tab">${k.pct(run.progress)}</span>
        </div>
        <div class="a-bar lg"><i style="width:${run.progress * 100}%"></i></div>
      </div>
      <section>${kicker('Up next', `<span class="tab">${q.length}</span>`)}<ul class="a-card a-dls">${q
        .map((d) => line(d, `<button class="a-mini" aria-label="Reorder">${i('grip', 16)}</button>`, `${esc(plat(d.game).short)} · ${k.size(d.total)}${plat(d.game).tier === 'controller' ? ' · Controller' : ''}`))
        .join('')}</ul></section>
      <section>${kicker('Waiting for Wi-Fi')}<ul class="a-card a-dls">${line(wait, btn('Use cellular', { cls: 'sm' }), `${esc(plat(wait.game).short)} · ${k.size(wait.total)}`)}</ul></section>
      <section>${kicker('Didn’t arrive')}<ul class="a-card a-dls">${line(fail, btn('Retry', { icon: 'restart', cls: 'sm', size: 15 }), `<span class="t-bad-text">${esc(fail.reason)} at ${k.pct(fail.progress)}</span>`)}</ul></section>
      <section>${kicker('Arrived', `<span class="tab">${done.length}</span>`)}<ul class="a-card a-dls">${done
        .map((d) => line(d, `<span class="a-when tab">${esc(k.ago(d.at))}</span>`, `${esc(plat(d.game).short)} · ${k.size(d.total)}`))
        .join('')}</ul></section>
      <section class="a-arr-store">${kicker('On this iPhone', `<span class="tab">${k.size(used)} of ${k.size(S.total)}</span>`)}${storageBar(S, used)}</section>
    </div>`;
  }
  function storageBar(S, used) {
    const parts = [
      ['ROMs', S.roms, 'var(--l1)'],
      ['States', S.states, 'var(--l2)'],
      ['Cache', S.cache, 'var(--ink-3)'],
      ['Saves', S.saves, 'var(--ok)'],
      ['Cores & BIOS', S.cores + S.bios, 'var(--live)'],
    ];
    return `<div class="a-store">
      <div class="a-store-bar" role="img" aria-label="${k.size(used)} used of ${k.size(S.total)}">${parts.map(([, v, col]) => `<i style="flex:${v};background:${col}"></i>`).join('')}<i class="free" style="flex:${S.total - used}"></i></div>
      <ul class="a-store-key">${parts.map(([n, v, col]) => `<li><i style="background:${col}"></i>${n}<b class="tab">${k.size(v)}</b></li>`).join('')}</ul>
    </div>`;
  }
  function downloadsScreen(c) {
    return scene(
      ['ff7'],
      c.sys.sheet({
        detent: pane() ? 'pane' : 'large',
        title: 'Arriving',
        backdrop: (box) => (pane() ? playBackdrop(c, 'smetroid', box) : playScreen(c)),
        body: arrivingBody(c),
      }),
      'a-s-arriving',
    );
  }

  // ================================================================ SEARCH
  function searchScreen(c) {
    const q = 'metroid';
    const smMoments = momentsOf('smetroid').filter((m) => m.kind === 'state');
    const mf = lastMoment('metfusion');
    const sugg = D().search.suggestions;
    const best = `<article class="a-best" style="${lit('smetroid')}">
      <div class="a-best-img">${gs(k.frame('smetroid', 'b'), { alt: 'Super Metroid resume frame' })}</div>
      <div class="a-best-t">
        <p class="a-eyebrow"><b>Best match</b><span>Played 1 min ago</span></p>
        <h3><mark>Super Metroid</mark></h3>
        <p class="a-meta">SNES · Maridia glass tube</p>
        ${btn('Resume', { icon: 'play', kind: 'primary', cls: 'sm' })}
      </div>
    </article>`;
    const gameRow = (id) => {
      const g = k.game(id);
      const title = esc(g.title).replace(/(metroid)/i, '<mark>$1</mark>');
      return `<li class="a-sr"><img src="${k.cover(id, 'box')}" alt=""><span><b>${title}</b><small>${esc(plat(g).short)} · ${g.installed ? 'Ready to play' : 'Not downloaded'} · ${esc(k.tierLabel(plat(g).tier))}</small></span>${btn('Play', { icon: 'play', cls: 'sm', size: 14 })}</li>`;
    };
    const tokens = `<div class="a-tokens">${sugg.map((s, n) => `<button class="a-token${n === 0 ? ' on' : ''}">${n === 0 ? i('check', 13) : ''}${esc(s)}</button>`).join('')}</div>`;
    const rank = `<p class="a-rank">${i('sparkles', 13)}Ranked by your recent moments, then title</p>`;
    const moments = `<section>${kicker('Moments', `<span class="tab">${smMoments.length + 1}</span>`)}
        <div class="a-sm-row">${[...smMoments, mf].map((m) => momentTile(m, { size: 'xs', device: false })).join('')}</div>
      </section>`;
    const games = `<section>${kicker('Games', '<span class="tab">2</span>')}<ul class="a-card a-srs">${gameRow('smetroid')}${gameRow('metfusion')}</ul></section>`;
    const reels = `<section>${kicker('Reels', '<span class="tab">1</span>')}<ul class="a-card a-srs"><li class="a-sr"><span class="a-sr-reel">${k
      .collection('metroidvania')
      .games.slice(0, 3)
      .map((id) => `<img src="${k.cover(id, 'box')}" alt="">`)
      .join('')}</span><span><b><mark>Metroid</mark>vanias</b><small>4 games · made by you</small></span>${i('chevron-right', 16, 'a-chev')}</li></ul></section>`;
    const recent = `<section>${kicker('Recent')}<div class="a-recent">${D()
      .search.recent.map((r) => `<button class="a-token ghost">${i('history', 13)}${esc(r)}</button>`)
      .join('')}</div></section>`;
    if (reg()) {
      // Duo open: one results canvas. The field and tokens run full width, the best match
      // is a wide lit card beside the games, moments form a full-width strip.
      return scene(
        ['smetroid', 'metfusion', 'aria'],
        `${field('f-page f-canvas')}${c.sys.shell({
          tab: 'search',
          titleMode: 'none',
          body: `<div class="a-search open">
            <div class="a-search-field">${c.sys.search({ value: q, focused: true })}</div>
            <div class="a-search-bar">${tokens}${rank}</div>
            <div class="a-search-top">${best}${games}</div>
            ${moments}
            <div class="a-search-foot">${reels}${recent}</div>
          </div>`,
        })}`,
        'a-s-search',
      );
    }
    return scene(
      ['smetroid', 'metfusion', 'aria'],
      `${field('f-page')}${c.sys.shell({
        tab: 'search',
        titleMode: 'none',
        search: { value: q, focused: true },
        body: `<div class="a-search">${tokens}${rank}${best}${moments}${games}${reels}${recent}</div>`,
      })}`,
      'a-s-search',
    );
  }

  // ================================================================ SETTINGS
  function accountCard() {
    const P = D().profiles;
    return `<article class="a-acct gl">
      <div class="a-acct-top">
        <span class="a-acct-av">R</span>
        <span class="a-acct-t"><b>ryan</b><small>Home server · romm.homelab.dev</small></span>
      </div>
      <div class="a-acct-state">${sync('synced', 'Online')}<span class="a-sync t-ok"><i class="a-dot"></i><span>Synced 2 min ago</span></span><span class="a-ver tab">${esc(P[0].version)}</span></div>
      <div class="a-acct-other">
        <span class="a-acct-av sm">S</span>
        <span class="a-acct-t"><b>Sam’s library</b><small>games.samnet.io · offline-ready</small></span>
        ${btn('Switch', { cls: 'sm' })}
      </div>
    </article>`;
  }
  function fleet() {
    return `<div class="a-fleet">${D()
      .fleet.map(
        (f) =>
          `<div class="a-dev gl${f.name === 'This iPhone' ? ' on' : ''}">${i(f.kind === 'tablet' ? 'tablet' : 'smartphone', 18)}<b>${esc(f.name)}</b><small>${f.last ? esc(k.ago(f.last)) : 'Now'}</small>${
            f.pending ? `<span class="a-sync t-wait"><i class="a-dot"></i><span>${f.pending} waiting</span></span>` : '<span class="a-sync t-ok"><i class="a-dot"></i><span>Synced</span></span>'
          }</div>`,
      )
      .join('')}</div>`;
  }
  function settingsSync(c) {
    return group(
      'Sync',
      [
        row({ icon: 'save', tone: 'mint', title: 'Game-save data', sub: 'After every session', acc: c.sys.toggle(true, 'Sync game-save data') }),
        row({ icon: 'history', tone: 'sky', title: 'Resume states', sub: 'When you leave a game', acc: c.sys.toggle(true, 'Sync resume states') }),
        row({ icon: 'signal', tone: 'amber', title: 'Use cellular data', sub: 'Saves only, never downloads', acc: c.sys.toggle(false, 'Use cellular data') }),
      ],
      'Moments travel between This iPhone, Pixel 9 and iPad through your Home server.',
    );
  }
  function settingsPlayer(c) {
    return group('Player', [
      row({ icon: 'gamepad', tone: 'violet', title: 'Controls', sub: 'Touch · DualSense connected' }),
      row({ icon: 'sunrise', tone: 'ember', title: 'Bias lighting', sub: 'The game’s light spills around it', acc: c.sys.toggle(true, 'Bias lighting') }),
    ]);
  }
  function settingsStorage() {
    const S = D().storage;
    const used = S.roms + S.saves + S.states + S.cache + S.cores + S.bios;
    return `<section class="a-group">${`<h3>Storage</h3>`}<div class="a-card a-card-pad"><div class="a-store-h"><b class="tab">${k.size(used)}</b><span>of ${k.size(S.total)} on this iPhone</span></div>${storageBar(S, used)}</div></section>`;
  }
  function settingsCores() {
    return group('Cores & BIOS', [
      row({ icon: 'cpu', tone: 'sky', title: '11 cores', sub: '9 ready · 1 controller-only', value: '' }),
      row({ icon: 'alert', tone: 'amber', title: 'Beetle PSX needs BIOS', sub: 'Add scph5501.bin to play PlayStation' }),
      row({ icon: 'package', tone: 'amber', title: 'Azahar needs setup', sub: 'Bundled — finish setup for 3DS' }),
    ]);
  }
  function settingsAbout() {
    return group('About', [row({ icon: 'info', tone: 'grey', title: 'Rommio', value: '2.0 (412)' }), row({ icon: 'book-open', tone: 'grey', title: 'Licences and acknowledgements' })]);
  }
  function settingsScreen(c) {
    const base = { tab: 'play', title: 'Settings', back: 'Play' };
    if (reg()) {
      // Duo open: one page reflowed into three columns; the identity card spans two.
      const devices = `<section class="a-group"><h3>Devices</h3><div class="a-card">${D()
        .fleet.map((f) =>
          row({
            icon: f.kind === 'tablet' ? 'tablet' : 'smartphone',
            tone: f.name === 'This iPhone' ? '' : 'grey',
            title: esc(f.name),
            sub: f.last ? esc(k.ago(f.last)) : 'Now',
            acc: f.pending ? `<span class="a-sync t-wait"><i class="a-dot"></i><span>${f.pending} waiting</span></span>` : '<span class="a-sync t-ok"><i class="a-dot"></i><span>Synced</span></span>',
          }),
        )
        .join('')}</div></section>`;
      return scene(
        ['smetroid'],
        `${field('f-quiet f-canvas')}${c.sys.shell({
          ...base,
          body: `<div class="a-set open">
            <div class="a-set-acct">${accountCard()}</div>
            ${devices}
            <div class="a-set-col">${settingsSync(c)}</div>
            <div class="a-set-col">${settingsPlayer(c)}${settingsStorage()}</div>
            <div class="a-set-col">${settingsCores()}${settingsAbout()}</div>
          </div>`,
        })}`,
        'a-s-settings',
      );
    }
    return scene(
      ['smetroid'],
      `${field('f-quiet')}${c.sys.shell({
        ...base,
        body: `<div class="a-set">${accountCard()}${fleet()}${settingsSync(c)}${settingsPlayer(c)}${settingsStorage()}${settingsCores()}${settingsAbout()}</div>`,
      })}`,
      'a-s-settings',
    );
  }

  // ================================================================ CONTROLS
  const slider = (label, value, pct, note = '') =>
    `<div class="a-slider"><div class="a-slider-h"><b>${esc(label)}</b><span class="tab">${esc(value)}</span></div><div class="a-track" role="slider" aria-label="${esc(label)}" aria-valuenow="${pct}"><i style="width:${pct}%"></i><b style="left:${pct}%"></b></div>${note ? `<small>${esc(note)}</small>` : ''}</div>`;
  function controlsPreview(c) {
    const src = k.frame('smetroid', 'a');
    if (onDuo(c)) {
      // Duo closed: the preview is the landscape Duo player itself (k.stage), scaled to the
      // column; the live tag takes the player's free notice spot above the game.
      const { st, W, H, lc } = landStage(c);
      const s = (c.sys.layout({ back: true }).width - 40) / W;
      return `<div class="a-cprev stage" style="${lit('smetroid')};height:${Math.round(H * s)}px">
        <div class="a-cprev-scale" style="width:${W}px;height:${H}px;transform:scale(${s.toFixed(4)})">${stageLandscape(lc, st, W, H, { toast: false })}</div>
        <span class="a-cprev-tag st" style="top:${Math.round(st.toast.cy * s)}px">${i('eye', 12)}Live preview · 72%</span>
      </div>`;
    }
    return `<div class="a-cprev" style="${lit('smetroid')}">
      <div class="a-cprev-bias"><img src="${src}" alt=""></div>
      <div class="a-cprev-game"><img class="px" src="${src}" alt="Super Metroid preview"></div>
      <div class="a-cprev-ctl" aria-hidden="true">
        <span class="a-cp-sh l">L</span><span class="a-cp-sh r">R</span>
        <span class="a-cp-dpad"><i></i></span>
        <span class="a-cp-face"><i>X</i><i>Y</i><i>A</i><i>B</i></span>
        <span class="a-cp-pill sel">Select</span><span class="a-cp-pill st">Start</span>
      </div>
      <span class="a-cprev-tag">${i('eye', 12)}Live preview · 72%</span>
    </div>`;
  }
  function controlsSettings(c) {
    const Ct = D().controls;
    return `${`<div class="a-pad-banner gl">${i('gamepad', 20)}<span><b>DualSense Wireless Controller</b><small>Connected · touch controls hide while you use it</small></span></div>`}
      ${group('Touch controls', [
        row({ title: 'Show touch controls', acc: c.sys.toggle(Ct.showTouch, 'Show touch controls') }),
        row({ title: 'Hide with a controller', acc: c.sys.toggle(Ct.autoHideOnController, 'Hide with a controller') }),
        row({ title: 'Left-handed', sub: 'Swap the d-pad and buttons', acc: c.sys.toggle(Ct.leftHanded, 'Left-handed') }),
      ])}
      <section class="a-group"><h3>Tuning</h3><div class="a-card a-card-pad a-sliders">
        ${slider('Opacity', '72%', 72)}
        ${slider('Size', '100%', 50)}
        ${slider('Stick deadzone', '12%', 24)}
      </div></section>
      ${group('Feel', [
        row({ title: 'Haptics', acc: c.sys.toggle(Ct.haptics, 'Haptics') }),
        row({ title: 'OLED black', sub: 'Pure black rails when bias lighting is off', acc: c.sys.toggle(Ct.oled, 'OLED black') }),
        row({ title: 'Console colours', sub: 'Classic face-button colours', acc: c.sys.toggle(Ct.consoleColors, 'Console colours') }),
        row({ title: 'Controller type', value: 'Automatic' }),
      ])}`;
  }
  function controlsScreen(c) {
    const base = { tab: 'play', title: 'Controls', back: 'Settings' };
    if (reg()) {
      // Duo open: the preview IS the open-Duo player — the same k.stage geometry and controls
      // as in game — scaled to the canvas left of the vertical bar and set low, like the
      // player. The settings float in the game's place, so the rails (shoulders, d-pad, face
      // buttons) and the Select · Menu · Start row beneath stay in view while tuning.
      const W = c.w;
      const H = c.h;
      const cw = c.sys.layout({ ...base }).width;
      const s = cw / W;
      const src = k.frame('smetroid', 'a');
      const { st } = landStage(c);
      const Ct = D().controls;
      const tog = (title, on) => `<div class="a-ctlo-tog"><b>${esc(title)}</b>${c.sys.toggle(on, title)}</div>`;
      const ty = Math.round(H - H * s);
      const stage = `<div class="a-pl stage a-ctlo-stage" style="${lit('smetroid')};--gx:${(((st.game.x + st.game.w / 2) * s) / W * 100).toFixed(1)}%;--gy:${(((ty + (st.game.y + st.game.h / 2) * s) / H) * 100).toFixed(1)}%;width:${W}px;height:${H}px">
        ${bias(src, 'st')}
        <div class="a-ctlo-scale" style="width:${W}px;height:${H}px;transform:translateY(${ty}px) scale(${s.toFixed(4)})">${stageLandscape(c, st, W, H, { toast: false, bare: true })}</div>
      </div>`;
      const pw = Math.min(392, Math.round(st.game.w * s) - 40);
      const panel = `<div class="a-ctlo-panel" style="left:${Math.round((st.game.x + st.game.w / 2) * s - pw / 2)}px;width:${pw}px">
        <header class="a-ctlo-h"><h1>Controls</h1><span class="a-ctlo-live">${i('eye', 12)}Live · 72%</span></header>
        <p class="a-ctlo-pad">${i('gamepad', 16)}<span><b>DualSense connected</b> · hides touch controls</span></p>
        <div class="a-card a-card-pad a-sliders">${slider('Opacity', '72%', 72)}${slider('Size', '100%', 50)}${slider('Stick deadzone', '12%', 24)}</div>
        <div class="a-card">
          ${row({ title: 'Show touch controls', acc: c.sys.toggle(Ct.showTouch, 'Show touch controls') })}
          ${row({ title: 'Hide with a controller', acc: c.sys.toggle(Ct.autoHideOnController, 'Hide with a controller') })}
          ${row({ title: 'Left-handed', sub: 'Swap the d-pad and buttons', acc: c.sys.toggle(Ct.leftHanded, 'Left-handed') })}
        </div>
        <div class="a-card a-ctlo-grid">${tog('Haptics', Ct.haptics)}${tog('OLED black', Ct.oled)}${tog('Console colours', Ct.consoleColors)}<div class="a-ctlo-tog"><b>Controller</b><span class="a-row-val">Automatic</span></div></div>
      </div>`;
      return scene(
        ['smetroid'],
        `${field('f-quiet f-canvas')}${c.sys.shell({ ...base, titleMode: 'none', heroUnderBar: true, edge: false, body: `<div class="a-ctlo">${stage}${panel}</div>` })}`,
        'a-s-controls',
      );
    }
    return scene(
      ['smetroid'],
      `${field('f-quiet')}${c.sys.shell({ ...base, body: `<div class="a-set">${controlsPreview(c)}${controlsSettings(c)}</div>` })}`,
      'a-s-controls',
    );
  }

  // ================================================================ ONBOARDING
  function welcomeScreen(c) {
    // Duo open: five remembered scenes fan across the fold above one centred promise.
    const stack = reg()
      ? [
          ['metfusion', 'll'],
          ['earthbound', 'rr'],
          ['chrono', 'l'],
          ['linksawak', 'r'],
          ['smetroid', 'c'],
        ]
      : [
          ['chrono', 'l'],
          ['linksawak', 'r'],
          ['smetroid', 'c'],
        ];
    return scene(
      ['smetroid', 'linksawak', 'chrono'],
      `${field('f-room f-welcome' + (reg() ? ' f-canvas' : ''))}${c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        edge: false,
        body: `<div class="a-welcome${reg() ? ' open' : ''}">
          <div class="a-wl-stage" aria-hidden="true">${stack
            .map(([id, pos]) => `<figure class="a-wl-card ${pos}" style="${lit(id)}"><img class="a-wl-glow" src="${k.cover(id, 'box')}" alt=""><img class="a-wl-img" src="${k.cover(id, 'box')}" alt=""></figure>`)
            .join('')}</div>
          <div class="a-wl-body">
            <p class="a-brand"><span class="a-mark" aria-hidden="true"><i></i></span>rommio</p>
            <h1 class="a-title-xxl">Every game on your server, right where you left it.</h1>
            <p class="a-lede">Browse your RomM library, play offline, and pick up on any device.</p>
            <div class="a-stack">${btn('Connect to your server', { kind: 'primary', cls: 'lg wide' })}${btn('Scan setup code', { icon: 'qr', cls: 'lg wide' })}</div>
            <p class="a-fine">Rommio doesn’t include any games. It plays the library on a RomM server you run.</p>
          </div>
        </div>`,
      })}`,
      'a-s-welcome',
    );
  }
  function connectScreen(c) {
    const S = D().server.discovered;
    const methods = ['Automatic', 'Direct', 'Cloudflare Access', 'Service token'];
    const input = `<div class="a-field-in gl">
            <label class="a-in">${i('globe', 20)}<span><small>Server address</small><input type="url" value="romm.homelab.dev" spellcheck="false" aria-label="Server address"></span><button class="a-in-x" aria-label="Clear">${i('x', 14)}</button></label>
            <div class="a-check ok" role="status"><span class="a-check-ic">${i('check', 14)}</span><span><b>RomM 5.2.0</b><small>Protected by Cloudflare Access · reachable in 84 ms</small></span></div>
          </div>`;
    const cont = btn('Continue', { kind: 'primary', cls: 'lg wide' });
    const nearby = `${kicker('Saved and nearby')}
          <div class="a-card">
            ${row({ icon: 'server', tone: 'violet', title: esc(S[0].name), sub: esc(S[0].note), acc: `<span class="a-tag ok">${i('lock', 12)}HTTPS</span>` })}
            ${row({ icon: 'wifi', tone: 'grey', title: esc(S[1].name), sub: esc(S[1].note), acc: `<span class="a-tag warn">${i('alert', 12)}Not encrypted</span>` })}
          </div>`;
    const access = `${kicker('Access method')}
          <div class="a-methods" role="radiogroup" aria-label="Access method">${methods
            .map((m, n) => `<button role="radio" aria-checked="${n === 0}" class="a-method${n === 0 ? ' on' : ''}"><span class="a-radio"></span><b>${esc(m)}</b>${n === 0 ? '<small>Uses Cloudflare Access</small>' : ''}</button>`)
            .join('')}</div>`;
    const scan = `<p class="a-fine">Have a setup code from RomM? <button class="a-link">${i('qr', 14)}Scan it</button></p>`;
    const base = { tabBar: false, back: 'Welcome' };
    if (reg()) {
      // Duo open: one canvas. Address and Continue on one line, the lit route to the
      // server as a band across the fold, then access method and saved servers.
      return scene(
        ['metfusion', 'linksawak'],
        `${field('f-page f-canvas')}${c.sys.shell({
          ...base,
          title: 'Connect',
          subtitle: 'Step 1 of 2 · the address you open RomM with in a browser',
          body: `<div class="a-onb open">
            <div class="a-conn-grid">
              <div class="a-conn-main">${input}
                <section class="a-route gl" aria-label="How Rommio reaches your server">
                  ${kicker('Live check', '<span class="tab">Checked 2 s ago</span>')}
                  <ol>
                    <li><span>${i('smartphone', 20)}</span><b>This iPhone</b><small>Home Wi-Fi</small></li>
                    <li><span>${i('shield', 20)}</span><b>Cloudflare Access</b><small>Verified</small></li>
                    <li><span>${i('server', 20)}</span><b>RomM 5.2.0</b><small class="tab">84 ms</small></li>
                  </ol>
                </section>
              </div>
              <div class="a-conn-side">${cont}${scan}${nearby}</div>
            </div>
            <div class="a-conn-access">${access}<p class="a-connect-note">Automatic uses Cloudflare Access when you are away and a direct connection on your home network. Choose Service token for a headless setup.</p></div>
          </div>`,
        })}`,
        'a-s-connect',
      );
    }
    return scene(
      ['metfusion', 'linksawak'],
      `${field('f-page')}${c.sys.shell({
        ...base,
        title: 'Connect',
        subtitle: 'Step 1 of 2 · your RomM server',
        body: `<div class="a-onb">${input}${cont}${nearby}${access}${scan}</div>`,
      })}`,
      'a-s-connect',
    );
  }
  function signinScreen(c) {
    const server = `<div class="a-server gl"><span class="a-server-ic">${i('server', 20)}</span><span><b>romm.homelab.dev</b><small>${i('shield', 12)}RomM 5.2.0 · access verified</small></span>${btn('Change', { cls: 'sm' })}</div>`;
    const form = `<div class="a-field-in gl">
            <label class="a-in">${i('user', 20)}<span><small>Username</small><input type="text" value="ryan" aria-label="Username"></span></label>
            <label class="a-in focus">${i('key', 20)}<span><small>Password</small><input type="password" value="correcthorse" aria-label="Password"></span><button class="a-in-x" aria-label="Show password">${i('eye', 16)}</button></label>
          </div>
          ${btn('Sign in', { kind: 'primary', cls: 'lg wide' })}
          <div class="a-or"><span>or</span></div>
          ${btn('Continue with single sign-on', { icon: 'key', cls: 'lg wide' })}
          <p class="a-fine">Your password goes only to romm.homelab.dev.</p>`;
    const base = { tabBar: false, back: 'Connect' };
    if (reg()) {
      // Duo open: the verified server is a lit band across the canvas; the form is one
      // centred column beneath it, straddling the crease.
      const band = `<div class="a-srv-band">
          <div class="a-srv-orb" aria-hidden="true">${i('server', 38)}</div>
          <div class="a-srv-t">
            <h2 class="a-title-xl">romm.homelab.dev</h2>
            <div class="a-srv-facts">${sync('synced', 'Reachable · 84 ms')}<span class="a-tag">${i('shield', 12)}Cloudflare Access verified</span><span class="a-tag">RomM 5.2.0</span></div>
          </div>
          ${btn('Change server', { icon: 'swap', cls: 'md' })}
        </div>`;
      return scene(
        ['chrono', 'metfusion'],
        `${field('f-page f-canvas')}${c.sys.shell({
          ...base,
          title: 'Sign in',
          subtitle: 'Step 2 of 2 · Rommio keeps you signed in on this iPhone only',
          body: `<div class="a-onb open signin">${band}<div class="a-signin-form">${form}</div></div>`,
        })}`,
        'a-s-signin',
      );
    }
    return scene(
      ['chrono', 'metfusion'],
      `${field('f-page')}${c.sys.shell({
        ...base,
        title: 'Sign in',
        subtitle: 'Step 2 of 2',
        body: `<div class="a-onb">${server}${form}</div>`,
      })}`,
      'a-s-signin',
    );
  }
  function hydrateScreen(c) {
    const wall = ['smetroid', 'linksawak', 'chrono', 'sotn', 'earthbound', 'sonic2', 'metfusion', 'pkcrystal', 'sor2', 'mm2', 'ff6', 'kirbyadv', 'oot3d', 'jetset', 'aria', 'alttp', 'tetris', 'sm64', 'pkblack', 'mmx', 'sf2', 'galaga', 'yoshi', 'twewy'];
    const ready = new Set([0, 1, 2, 3, 4, 5, 7, 9, 12, 14, 16, 18, 21]);
    const t = k.totals();
    const wallHTML = `<div class="a-hyd-wall" aria-hidden="true">${(reg() ? wall.concat(wall.slice(0, 6)) : wall)
      .slice(0, reg() ? 30 : 18)
      .map((id, n) => `<figure class="${ready.has(n) ? 'on' : ''}" style="${lit(id)}"><img src="${k.cover(id, 'box')}" alt=""></figure>`)
      .join('')}</div>`;
    const card = `<div class="a-hyd-card gl">
              <div class="a-hyd-big"><b class="tab">61<small>%</small></b><span>Artwork kept for offline</span></div>
              <div class="a-bar lg"><i style="width:61%"></i></div>
              <ul class="a-hyd-list">
                <li>${i('check', 14)}<b>Platforms</b><span class="tab">${t.platforms}</span></li>
                <li>${i('check', 14)}<b>Games</b><span class="tab">${k.num(t.games)}</span></li>
                <li>${i('check', 14)}<b>Collections</b><span>Done</span></li>
              </ul>
            </div>`;
    const rest = `<p class="a-hyd-sync">${i('sync', 16)}<span><b>Your moments follow you.</b> Game saves and resume states sync between your devices through your server.</span></p>
            <div class="a-card">
              ${row({ title: 'Keep artwork offline', sub: 'About 640 MB', acc: c.sys.toggle(true, 'Keep artwork offline') })}
              ${row({ title: 'Use cellular data', sub: 'Saves only', acc: c.sys.toggle(false, 'Use cellular data') })}
            </div>
            ${btn('Start playing', { icon: 'play', kind: 'primary', cls: 'lg wide' })}
            <p class="a-fine">Artwork keeps arriving in the background.</p>`;
    const head = `${kicker('Home server · first sync')}<h1 class="a-title-xl">Your library is developing</h1>`;
    // Duo open: the cover wall develops across the whole canvas; the headline sits on
    // its fading edge and the progress, choices and Start playing read as one row.
    const open = () => `<div class="a-hyd open">${wallHTML}
        <div class="a-hyd-head">${head}<p class="a-hyd-sync">${i('sync', 16)}<span><b>Your moments follow you.</b> Game saves and resume states sync between your devices through your server.</span></p></div>
        <div class="a-hyd-row">
          ${card}
          <div class="a-hyd-opts">
            <div class="a-card">
              ${row({ title: 'Keep artwork offline', sub: 'About 640 MB', acc: c.sys.toggle(true, 'Keep artwork offline') })}
              ${row({ title: 'Use cellular data', sub: 'Saves only', acc: c.sys.toggle(false, 'Use cellular data') })}
            </div>
          </div>
          <div class="a-hyd-go">${btn('Start playing', { icon: 'play', kind: 'primary', cls: 'lg wide' })}<p class="a-fine">Artwork keeps arriving in the background.</p></div>
        </div>
      </div>`;
    return scene(
      ['chrono', 'linksawak', 'smetroid'],
      `${field('f-page' + (reg() ? ' f-canvas' : ''))}${c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        edge: false,
        body: reg() ? open() : `<div class="a-hyd">${wallHTML}<div class="a-hyd-body">${head}${card}${rest}</div></div>`,
      })}`,
      'a-s-hydrate',
    );
  }

  // ================================================================ PLAYER
  const ctl = {
    dpad: (x, y, s) =>
      `<div class="a-ctl a-dpad" style="left:${x - s / 2}px;top:${y - s / 2}px;--s:${s}px" role="group" aria-label="D-pad"><i class="a-dpad-x" aria-hidden="true"></i>${['up', 'down', 'left', 'right']
        .map((d) => `<button class="a-dp ${d}" aria-label="${d}">${i('chevron-' + d, Math.round(s * 0.14))}</button>`)
        .join('')}</div>`,
    face: (x, y, s, set = 'diamond') => {
      const b = Math.round(s * 0.37);
      const labels = set === 'duo' ? ['B', 'A'] : ['X', 'Y', 'A', 'B'];
      return `<div class="a-face ${set}" style="left:${x - s / 2}px;top:${y - s / 2}px;--s:${s}px;--b:${b}px" role="group" aria-label="Face buttons">${labels.map((l) => `<button class="a-ctl a-fb fb-${l.toLowerCase()}" aria-label="${l}">${l}</button>`).join('')}</div>`;
    },
    pill: (x, y, w, label, cls = '') => `<button class="a-ctl a-pill ${cls}" style="left:${x - w / 2}px;top:${y - 17}px;width:${w}px">${esc(label)}</button>`,
    sh: (x, y, w, label) => `<button class="a-ctl a-sh" style="left:${x - w / 2}px;top:${y}px;width:${w}px" aria-label="${label} shoulder">${label}</button>`,
    menu: (x, y) => `<button class="a-ctl a-menu" style="left:${x - 22}px;top:${y - 22}px" aria-label="Menu">${i('menu', 18)}</button>`,
  };
  const bias = (src, cls = '') => `<div class="a-bias ${cls}" aria-hidden="true"><img src="${src}" alt=""><i></i></div>`;
  // Two sources (DS on the Duo): the top screen lights the upper display, the touch
  // screen the lower, blended into one field so the fold never reads as a band.
  const bias2 = (a, b) => `<div class="a-bias two" aria-hidden="true"><img src="${a}" alt=""><img src="${b}" alt=""><i></i></div>`;

  // ---------------- iPhone Duo: the stage
  // Every Duo player surface — in game, the Pause/Save states backdrops and the Controls
  // preview — takes its geometry from k.stage: the game shrinks to leave rails, the
  // clusters sit low where thumbs rest, and nothing is ever drawn over a game screen. The
  // game's light fills the rails (the blurred frame, centred on the game, plus a pool of its
  // key colour under each cluster) and the frosted controls sit in that light. Light is
  // built from gradients, never large blurred shadows.
  const onDuo = (c) => !!(c && c.device.duo);
  const at = (r) => `left:${r.x}px;top:${r.y}px;width:${r.w}px;height:${r.h}px`;
  // Centre of the lit screens, as a percentage of the surface — the bias veil opens there.
  const litAt = (rs, W, H) => {
    const x0 = Math.min(...rs.map((r) => r.x));
    const y0 = Math.min(...rs.map((r) => r.y));
    const x1 = Math.max(...rs.map((r) => r.x + r.w));
    const y1 = Math.max(...rs.map((r) => r.y + r.h));
    return `--gx:${(((x0 + x1) / 2 / W) * 100).toFixed(1)}%;--gy:${(((y0 + y1) / 2 / H) * 100).toFixed(1)}%`;
  };
  const st$ = {
    screen: (r, src, alt, cls = '') =>
      `<i class="a-drop" style="left:${r.x}px;top:${r.y + r.h}px;width:${r.w}px" aria-hidden="true"></i><div class="a-vp st ${cls}" style="${at(r)}"><img class="px" src="${src}" alt="${esc(alt)}"></div>`,
    pool: (p) => {
      const d = Math.round(p.size * 2);
      return `<i class="a-pool" style="left:${Math.round(p.cx - d / 2)}px;top:${Math.round(p.cy - d / 2)}px;width:${d}px;height:${d}px" aria-hidden="true"></i>`;
    },
    sh: (r, label) => `<button class="a-ctl a-sh" style="${at(r)}" aria-label="${label} shoulder">${label}</button>`,
    // Select · Menu · Start as one centred row inside the tertiary band.
    row: (t, big) => {
      const m = Math.min(44, t.h);
      const gap = big ? 16 : 10;
      const pw = Math.min(big ? 96 : 78, Math.floor((t.w - m - 2 * gap) / 2));
      const ph = big ? 38 : 34;
      const x0 = Math.round(t.cx - (2 * pw + m + 2 * gap) / 2);
      const y = Math.round(t.cy - ph / 2);
      return (
        `<button class="a-ctl a-pill" style="left:${x0}px;top:${y}px;width:${pw}px;height:${ph}px">Select</button>` +
        `<button class="a-ctl a-menu" style="left:${x0 + pw + gap}px;top:${Math.round(t.cy - m / 2)}px;width:${m}px;height:${m}px" aria-label="Menu">${i('menu', 18)}</button>` +
        `<button class="a-ctl a-pill" style="left:${x0 + pw + gap + m + gap}px;top:${y}px;width:${pw}px;height:${ph}px">Start</button>`
      );
    },
    toast: (t) => `<div class="a-toast st" role="status" style="left:${Math.round(t.cx)}px;top:${Math.round(t.cy)}px">${i('check', 14)}<span>Automatic resume updated</span></div>`,
  };
  // The landscape Duo player (Super Metroid) on a W×H surface. `paused` keeps only the lit
  // game (sheet backdrops); `bare` leaves the light to an outer layer (scaled previews).
  function stageLandscape(c, st, W, H, { toast = true, paused = false, bare = false } = {}) {
    const id = 'smetroid';
    const src = k.frame(id, 'a');
    const big = c.device.id === 'duo-open';
    const controls = paused
      ? ''
      : `${st$.sh(st.l, 'L')}${st$.sh(st.r, 'R')}${ctl.dpad(st.dpad.cx, st.dpad.cy, st.size)}${ctl.face(st.face.cx, st.face.cy, st.size)}${st$.row(st.tertiary, big)}`;
    return `<div class="a-pl stage${big ? ' big' : ''}${paused ? ' paused' : ''}${bare ? ' bare' : ''}" style="${lit(id)};${litAt([st.game], W, H)};width:${W}px;height:${H}px">
      ${bare ? '' : bias(src, 'st')}
      ${paused ? '' : st$.pool(st.dpad) + st$.pool(st.face)}
      ${st$.screen(st.game, src, paused ? 'Super Metroid, paused' : 'Super Metroid gameplay')}
      ${controls}
      ${toast && !paused ? st$.toast(st.toast) : ''}
    </div>`;
  }
  // The landscape stage for a Duo screen of any kind: the player itself, or an app screen
  // (Controls) that previews the player at the device's landscape size.
  function landStage(c) {
    const aspect = k.aspect('smetroid');
    if (c.w > c.h) return { st: k.stage(c, { aspect }), W: c.w, H: c.h, lc: c };
    const L = window.Studio.DEVICES[c.device.id].landscape;
    const lc = { ...c, w: L.w, h: L.h, safe: L.safe };
    return { st: k.stage(lc, { aspect }), W: L.w, H: L.h, lc };
  }

  // ---------------- phones (iPhone, Android): the game fills the height at 4:3 between rails
  function playerLandscape(c, { toast = true, paused = false } = {}) {
    if (onDuo(c)) return stageLandscape(c, k.stage(c, { aspect: k.aspect('smetroid') }), c.w, c.h, { toast, paused });
    const id = 'smetroid';
    const src = k.frame(id, 'a');
    const gh = c.h - 20;
    const gw = Math.round(gh * (4 / 3));
    const g = { x: Math.round((c.w - gw) / 2), y: 10, w: gw, h: gh };
    const rail = (c.w - gw) / 2;
    const ds = 118;
    const fs = 126;
    let controls = '';
    if (!paused) {
      const lx = Math.max(rail / 2 + 6, (c.safe.left || 0) + ds / 2 + 4);
      const rx = c.w - lx;
      const cy = Math.round(c.h * 0.6);
      controls = `${ctl.sh(lx, 14, Math.min(104, rail + 20), 'L')}${ctl.sh(rx, 14, Math.min(104, rail + 20), 'R')}
          ${ctl.dpad(lx, cy, ds)}${ctl.face(rx, cy, fs)}
          ${ctl.pill(lx - 18, c.h - 34, 64, 'Select')}${ctl.menu(lx + 38, c.h - 34)}${ctl.pill(rx, c.h - 34, 64, 'Start')}`;
    }
    return `<div class="a-pl${paused ? ' paused' : ''}" style="${lit(id)}">
      ${bias(src)}
      <div class="a-vp" style="left:${g.x}px;top:${g.y}px;width:${g.w}px;height:${g.h}px"><img class="px" src="${src}" alt="Super Metroid gameplay"></div>
      ${controls}
      ${toast && !paused ? `<div class="a-toast" role="status" style="left:${g.x + g.w / 2}px;top:${g.y + 12}px">${i('check', 14)}<span>Automatic resume updated</span></div>` : ''}
    </div>`;
  }

  function playerPortrait(c) {
    const id = 'linksawak';
    const src = k.frame(id, 'a');
    const W = c.w;
    const H = c.h;
    const m = lastMoment(id);
    if (onDuo(c)) {
      // Duo: the game full width at the top (it crosses the fold when open), the controls in
      // the lit band beneath it, low. Game Boy Color has no shoulders, so the band holds the
      // d-pad, B/A and Select · Menu · Start; on the open Duo the moment's name sits between
      // the clusters.
      const st = k.stage(c, { aspect: k.aspect(id) });
      const big = c.device.id === 'duo-open';
      // Closed, the notice spot sits just above the clusters: the d-pad disc is drawn a touch
      // inside its size so the two never kiss.
      const ds = big ? st.size : Math.round(st.size * 0.94);
      const mid = (st.dpad.cx + st.face.cx) / 2;
      const info = big
        ? `<div class="a-pl-info center st" style="left:${Math.round(mid - 70)}px;top:${Math.round(st.dpad.cy - 26)}px;width:140px"><b>${esc(m.name)}</b><span class="tab">25 min</span></div>`
        : '';
      return `<div class="a-pl portrait stage${big ? ' big' : ''}" style="${lit(id)};${litAt([st.game], W, H)}">
        ${bias(src, 'full st')}
        ${st$.pool(st.dpad)}${st$.pool(st.face)}
        ${st$.screen(st.game, src, "Link's Awakening DX gameplay")}
        ${info}
        ${ctl.dpad(st.dpad.cx, st.dpad.cy, ds)}${ctl.face(st.face.cx, st.face.cy, st.size, 'duo')}
        ${st$.row(st.tertiary, big)}
        ${st$.toast(st.toast)}
      </div>`;
    }
    const gw = W - 28;
    const gy = c.safe.top + 8;
    const gh = Math.round(gw * (144 / 160));
    const gx = Math.round((W - gw) / 2);
    const zoneTop = gy + gh + 14 + 40;
    const zoneH = H - zoneTop - (c.safe.bottom || 20);
    const cy = zoneTop + Math.round(zoneH * 0.36);
    const ds = 144;
    const fs = 150;
    const lx = Math.round(ds / 2 + 26);
    const rx = W - lx;
    const py = zoneTop + zoneH - 34;
    return `<div class="a-pl portrait" style="${lit(id)}">
      ${bias(src, 'port')}
      <div class="a-vp" style="left:${gx}px;top:${gy}px;width:${gw}px;height:${gh}px"><img class="px" src="${src}" alt="Link's Awakening DX gameplay"></div>
      <div class="a-pl-info" style="left:${gx + 4}px;top:${gy + gh + 16}px;width:${gw - 8}px"><b>${esc(m.name)}</b><span class="tab">Link’s Awakening DX · 25 min</span></div>
      ${ctl.dpad(lx, cy, ds)}${ctl.face(rx, cy, fs, 'duo')}
      ${ctl.pill(W / 2 - 50, py, 76, 'Select')}${ctl.pill(W / 2 + 50, py, 76, 'Start')}${ctl.menu(W / 2, py - 58)}
    </div>`;
  }

  function dualScreen(c) {
    const id = 'pkblack';
    const d = k.dual(id, 'a');
    const W = c.w;
    const H = c.h;
    if (onDuo(c)) {
      // Duo: the screens stacked (the top screen above the fold when open), the controls in a
      // lit band at the bottom. Nothing touches either screen, so the whole touch screen stays
      // free for the stylus; its label sits in the rail beside it.
      const st = k.stage(c, { dual: true });
      const big = c.device.id === 'duo-open';
      const t = st.touch;
      return `<div class="a-pl portrait dual stage${big ? ' big' : ''}" style="${lit(id)};${litAt([st.top, t], W, H)}">
        ${bias2(d.top, d.bottom)}
        ${st$.pool(st.dpad)}${st$.pool(st.face)}
        ${st$.screen(st.top, d.top, 'Pokémon Black top screen', 'ds')}${st$.screen(t, d.bottom, 'Pokémon Black touch screen', 'ds touch')}
        <span class="a-stylus side" style="left:${t.x + t.w + 10}px;top:${t.y + 4}px">${i('pointer', 12)}Touch</span>
        ${st$.sh(st.l, 'L')}${st$.sh(st.r, 'R')}
        ${ctl.dpad(st.dpad.cx, st.dpad.cy, st.size)}${ctl.face(st.face.cx, st.face.cy, st.size)}
        ${st$.row(st.tertiary, big)}
        ${st$.toast(st.toast)}
      </div>`;
    }
    const sw = W - 32;
    const sh = Math.round(sw * 0.75);
    const y0 = c.safe.top + 4;
    const top = { x: (W - sw) / 2, y: y0, w: sw, h: sh };
    const bot = { x: (W - sw) / 2, y: y0 + sh + 6, w: sw, h: sh };
    const zt = bot.y + sh + 10;
    const zh = H - zt - (c.safe.bottom || 20);
    const ds = 132;
    const cy = zt + Math.round(zh * 0.5) + 6;
    const lx = ds / 2 + 22;
    const controls = `${ctl.sh(62, zt + 6, 100, 'L')}${ctl.sh(W - 62, zt + 6, 100, 'R')}${ctl.pill(W / 2 - 38, zt + 26, 64, 'Select')}${ctl.pill(W / 2 + 38, zt + 26, 64, 'Start')}${ctl.dpad(lx, cy + 22, ds)}${ctl.face(W - lx, cy + 22, ds + 6)}${ctl.menu(W / 2, cy + 22)}`;
    const scr = (b, src, alt, cls = '') => `<div class="a-vp ds ${cls}" style="left:${b.x}px;top:${b.y}px;width:${b.w}px;height:${b.h}px"><img class="px" src="${src}" alt="${alt}"></div>`;
    return `<div class="a-pl portrait dual" style="${lit(id)}">
      ${bias(d.top, 'port')}
      ${scr(top, d.top, 'Pokémon Black top screen')}${scr(bot, d.bottom, 'Pokémon Black touch screen', 'touch')}
      <span class="a-stylus" style="left:${bot.x + bot.w - 12}px;top:${bot.y + 10}px">${i('pointer', 12)}Touch</span>
      ${controls}
    </div>`;
  }

  // ---------------- Pause & Save states (sheets over the paused game)
  function pausedBackdrop(c, box) {
    // Duo: the frozen game sits exactly where the player draws it (k.stage), lit, under the
    // floating inspector (open) or the sheet (closed).
    if (onDuo(c)) return stageLandscape(c, k.stage({ ...c, w: box.w, h: box.h }, { aspect: k.aspect('smetroid') }), box.w, box.h, { paused: true });
    const src = k.frame('smetroid', 'a');
    const gh = Math.min(box.h - 40, Math.round((box.w - 40) * 0.75));
    const gw = Math.round(gh * (4 / 3));
    const gx = (box.w - gw) / 2;
    const gy = (box.h - gh) / 2;
    return `<div class="a-pl paused" style="${lit('smetroid')};width:${box.w}px;height:${box.h}px">${bias(src)}<div class="a-vp" style="left:${gx}px;top:${gy}px;width:${gw}px;height:${gh}px"><img class="px" src="${src}" alt="Super Metroid, paused"></div></div>`;
  }
  function pauseBody(c) {
    const isPane = pane();
    const P = D().progress;
    const tile = (icon, label, sub, cls = '') => `<button class="a-ptile ${cls}">${i(icon, 20)}<b>${esc(label)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</button>`;
    return `<div class="a-pause${isPane ? ' pane' : ''}">
      <div class="a-now">
        <div class="a-now-img"><img class="px" src="${k.frame('smetroid', 'a')}" alt=""></div>
        <div class="a-now-t">
          <small>Now · session ${P.session} min</small>
          <b>Maridia glass tube</b>
          <span class="a-now-sync">${sync('synced', 'Game-save data synced')}${sync('waiting', 'Resume waiting to sync')}</span>
        </div>
      </div>
      <div class="a-ptiles">
        ${tile('camera', 'Save state', 'Slot 4 is free', 'hero')}
        ${tile('layers', 'Load state', '3 states')}
        ${tile('gamepad', 'Controls', 'Touch · 72%')}
        ${tile('fast-forward', 'Fast-forward', '2×')}
        ${tile('restart', 'Reset', '')}
        ${tile('exit', 'Leave game', 'Resume is kept', 'leave')}
      </div>
      ${
        isPane
          ? `${kicker('Jump to a moment', '<span>Load state</span>')}<div class="a-jump">${P.slots
              .filter((s) => !s.empty)
              .map((s) => `<figure><img class="px" src="${k.frame('smetroid', s.frame)}" alt=""><figcaption><b>${esc(s.name)}</b><span class="tab">Slot ${s.n} · ${esc(k.agoShort(s.at))}</span></figcaption></figure>`)
              .join('')}</div>`
          : ''
      }
      <p class="a-foot">Leave game updates the automatic resume on This iPhone, then syncs it to Pixel 9 and iPad.</p>
    </div>`;
  }
  function pauseScreen(c) {
    return scene(
      ['smetroid'],
      c.sys.sheet({
        detent: pane() ? 'pane' : 'large',
        title: 'Paused',
        done: { label: 'Resume', icon: 'play' },
        backdrop: (box) => pausedBackdrop(c, box),
        body: pauseBody(c),
      }),
      'a-s-pause',
    );
  }
  function statesBody(c) {
    const P = D().progress;
    const isPane = pane();
    const slots = P.slots
      .map((s) =>
        s.empty
          ? `<figure class="a-slot empty"><div class="a-slot-img">${i('camera', 20)}<span>Save state here</span></div><figcaption><b>Slot ${s.n}</b><small>Empty</small></figcaption></figure>`
          : `<figure class="a-slot"><div class="a-slot-img"><img class="px" src="${k.frame('smetroid', s.frame)}" alt=""><span class="a-slot-n tab">${s.n}</span></div><figcaption><b>${esc(s.name)}</b><small>${esc(k.ago(s.at))} · ${esc(s.device)}</small>${sync(s.remote)}</figcaption></figure>`,
      )
      .join('');
    const hist = P.history
      .map((h) => `<li><span class="a-h-kind">${esc(h.kind)}</span><span class="a-h-meta">${esc(k.ago(h.at))} · ${esc(h.device)} · ${esc(h.reason)}</span></li>`)
      .join('');
    return `<div class="a-states${isPane ? ' pane' : ''}">
      <div class="a-pair">
        <article class="a-keep resume">
          <div class="a-keep-img"><img class="px" src="${k.frame('smetroid', P.resume.frame)}" alt=""></div>
          <div class="a-keep-t"><small>Automatic resume</small><b>Maridia glass tube</b><span>${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}</span>${sync(P.resume.remote)}</div>
          ${btn('Resume', { icon: 'play', kind: 'primary', cls: 'sm' })}
        </article>
        <article class="a-keep save">
          <div class="a-keep-img save">${i('save', 22)}</div>
          <div class="a-keep-t"><small>Game-save data</small><b>${esc(P.gameSave.detail)}</b><span>${esc(k.ago(P.gameSave.at))} · ${esc(P.gameSave.device)}</span>${sync(P.gameSave.remote)}</div>
          ${btn('Start with game save', { cls: 'sm' })}
        </article>
      </div>
      ${kicker('Save states', isPane ? '' : `<span class="a-hlinks"><button class="a-link">${i('history', 13)}State history</button><button class="a-link">${i('save', 13)}Game-save history</button></span>`)}
      <div class="a-slots">${slots}</div>
      ${isPane ? `${kicker('Recovery history')}<div class="a-hist-seg">${c.sys.segmented(['Resume & states', 'Game-save data'], 0)}</div><ul class="a-hist">${hist}</ul>` : ''}
    </div>`;
  }
  function statesScreen(c) {
    return scene(
      ['smetroid'],
      c.sys.sheet({
        detent: pane() ? 'pane' : 'large',
        title: 'Save states',
        backdrop: (box) => pausedBackdrop(c, box),
        body: statesBody(c),
      }),
      'a-s-states',
    );
  }

  // ---------------- Conflict
  function conflictBody(c) {
    const cf = D().conflict;
    const [a, b] = cf.branches;
    const side = (x, on) => `<figure class="a-choice${on ? ' on' : ''}" role="radio" aria-checked="${on}">
      <div class="a-choice-img">${gs(k.frame('earthbound', x.frame), { alt: `EarthBound at ${x.where}` })}</div>
      <figcaption>
        <span class="a-radio${on ? ' on' : ''}" aria-hidden="true"></span>
        <b>${esc(x.where)}</b>
        <span>${i('smartphone', 13)}${esc(x.device)} · ${esc(k.ago(x.at))}</span>
        <span class="tab">${esc(x.playtime)} played</span>
      </figcaption>
    </figure>`;
    // Where the two branches split, from the play sessions.
    const S = D().sessions.filter((s) => s.game === 'earthbound');
    const root = S.find((s) => s.day > 0);
    const branch = (s, on) => {
      const br = cf.branches.find((x) => x.device === s.device);
      return `<li class="${on ? 'on' : ''}"><i></i><span><b>${esc(s.note)}</b><small class="tab">${esc(s.device)} · ${s.minutes} min session · ${esc(br.reason)}</small></span><em class="tab">${esc(s.start)}</em></li>`;
    };
    const today = S.filter((s) => s.day === 0).sort((x, y) => (x.start > y.start ? 1 : -1));
    const recovery = `<div class="a-card">${row({ icon: 'archive', tone: 'sky', title: 'Keep a recovery copy', sub: `${esc(b.where)} stays in EarthBound’s memories`, acc: c.sys.toggle(true, 'Keep a recovery copy'), cls: 'wrap' })}</div>`;
    if (pane()) {
      // The two scenes are already large in the backdrop; the pane is the decision.
      const choice = (s) => {
        const br = cf.branches.find((x) => x.device === s.device);
        const on = br.id === a.id;
        return `<li class="pick${on ? ' on' : ''}" role="radio" aria-checked="${on}"><span class="a-radio${on ? ' on' : ''}" aria-hidden="true"></span><img class="px" src="${k.frame('earthbound', br.frame)}" alt=""><span><b>${esc(br.where)}</b><small class="tab">${esc(s.device)} · ${esc(k.ago(br.at))} · ${esc(br.reason)}</small><small class="tab">${s.minutes} min session · ${esc(br.playtime)} played</small></span></li>`;
      };
      return `<div class="a-conf pane" style="${lit('earthbound')}">
        <div class="a-conf-h">${sync('conflict')}<h2 class="a-title-xl">Choose a moment</h2><p class="a-lede sm">This iPhone and Pixel 9 both played since the last sync. The moment you choose continues on every device.</p></div>
        <section class="a-branch gl" role="radiogroup" aria-label="Choose a moment">
          ${kicker('Since the last sync')}
          <ol>
            <li class="root"><i></i><span><b>${esc(root.note)}</b><small class="tab">Last shared moment · ${root.day} days ago</small></span></li>
            ${today.slice().reverse().map(choice).join('')}
          </ol>
        </section>
        ${recovery}
        <div class="a-conf-act">${btn(`Continue from ${a.where}`, { icon: 'play', kind: 'primary', cls: 'lg wide' })}</div>
      </div>`;
    }
    return `<div class="a-conf" style="${lit('earthbound')}">
      <div class="a-conf-h">
        ${sync('conflict')}
        <h2 class="a-title-xl">Two moments</h2>
        <p class="a-lede sm">This iPhone and Pixel 9 both played since the last sync. Choose which moment continues.</p>
      </div>
      <div class="a-diptych" role="radiogroup" aria-label="Choose a moment">${side(a, true)}${side(b, false)}</div>
      <section class="a-branch gl" aria-label="Since the last sync">
        ${kicker('Since the last sync')}
        <ol>
          <li class="root"><i></i><span><b>${esc(root.note)}</b><small class="tab">Last shared moment · ${root.day} days ago</small></span></li>
          ${today.map((s) => branch(s, s.device === a.device)).join('')}
        </ol>
      </section>
      ${recovery}
      <div class="a-conf-act">${btn(`Continue from ${a.where}`, { icon: 'play', kind: 'primary', cls: 'lg wide' })}</div>
    </div>`;
  }
  // Duo open: EarthBound's light fills the whole display; the two moments are drawn large
  // in the part the floating inspector leaves uncovered (box.clear), since the choice is
  // made by recognising them.
  function conflictBackdrop(box) {
    const [a, b] = D().conflict.branches;
    const cl = box.clear;
    const fig = (x, on) =>
      `<figure class="${on ? 'on' : ''}">${gs(k.frame('earthbound', x.frame), { alt: `EarthBound at ${x.where}` })}<figcaption><b>${esc(x.where)}</b><span>${esc(x.device)} · ${esc(k.ago(x.at))}</span></figcaption></figure>`;
    return scene(
      ['earthbound'],
      `${field('f-room f-canvas', k.cover('earthbound', 'tall'))}<div class="a-cbd" style="left:${cl.x}px;top:${cl.y}px;width:${cl.w}px;height:${cl.h}px">${kicker('EarthBound · two moments', '<span>Since the last sync</span>')}${fig(a, true)}${fig(b, false)}</div>`,
      'a-s-play',
    );
  }
  function conflictScreen(c) {
    return scene(
      ['earthbound'],
      c.sys.sheet({
        detent: pane() ? 'pane' : 'large',
        title: 'EarthBound',
        backdrop: (box) => (pane() ? conflictBackdrop(box) : playScreen(c, 'earthbound')),
        body: conflictBody(c),
      }),
      'a-s-conflict',
    );
  }

  // ================================================================ register
  const screens = {
    welcome: welcomeScreen,
    connect: connectScreen,
    signin: signinScreen,
    hydrate: hydrateScreen,
    home: (c) => playScreen(c),
    library: libraryScreen,
    platform: platformScreen,
    collections: collectionsScreen,
    game: gameScreen,
    downloads: downloadsScreen,
    search: searchScreen,
    signature: momentsScreen,
    settings: settingsScreen,
    controls: controlsScreen,
    player: (c) => playerLandscape(c),
    'player-portrait': playerPortrait,
    pause: pauseScreen,
    states: statesScreen,
    conflict: conflictScreen,
    dual: dualScreen,
  };
  for (const [id, fn] of Object.entries(screens)) {
    screens[id] = (c) => {
      C = c;
      try {
        return fn(c);
      } finally {
        C = null;
      }
    };
  }

  Studio.defineVersion({
    id: 'aurora',
    name: 'Aurora',
    tagline: 'Moments, not files — lit by the game in focus.',
    thesis:
      'People remember games as moments — the glass tube in Maridia, the night in Threed — not as files sorted by console. Aurora organises Rommio around moments of play: Play is a set of rooms, one per game in progress, each lit by its own palette with the resume frame glowing at its centre; Moments is a gallery of every resume point, save state and game save, grouped by day like photo memories; the Library is browsed by era and atmosphere. Native Liquid Glass sits on the light instead of competing with it.',
    swatch: ['#07070C', '#FF7A3D', '#F6F4F1'],
    fonts: ['Bricolage+Grotesque:opsz,wdth,wght@12..96,75..100,300..800', 'Figtree:wght@400..800'],
    tabs: [
      { id: 'play', label: 'Play', icon: 'circle-play' },
      { id: 'moments', label: 'Moments', icon: 'gallery' },
      { id: 'library', label: 'Library', icon: 'library' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },
    ],
    palette: [
      { name: 'Daylight · base', hex: '#F6F4F1' },
      { name: 'Daylight · ink', hex: '#16131D' },
      { name: 'Frost glass (58%)', hex: '#FFFFFF94' },
      { name: 'Night · base', hex: '#07070C' },
      { name: 'Night · ink', hex: '#F6F5FA' },
      { name: 'Night glass (8%)', hex: '#FFFFFF14' },
      { name: 'Synced · day / night', hex: '#0B8457' },
      { name: 'Synced · night', hex: '#7CF2C4' },
      { name: 'Waiting · day', hex: '#A35F00' },
      { name: 'Waiting · night', hex: '#FFC66B' },
      { name: 'Conflict · day', hex: '#C8352A' },
      { name: 'Conflict · night', hex: '#FF7A6B' },
      { name: 'Light · Super Metroid ember', hex: '#FF7A3D' },
      { name: 'Light · Super Metroid violet', hex: '#5D2A6E' },
      { name: 'Light · Link’s Awakening sea', hex: '#2F8FB8' },
      { name: 'Light · EarthBound bubblegum', hex: '#E97FA3' },
    ],
    type: [
      { role: 'Display', family: 'Bricolage Grotesque', css: "'Bricolage Grotesque', sans-serif; font-weight: 700; font-stretch: 82%", sample: 'Maridia glass tube' },
      { role: 'UI', family: 'Figtree', css: "'Figtree', sans-serif", sample: 'Automatic resume · 1 min ago · This iPhone' },
      { role: 'Kicker', family: 'Figtree 700 caps', css: "'Figtree', sans-serif; font-weight: 700; letter-spacing: .12em; text-transform: uppercase; font-size: 12px", sample: 'Today · 4 sessions' },
      { role: 'Numbers', family: 'Figtree tabular', css: "'Figtree', sans-serif; font-variant-numeric: tabular-nums", sample: '1.2 of 1.9 GB · 18.4 MB/s · 62%' },
    ],
    tweaks: [
      { var: '--ambient', label: 'Ambient light', type: 'range', min: 0, max: 100, step: 5, value: 100, unit: '%' },
      { var: '--bias', label: 'Bias lighting (player)', type: 'range', min: 0, max: 100, step: 5, value: 85, unit: '%' },
      { var: '--frost', label: 'Glass frost', type: 'range', min: 0, max: 48, step: 2, value: 24, unit: 'px' },
      { var: '--r', label: 'Corner radius', type: 'range', min: 10, max: 34, step: 2, value: 22, unit: 'px' },
      { var: '--ctl-o', label: 'Control opacity', type: 'range', min: 30, max: 100, step: 2, value: 72, unit: '%' },
      { var: '--grain', label: 'Film grain', type: 'range', min: 0, max: 100, step: 5, value: 60, unit: '%' },
    ],
    notes: {
      welcome: 'Three covers float in the light like remembered scenes; the field is mixed from three real palettes (Super Metroid, Link’s Awakening, Chrono Trigger). No tab bar until there is a library. Duo open: one centred composition — five covers fan across the crease above the promise, with Connect and Scan side by side; Duo closed stacks them so nothing overlaps on the short screen.',
      connect: 'Glass fields over a Metroid Fusion / Link’s Awakening dawn. The live check lives inside the same glass as the address, so errors sit beside the input; access method is a radio grid rather than a hidden menu. Duo open: one canvas — the address and a lit route diagram (This iPhone → Cloudflare Access → RomM) on the wide side, Continue and saved servers beside them, the four access methods as a single row across the display.',
      signin: 'The verified server identity stays visible with Change. Duo open: the server is a lit band across the canvas (reachability, access, version, Change server) and the form is one centred column beneath it that straddles the crease.',
      hydrate: 'First sync reads as a library developing: covers resolve from frost to colour as artwork is cached (61%). Finished counts are checked; the sync promise is phrased as moments following you, and Start playing is available immediately. Duo open: the cover wall develops across the whole display, the headline sits on its fading edge, and progress, choices and Start playing read as one row.',
      home: 'Play is a set of rooms, one per game in progress, paged. The room is lit by the game’s own palette; the resume frame glows as a screen with a peek of the next room at the edge; the moment’s name is the headline and Resume is the only loud action. Other rooms sit below — EarthBound appears as "Two moments" because it needs a choice. Duo open: the room is the whole canvas — the resume screen spans the fold with the moment beside it, and the other rooms plus the arriving download (developing from frost) run the full width beneath, under one light field.',
      library: 'Browse by era, not by grid. An era ruler (each segment lit by that era’s games, width = its share of the library) sits above a coverflow; platforms become facets under it and support tiers are counted separately (Touch, Controller, Browse only). Duo open: one canvas — ruler and platforms full width, a seven-cover coverflow across the fold with Super Metroid on the crease, then tiers and reels.',
      platform: 'A platform is split by light: games on this iPhone are lit and show the moment you left them at; games not downloaded are unlit, with their size. Duo open: a true list → detail — a narrow era index (300pt, same light, no divider) selects Super Nintendo, whose detail takes the rest of the width with a five-column grid.',
      collections: 'Collections are reels — a strip of covers running through the light of their own games. Manual reels are full cards; smart and automatic reels are compact rows that show their rule. Duo open: a true list → detail — every reel as a row in a narrow list (the selected one lit), the chosen reel wide beside it with its fan of covers, Continue the reel and each game’s last moment.',
      game: 'The page is built around memories: a timeline of the game’s moments (automatic resume, three save states, the in-game save) with times, devices and sync state. Resume is the native prominent action; Start with game save and Sync now follow; file, core and metadata are secondary. Duo open: one composition — key art across the display (under the bar), title and actions on its fading edge with a glass progress card beside them, the five memories spanning the fold, facts in columns below.',
      downloads: 'Downloads are Arriving: a native sheet from the toolbar arrow (badge 4). The running download is a scene developing from frost to colour at its real progress, with a lit edge at 62%. Queue, waiting for Wi-Fi (Use cellular), the failure with Retry, and arrivals follow; storage closes the sheet. Duo open: a floating inspector over the whole Play canvas, which stays drawn edge to edge behind it.',
      search: 'Results are ranked by your recent moments first, then title: the best match is a lit card you can resume from, then matching moments (each performs its own verb), games with Play and tier, and reels. Suggestions are tokens; recent searches stay at the foot. Duo open: one results canvas — field and tokens full width, the best match wide beside the games, moments as a full-width strip, reels and recent below.',
      signature: 'Moments: every resume point, save state and game-save as a captured scene, grouped by day like photo memories. Each day carries a light strip mixed from the games played that day, plus its sessions and playtime. Each tile shows kind, device and sync state; tapping performs its verb (Resume, Load state, Start with game save). Duo open: one row per day — the day’s name, sessions and a vertical light strip in a narrow margin, its moments in a four-column mosaic across the fold (today’s latest moment large).',
      settings: 'Settings stays quiet — the last game’s light, dimmed. Account is a glass identity card with the second profile inside it; devices show what each still has waiting; storage is a spectrum bar. Bias lighting is a real player setting in this direction. Duo open: the same page reflowed into three columns, the identity card spanning two.',
      controls: 'Tuning with a live preview: the preview is a small lit room with the controls at the chosen opacity. iPhone Duo: the preview is the Duo player itself — the same k.stage geometry as in game. Closed, it is the landscape player scaled into the column, the live tag in its notice spot above the game. Open, it spans the canvas left of the bar, set low like the player, and the settings float in the game’s place, so the shoulders, d-pad, face buttons and the Select · Menu · Start row stay in view while you drag.',
      player: 'Bias lighting: the live frame, blurred, spills into the rails; frosted controls sit in that light (pale frost with ink legends by day, smoked glass by night). iPhone Duo (open and closed): geometry from k.stage — the game shrinks to leave wide rails (centred across the fold when open) and nothing touches it. The game’s light fills the rails, pooling in its key colour under the d-pad and face buttons, which sit low where thumbs rest with L/R just above; Select · Menu · Start run beneath the game and the resume notice floats above it.',
      'player-portrait': 'Link’s Awakening above, controls below in the frame’s own sea-and-sun light. iPhone Duo: geometry from k.stage — the game full width at the top (crossing the fold when open); the lit band beneath it holds the d-pad and B/A low (Game Boy Color has no shoulders), Select · Menu · Start at the foot and the resume notice just under the game; open, the moment’s name sits between the clusters. Nothing overlaps the game.',
      pause: 'Pause is a native sheet with Resume as its prominent action. The first row is the current position framed as a moment with the exact wording (Game-save data synced · Resume waiting to sync · session 42 min). Save state is the hero tile (Slot 4 is free); Leave game is always visible and says what it keeps. iPhone Duo: the frozen game sits exactly where the player draws it (k.stage), lit; open, a floating inspector over it adds a Jump to a moment strip.',
      states: 'Automatic resume and game-save data are their own items, side by side; slots 1–3 are a strip of moments with device and sync state, slot 4 invites a capture. On phones the two histories are one tap away; on Duo open the floating inspector (over the paused game, drawn exactly where the player draws it) shows them in full.',
      conflict: 'A diptych: the two moments at the same size, each with place, device, time and playtime, so the choice is made by recognising the scene. Below, a branch diagram built from the play sessions shows where they split (Happy Happy Village, 9 days ago). Keeping a recovery copy is on by default. Duo open: EarthBound’s light fills the display, the two scenes are drawn large in the part the floating inspector leaves clear, and the inspector holds the decision (branch radios, recovery, Continue).',
      dual: 'Pokémon Black’s screens stay stacked in their own bias light; the touch screen is labelled for the stylus. iPhone Duo: geometry from k.stage — the screens stacked (the top screen above the fold when open), one bias field across both, the controls in a lit band at the bottom: L/R over the d-pad and face buttons, Select · Menu · Start and the resume notice between them. Nothing touches either screen; the stylus label sits in the rail beside the touch screen.',
    },
    screens,
  });
})();
