/*
 * SHELF — round 3 · Aurora · "Games are objects you pick up and put down."
 *
 * DESIGN PLAN
 * ===========
 * Thesis     A retro library is a physical thing: cartridges, cases and discs on shelves,
 *            and a few games lying on the desk because you're in the middle of them. Shelf
 *            keeps Aurora's material (per-game light, glass, Bricolage + Figtree) and gives
 *            the structure a physical grammar: Home is the stack on your desk; the Library
 *            is a bookcase of spines.
 *
 * Complaint  Today Play shows one game per page and the Library one or two covers at a time,
 *            behind three stacked filters. Shelf answers both with density you can read at a
 *            glance: Home shows every game in progress at once (one expanded, the rest as a
 *            Wallet-style pile of 48 pt strips), and the Library replaces the era ruler, facets
 *            and tier chips with one Arrange by control over shelves of vertical titles — a
 *            full shelf holds 12 legible spines across the phone, so a screen reads 16–30
 *            titles depending on how full the shelves are (16 in the sparse mock data).
 *
 * IA         Tabs are the app's (Play · Library · Stats · Settings · Search).
 *              Play      "The stack". The front card is the latest game, fully expanded in
 *                        its own light: resume frame as a glowing screen, the moment as the
 *                        headline, Resume + Details. The other games in progress are a pile
 *                        of strips beneath it (name, platform, time, sync dot), each a real
 *                        radio: tapping a strip brings that card to the front and relights the
 *                        page. EarthBound's strip carries "Two resume points". Then "Put down
 *                        a while ago" (games with progress untouched for 5+ days, drawn as
 *                        the actual objects: a clear-teal Crystal cart, a PS1 jewel case, a DS
 *                        card…) and "Saved lately" (states and game saves).
 *              Library   "Spines". One shelf per platform, ordered by what you play most, all
 *                        under one "Arrange by" control (Console · Era · Genre · Series).
 *                        Spines take the game's palette with the title set vertically; width
 *                        is the game's size (a 40 KB NES cart is slim, a 1.9 GB PS1 game is a
 *                        fat jewel case). A lit LED = on this iPhone; a ribbon sticking out of
 *                        the top = in progress. One spine is pulled out to face you, with a
 *                        shelf-talker tag hanging from the ledge: that is the selection and it
 *                        lights the page.
 *              Platform  "The Super Nintendo bookcase" (See all). Every SNES game A–Z on
 *                        stacked glass shelves with letter markers on the ledges. The pulled
 *                        game leaves a lit gap in its shelf and opens beneath it as a glass
 *                        detail card (cover, facts, On this iPhone, Resume, its last moment).
 *
 * Scale      Shelves are as wide as their games. A platform with a handful of games takes a
 *            compartment and shares a shelf with its neighbours, packed first-fit in play
 *            order behind a glass partition (the owner's real library: 72 games on 17
 *            platforms packs into ~6 shelves); a platform with more games than fit gets the
 *            full width and scrolls, ending in a "+N more" block (the mock's 2,164). The
 *            drill-in is A–Z with letter markers so 241 games stay navigable.
 *
 * Components Front card · Strip (a card's header; the pile is the same element collapsed) ·
 *            Object (cart / pocket cart / card / disc, label = the game's cover art) ·
 *            Spine · Pull (face-out cover) · Shelf talker · Glass ledge · Compartment ·
 *            More block · Gap (where a pulled game was) · Detail card.
 *
 * No preview Ocarina of Time 3D has no resume frame. In this metaphor its card face is the
 *            object itself: a 3DS game card whose label is built from the cover art, lying in
 *            the card's light, with "Start with game save" as the verb. It reads as a thing
 *            you picked up, not a missing screenshot. (Tap its strip on Home.)
 *
 * Light      Home is lit by the front card's game; the Library and the bookcase by the
 *            pulled-out game (Super Metroid). Strips and cards carry their own game's light
 *            via AuroraKit.lit: pastel by day, deep and glowing at night.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;
  const Art = () => window.StudioArt;
  const AK = window.AuroraKit;
  const AX = window.AuroraX;

  let C = null;
  let UID = 0;
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const contrast = (a, b) => {
    const x = k.luminance(a);
    const y = k.luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  };
  const reg = () => C && C.size === 'regular';

  // ================================================================ data
  const IN_PROGRESS = AX.IN_PROGRESS;
  const FRONT = 0; // index of the card in front when the page opens (the most recent game)
  const sessionOf = (id) => D().sessions.find((s) => s.game === id);
  const placeOf = (id) => {
    const m = AK.lastMoment(id);
    if (m) return m.name;
    const s = sessionOf(id);
    return s ? s.note : '';
  };
  // Games with progress you haven't touched for five days or more (not in the stack).
  const PUT_DOWN = D()
    .games.filter((g) => !IN_PROGRESS.includes(g.id) && g.played != null && g.played >= 60 * 24 * 5)
    .filter((g) => AK.lastMoment(g.id) || D().sessions.some((s) => s.game === g.id && s.ended !== 'quit'))
    .sort((a, b) => a.played - b.played)
    .map((g) => g.id);
  const PROGRESS = new Set([...IN_PROGRESS, ...PUT_DOWN]);

  // Platforms ordered by what you play most: this month's top platforms, then total playtime.
  const hoursOn = (pid) => k.gamesOn(pid).reduce((n, g) => n + (g.hours || 0), 0);
  const PLAT_ORDER = (() => {
    const top = D().stats.topPlatforms.map((x) => x.platform);
    const rest = D()
      .platforms.map((p) => p.id)
      .filter((id) => !top.includes(id))
      .sort((a, b) => hoursOn(b) - hoursOn(a) || k.platform(b).games - k.platform(a).games);
    return [...top, ...rest];
  })();

  // ================================================================ objects
  // What a game physically is on its platform.
  const OBJ = {
    nes: 'cart', snes: 'cart', n64: 'cart', genesis: 'cart', sms: 'cart', arcade: 'cart',
    gb: 'pocket', gbc: 'pocket', gba: 'pocket', gg: 'pocket', ngp: 'pocket', lynx: 'pocket',
    pce: 'card', nds: 'card', '3ds': 'card',
    psx: 'disc', psp: 'disc', saturn: 'disc', dc: 'disc', gc: 'disc', ps2: 'disc',
  };
  // Shell plastic per platform (Pokémon Crystal's cart really is clear teal).
  const PLASTIC = { snes: 'grey', nes: 'grey', gb: 'grey', n64: 'charcoal', genesis: 'black', gba: 'charcoal', gbc: 'black', nds: 'grey', '3ds': 'grey', psx: 'clear' };
  const plasticOf = (g) => (g.id === 'pkcrystal' ? 'crystal' : PLASTIC[g.platform] || 'grey');

  // Spine thickness from the game's size: 26 pt for a 30 KB cart, 40 pt for a 1.9 GB disc.
  const thick = (g, s = 1) => Math.round((26 + 14 * clamp((Math.log10(Math.max(g.size, 0.01)) + 1.6) / 4.9, 0, 1)) * s);

  // Spine colours from the game's palette; the title colour always clears 4.5:1.
  const SCACHE = new Map();
  function spineCol(id) {
    if (SCACHE.has(id)) return SCACHE.get(id);
    const pal = k.game(id).art.pal;
    let body = pal[2];
    const band = pal[4];
    const W = '#ffffff';
    const INK = '#15121c';
    const ink = contrast(body, W) >= contrast(body, INK) ? W : INK;
    for (let n = 0; n < 14 && contrast(body, ink) < 4.9; n++) body = k.shade(body, ink === W ? -0.07 : 0.07);
    const out = { body, band, ink, edge: k.shade(body, -0.28), lift: k.shade(body, 0.14) };
    SCACHE.set(id, out);
    return out;
  }

  // Vertical title: pick a size so it fits one column, or two for long titles on wide spines.
  function titleFit(title, w, avail) {
    const est = (fs) => title.length * fs * 0.55;
    if (est(12) <= avail) return { fs: 12, lines: 1 };
    if (est(11.5) <= avail) return { fs: 11.5, lines: 1 };
    const twoAt = (fs) => est(fs) / 2 + fs * 2 <= avail && w >= fs * 2.2 + 3;
    if (twoAt(11)) return { fs: 11, lines: 2 };
    if (twoAt(10)) return { fs: 10, lines: 2 };
    return { fs: clamp(avail / (title.length * 0.55), 9.5, 12), lines: 1 };
  }

  function spine(g, { h = 146, scale = 1 } = {}) {
    const col = spineCol(g.id);
    const w = thick(g, scale);
    const kind = OBJ[g.platform] || 'cart';
    const prog = PROGRESS.has(g.id);
    const avail = h - (kind === 'disc' ? 22 : 18) - 26;
    const fit = titleFit(g.title, w, avail);
    const state = [g.installed ? 'On this iPhone' : 'Not downloaded', prog ? 'In progress' : ''].filter(Boolean).join(' · ');
    return `<li class="sh-sp k-${kind}${g.installed ? ' on' : ''}${prog ? ' prog' : ''}" style="--w:${w}px;--sb:${col.body};--se:${col.edge};--sl:${col.lift};--band:${col.band};--si:${col.ink};--fs:${fit.fs}px;--tl:${avail}px" role="button" aria-label="${esc(g.title)}, ${state}">
      ${prog ? '<i class="sh-rib" aria-hidden="true"></i>' : ''}
      <span class="sh-sp-t${fit.lines === 2 ? ' two' : ''}">${esc(g.title)}</span>
      <i class="sh-led" aria-hidden="true"></i>
    </li>`;
  }

  // A physical object whose label is the game's cover art.
  function object(id, { size = 'sm', cls = '' } = {}) {
    const g = k.game(id);
    const kind = OBJ[g.platform] || 'cart';
    const pid = g.platform;
    const label =
      kind === 'disc'
        ? `<img class="sh-o-label px" src="${k.cover(id, 'square')}" alt="">`
        : kind === 'cart'
          ? `<img class="sh-o-label px" src="${k.cover(id, 'wide')}" alt="">`
          : `<img class="sh-o-label px" src="${k.cover(id, pid === 'gba' ? 'wide' : 'box')}" alt="">`;
    const mark = { snes: 'SUPER NINTENDO', n64: 'NINTENDO 64', nes: 'NINTENDO', gb: 'GAME BOY', gbc: 'GAME BOY COLOR', gba: 'GAME BOY ADVANCE', nds: 'NINTENDO DS', '3ds': 'NINTENDO 3DS', psx: 'PlayStation', genesis: 'GENESIS' }[pid] || '';
    return `<div class="sh-o o-${kind} p-${pid} pl-${plasticOf(g)} o-${size} ${cls}" role="img" aria-label="${esc(g.title)} ${kind === 'disc' ? 'case' : kind === 'card' ? 'game card' : 'cartridge'}">
      <div class="sh-o-shell">
        ${kind === 'disc' ? '<i class="sh-o-hinge"></i>' : '<i class="sh-o-grip"></i>'}
        <div class="sh-o-win">${label}${size !== 'sm' ? `<span class="sh-o-title"><b>${esc(g.title)}</b></span>` : ''}</div>
        ${mark && size !== 'sm' ? `<span class="sh-o-mark">${esc(mark)}</span>` : ''}
      </div>
    </div>`;
  }

  // ================================================================ PLAY · the stack
  const TIMEAGO = (id) => {
    const m = AK.lastMoment(id);
    return m ? m.at : k.game(id).played;
  };
  const DOT = { synced: 'ok', pending: 'wait', waiting: 'wait', syncing: 'live', conflict: 'bad', local: 'mute', none: 'mute' };
  const stateOf = (id) => {
    if (id === 'earthbound') return 'conflict';
    const m = AK.lastMoment(id);
    return m ? m.remote : k.game(id).sync;
  };

  // The header strip every card carries; collapsed in the pile it is all you see.
  function stripInner(id, { front = false } = {}) {
    const g = k.game(id);
    const p = AK.plat(g);
    const st = stateOf(id);
    const right =
      id === 'earthbound' && !front
        ? `<span class="sh-two">${i('split', 13)}Two resume points</span>`
        : `<span class="sh-when tab">${esc(front ? `${k.hours(g.hours)} played` : k.ago(TIMEAGO(id)))}</span><i class="sh-dot t-${DOT[st] || 'mute'}" role="img" aria-label="${esc(k.syncLabel(st))}"></i>`;
    return `<span class="sh-chip"><img class="px" src="${k.cover(id, 'box')}" alt=""></span>
      <span class="sh-name"><b>${esc(g.title)}</b><small>${esc(p.short)}</small></span>
      ${right}`;
  }

  // The card's face: a glowing screen, a diptych (conflict), or the object (no preview).
  function screenOf(id) {
    const g = k.game(id);
    const ar = k.aspect(id);
    const fitCls = ar >= 1.3 ? 'cover' : 'contain';
    if (id === 'earthbound') {
      const [a, b] = D().conflict.branches;
      const pane = (x) =>
        `<figure class="sh-dip"><div class="sh-dip-f"><img class="px" src="${k.frame(id, x.frame)}" alt="EarthBound at ${esc(x.where)}"></div><figcaption><b>${esc(x.where)}</b><span>${esc(x.device)} · ${esc(k.ago(x.at))}</span></figcaption></figure>`;
      return `<div class="sh-scr two">${pane(a)}${pane(b)}</div>`;
    }
    if (!AX.hasPreview(id)) {
      return `<div class="sh-scr obj" style="--art:url(${k.cover(id, 'wide')})">
        <i class="sh-scr-desk" aria-hidden="true"></i>
        ${object(id, { size: 'lg', cls: 'sh-o-hero' })}
        <span class="sh-scr-chip">${AK.sync('synced', 'Game-save data synced')}</span>
      </div>`;
    }
    const m = AK.lastMoment(id);
    const src = k.frame(id, m.frame);
    return `<div class="sh-scr ${fitCls}" style="--ar:${ar}">
      <img class="sh-scr-glow" src="${src}" alt="">
      <div class="sh-scr-face"><img class="sh-scr-bg" src="${src}" alt=""><img class="sh-scr-img px" src="${src}" alt="${esc(g.title)} at ${esc(m.name)}"></div>
      <span class="sh-scr-chip">${AK.sync(m.remote, m.remote === 'waiting' ? 'Resume waiting to sync' : '')}</span>
    </div>`;
  }

  function face(id, n) {
    const g = k.game(id);
    let head;
    let meta;
    let verb;
    let icon = 'play';
    if (id === 'earthbound') {
      head = 'Two resume points';
      const [a, b] = D().conflict.branches;
      meta = `${D().conflict.kind} · ${a.device} and ${b.device}`;
      verb = 'Choose one';
      icon = 'split';
    } else if (!AX.hasPreview(id)) {
      head = `Played ${k.ago(g.played).toLowerCase()}`;
      meta = 'Game-save data · starts from your last in-game save';
      verb = AK.KIND.save.verb;
    } else {
      const m = AK.lastMoment(id);
      head = m.name;
      meta = `${AK.kindLabel(m)} · ${k.ago(m.at)} · ${m.device}`;
      verb = AK.KIND[m.kind].verb;
    }
    return `<article class="sh-card sh-face${n === FRONT ? '' : ' sh-anim'}" data-i="${n}" style="${AK.lit(id)}" aria-label="${esc(g.title)}, in front">
      <header class="sh-card-hd">${stripInner(id, { front: true })}</header>
      ${screenOf(id)}
      <div class="sh-cap">
        <h2 class="sh-head">${esc(head)}</h2>
        <p class="sh-meta">${esc(meta)}</p>
      </div>
      <div class="sh-act">${AK.btn(verb, { icon, kind: 'primary', cls: 'md grow' })}${AK.btn('Details', { icon: 'info', cls: 'md sh-details' })}</div>
    </article>`;
  }

  function strip(id, n, uid) {
    const g = k.game(id);
    return `<label class="sh-strip${id === 'earthbound' ? ' is-two' : ''}" data-i="${n}" style="${AK.lit(id)}">
      <input type="radio" name="sh-front-${uid}" value="${esc(id)}"${n === FRONT ? ' checked' : ''} aria-label="Bring ${esc(g.title)} to the front">
      <span class="sh-strip-in">${stripInner(id)}</span>
    </label>`;
  }

  function putDown(id) {
    const g = k.game(id);
    const m = AK.lastMoment(id);
    const state = m ? m.remote : g.sync;
    return `<li class="sh-pd" style="${AK.lit(id)}">
      <div class="sh-pd-obj">${object(id)}</div>
      <b class="sh-pd-t">${esc(g.title)}</b>
      <span class="sh-pd-m">${esc(placeOf(id))}</span>
      <span class="sh-pd-a"><span class="tab">${esc(k.ago(g.played))}</span><i class="sh-dot t-${DOT[state] || 'mute'}" role="img" aria-label="${esc(k.syncLabel(state))}"></i></span>
      ${AK.btn('Pick back up', { kind: 'glass', cls: 'sm sh-pd-b' })}
    </li>`;
  }

  function savedLately() {
    const ms = D()
      .moments.filter((m) => m.kind !== 'resume')
      .sort((a, b) => a.at - b.at)
      .slice(0, 5);
    return `<ul class="sh-saved a-card">${ms
      .map((m) => {
        const g = k.game(m.game);
        return `<li class="sh-sv" style="${AK.lit(m.game)}">
          <span class="sh-sv-img"><img class="px" src="${k.frame(m.game, m.frame)}" alt=""></span>
          <span class="sh-sv-t"><b>${esc(m.name)}</b><small>${esc(g.title)} · ${esc(AK.kindLabel(m))}</small><span class="sh-sv-s"><span class="tab">${esc(k.ago(m.at))} · ${esc(m.device)}</span>${AK.sync(m.remote)}</span></span>
        </li>`;
      })
      .join('')}</ul>`;
  }

  function homeScreen(c) {
    const uid = ++UID;
    const u = `sh-u${uid}`;
    const on = (n) => `.${u}:has(.sh-strip[data-i="${n}"] input:checked)`;
    // Which card is in front is a real radio group: each game relights the page when chosen.
    const css = IN_PROGRESS.map((id, n) => {
      const L = AK.lights(id);
      return `${on(n)}{--l1:${L.l1}!important;--l2:${L.l2}!important;--l3:${L.l3}!important;--k1:${L.k1}!important;--k2:${L.k2}!important;--k3:${L.k3}!important;--tint:${L.tint}!important;--sys-tint:${L.tint}!important;--sys-bg:${L.bg}!important}${on(n)} .sh-face[data-i="${n}"],${on(n)} .sh-amb[data-i="${n}"]{display:block}${on(n)} .sh-strip[data-i="${n}"]{display:none}`;
    }).join('');
    const amb = `<div class="a-field f-room" aria-hidden="true">${IN_PROGRESS.map((id, n) => `<img class="a-field-art sh-amb" data-i="${n}" src="${k.cover(id, 'tall')}" alt="">`).join('')}<i class="a-field-light"></i><i class="a-field-veil"></i><i class="a-field-grain" style="background-image:url(${Art().grain(0.08)})"></i></div>`;
    const body = `<div class="sh-home">
      <div class="sh-home-a">
        <div class="sh-stack" role="radiogroup" aria-label="Games in progress">
          <div class="sh-faces">${IN_PROGRESS.map(face).join('')}</div>
          <div class="sh-pile">${IN_PROGRESS.map((id, n) => strip(id, n, uid)).join('')}</div>
        </div>
        <p class="sh-pile-foot"><span class="tab">${IN_PROGRESS.length} games in progress</span>${AK.sync('synced', 'Home server · synced 2 min ago')}</p>
      </div>
      <div class="sh-home-b">
        <section class="sh-sec">
          <header class="sh-sec-h"><h3>Put down a while ago</h3><span class="tab">${PUT_DOWN.length} games</span></header>
          <ul class="sh-pds">${PUT_DOWN.map(putDown).join('')}</ul>
        </section>
        <section class="sh-sec">
          <header class="sh-sec-h"><h3>Saved lately</h3><span>States and game saves</span></header>
          ${savedLately()}
        </section>
      </div>
    </div>`;
    return AK.scene(
      [IN_PROGRESS[0]],
      `<style>${css}</style>${amb}${c.sys.shell({
        tab: 'play',
        titleMode: 'none',
        actions: [AK.ARRIVING, AK.ACCOUNT],
        edge: false,
        body,
      })}`,
      `a-s-play sh-s-home ${u}`,
    );
  }

  // ================================================================ LIBRARY · spines
  const PULL = 'smetroid';
  const GAP = 2;
  const PULL_W = (h) => Math.round(h * 0.75) + 10;

  // Within a shelf: most played first, then what's on this iPhone, then the rest A–Z.
  const sortKey = (t) => t.replace(/^(A|The) /, '');
  const byPlay = (a, b) => (b.hours || 0) - (a.hours || 0) || Number(b.installed) - Number(a.installed) || sortKey(a.title).localeCompare(sortKey(b.title));
  const byTitle = (a, b) => sortKey(a.title).localeCompare(sortKey(b.title));

  const TIER_ICON = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tierMark = (t) => `<span class="sh-tier" role="img" aria-label="${esc(k.tierLabel(t))}" title="${esc(k.tierLabel(t))}">${i(TIER_ICON[t], 13)}</span>`;

  // The run a platform needs, and whether it fits a compartment.
  const MORE_W = { full: 30, comp: 20 };
  function compartment(pid, h) {
    const p = k.platform(pid);
    const games = k.gamesOn(pid).sort(byPlay);
    const more = p.games - games.length;
    const pulled = games.some((g) => g.id === PULL);
    const runW = games.reduce((n, g) => n + (g.id === PULL ? PULL_W(h) : thick(g)) + GAP, 0) + (more > 0 ? MORE_W.comp : 0);
    return { p, pid, games, more, pulled, runW };
  }
  const nameW = (s, fs) => s.length * fs * 0.5;

  // Pack compartments into shelves, first fit in play order: a platform takes the first
  // shelf with room for it. Big (or selected) platforms get a full run that scrolls.
  function pack(W, h) {
    const plats = PLAT_ORDER.filter((pid) => k.gamesOn(pid).length);
    const comps = plats.map((pid) => compartment(pid, h));
    const rows = [];
    const PAD = 4;
    const DIV = 14;
    for (const x of comps) {
      const need = Math.max(x.runW + PAD, 80);
      x.need = need;
      if (x.pulled || need > W) {
        rows.push({ full: true, comps: [x] });
        continue;
      }
      const row = rows.find((r) => !r.full && r.used + DIV + need <= W);
      if (row) {
        row.comps.push(x);
        row.used += DIV + need;
      } else rows.push({ full: false, comps: [x], used: need });
    }
    // Share the leftover width, then decide which compartments can spell their full name.
    for (const r of rows) {
      if (r.full) continue;
      const spare = W - r.used - DIV * (r.comps.length - 1);
      r.comps.forEach((x) => {
        x.width = x.need + spare / r.comps.length;
        x.label = nameW(x.p.name, 18) + 16 <= x.width ? x.p.name : x.p.short;
        x.meta = x.width >= 150 ? 'long' : 'short';
      });
    }
    return rows;
  }

  function shelfHeader(x, full) {
    const p = x.p;
    if (full) {
      const prog = x.games.filter((g) => PROGRESS.has(g.id)).length;
      return `<header class="sh-hd">
        <h3>${esc(p.name)}</h3>
        <button class="sh-see">See all${i('chevron-right', 15)}</button>
        <p><span class="tab">${k.num(p.games)} games</span><span class="sh-hd-on"><i class="sh-led-k"></i><span class="tab">${p.installed}</span> on this iPhone</span>${prog ? `<span class="sh-hd-on"><i class="sh-rib-k"></i><span class="tab">${prog}</span> in progress</span>` : ''}${tierMark(p.tier)}</p>
      </header>`;
    }
    return `<button class="sh-hd sm" aria-label="${esc(p.name)}, ${p.games} games, ${p.installed} on this iPhone. See all">
      <h3>${esc(x.label)}${i('chevron-right', 14)}</h3>
      <p><span class="tab">${x.meta === 'long' ? `${k.num(p.games)} games` : k.num(p.games)}</span><span class="sh-hd-on"><i class="sh-led-k"></i><span class="tab">${p.installed}</span>${x.meta === 'long' ? ' here' : ''}</span>${tierMark(p.tier)}</p>
    </button>`;
  }

  function pullItem(g, h) {
    const col = spineCol(g.id);
    return `<li class="sh-pull" style="--w:${PULL_W(h)}px;--sb:${col.body};--se:${col.edge};--band:${col.band};${AK.lit(g.id)}" role="button" aria-label="${esc(g.title)}, pulled out">
      <div class="sh-pull-obj"><i class="sh-pull-side" aria-hidden="true"></i><img class="sh-pull-face px" src="${k.cover(g.id, 'box')}" alt=""><i class="sh-pull-sheen" aria-hidden="true"></i></div>
    </li>`;
  }

  const moreBlock = (x, full) =>
    `<li class="sh-more${full ? '' : ' sm'}" role="button" aria-label="${k.num(x.more)} more ${esc(x.p.short)} games. See all"><span class="tab">+${k.num(x.more)}</span>${full ? '<small>more</small>' : ''}</li>`;

  function talker(g) {
    const m = AK.lastMoment(g.id);
    return `<div class="sh-talker" style="${AK.lit(g.id)}">
      <i class="sh-talker-clip" aria-hidden="true"></i>
      <span class="sh-talker-t"><b>${esc(g.title)}</b><small>${esc(m.name)} · ${esc(k.ago(m.at))}</small></span>
      <button class="sh-talker-go" aria-label="Resume ${esc(g.title)}">${i('play', 16)}</button>
    </div>`;
  }

  function shelfRow(r, h) {
    if (r.full) {
      const x = r.comps[0];
      let at = 0;
      let pullX = null;
      const items = x.games
        .map((g) => {
          if (g.id === PULL) {
            pullX = at;
            at += PULL_W(h) + GAP;
            return pullItem(g, h);
          }
          at += thick(g) + GAP;
          return spine(g, { h });
        })
        .join('');
      return `<section class="sh-row full${pullX != null ? ' has-talker' : ''}">
        ${shelfHeader(x, true)}
        <div class="sh-run-wrap"><ul class="sh-run">${items}${x.more > 0 ? moreBlock(x, true) : ''}</ul></div>
        <i class="sh-ledge" aria-hidden="true"></i>
        ${pullX != null ? `<div class="sh-talker-at" style="--x:${pullX}px">${talker(k.game(PULL))}</div>` : ''}
      </section>`;
    }
    return `<section class="sh-row">
      <div class="sh-comps">${r.comps
        .map(
          (x) => `<div class="sh-comp" style="flex:${Math.round(x.width)} 1 0">
          ${shelfHeader(x, false)}
          <ul class="sh-run">${x.games.map((g) => spine(g, { h })).join('')}${x.more > 0 ? moreBlock(x, false) : ''}</ul>
        </div>`,
        )
        .join('')}</div>
      <i class="sh-ledge" aria-hidden="true"></i>
    </section>`;
  }

  function arrange() {
    const opts = ['Console', 'Era', 'Genre', 'Series'];
    return `<div class="sh-arrange" role="radiogroup" aria-label="Arrange by">
      <span class="sh-arrange-l">Arrange by</span>
      ${opts.map((o, n) => `<button role="radio" aria-checked="${n === 0}" class="${n === 0 ? 'on' : ''}">${esc(o)}</button>`).join('')}
    </div>`;
  }

  function libraryScreen(c) {
    const L = c.sys.layout({ title: 'Library' });
    const W = L.width - (reg() ? 48 : 32);
    const h = 146;
    const rows = pack(W, h);
    const t = k.totals();
    const shown = new Set(PLAT_ORDER.filter((pid) => k.gamesOn(pid).length));
    const closed = PLAT_ORDER.filter((pid) => !shown.has(pid));
    const closedList = closed.length
      ? `<section class="sh-sec sh-closed">
          <header class="sh-sec-h"><h3>Further along</h3><span class="tab">${closed.length} more shelves</span></header>
          <ul class="a-card">${closed
            .map((pid) => {
              const p = k.platform(pid);
              return `<li class="sh-cl"><i class="sh-cl-c" style="background:${p.color}"></i><span class="sh-cl-t"><b>${esc(p.name)}</b><small class="tab">${k.num(p.games)} games · ${p.installed ? `${p.installed} on this iPhone` : 'none on this iPhone'}</small></span>${tierMark(p.tier)}${i('chevron-right', 16, 'a-chev')}</li>`;
            })
            .join('')}</ul>
        </section>`
      : '';
    const inProg = [...PROGRESS].length;
    return AK.scene(
      [PULL],
      `${AK.field('f-page', k.cover(PULL, 'tall'))}${c.sys.shell({
        tab: 'library',
        title: 'Library',
        subtitle: `${k.num(t.games)} games · ${t.platforms} platforms · ${t.installed} on this iPhone`,
        actions: [{ icon: 'search', label: 'Search the library' }, AK.ARRIVING, AK.ACCOUNT],
        body: `<div class="sh-lib" style="--sp-h:${h}px">
          ${arrange()}
          ${rows.map((r) => shelfRow(r, h)).join('')}
          ${closedList}
          <p class="sh-legend"><span><i class="sh-led-k"></i>On this iPhone</span><span><i class="sh-rib-k"></i>In progress · ${inProg}</span><span>Thickness shows size</span></p>
        </div>`,
      })}`,
      'a-s-library sh-s-lib',
    );
  }

  // ================================================================ PLATFORM · the SNES bookcase
  function bookcase(W, h, scale) {
    const games = k.gamesOn('snes').sort(byTitle);
    const shelves = [];
    let cur = [];
    let used = 0;
    for (const g of games) {
      const w = thick(g, scale) + GAP;
      if (used + w > W && cur.length) {
        shelves.push(cur);
        cur = [];
        used = 0;
      }
      cur.push(g);
      used += w;
    }
    if (cur.length) shelves.push(cur);
    return shelves;
  }

  function detailCard(g, notchX) {
    const m = AK.lastMoment(g.id);
    const p = AK.plat(g);
    const verb = g.installed ? (m ? AK.KIND[m.kind].verb : 'Play') : 'Download';
    return `<article class="sh-detail" style="--nx:${notchX}px;${AK.lit(g.id)}" aria-label="${esc(g.title)}, pulled out">
      <i class="sh-detail-notch" aria-hidden="true"></i>
      <div class="sh-detail-top">
        <div class="sh-detail-cover"><img class="sh-detail-glow" src="${k.cover(g.id, 'box')}" alt=""><img class="sh-detail-img px" src="${k.cover(g.id, 'box')}" alt="${esc(g.title)} cover"></div>
        <div class="sh-detail-t">
          <p class="sh-detail-k">${esc(p.name)} · <span class="tab">${g.year}</span></p>
          <h2>${esc(g.title)}</h2>
          <p class="sh-detail-here${g.installed ? '' : ' off'}">${g.installed ? `${i('check', 14)}<span>On this iPhone · <span class="tab">${k.size(g.size)}</span></span>` : `${i('cloud-download', 14)}<span>Not downloaded · <span class="tab">${k.size(g.size)}</span></span>`}</p>
          <div class="sh-act">${AK.btn(verb, { icon: g.installed ? 'play' : 'download', kind: 'primary', cls: 'md grow' })}${AK.btn('', { icon: 'more', cls: 'md round', aria: `More for ${g.title}` })}</div>
        </div>
      </div>
      <dl class="sh-facts">
        <div><dt>Genre</dt><dd>${esc(g.genre)}</dd></div>
        <div><dt>Publisher</dt><dd>${esc(g.publisher)}</dd></div>
        <div><dt>Players</dt><dd class="tab">${g.players}</dd></div>
        <div><dt>Rating</dt><dd class="tab">${g.rating}</dd></div>
      </dl>
      ${
        m
          ? `<div class="sh-last">
        <span class="sh-last-img"><img class="px" src="${k.frame(g.id, m.frame)}" alt=""></span>
        <span class="sh-last-t"><span class="sh-last-k"><small>Last moment</small>${AK.sync(m.remote)}</span><b>${esc(m.name)}</b><small>${esc(AK.kindLabel(m))} · ${esc(k.ago(m.at))} · ${esc(m.device)}</small></span>
      </div>`
          : ''
      }
    </article>`;
  }

  function platformScreen(c) {
    const Lay = c.sys.layout({ title: 'Super Nintendo', titleMode: 'inline', back: true });
    const W = Lay.width - (reg() ? 48 : 32);
    const h = 142;
    const scale = 1.3;
    const p = k.platform('snes');
    const shelves = bookcase(W, h, scale);
    const letter = (g) => sortKey(g.title)[0].toUpperCase();
    let prev = '';
    const shelfHTML = shelves
      .map((games) => {
        let x = 0;
        let gapX = null;
        const marks = [];
        const items = games
          .map((g) => {
            const w = thick(g, scale);
            const L = letter(g);
            if (L !== prev) {
              marks.push(`<span class="sh-mark" style="--x:${x}px;--mw:${w}px">${esc(L)}</span>`);
              prev = L;
            }
            let out;
            if (g.id === PULL) {
              gapX = x + w / 2;
              out = `<li class="sh-gap" style="--w:${w}px;${AK.lit(g.id)}" aria-label="${esc(g.title)} is pulled out"></li>`;
            } else out = spine(g, { h, scale });
            x += w + GAP;
            return out;
          })
          .join('');
        return `<section class="sh-row bk">
          <ul class="sh-run">${items}</ul>
          <div class="sh-ledge tall" aria-hidden="true"><span class="sh-marks">${marks.join('')}</span></div>
        </section>${gapX != null ? detailCard(k.game(PULL), gapX) : ''}`;
      })
      .join('');
    const tierIcon = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' }[p.tier];
    return AK.scene(
      [PULL],
      `${AK.field('f-page', k.cover(PULL, 'tall'))}${c.sys.shell({
        tab: 'library',
        title: 'Super Nintendo',
        titleMode: 'inline',
        back: 'Library',
        actions: [{ icon: 'search', label: 'Search Super Nintendo' }, { icon: 'sort', label: 'Sort · A to Z' }],
        body: `<div class="sh-plat" style="--sp-h:${h}px">
          <div class="sh-sum gl">
            <div class="sh-sum-c"><b class="tab">${p.games}</b><span>games</span></div>
            <div class="sh-sum-c"><b class="tab"><i class="sh-led-k"></i>${p.installed}</b><span>on this iPhone</span></div>
            <div class="sh-sum-c"><b>${i(tierIcon, 18)}</b><span>${esc(k.tierLabel(p.tier))} controls</span></div>
            <div class="sh-sum-c"><b>${i('cpu', 18)}</b><span>${esc(p.core)}</span></div>
          </div>
          ${shelfHTML}
        </div>`,
      })}`,
      'a-s-platform sh-s-plat',
    );
  }

  // ================================================================ register
  const use = (fn) => (c) => {
    C = c;
    try {
      return fn(c);
    } finally {
      C = null;
    }
  };

  Studio.defineVersion({
    id: 'shelf',
    round: 3,
    extends: 'aurora',
    name: 'Aurora · Shelf',
    tagline: 'Games are objects you pick up and put down: a stack on the desk, spines on the shelves.',
    thesis:
      'A retro library is a physical thing. Home is the stack on your desk: every game in progress at once, the latest expanded in its own light with its resume frame glowing, the rest a Wallet-style pile of strips you tap to bring to the front, and the games you put down a while ago drawn as the actual cartridges, cases and cards. The Library is a bookcase: one shelf per platform, ordered by what you play most, spines coloured from each game and as thick as the game is large, a lit LED for what is on this iPhone and a ribbon for what is in progress. Small platforms share a shelf; big ones get a full run that scrolls, so it reads the same at 72 games and at 2,164.',
    swatch: ['#1B1026', '#5D2A6E', '#FF7A3D'],
    tabs: AX.TABS,
    fonts: [],
    notes: {
      home: 'The stack. The front card is Super Metroid, full width in its own light: the header strip every card shares (cover, name, platform, playtime, sync dot), the Maridia resume frame as a glowing screen with its sync chip, the moment as the headline, Resume and Details. The other six games in progress sit beneath as a Wallet-style pile of 48 pt strips, each in its own colour; the strips are a real radio group, so tapping one brings that card to the front and relights the page (EarthBound shows its two resume points as a diptych; Ocarina of Time 3D, which has no resume preview, shows its 3DS game card with a label built from the cover art and "Start with game save"). Below: "Put down a while ago" draws the untouched games as their objects (a clear-teal Crystal cart, a PS1 jewel case, a DS card, N64 and SNES carts) with Pick back up, then "Saved lately" lists states and game saves.',
      library: 'Spines. One Arrange by control (Console · Era · Genre · Series) replaces the era ruler, facets and tier chips. Shelves follow what you play most: Super Nintendo gets a full run that scrolls to a "+226 more" block; smaller platforms share shelves as compartments behind a glass partition (packed first-fit in play order, short names when a compartment is narrow), so a 72-game library packs into a handful of shelves. A full shelf holds 12 legible spines across the phone; the first screen reads 16 titles plus a face-out cover in the sparse mock data, where most platforms have two to four games. Each spine uses the game’s palette with its title set vertically (sized to fit, always 4.5:1), its width is the game’s size (slim NES carts, fat PlayStation cases with a hinge), a lit LED means on this iPhone and a ribbon means in progress. Super Metroid is pulled out face-first with a shelf-talker tag (moment, Resume) hanging from the ledge; it is the selection and lights the page. Platforms the mock has no games for close the page as a list, followed by the key (LED, ribbon, thickness shows size).',
      platform: 'The Super Nintendo bookcase, from See all. A summary strip (241 games, 14 on this iPhone, Touch controls, Snes9x), then every SNES game A–Z (leading articles ignored, so A Link to the Past files under L) on glass shelves with letter markers printed on the ledges under the first spine of each letter; the spines are drawn a little larger than on the Library, as if you had walked up to the bookcase. Super Metroid is pulled out: its slot is a lit gap, and the game opens beneath that shelf as a glass detail card whose notch points back at the gap — cover, year, genre, publisher, players, rating, On this iPhone and size, Resume, and the last moment with its sync state.',
    },
    screens: AX.wrap({ home: use(homeScreen), library: use(libraryScreen), platform: use(platformScreen) }),
  });
})();
