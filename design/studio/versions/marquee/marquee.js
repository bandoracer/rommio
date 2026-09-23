/*
 * MARQUEE — round 2. "Pick up and play."
 *
 * DESIGN PLAN
 * -----------
 * Thesis      Rommio is a game console of its own. The fun is in choosing and playing, so
 *             the information architecture is hardware-first and habit-forming: you pick a
 *             console, then a cartridge; the app celebrates play with streaks, records and
 *             high scores; and every screen is the fastest path to pressing Play. Loud and
 *             joyful, never childish.
 *
 * IA          Tabs: Arcade · Consoles · Stats · Search (Search is separate on iOS 26).
 *             Arcade (home)    cabinet row of big paged cartridges for what you're playing (resume
 *                              frame as the label, playtime as a score, the console's own A button
 *                              as the Play key); today's streak as a marquee strip; the EarthBound
 *                              conflict as a "two players" choice; the loading bay as a sticker.
 *               └ Loading bay  (downloads) pushed from the bay sticker and card.
 *               └ Settings     via the account avatar ("Player 1" card, sync keys, memory bar).
 *                   └ Controls "button mapping" card with console colours and a live preview.
 *             Consoles         console select screen: the cursor rests on the last-played console;
 *                              every console is a colour tile grouped by tier (Touch / Controller /
 *                              Browse only with its reason).
 *               └ Platform     one console's cartridge rack: installed carts plugged in (lit LED),
 *                              server carts in the box (download slot).
 *               └ Cabinets     collections as arcade cabinets (playlists you load with cartridges).
 *               └ Game         character select: the cover as a sticker, a scoreboard, memory cards.
 *             Stats            the signature: streak, 28-day heatmap, top consoles, high-score board.
 *             Search           results ranked like a leaderboard (playable now ranks first).
 *             Player           Pause and Save states are native sheets with arcade content.
 *
 * Palette     Day (light)  Cream #FFF4E2 ground · Paper #FFFDF8 cards · Lip #E7CFA5 hard shadow ·
 *                          Ink #1C1636 · Ink-2 #4A4366 · Ink-3 #6B6386 · pastel section slabs
 *                          Sun #FFE7A0 · Coral #FFD0C8 · Mint #C4F2DA · Sky #CBEEFC · Grape #E1D7FF.
 *             Night (dark) Night #120F24 ground · Slab #1E1A3A · Slab-2 #29244D · Lip #0A0817 ·
 *                          Moon #FFF8EC · Ink-2 #C9C2E4 · Ink-3 #9C94C2 · saturated section slabs.
 *             Accents (both) Sun #FFC83D · Coral #FF5A4E · Mint #3DDC97 · Sky #43C6F7 · Grape #8C6CFF.
 *             Text on any slab is always On-ink #17122E. Accent text by day uses deep variants
 *             (Coral #C42E1E, Mint #0B7446, Sky #0A6690, Grape #5A3BD1, Sun #8A5A00).
 *             Chrome tint: Arcade red #D02C22 by day, #FF5A4E by night (the SNES A button);
 *             switches in Mint. Platform colours are identity: cartridges and console tiles wear
 *             them in both appearances; very dark plastics are lifted at night.
 *
 * Type        Display — Unbounded 700–900: large titles, console names, keys.
 *             Interface — Outfit 400–700: everything you read.
 *             Scores — Press Start 2P: counters, playtime, ranks, legends. Never prose.
 *
 * Components  Cartridge (plastic shell in the platform colour, a paper label carrying the resume
 *             frame, an LCD score window, the console's A button as the Play key) · Console tile ·
 *             Rack spine · Memory card · Marquee strip with bulbs · Cabinet · Scoreboard LCD ·
 *             Leaderboard row · Toggle keys · Power bars · Stickers. Depth is a solid lip, never
 *             blur (the native glass chrome is the only blur on screen).
 *
 * Duo         Closed (466×678): the compact layouts, with the vertical bar on the trailing edge.
 *             Open (951×669): one arcade floor, not two halves. The fold is a crease that content
 *             runs straight across: Arcade is the whole cabinet row (cartridges crossing the fold)
 *             under a full-width marquee strip; Consoles is one wide select screen (the SNES band
 *             with its plugged-in carts, then every console in ten columns); Game is a full-width
 *             character card with the memory card beneath; Stats is one wide scoreboard grid.
 *             Only Cabinets keeps a list → detail split (narrow cabinet list, wide open cabinet).
 *             Sheets are floating inspectors: Pause and Save states over the whole paused game,
 *             Conflict over the whole Arcade canvas with the vertical bar beside it.
 *
 * Player      OLED black around the game. Console-coloured translucent buttons with thick rings
 *             (k.consoleColors), pixel legends, rommio key for Menu. Duo (open and closed): all
 *             geometry comes from k.stage, and no control ever touches a game screen. Landscape,
 *             the game sits between side rails (across the fold when open) with the d-pad and face
 *             buttons low in the rails, L/R above them and Select · Menu · Start under the game.
 *             GBC runs full width at the top with its lamp and wordmark between screen and pad; DS
 *             stacks its screens (top above the fold) over a control band, the Touch tag hanging
 *             under the touch screen as its caption. Pause is a big menu list with a blinking
 *             cursor; Save states are memory cards.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;

  // ---------- colour ----------
  const rgb = (h) => {
    const n = parseInt(h.replace('#', ''), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };
  const hex = (c) => '#' + c.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
  const mix = (a, b, t) => {
    const A = rgb(a);
    const B = rgb(b);
    return hex(A.map((v, n) => v + (B[n] - v) * t));
  };
  const lum = (h) => k.luminance(h);
  const ONINK = '#17122E';
  const MOON = '#FFF8EC';
  const contrast = (a, b) => {
    const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m);
    return (x + 0.05) / (y + 0.05);
  };
  const on = (h) => (contrast(h, ONINK) >= contrast(h, MOON) ? ONINK : MOON);
  const CREAM = '#FFF6E6';

  // Platform variables: --pc plastic, --pi ink on it, --pl its lip; --ps section slab
  // (pastel by day, the plastic by night), --psi ink on the slab, --psl its lip.
  const pv = (p, c) => {
    const dark = c.scheme === 'dark';
    let pc = p.color;
    if (dark && lum(pc) < 0.035) pc = mix(pc, '#8a86b0', 0.26);
    const ps = dark ? pc : mix(p.color, CREAM, 0.64);
    const psl = dark ? mix(pc, '#07050f', 0.5) : mix(p.color, CREAM, 0.3);
    return `--pc:${pc};--pi:${on(pc)};--pl:${mix(pc, '#07050f', 0.42)};--ps:${ps};--psi:${on(ps)};--psl:${psl}`;
  };
  // The console's own confirm button colour is the Play key.
  const aOf = (pid) => {
    const cc = k.consoleColors(pid);
    return cc.A || cc.cross || Object.values(cc)[0];
  };
  const CROSS = '<svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true"><path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
  const legend = (pid) => (['psx', 'psp'].includes(pid) ? CROSS : '<span class="pix">A</span>');

  // ---------- formatting ----------
  const score = (h) => k.hours(h).replace(/ h /, 'H ').replace(/ m$/, 'M');
  const mins = (m) => (m >= 60 ? `${Math.floor(m / 60)}H ${String(m % 60).padStart(2, '0')}M` : `${m}M`);
  const gb = (mb) => (mb >= 1000 ? `${(mb / 1000).toFixed(1)} GB` : k.size(mb));
  const fit = (text, width, max, factor = 0.86) => Math.max(10, Math.min(max, Math.floor(width / (text.length * factor))));
  const wide = (c) => c.size === 'regular';
  const droid = (c) => c.os === 'android';

  // ---------- console silhouettes (48×48, evenodd) ----------
  const FORM = {
    nes: 'cart', snes: 'cart', n64: 'cart', genesis: 'cart', sms: 'cart', pce: 'cart', vb: 'cart',
    gb: 'hv', gbc: 'hv', ngp: 'hv', gba: 'hh', gg: 'hh', lynx: 'hh', psp: 'hh',
    nds: 'clam', '3ds': 'clam', psx: 'disc', saturn: 'disc', dc: 'disc', gc: 'disc', ps2: 'disc', arcade: 'cab',
  };
  const SIL = {
    cart: '<path d="M9 20h30a5 5 0 0 1 5 5v10a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5V25a5 5 0 0 1 5-5zM11 29.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM17.5 29.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM26 30h12v3H26z"/><path d="M15 7h18v14H15zM18 10v6h12v-6z"/>',
    disc: '<path d="M8 14h32a5 5 0 0 1 5 5v16a5 5 0 0 1-5 5H8a5 5 0 0 1-5-5V19a5 5 0 0 1 5-5zM18 19a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM33 21h6v3h-6zM33 28h6v3h-6z"/><circle cx="18" cy="27" r="2.6"/>',
    hv: '<path d="M13 3h22a3 3 0 0 1 3 3v32a8 8 0 0 1-8 8H13a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM15 7v15h18V7zM17 29h3v-3h3v3h3v3h-3v3h-3v-3h-3zM30 31a1.9 1.9 0 1 0 0 3.8a1.9 1.9 0 1 0 0-3.8zM33.5 27.5a1.9 1.9 0 1 0 0 3.8a1.9 1.9 0 1 0 0-3.8z"/>',
    hh: '<path d="M11 12h26a9 9 0 0 1 9 9v6a9 9 0 0 1-9 9H11a9 9 0 0 1-9-9v-6a9 9 0 0 1 9-9zM15 16v16h18V16zM6 23h2.5v-2.5h2.5V23h2.5v2.5H11V28H8.5v-2.5H6zM38.5 20.5a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4zM41.5 24.5a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z"/>',
    clam: '<path d="M10 3h28a3 3 0 0 1 3 3v15H7V6a3 3 0 0 1 3-3zM12 7v11h24V7z"/><path d="M7 23h34v18a3 3 0 0 1-3 3H10a3 3 0 0 1-3-3zM16 26v12h16V26zM10 31h2v-2h2v2h2v2h-2v2h-2v-2h-2zM36.5 28.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3zM36.5 34a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z"/>',
    cab: '<path d="M14 2h20v8l-2 2v13l5 4v17H11V29l5-4V12l-2-2zM17 4.5v3h14v-3zM18 14v10h12V14zM18.5 32a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM26 33h2.5v2.5H26zM30.5 33H33v2.5h-2.5z"/>',
  };
  const sil = (pid, cls = '') => `<svg class="mq-sil ${cls}" viewBox="0 0 48 48" fill="currentColor" fill-rule="evenodd" aria-hidden="true">${SIL[FORM[pid] || 'cart']}</svg>`;

  // ---------- primitives ----------
  const pix = (t, cls = '') => `<span class="pix ${cls}">${esc(t)}</span>`;
  const img = (src, cls = '') => `<img class="${cls}" src="${src}" alt="">`;
  const shot = (id, v = 'a', cls = '') => `<img class="mq-px ${cls}" src="${k.frame(id, v)}" alt="">`;
  const cover = (id, cls = '', kind = 'box') => `<img class="mq-cov ${cls}" src="${k.cover(id, kind)}" alt="">`;
  const logo = (s = 26) => `<svg class="mq-logo" width="${s}" height="${s}" viewBox="0 0 32 32" aria-hidden="true">
      <rect x="1" y="5" width="30" height="24" rx="9" fill="#FF5A4E"/>
      <rect x="1" y="3" width="30" height="24" rx="9" fill="#FFC83D"/>
      <path d="M8.5 13.5h3v-3h3v3h3v3h-3v3h-3v-3h-3z" fill="#17122E"/>
      <circle cx="21.5" cy="12.8" r="2.3" fill="#17122E"/><circle cx="25" cy="17.6" r="2.3" fill="#17122E"/></svg>`;

  const key = (label, { tone = 'sun', icon, trail, size = '', cls = '', aria } = {}) =>
    `<button class="mq-key ${tone} ${size} ${cls}"${aria ? ` aria-label="${esc(aria)}"` : ''}>${icon ? i(icon, size === 'sm' ? 16 : 19) : ''}${label ? `<span>${esc(label)}</span>` : ''}${trail ? i(trail, 18) : ''}</button>`;

  // The console's A button with a label: the Play key everywhere in Marquee.
  const aKey = (pid, label, { size = '', cls = '', aria } = {}) => {
    const a = aOf(pid);
    return `<button class="mq-akey ${size} ${cls}" style="--ac:${a};--ai:${on(a)};--al:${mix(a, '#07050f', 0.45)}" aria-label="${esc(aria || label)}"><span class="mq-akey-a">${legend(pid)}</span>${label && size !== 'dot' ? `<span class="mq-akey-t">${esc(label)}</span>` : ''}</button>`;
  };

  const SYNC = {
    synced: ['mint', 'cloud-check'],
    pending: ['sun', 'cloud-upload'],
    waiting: ['sun', 'cloud-upload'],
    syncing: ['sky', 'sync'],
    conflict: ['coral', 'alert'],
    local: ['mute', 'smartphone'],
    signin: ['grape', 'log-in'],
    none: ['line', 'download'],
  };
  const stk = (state, { label, solid = false, icon = true, cls = '' } = {}) => {
    const [t, ic] = SYNC[state] || SYNC.local;
    return `<span class="mq-stk ${t}${solid ? ' solid' : ''} ${cls}">${icon ? i(ic, 13) : ''}<span>${esc(label || k.syncLabel(state))}</span></span>`;
  };
  const TIER = { touch: ['sun', 'hand'], controller: ['sky', 'gamepad'], unsupported: ['mute', 'eye'] };
  const tierStk = (t, solid = false) => {
    const [c, ic] = TIER[t];
    return `<span class="mq-stk ${c}${solid ? ' solid' : ''}">${i(ic, 13)}<span>${esc(k.tierLabel(t))}</span></span>`;
  };
  const pchip = (p, c, cls = '') => `<span class="mq-pchip ${cls}" style="${pv(p, c)}">${esc(p.short)}</span>`;
  const pbar = (pct, { segs = 20, tone = 'mint', live = false, cls = '' } = {}) => {
    const n = Math.round(pct * segs);
    return `<span class="mq-pbar ${tone} ${cls}" role="progressbar" aria-valuenow="${Math.round(pct * 100)}" aria-valuemin="0" aria-valuemax="100">${k
      .range(segs)
      .map((x) => `<i class="${x < n ? 'on' : ''}${live && x === n - 1 ? ' tip' : ''}"></i>`)
      .join('')}</span>`;
  };
  const bulbs = (n, lit, cls = '') => `<span class="mq-bulbs ${cls}" aria-hidden="true">${k.range(n).map((x) => `<i class="${x < lit ? 'on' : ''}"></i>`).join('')}</span>`;
  const head = (title, { count, more, cls = '', tone = '' } = {}) =>
    `<div class="mq-head ${cls}"><h2>${esc(title)}</h2>${count != null ? `<span class="pix mq-count ${tone}">${esc(count)}</span>` : ''}${more ? `<button class="mq-more">${more}</button>` : ''}</div>`;
  const sw = (on, label) => `<button class="mq-sw${on ? ' on' : ''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}"><i></i></button>`;
  const chev = () => `<span class="mq-chev" aria-hidden="true">${i('chevron-right', 18)}</span>`;
  const back = (c) => (c.os === 'android' ? 'arrow-left' : 'chevron-left');

  // ---------- data ----------
  // What you're playing: the newest resume point or save state per game (EarthBound waits on a choice).
  const playing = () => {
    const seen = new Set(['earthbound']);
    const out = [];
    for (const m of D().moments) {
      if (seen.has(m.game) || (m.kind !== 'resume' && m.kind !== 'state')) continue;
      seen.add(m.game);
      out.push(m);
    }
    return out.slice(0, 5);
  };
  const records = () => {
    const m = new Map();
    for (const s of D().sessions) {
      const r = m.get(s.game) || { game: s.game, total: 0, count: 0, longest: null };
      r.total += s.minutes;
      r.count += 1;
      if (!r.longest || s.minutes > r.longest.minutes) r.longest = s;
      m.set(s.game, r);
    }
    return [...m.values()].sort((a, b) => b.total - a.total);
  };
  const dayAgo = (d) => (d === 0 ? 'today' : d === 1 ? 'yesterday' : `${d} days ago`);

  // ================================================================
  // Shared content components
  // ================================================================

  // Cartridge: plastic shell in the platform colour, a paper label with the resume frame,
  // an LCD score window and the console's A button.
  const cart = (m, c, { size = '' } = {}) => {
    const g = k.game(m.game);
    const p = k.platformOf(g);
    const act = m.kind === 'state' ? 'Load state' : 'Resume';
    const where = `${esc(m.kind === 'state' ? `State ${m.slot} · ` : '')}${esc(m.name)}`;
    return `<article class="mq-cart ${size}" style="${pv(p, c)}" aria-label="${esc(g.title)}">
      ${size.includes('now') ? '<span class="mq-cursor pix" aria-hidden="true">Now</span>' : ''}
      <div class="mq-cart-lip" aria-hidden="true"><i></i><i></i></div>
      <div class="mq-cart-label">
        <div class="mq-cart-shot">${shot(g.id, m.frame)}
          <span class="mq-cart-chip">${esc(p.short)}</span>
          ${stk(m.remote, { solid: true, cls: 'mq-cart-sync' })}
        </div>
        <div class="mq-cart-copy">
          <h3>${esc(g.title)}</h3>
          <p>${where} · ${esc(k.ago(m.at))}${m.device !== 'This iPhone' ? ` · ${esc(m.device)}` : ''}</p>
        </div>
      </div>
      <div class="mq-cart-foot">
        <span class="mq-lcd"><span class="pix lbl">Playtime</span><b class="pix">${score(g.hours)}</b></span>
        ${aKey(p.id, act, { aria: `${act} ${g.title}` })}
      </div>
      <div class="mq-cart-grip" aria-hidden="true">${'<i></i>'.repeat(7)}</div>
    </article>`;
  };

  // Today's streak as a lit marquee strip with a ticker of records.
  const marqueeStrip = () => {
    const s = D().stats.month;
    const top = k.platform(D().stats.topPlatforms[0].platform).short;
    const run = [`Best ${s.bestStreak} days`, `${mins(s.minutes)} this month`, `${s.sessions} sessions`, `${s.games} games`, `Top console ${top}`]
      .map((t) => `<span>${esc(t)}</span><i></i>`)
      .join('');
    return `<section class="mq-strip" aria-label="${s.streak}-day streak, best ${s.bestStreak}">
      <span class="mq-strip-lead">${i('flame', 18)}<b class="pix">${s.streak}</b><span class="pix">Day<br>streak</span></span>
      <span class="mq-strip-run" aria-hidden="true"><span class="mq-ticker">${run}${run}</span></span>
    </section>`;
  };

  // The big lit sign version (Stats).
  const marqueeSign = (cls = '') => {
    const s = D().stats.month;
    return `<section class="mq-sign ${cls}" aria-label="${s.streak}-day streak, best ${s.bestStreak}">
      <span class="mq-sign-n pix">${s.streak}</span>
      <span class="mq-sign-t"><b>Day streak</b><small>Four days in a row. Nice run.</small>${bulbs(s.bestStreak, s.streak)}<span class="pix mq-sign-best">Best ${s.bestStreak}</span></span>
    </section>`;
  };

  // EarthBound conflict as a two-player choice.
  const twoPlayers = (c, cls = '') => {
    const C = D().conflict;
    const [a, b] = C.branches;
    return `<article class="mq-2p ${cls}" aria-label="EarthBound needs a choice: two devices moved it forward">
      <span class="mq-2p-art">
        <figure class="p1">${shot(C.game, a.frame)}<span class="pix">P1</span></figure>
        <span class="mq-vs">VS</span>
        <figure class="p2">${shot(C.game, b.frame)}<span class="pix r">P2</span></figure>
      </span>
      <span class="mq-2p-t">
        <span class="pix mq-2p-eyebrow">2 players · Conflict</span>
        <b>EarthBound</b>
        <small>${esc(a.device)} and ${esc(b.device)} both played. Who continues?</small>
      </span>
      ${chev()}
    </article>`;
  };

  const bayCard = (c, cls = '') => {
    const dl = D().downloads;
    const run = dl.find((d) => d.state === 'running');
    const g = k.game(run.game);
    const next = dl.filter((d) => d.state === 'queued' || d.state === 'waiting').length;
    return `<article class="mq-baycard ${cls}">
      <span class="mq-baycard-ic">${i('downloads', 20)}</span>
      <span class="mq-baycard-t"><b>Loading bay</b><small>${esc(g.title)} · ${k.pct(run.progress)} · ${next} up next · 1 failed</small>${pbar(run.progress, { segs: 16, tone: 'mint', live: true, cls: 'thin' })}</span>
      ${chev()}
    </article>`;
  };

  const bayStk = (cls = '') => {
    const run = D().downloads.find((d) => d.state === 'running');
    return `<button class="mq-baystk ${cls}" aria-label="Loading bay: downloading, 3 waiting, 1 failed">${i('downloads', 14)}<b class="pix">${k.pct(run.progress)}</b><span>3 up next</span></button>`;
  };

  // Console tile for the select grid.
  const ctile = (p, c, { sel = false, w = 80 } = {}) => {
    const s = p.short.toUpperCase();
    return `<button class="mq-ctile${sel ? ' sel' : ''}" style="${pv(p, c)}" aria-label="${esc(p.name)}, ${p.games} games, ${p.installed} installed"${sel ? ' aria-current="true"' : ''}>
      ${sil(p.id)}
      ${p.installed ? `<span class="mq-ctile-in" aria-hidden="true">${i('check', 11)}<span class="pix">${p.installed}</span></span>` : ''}
      <b style="font-size:${fit(s, w - 16, 21)}px">${esc(s)}</b>
      <span class="pix mq-ctile-n">${p.games}</span>
      ${sel ? '<span class="mq-cursor pix" aria-hidden="true">P1</span>' : ''}
    </button>`;
  };

  const browseRow = (p, c) => `<div class="mq-browse-row" style="${pv(p, c)}">
      <span class="mq-browse-tile">${sil(p.id)}<b>${esc(p.short.toUpperCase())}</b></span>
      <span class="mq-browse-t"><b>${esc(p.name)}</b><small>${esc(p.blocked)}</small></span>
      <span class="pix mq-browse-n">${p.games}</span>
    </div>`;

  const selectGrid = (c, sel, { cols = 4, w = 80 } = {}) => {
    const notes = {
      touch: `${k.tier('touch').length} consoles · on-screen controls`,
      controller: `${D().controls.connectedController.replace(' Wireless Controller', '')} connected · ready`,
      unsupported: 'In your library, not playable here',
    };
    return ['touch', 'controller', 'unsupported']
      .map((t) => {
        const ps = k.tier(t);
        const h = `<div class="mq-tierh">${tierStk(t, true)}<span>${esc(notes[t])}</span></div>`;
        if (t === 'unsupported') return `${h}<div class="mq-browse">${ps.map((p) => browseRow(p, c)).join('')}</div>`;
        return `${h}<div class="mq-cgrid" style="--cols:${cols}">${ps.map((p) => ctile(p, c, { sel: p.id === sel, w })).join('')}</div>`;
      })
      .join('');
  };

  // The selected console, big.
  const consolePanel = (p, c, { cls = '', action = true } = {}) => `
    <section class="mq-cpanel ${cls}" style="${pv(p, c)}">
      ${sil(p.id, 'mq-cpanel-sil')}
      <span class="pix mq-cpanel-eye">Last played</span>
      <b class="mq-cpanel-short">${esc(p.short.toUpperCase())}</b>
      <span class="mq-cpanel-name">${esc(p.name)} · ${esc(p.maker)} · ${p.year}</span>
      <span class="mq-cpanel-stats"><span><b class="pix">${p.games}</b>carts</span><span><b class="pix">${p.installed}</b>installed</span></span>
      <span class="mq-cpanel-tags">${tierStk(p.tier, true)}<span class="mq-stk plain">${i('cpu', 13)}<span>${esc(p.core)}</span></span></span>
      ${action ? key('Open rack', { tone: 'ink', trail: 'arrow-right', size: 'sm', cls: 'mq-cpanel-go' }) : ''}
    </section>`;

  // Rack spine: a cartridge seen end-on. Installed = plugged in with a lit LED.
  // Wide (Duo open) rows add last-played, players and size columns instead of a second column of carts.
  const spine = (g, c, { wide: w = false } = {}) => {
    const p = k.platformOf(g);
    const inst = g.installed;
    const right = inst
      ? `${g.hours ? `<span class="mq-spine-score"><span class="pix lbl">Time</span><b class="pix">${score(g.hours)}</b></span>` : '<span class="pix mq-new">New</span>'}<span class="mq-led" aria-label="Installed"></span>`
      : `<span class="mq-spine-size">${esc(k.size(g.size))}</span><button class="mq-dl" aria-label="Download ${esc(g.title)}">${i('download', 18)}</button>`;
    const cols = w
      ? `<span class="mq-spine-col"><span class="pix lbl">Last played</span><b>${g.played != null ? esc(k.ago(g.played)) : 'Not yet'}</b></span>
        <span class="mq-spine-col"><span class="pix lbl">Players</span><b>${g.players}</b></span>
        <span class="mq-spine-col"><span class="pix lbl">Rating</span><b>${g.rating}</b></span>`
      : '';
    return `<article class="mq-spine${inst ? ' in' : ' out'}" style="${pv(p, c)}">
      <span class="mq-spine-end" aria-hidden="true"><i></i><i></i><i></i></span>
      ${cover(g.id, 'mq-spine-label')}
      <span class="mq-spine-t"><b>${esc(g.title)}${g.favorite ? ` ${i('heart', 13, 'mq-fav')}` : ''}</b><small>${g.year} · ${esc(g.genre)}${g.sync === 'pending' ? ' · <em class="sun">Waiting to sync</em>' : g.sync === 'conflict' ? ' · <em class="coral">Conflict</em>' : ''}</small></span>
      ${cols}${right}
    </article>`;
  };
  const rack = (c, games, { wide: w = false } = {}) => {
    const inst = games.filter((g) => g.installed);
    const out = games.filter((g) => !g.installed);
    const p = k.platform('snes');
    const cls = w ? ' wide' : '';
    return `${head('In the console', { count: p.installed, cls: 'sm', tone: 'mint' })}<div class="mq-rack${cls}">${inst.map((g) => spine(g, c, { wide: w })).join('')}</div>
      ${head('On the server', { count: p.games - p.installed, cls: 'sm' })}<div class="mq-rack out${cls}">${out.map((g) => spine(g, c, { wide: w })).join('')}</div>`;
  };
  const snesGames = () => k.gamesOn('snes').sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));

  // Memory card block.
  const mcard = ({ n, title, meta, frame, remote, tone = '', empty = false, icon }) => {
    if (empty)
      return `<button class="mq-mc empty" aria-label="Save state to slot ${n}"><span class="mq-mc-n pix">${esc(n)}</span><span class="mq-mc-plus">${i('plus', 20)}</span><b>Empty slot</b><small>Save state here</small></button>`;
    return `<article class="mq-mc ${tone}">
      <span class="mq-mc-shot">${frame ? frame : `<span class="mq-mc-icon">${i(icon, 22)}</span>`}<span class="mq-mc-n pix">${esc(n)}</span></span>
      <b>${esc(title)}</b>
      <small>${meta}</small>
      ${stk(remote)}
    </article>`;
  };
  const memoryCards = (c) => {
    const P = D().progress;
    const g = k.game(P.game);
    return [
      mcard({ n: 'Auto', title: 'Automatic resume', meta: `${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}`, frame: shot(g.id, P.resume.frame), remote: P.resume.remote, tone: 'auto' }),
      mcard({ n: 'Save', title: 'Game-save data', meta: `SRAM · 8 KB · ${esc(k.agoShort(P.gameSave.at))} ago`, icon: 'save', remote: P.gameSave.remote, tone: 'gsave' }),
      ...P.slots.map((s) =>
        s.empty ? mcard({ n: `0${s.n}`, empty: true }) : mcard({ n: `0${s.n}`, title: s.name, meta: `${esc(s.device)} · ${esc(k.agoShort(s.at))} ago`, frame: shot(g.id, s.frame), remote: s.remote }),
      ),
    ].join('');
  };

  // Scoreboard (LCD).
  const scoreboard = (cells, cls = '') =>
    `<div class="mq-board ${cls}">${cells.map(([l, v]) => `<span><span class="pix lbl">${esc(l)}</span><b class="pix">${esc(v)}</b></span>`).join('')}</div>`;

  // Arcade cabinet (collections).
  const cabinet = (col, tone, c, { sel = false } = {}) => {
    const ids = col.games.slice(0, 3);
    return `<article class="mq-cab ${tone}${sel ? ' sel' : ''}" aria-label="${esc(col.name)}, ${col.games.length} games">
      <header class="mq-cab-marquee"><b>${esc(col.name)}</b></header>
      <div class="mq-cab-screen">${ids.map((id, n) => cover(id, `f${n}`)).join('')}</div>
      <div class="mq-cab-panel"><span class="mq-cab-stick" aria-hidden="true"></span><span class="mq-cab-btns" aria-hidden="true"><i></i><i></i></span><span class="pix">${col.games.length} carts</span></div>
      <p class="mq-cab-note">${esc(col.note)}</p>
    </article>`;
  };

  // Toggle key (settings, controls).
  const tkey = (label, onState, icon, tone = 'mint', sub = '') =>
    `<button class="mq-tkey ${tone}${onState ? ' on' : ''}" role="switch" aria-checked="${onState}" aria-label="${esc(label)}">
      <span class="mq-tkey-top">${i(icon, 20)}<span class="mq-lamp pix">${onState ? 'On' : 'Off'}</span></span>
      <b>${esc(label)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}
    </button>`;

  // Power-bar slider.
  const slider = (label, val, pct, icon, tone) =>
    `<div class="mq-slide ${tone}"><span class="mq-slide-top">${i(icon, 18)}<b>${esc(label)}</b><span class="pix">${esc(val)}</span></span>
      <span class="mq-slide-bar" role="slider" aria-label="${esc(label)}" aria-valuetext="${esc(val)}">${pbar(pct, { segs: 20, tone })}<i class="mq-knob" style="--v:${pct}"></i></span></div>`;

  // Onboarding step meter.
  const steps = (n) => `<div class="mq-steps" role="img" aria-label="Step ${n} of 3">${[1, 2, 3].map((x) => `<i class="${x < n ? 'done' : x === n ? 'on' : ''}"></i>`).join('')}<span class="pix">${n}/3</span></div>`;

  // ================================================================
  // Player pieces
  // ================================================================
  const roundPoly = (pts, r) => {
    const n = pts.length;
    let d = '';
    for (let j = 0; j < n; j++) {
      const p0 = pts[(j - 1 + n) % n];
      const p1 = pts[j];
      const p2 = pts[(j + 1) % n];
      const v1 = [p0[0] - p1[0], p0[1] - p1[1]];
      const v2 = [p2[0] - p1[0], p2[1] - p1[1]];
      const l1 = Math.hypot(...v1);
      const l2 = Math.hypot(...v2);
      const a = [p1[0] + (v1[0] / l1) * r, p1[1] + (v1[1] / l1) * r];
      const b = [p1[0] + (v2[0] / l2) * r, p1[1] + (v2[1] / l2) * r];
      d += `${j ? 'L' : 'M'}${a[0].toFixed(1)} ${a[1].toFixed(1)}Q${p1[0]} ${p1[1]} ${b[0].toFixed(1)} ${b[1].toFixed(1)}`;
    }
    return d + 'Z';
  };
  const PLUS = roundPoly([[42, 2], [78, 2], [78, 42], [118, 42], [118, 78], [78, 78], [78, 118], [42, 118], [42, 78], [2, 78], [2, 42], [42, 42]], 11);
  const dpad = (cls = '', style = '') => `<div class="mq-dpad ${cls}" style="${style}" role="group" aria-label="D-pad">
      <svg viewBox="0 0 120 120" aria-hidden="true"><path class="dp-body" d="${PLUS}"/>
        <path class="dp-arrow" d="M60 14l9 11H51z"/><path class="dp-arrow" d="M60 106l9-11H51z"/>
        <path class="dp-arrow" d="M14 60l11-9v18z"/><path class="dp-arrow" d="M106 60l-11-9v18z"/>
        <circle class="dp-hub" cx="60" cy="60" r="9"/></svg>
      <button class="dp up" aria-label="Up"></button><button class="dp down" aria-label="Down"></button>
      <button class="dp left" aria-label="Left"></button><button class="dp right" aria-label="Right"></button>
    </div>`;
  const fb = (l, col, pos) => `<button class="fb ${pos}" style="--fc:${col}" aria-label="${l}"><span class="pix">${l}</span></button>`;
  const face = (pid, cls = '', style = '') => {
    const cc = k.consoleColors(pid);
    if (['gb', 'gbc', 'nes', 'gba'].includes(pid))
      return `<div class="mq-face two ${cls}" style="${style}" role="group" aria-label="Face buttons">${fb('B', cc.B, 'b')}${fb('A', cc.A, 'a')}</div>`;
    return `<div class="mq-face ${cls}" style="${style}" role="group" aria-label="Face buttons">${fb('Y', cc.Y, 'y')}${fb('X', cc.X, 'x')}${fb('B', cc.B, 'b')}${fb('A', cc.A, 'a')}</div>`;
  };
  const shoulder = (s, cls = '', style = '') => `<button class="mq-shl ${s.toLowerCase()} ${cls}" style="${style}" aria-label="${s} shoulder"><span class="pix">${s}</span></button>`;
  const tpill = (label, cls = '', style = '') => `<button class="mq-tpill ${cls}" style="${style}" aria-label="${label}"><span class="pix">${label}</span></button>`;
  const menuBtn = (cls = '', style = '') => `<button class="mq-menu ${cls}" style="${style}" aria-label="Menu">${logo(22)}</button>`;

  // Landscape geometry: centre the game, keep rails wide enough for the controls.
  const landGeo = (c, ar) => {
    const s = c.safe;
    const rail = 132;
    let gh = c.h - 24 - s.bottom;
    let gw = gh * ar;
    if (c.w - gw - s.left - s.right < rail * 2) {
      gw = c.w - s.left - s.right - rail * 2;
      gh = gw / ar;
    }
    const gx = (c.w - gw) / 2;
    const gy = Math.max(10, (c.h - s.bottom - gh) / 2);
    return { gw, gh, gx, gy, rw: gx - s.left };
  };

  // iPhone Duo (open and closed): every player takes its geometry from k.stage. The game shrinks
  // to leave rails for the controls and nothing is ever drawn over it; the d-pad and face buttons
  // sit low in the rails where thumbs rest, L/R just above them, Select · Menu · Start in the row
  // under the game, and notices land in the free spot the stage leaves for them.
  const isDuo = (c) => !!(c.device && c.device.duo);
  const px = (n) => `${Math.round(n)}px`;
  const atRect = (r) => `left:${px(r.x)};top:${px(r.y)};width:${px(r.w)};height:${px(r.h)}`;
  const atDisc = (d) => `left:${px(d.cx - d.size / 2)};top:${px(d.cy - d.size / 2)};width:${px(d.size)};height:${px(d.size)}`;
  const atRow = (t) => `left:${px(t.cx - t.w / 2)};top:${px(t.cy - t.h / 2)};width:${px(t.w)};height:${px(t.h)}`;
  const duoStage = (c, p, gid, v, { controls = true, toast = false, cls = '' } = {}) => {
    const st = k.stage(c, { aspect: k.aspect(gid) });
    return `<div class="mq-pl duo ${c.device.id} ${cls}" style="--S:${st.size}px">
      <div class="mq-pl-view" style="${atRect(st.game)}">${shot(gid, v)}</div>
      ${
        controls
          ? `<div class="mq-duoctl" role="group" aria-label="Touch controls">
        ${shoulder('L', 'duo', atRect(st.l))}${shoulder('R', 'duo', atRect(st.r))}
        ${dpad('duo', atDisc(st.dpad))}${face(p.id, 'duo', atDisc(st.face))}
        <div class="mq-duo-tert" style="${atRow(st.tertiary)}">${tpill('Select')}${menuBtn()}${tpill('Start')}</div>
      </div>`
          : ''
      }
      ${toast ? toastHTML('at', `left:${px(st.toast.cx)};top:${px(st.toast.cy)}`) : ''}
    </div>`;
  };

  const playerLandscape = (c, { toast = false, gid = 'smetroid', v = 'b', controls = true } = {}) => {
    const g = k.game(gid);
    const p = k.platformOf(g);
    const ar = k.aspect(gid);
    if (isDuo(c)) return duoStage(c, p, gid, v, { controls, toast });
    const G = landGeo(c, ar);
    return `<div class="mq-pl" style="--rw:${G.rw}px">
      <div class="mq-pl-view" style="left:${G.gx}px;top:${G.gy}px;width:${G.gw}px;height:${G.gh}px">${shot(gid, v)}</div>
      ${controls ? `<div class="mq-rail l" style="left:${c.safe.left}px;width:${G.rw}px;bottom:${c.safe.bottom}px">${shoulder('L')}${dpad()}<div class="mq-tert">${menuBtn()}${tpill('Select')}</div></div>
      <div class="mq-rail r" style="right:${c.safe.right}px;width:${G.rw}px;bottom:${c.safe.bottom}px">${shoulder('R')}${face(p.id)}<div class="mq-tert">${tpill('Start')}</div></div>` : ''}
      ${toast ? toastHTML() : ''}
    </div>`;
  };
  const toastHTML = (cls = '', style = '') => `<div class="mq-toast ${cls}" style="${style}" role="status"><span class="mq-toast-ic">${i('check', 14)}</span><span>Automatic resume updated</span>${stk('waiting', { icon: false })}</div>`;

  // ================================================================
  // Screens
  // ================================================================
  const screens = {};
  const ACCOUNT = { avatar: 'R', label: 'Account and settings · ryan · online', badge: true };

  /* ---------------- Onboarding ---------------- */
  screens.welcome = (c) => {
    const P = D().platforms.filter((p) => p.tier !== 'unsupported');
    // Duo open: the wall runs the full width of the display, over the fold.
    const per = wide(c) ? 11 : 6;
    const rows = [0, 1, 2, 3, 4, 5].map((r) => P.slice((r * 5) % P.length).concat(P, P).slice(0, per));
    const wall = rows
      .map((row, r) => `<div class="mq-wall-row r${r}">${row.map((p) => `<span class="mq-wall-tile" style="${pv(p, c)}">${sil(p.id)}<b>${esc(p.short.toUpperCase())}</b></span>`).join('')}</div>`)
      .join('');
    const tick = D().platforms.map((p) => `<span>${esc(p.short.toUpperCase())}</span><i></i>`).join('');
    return c.sys.shell({
      tabBar: false,
      titleMode: 'none',
      heroUnderBar: true,
      edge: false,
      body: `<div class="mq-welcome">
        <div class="mq-wall" aria-hidden="true"><div class="mq-wall-in">${wall}</div></div>
        <div class="mq-wticker" aria-hidden="true"><div class="mq-ticker">${tick}${tick}</div></div>
        <div class="mq-wbody">
          <div class="mq-brand">${logo(34)}<span>rommio</span></div>
          <h1>Your RomM library is a console now.</h1>
          <p class="mq-lede">Pick a console, pick a cartridge, press play — offline too. Game saves and resume states follow you to every device.</p>
          <div class="mq-wkeys">${key('Connect to RomM', { tone: 'sun', trail: 'arrow-right', cls: 'wide' })}${key('Scan setup code', { tone: 'ghost', icon: 'qr', cls: 'wide' })}</div>
          <p class="mq-fine">Uses your own RomM server. Rommio includes no games.</p>
        </div>
      </div>`,
    });
  };

  // Onboarding: one column on phones; on Duo open one wide grid per step (areas named in CSS).
  const obShell = (c, opts, primary, secondary) => c.sys.shell({ ...opts, body: `<div class="mq-ob">${primary}${secondary}</div>` });
  const obWide = (c, opts, cls, body) => c.sys.shell({ ...opts, body: `<div class="mq-ob wide ${cls}">${body}</div>` });

  // The server, drawn as the cartridge you are plugging in.
  const serverCart = (cls = '') => `<article class="mq-cart mq-scart ${cls}" aria-label="romm.homelab.dev, RomM 5.2.0, reachable">
      <div class="mq-cart-lip" aria-hidden="true"><i></i><i></i></div>
      <div class="mq-cart-label mq-scart-label">${logo(44)}<b>romm.homelab.dev</b><small>Home server · Cloudflare Access</small></div>
      <div class="mq-cart-foot"><span class="mq-lcd"><span class="pix lbl">RomM</span><b class="pix">5.2.0</b></span><span class="mq-stk mint solid">${i('check', 13)}<span>Reachable</span></span></div>
      <div class="mq-cart-grip" aria-hidden="true">${'<i></i>'.repeat(7)}</div>
    </article>`;

  screens.connect = (c) => {
    const disc = D().server.discovered;
    const access = ['Automatic', 'Direct', 'Cloudflare Access', 'Service token'];
    const form = `${steps(1)}
        <p class="mq-ob-lede">Where does your RomM live? Use the address you open in a browser.</p>
        <div class="mq-slot ok">
          <label for="mq-server" class="pix">Server address</label>
          <div class="mq-slot-in">${i('globe', 20)}<input id="mq-server" value="romm.homelab.dev" readonly><span class="mq-slot-ok" aria-label="Reachable">${i('check', 16)}</span></div>
          <p class="mq-slot-note ok" role="status">${i('shield', 15)}<span><b>RomM 5.2.0</b> · protected by Cloudflare Access</span></p>
        </div>
        ${head('Access method', { cls: 'sm' })}
        <div class="mq-opts" role="radiogroup" aria-label="Access method">${access
          .map((o, n) => `<button class="mq-opt${n ? '' : ' on'}" role="radio" aria-checked="${!n}">${n ? '' : i('check', 14)}<span>${esc(o)}</span></button>`)
          .join('')}</div>
        <p class="mq-ob-hint">Automatic found Cloudflare Access in front of this server and will sign you through it.</p>`;
    const saved = `${head('Saved and nearby', { cls: 'sm' })}
        <div class="mq-card mq-list">${disc
          .map(
            (s, n) => `<div class="mq-row"><span class="mq-ico ${n ? 'sky' : 'sun'}">${i(n ? 'wifi' : 'server', 19)}</span><span class="mq-row-t"><b>${esc(s.name)}</b><small>${esc(s.note)}</small>${
              s.secure ? '' : `<small class="mq-err">${i('alert-circle', 13)}Not encrypted — Rommio will ask before sending a password</small>`
            }</span>${s.secure ? `<span class="mq-lock" aria-label="Encrypted">${i('lock', 16)}</span>` : ''}${chev()}</div>`,
          )
          .join('')}</div>`;
    const opts = { tabBar: false, back: 'Welcome', title: 'Plug in', primary: { icon: 'arrow-right', label: 'Connect' } };
    // Duo open: the slot and its options run across the fold; the server cart you're plugging in
    // stands beside them; saved servers are full-width rows underneath.
    if (wide(c)) return obWide(c, opts, 'connect', `<div class="ob-a">${form}</div><div class="ob-cart">${head('Found it', { cls: 'sm' })}${serverCart()}</div><div class="ob-b">${saved}</div>`);
    return obShell(c, opts, form, saved);
  };

  screens.signin = (c) => {
    const ticket = `<div class="mq-ticket">
          <span class="mq-ticket-main"><span class="pix">Server</span><b>romm.homelab.dev</b><small>RomM 5.2.0 · Cloudflare Access verified</small></span>
          <button class="mq-ticket-stub" aria-label="Change server">${i('swap', 20)}<span class="pix">Change</span></button>
        </div>`;
    const fields = `<p class="mq-ob-lede">Sign in with your RomM account.</p>
        <div class="mq-slot">
          <label for="mq-user" class="pix">Username</label>
          <div class="mq-slot-in">${i('user', 20)}<input id="mq-user" value="ryan" readonly></div>
        </div>
        <div class="mq-slot focus">
          <label for="mq-pass" class="pix">Password</label>
          <div class="mq-slot-in">${i('lock', 20)}<input id="mq-pass" type="password" value="correcthorsebattery" readonly><button class="mq-eye" aria-label="Show password">${i('eye', 20)}</button></div>
        </div>
        <div class="mq-or"><span>or</span></div>
        ${key('Continue with single sign-on', { tone: 'ghost', icon: 'key', cls: 'wide' })}
        <p class="mq-fine">Your password goes straight to romm.homelab.dev. Rommio keeps only a session token.</p>`;
    const opts = { tabBar: false, back: 'Change server', title: 'Player 1', primary: { icon: 'log-in', label: 'Sign in' } };
    if (wide(c))
      return obWide(
        c,
        opts,
        'signin',
        `<div class="ob-a">${steps(2)}${fields}</div><div class="ob-cart">${head('Plugged in', { cls: 'sm' })}${serverCart()}<div class="mq-ob-change">${key('Change server', { tone: 'ghost', icon: 'swap', size: 'sm' })}<small>Your account and saves stay on this server.</small></div></div>`,
      );
    return obShell(c, opts, `${steps(2)}${ticket}${fields}`, '');
  };

  screens.hydrate = (c) => {
    const P = D().platforms;
    const tiles = P.map((p) => `<span class="mq-hy-tile" style="${pv(p, c)}" title="${esc(p.name)}">${sil(p.id)}</span>`).join('');
    const line = (label, v, done = true) =>
      `<li class="${done ? 'done' : 'live'}"><span class="mq-hy-ic">${i(done ? 'check' : 'image', 15)}</span><b>${esc(label)}</b><span class="pix">${esc(v)}</span></li>`;
    const load = `${steps(3)}
        <p class="mq-ob-lede">Caching Home server so it plays offline.</p>
        <section class="mq-hy">
          <div class="mq-hy-grid" aria-label="22 consoles loaded">${tiles}</div>
          <ul class="mq-hy-list">
            ${line('Consoles', '22')}
            ${line('Cartridges', '2,164')}
            ${line('Cabinets', 'Done')}
            <li class="live"><span class="mq-hy-ic">${i('image', 15)}</span><b>Artwork</b><span class="pix">61%</span></li>
          </ul>
          ${pbar(0.61, { segs: 24, tone: 'sun', live: true, cls: 'mq-hy-bar' })}
        </section>`;
    const rest = `<section class="mq-follow">
          <span class="mq-follow-devs" aria-hidden="true"><span>${i('smartphone', 20)}</span><i></i><span class="hub">${logo(22)}</span><i></i><span>${i('tablet', 20)}</span></span>
          <p><b>Your saves follow you.</b> Game-save data and resume states sync through RomM — stop on one device, continue on another.</p>
        </section>
        <div class="mq-tkeys two">
          ${tkey('Keep artwork offline', true, 'image', 'grape', 'Covers without a connection')}
          ${tkey('Sync on cellular', false, 'signal', 'sky', 'Wi-Fi only until you switch')}
        </div>
        <p class="mq-fine">You can start now — artwork keeps loading in the background.</p>`;
    const opts = { tabBar: false, title: 'Loading up', primary: { icon: 'play', label: 'Start playing' } };
    // Duo open: every console lights up in one long row, the four counters sit side by side,
    // and the sync promise and its two choices share one row beneath.
    if (wide(c)) return obWide(c, opts, 'hydrate', `<div class="ob-a">${load}</div><div class="ob-b">${rest}</div>`);
    return obShell(c, opts, load, rest);
  };

  /* ---------------- Arcade ---------------- */
  screens.home = (c) => {
    const P = playing();
    const statusRow = droid(c) ? `<div class="mq-arc-status inline">${bayStk()}</div>` : `<div class="mq-arc-status">${bayStk()}</div>`;
    if (wide(c)) {
      // Duo open: the whole cabinet row. The marquee strip runs the full width, every cartridge you're
      // playing stands in one row that crosses the fold (the next one peeks in), and the
      // two-player choice and the loading bay share the floor beneath.
      const row = `<div class="mq-cabrow" aria-label="Now playing, ${P.length} cartridges">${P.map((m, n) => cart(m, c, { size: n ? '' : 'now' })).join('')}</div>`;
      return c.sys.shell({
        tab: 'arcade',
        title: 'Arcade',
        actions: [ACCOUNT],
        body: `<div class="mq-arc duo">${statusRow}${marqueeStrip()}${row}<div class="mq-arc-foot">${twoPlayers(c)}${bayCard(c)}</div></div>`,
      });
    }
    const pager = `<div class="mq-pager" aria-label="Now playing, ${P.length} cartridges">${P.map((m) => cart(m, c)).join('')}</div>
      <div class="mq-dots" aria-hidden="true">${P.map((_, n) => `<i class="${n ? '' : 'on'}"></i>`).join('')}</div>`;
    return c.sys.shell({
      tab: 'arcade',
      title: 'Arcade',
      actions: [ACCOUNT],
      body: `<div class="mq-arc">${statusRow}${marqueeStrip()}${pager}${twoPlayers(c)}${bayCard(c)}</div>`,
    });
  };

  /* ---------------- Consoles ---------------- */
  screens.library = (c) => {
    const snes = k.platform('snes');
    const t = k.totals();
    const sub = `<p class="mq-sub pix">${t.platforms} consoles · ${k.num(t.games)} carts · ${t.installed} installed</p>`;
    const cabs = `<button class="mq-cabentry">
        <span class="mq-cabentry-art" aria-hidden="true">${['sun', 'coral', 'mint'].map((x) => `<i class="${x}"></i>`).join('')}</span>
        <span class="mq-row-t"><b>Cabinets</b><small>4 of yours · 2 auto-fill · 3 by genre and era</small></span>${chev()}
      </button>`;
    if (wide(c)) {
      // Duo open: one select screen. The last-played console is a full-width band whose plugged-in
      // cartridges stand across the fold; every console follows in ten columns, tier by tier.
      const inst = snesGames().filter((g) => g.installed);
      const band = `<section class="mq-cband" style="${pv(snes, c)}">
          ${sil(snes.id, 'mq-cpanel-sil')}
          <div class="mq-cband-id">
            <span class="pix mq-cpanel-eye">Last played</span>
            <b class="mq-cpanel-short">${esc(snes.short.toUpperCase())}</b>
            <span class="mq-cpanel-name">${esc(snes.name)} · ${esc(snes.maker)} · ${snes.year}</span>
            <span class="mq-cpanel-tags">${tierStk(snes.tier, true)}<span class="mq-stk plain">${i('cpu', 13)}<span>${esc(snes.core)}</span></span></span>
          </div>
          <div class="mq-cband-shelf" aria-label="In the console: ${snes.installed} cartridges">
            <span class="pix mq-cband-lbl">In the console · ${snes.installed} of ${snes.games}</span>
            <span class="mq-cband-carts">${inst.map((g) => `<span class="mq-shelfcart">${cover(g.id)}<i class="mq-led" aria-hidden="true"></i></span>`).join('')}</span>
          </div>
          ${key('Open rack', { tone: 'ink', trail: 'arrow-right', size: 'sm', cls: 'mq-cpanel-go' })}
        </section>`;
      return c.sys.shell({
        tab: 'consoles',
        title: 'Consoles',
        actions: [{ icon: 'sort', label: 'Sort consoles' }],
        body: `<div class="mq-lib duo">${sub}<div class="mq-pad-x">${band}${selectGrid(c, 'snes', { cols: 10, w: 74 })}${cabs}</div></div>`,
      });
    }
    return c.sys.shell({
      tab: 'consoles',
      title: 'Consoles',
      actions: [{ icon: 'sort', label: 'Sort consoles' }],
      body: `<div class="mq-lib">${sub}${consolePanel(snes, c)}${selectGrid(c, 'snes')}${cabs}</div>`,
    });
  };

  const platformHero = (p, c, cls = '', aside = '') => `
    <header class="mq-phero ${cls}" style="${pv(p, c)}">
      ${sil(p.id, 'mq-phero-sil')}
      <div class="mq-phero-in">
        <b class="mq-phero-short">${esc(p.short.toUpperCase())}</b>
        <span class="mq-phero-name">${esc(p.name)} · ${esc(p.maker)} · ${p.year}</span>
        <span class="mq-phero-stats"><span><b class="pix">${p.games}</b>carts</span><span><b class="pix">${p.installed}</b>in the console</span></span>
        <span class="mq-phero-tags">${tierStk(p.tier, true)}<span class="mq-stk plain">${i('cpu', 13)}<span>${esc(p.core)} ready</span></span></span>
      </div>
      ${aside}
    </header>`;
  const filterBar = (p) => `<div class="mq-filters" role="toolbar" aria-label="Filter and sort">
      <div class="mq-chips" role="tablist">
        <button class="mq-chip on" role="tab" aria-selected="true">All <span class="pix">${p.games}</span></button>
        <button class="mq-chip" role="tab" aria-selected="false">Installed <span class="pix">${p.installed}</span></button>
        <button class="mq-chip" role="tab" aria-selected="false">${i('heart', 14)}<span class="sr">Favourites</span><span class="pix">2</span></button>
      </div>
      <button class="mq-sort">${i('sort', 16)}<span>Recent</span></button>
    </div>`;

  screens.platform = (c) => {
    const p = k.platform('snes');
    const games = snesGames();
    if (wide(c)) {
      // Duo open: the console's hero band runs the full width (its cabinets and emulator ride along
      // on the trailing side), then the rack is one set of wide rows with extra columns.
      const aside = `<div class="mq-phero-aside">
          <span class="pix mq-phero-lbl">Cabinets with SNES carts</span>
          <span class="mq-cabchips"><span class="mq-chip">Metroidvanias <span class="pix">1</span></span><span class="mq-chip">Finish in 2026 <span class="pix">2</span></span><span class="mq-chip">Couch co-op <span class="pix">2</span></span></span>
          <span class="pix mq-phero-lbl">Emulator</span>
          <span class="mq-phero-emu">${i('cpu', 17)}<b>Snes9x 1.63</b><small>Touch controls · landscape</small>${stk('synced', { label: 'Ready', solid: true })}</span>
        </div>`;
      return c.sys.shell({
        tab: 'consoles',
        back: 'Consoles',
        title: 'Super Nintendo',
        titleMode: 'none',
        heroUnderBar: true,
        edge: false,
        actions: [{ icon: 'search', label: 'Search Super Nintendo' }],
        body: `${platformHero(p, c, 'duo', aside)}<div class="mq-plat duo">${filterBar(p)}${rack(c, games, { wide: true })}</div>`,
      });
    }
    return c.sys.shell({
      tab: 'consoles',
      back: 'Consoles',
      title: 'Super Nintendo',
      titleMode: 'none',
      heroUnderBar: true,
      edge: false,
      actions: [{ icon: 'search', label: 'Search Super Nintendo' }],
      body: `${platformHero(p, c)}<div class="mq-plat">${filterBar(p)}${rack(c, games)}</div>`,
    });
  };

  /* ---------------- Cabinets (collections) ---------------- */
  screens.collections = (c) => {
    const cols = D().collections;
    const mine = cols.filter((x) => x.kind === 'manual');
    const smart = cols.filter((x) => x.kind === 'smart');
    const auto = cols.filter((x) => x.kind === 'auto');
    const tones = ['coral', 'grape', 'sun', 'sky'];
    const smartRows = smart
      .map(
        (x) => `<div class="mq-auto">
          <span class="mq-auto-art" aria-hidden="true">${x.games.slice(0, 3).map((id) => cover(id)).join('')}</span>
          <span class="mq-row-t"><b>${esc(x.name)}</b><small class="mq-rule">${i('filter', 12)}${esc(x.rule)}</small></span>
          <span class="pix mq-auto-n">${x.games.length}</span>
        </div>`,
      )
      .join('');
    const autoChips = auto.map((x) => `<button class="mq-genre"><b>${esc(x.name)}</b><small>${esc(x.rule)}</small><span class="pix">${x.games.length}</span></button>`).join('');
    const intro = `<p class="mq-cabs-intro">Machines you load with carts from any console.</p>`;
    const grid = `<div class="mq-cabs">${mine.map((x, n) => cabinet(x, tones[n], c, { sel: wide(c) && x.id === 'metroidvania' })).join('')}</div>`;
    const rest = `${head('Auto-fill', { count: smart.length, cls: 'sm' })}<div class="mq-card mq-autos">${smartRows}</div>
      ${head('Built by Rommio', { count: auto.length, cls: 'sm' })}<div class="mq-genres">${autoChips}</div>`;
    if (wide(c)) {
      // Duo open: a real list → detail. The narrow list is the arcade floor (every cabinet, then the
      // auto-fill and genre machines); the wide detail is the selected cabinet opened up, its marquee
      // and screen across the top and its cartridges racked beneath. One background, no divider.
      const mv = k.collection('metroidvania');
      const mvGames = mv.games.map((id) => k.game(id));
      const mvConsoles = new Set(mvGames.map((g) => g.platform)).size;
      const mvIn = mvGames.filter((g) => g.installed).length;
      const secondary = `<div class="mq-cabopen">
        <header class="mq-cabopen-h grape">
          <span class="mq-cabopen-t"><span class="pix">Cabinet · selected</span><b>${esc(mv.name)}</b><small>${esc(mv.note)} · ${mv.games.length} carts from ${mvConsoles} consoles</small></span>
          <span class="mq-cabopen-screen" aria-hidden="true">${mv.games.slice(0, 4).map((id, n) => cover(id, `f${n}`)).join('')}</span>
          ${bulbs(18, 18, 'edge')}
        </header>
        <div class="mq-cabopen-keys">${key('Shuffle', { tone: 'sun', icon: 'shuffle', size: 'sm' })}${key('Edit cabinet', { tone: 'ghost', icon: 'pen', size: 'sm' })}<span class="pix mq-cabopen-n">${mvIn} of ${mv.games.length} installed</span></div>
        <div class="mq-rack">${mvGames.map((g) => spine(g, c)).join('')}</div>
      </div>`;
      return c.sys.shell({
        tab: 'consoles',
        back: 'Consoles',
        title: 'Cabinets',
        actions: [{ icon: 'plus', label: 'New cabinet' }],
        split: { primaryTitle: 'Cabinets', primary: `<div class="mq-pad-x mq-cabs-list">${intro}${grid}${rest}</div>`, secondary },
      });
    }
    return c.sys.shell({
      tab: 'consoles',
      back: 'Consoles',
      title: 'Cabinets',
      actions: [{ icon: 'plus', label: 'New cabinet' }],
      body: `<div class="mq-pad-x">${intro}${grid}${rest}</div>`,
    });
  };

  /* ---------------- Game ---------------- */
  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const P = D().progress;
    const rec = records().find((r) => r.game === g.id);
    const hero = `<header class="mq-ghero" style="${pv(p, c)}">
        <span class="mq-rays" aria-hidden="true"></span>
        <figure class="mq-sticker">${cover(g.id)}<span class="mq-rating pix" aria-label="Rating ${g.rating}">${g.rating}</span></figure>
        <dl class="mq-gstats">
          <div><dt class="pix">Console</dt><dd>${pchip(p, c)}</dd></div>
          <div><dt class="pix">Year</dt><dd>${g.year}</dd></div>
          <div><dt class="pix">Maker</dt><dd>${esc(g.publisher)}</dd></div>
          <div><dt class="pix">Players</dt><dd>${g.players}</dd></div>
        </dl>
      </header>`;
    const name = `<div class="mq-gname"><h1>${esc(g.title)}</h1><p>${esc(g.genre)}</p></div>`;
    const board = scoreboard([
      ['Playtime', score(g.hours)],
      ['Sessions', String(rec.count)],
      ['Longest', mins(rec.longest.minutes)],
    ]);
    const resume = `<section class="mq-card mq-resume">
        <div class="mq-resume-top">
          <span class="mq-resume-shot">${shot(g.id, P.resume.frame)}</span>
          <span class="mq-row-t"><span class="pix mq-eyebrow">Automatic resume</span><b>Maridia glass tube</b><small>${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}</small>${stk(P.resume.remote)}</span>
        </div>
        ${key('Start with game save', { tone: 'ghost', icon: 'save', cls: 'wide' })}
      </section>`;
    const mem = `<div class="mq-pad-x">${head('Memory card', { cls: 'sm', more: `${i('sync', 15)}<span>Sync now</span>` })}</div><div class="mq-mcs">${memoryCards(c)}</div>`;
    const file = `<section class="mq-card mq-file">
        <div class="mq-row"><span class="mq-ico ink">${i('cartridge', 19)}</span><span class="mq-row-t"><b>Super Metroid (USA).sfc</b><small>${esc(k.size(g.size))} · Installed on this iPhone</small></span><span class="mq-led" aria-label="Installed"></span></div>
        <div class="mq-file-tags">${tierStk(p.tier)}<span class="mq-stk mint">${i('cpu', 13)}<span>${esc(p.core)} ready</span></span>${stk('synced', { label: 'Game-save data synced' })}</div>
        <div class="mq-file-foot">${key('Remove download', { tone: 'ghost', icon: 'trash', size: 'sm' })}<small>Progress stays on your server.</small></div>
      </section>`;
    const blurb = `<p class="mq-blurb">${esc(g.blurb)}</p>`;
    const opts = {
      tab: 'consoles',
      back: 'Super Nintendo',
      title: g.title,
      titleMode: 'none',
      primary: { icon: 'play', label: 'Resume' },
      actions: [{ icon: 'heart', label: 'Favourite' }, { icon: 'more', label: 'More' }],
      heroUnderBar: true,
      edge: false,
    };
    if (wide(c)) {
      // Duo open: one character card across the whole display — sticker, name, player stats and the
      // scoreboard in a single band over the fold — with the resume point and memory card beneath.
      const heroDuo = `<header class="mq-ghero duo" style="${pv(p, c)}">
          <span class="mq-rays" aria-hidden="true"></span>
          <figure class="mq-sticker">${cover(g.id)}<span class="mq-rating pix" aria-label="Rating ${g.rating}">${g.rating}</span></figure>
          <div class="mq-ghero-main">
            <h1>${esc(g.title)}</h1>
            <p>${esc(g.genre)}</p>
            <dl class="mq-gstats row">
              <div><dt class="pix">Console</dt><dd>${pchip(p, c)}</dd></div>
              <div><dt class="pix">Year</dt><dd>${g.year}</dd></div>
              <div><dt class="pix">Maker</dt><dd>${esc(g.publisher)}</dd></div>
              <div><dt class="pix">Players</dt><dd>${g.players}</dd></div>
            </dl>
          </div>
          ${board}
        </header>`;
      return c.sys.shell({
        ...opts,
        body: `${heroDuo}<div class="mq-game duo"><div class="g-a">${resume}${file}</div><div class="g-b">${mem}${blurb}</div></div>`,
      });
    }
    return c.sys.shell({ ...opts, body: `${hero}${name}<div class="mq-pad-x">${board}</div>${resume}${mem}${file}${blurb}` });
  };

  /* ---------------- Loading bay (downloads) ---------------- */
  screens.downloads = (c) => {
    const dl = D().downloads;
    const run = dl.find((d) => d.state === 'running');
    const rg = k.game(run.game);
    const queued = dl.filter((d) => d.state === 'queued' || d.state === 'waiting');
    const failed = dl.find((d) => d.state === 'failed');
    const fg = k.game(failed.game);
    const done = dl.filter((d) => d.state === 'done');
    const st = D().storage;
    const loading = `<section class="mq-loading" style="${pv(k.platformOf(rg), c)}">
        <div class="mq-loading-top">
          ${cover(rg.id, 'mq-loading-cov')}
          <span class="mq-row-t"><span class="pix mq-blink">Now loading</span><b>${esc(rg.title)}</b><small>${esc(k.platformOf(rg).short)} · ${esc(gb(run.total))}</small></span>
          <b class="pix mq-loading-pct">${k.pct(run.progress)}</b>
        </div>
        ${pbar(run.progress, { segs: 24, tone: 'mint', live: true, cls: 'xl' })}
        <div class="mq-loading-foot"><span><b>${esc(gb(run.done))} of ${esc(gb(run.total))}</b> · ${run.rate} MB/s · <b class="mq-eta">${esc(run.eta)} left</b></span>
          <span class="mq-loading-btns">${key('', { tone: 'ghost', icon: 'pause', size: 'sq', aria: 'Pause download' })}${key('', { tone: 'ghost', icon: 'x', size: 'sq', aria: 'Cancel download' })}</span></div>
      </section>`;
    const fail = `<section class="mq-fail">
        ${cover(fg.id, 'mq-thumb')}
        <span class="mq-row-t"><b>${esc(fg.title)}</b><small class="mq-err">${i('alert-circle', 13)}${esc(failed.reason)} at ${k.pct(failed.progress)}</small></span>
        ${key('Retry', { tone: 'coral', icon: 'restart', size: 'sm' })}
      </section>`;
    const queue = `<ol class="mq-conveyor">${queued
      .map((d, n) => {
        const g = k.game(d.game);
        return `<li class="${d.state}"><span class="mq-slotn pix">${n + 1}</span>${cover(g.id, 'mq-thumb')}<span class="mq-row-t"><b>${esc(g.title)}</b><small>${esc(k.platformOf(g).short)} · ${esc(gb(d.total))}</small></span>${
          d.state === 'waiting' ? `<span class="mq-stk sun">${i('wifi-off', 13)}<span>Waiting for Wi-Fi</span></span>` : `<span class="mq-grip" aria-label="Reorder">${i('grip', 18)}</span>`
        }</li>`;
      })
      .join('')}</ol>`;
    const delivered = `<div class="mq-card mq-list">${done
      .map((d) => {
        const g = k.game(d.game);
        return `<div class="mq-row">${cover(g.id, 'mq-thumb sm')}<span class="mq-row-t"><b>${esc(g.title)}</b><small>${esc(k.platformOf(g).short)} · ${esc(gb(d.total))} · ${esc(k.ago(d.at))}</small></span><span class="mq-led" aria-label="Installed"></span></div>`;
      })
      .join('')}</div>`;
    const fuel = `<section class="mq-fuel"><span class="mq-fuel-top"><b>Cartridges on this iPhone</b><span class="pix">${gb(st.roms)}</span></span>${pbar(st.roms / st.total, { segs: 32, tone: 'grape' })}<small>${gb(st.total - st.roms)} free of ${gb(st.total)}</small></section>`;
    const opts = { tab: 'arcade', back: 'Arcade', title: 'Loading bay', actions: [{ icon: 'pause', label: 'Pause all' }] };
    // Duo open: the Now Loading card runs the full width (its bar crosses the fold); the conveyor and
    // the cart that needs you take the wide lane beneath, deliveries and memory the narrow one.
    if (wide(c))
      return c.sys.shell({
        ...opts,
        body: `<div class="mq-pad-x mq-bay duo">${loading}<div class="mq-bay-grid">
          <div class="b-a">${head('Needs you', { count: 1, cls: 'sm', tone: 'coral' })}${fail}${head('Up next', { count: queued.length, cls: 'sm' })}${queue}</div>
          <div class="b-b">${head('Delivered', { count: done.length, cls: 'sm' })}${delivered}${head('Memory', { cls: 'sm' })}${fuel}</div>
        </div></div>`,
      });
    return c.sys.shell({
      ...opts,
      body: `<div class="mq-pad-x mq-bay">${loading}${head('Needs you', { count: 1, cls: 'sm', tone: 'coral' })}${fail}${head('Up next', { count: queued.length, cls: 'sm' })}${queue}${head('Delivered', { count: done.length, cls: 'sm' })}${delivered}${fuel}</div>`,
    });
  };

  /* ---------------- Search ---------------- */
  screens.search = (c) => {
    const S = D().search;
    const sm = k.game('smetroid');
    const mf = k.game('metfusion');
    const rank = (n, body, why) => `<li class="mq-lb-row${n === 1 ? ' top' : ''}"><span class="mq-lb-rank pix">${['1st', '2nd', '3rd', '4th', '5th'][n - 1]}</span>${body}<span class="mq-lb-why">${why}</span></li>`;
    const gameRes = (g, m, label) => {
      const p = k.platformOf(g);
      return `<span class="mq-lb-main">${cover(g.id, 'mq-lb-cov')}<span class="mq-row-t"><b>${esc(g.title)}</b><small>${pchip(p, c, 'sm')} ${esc(k.ago(m.at))}</small></span>${aKey(p.id, label, { size: 'dot', aria: `${label} ${g.title}` })}</span>`;
    };
    const mom = (id) => D().moments.find((m) => m.game === id);
    const board = `<ol class="mq-lb">
      ${rank(1, gameRes(sm, mom('smetroid'), 'Resume'), `<span class="mq-why">${i('check', 12)}Playable now</span><span class="mq-why">Title match</span>`)}
      ${rank(2, gameRes(mf, mom('metfusion'), 'Resume'), `<span class="mq-why">${i('check', 12)}Playable now</span><span class="mq-why">Title match</span>`)}
      ${rank(3, `<span class="mq-lb-main"><span class="mq-lb-cab grape" aria-hidden="true">${i('collections', 18)}</span><span class="mq-row-t"><b>Metroidvanias</b><small>Cabinet · 4 carts, 2 match</small></span>${chev()}</span>`, '<span class="mq-why">Contains matches</span>')}
      ${rank(4, `<span class="mq-lb-main">${ctile(k.platform('snes'), c, { w: 44 }).replace('mq-ctile', 'mq-ctile mini')}<span class="mq-row-t"><b>Super Nintendo</b><small>Console · 1 match</small></span>${chev()}</span>`, '')}
      ${rank(5, `<span class="mq-lb-main">${ctile(k.platform('gba'), c, { w: 44 }).replace('mq-ctile', 'mq-ctile mini')}<span class="mq-row-t"><b>Game Boy Advance</b><small>Console · 1 match</small></span>${chev()}</span>`, '')}
    </ol>`;
    const how = `<p class="mq-how">${i('info', 14)}<span>Ranked like a leaderboard: what you can play right now first, then recently played, then cabinets and consoles.</span></p>`;
    const chips = `<div class="mq-sugg" role="group" aria-label="Filters">${S.suggestions.map((s) => `<button class="mq-chip">${esc(s)}</button>`).join('')}</div>`;
    const recent = `${head('Recent', { cls: 'sm', more: 'Clear' })}<ul class="mq-recent">${S.recent.map((r) => `<li>${i('history', 16)}<span>${esc(r)}</span>${i('arrow-up-right', 16)}</li>`).join('')}</ul>`;
    const summary = `<div class="mq-lb-head"><span class="pix">2 carts · 1 cabinet · 2 consoles</span></div>`;
    if (wide(c)) {
      // Duo open: one wide high-score table. Each row reads rank · cart · why it placed · action
      // straight across the fold; how ranking works and recent searches sit underneath.
      const recents = `<div class="mq-srch-foot">${how}<div class="mq-recent-chips"><span class="pix mq-recent-lbl">Recent</span>${S.recent.map((r) => `<button class="mq-chip">${i('history', 15)}${esc(r)}</button>`).join('')}<button class="mq-more">Clear</button></div></div>`;
      return c.sys.shell({
        tab: 'search',
        title: 'Search',
        titleMode: 'none',
        edge: false,
        search: { value: 'metroid', focused: true },
        body: `<div class="mq-srch duo">${chips}${summary}${board.replace('class="mq-lb"', 'class="mq-lb wide"')}${recents}</div>`,
      });
    }
    return c.sys.shell({
      tab: 'search',
      title: 'Search',
      titleMode: 'none',
      edge: false,
      search: { value: 'metroid', focused: true },
      body: `<div class="mq-srch">${chips}${summary}${board}${how}${recent}</div>`,
    });
  };

  /* ---------------- Stats (signature) ---------------- */
  screens.signature = (c) => {
    const S = D().stats;
    const m = S.month;
    const days = S.days;
    const max = Math.max(...days);
    const DOW = ['T', 'F', 'S', 'S', 'M', 'T', 'W']; // 27 days ago was a Thursday; today is Wednesday.
    const heat = `<section class="mq-card mq-heat">
        <div class="mq-heat-h"><b>Last 28 days</b><span class="pix">${days.filter((d) => d).length} days played</span></div>
        <div class="mq-heat-grid" role="img" aria-label="Minutes played per day for the last 28 days">
          ${DOW.map((d) => `<span class="pix dow">${d}</span>`).join('')}
          ${days
            .map((v, n) => {
              const lvl = v === 0 ? 0 : v < 30 ? 1 : v < 60 ? 2 : v < 100 ? 3 : 4;
              return `<i class="l${lvl}${n === days.length - 1 ? ' today' : ''}" title="${v} min"></i>`;
            })
            .join('')}
        </div>
        <div class="mq-heat-key"><span>Less</span>${[0, 1, 2, 3, 4].map((l) => `<i class="l${l}"></i>`).join('')}<span>More</span><span class="mq-heat-best pix">Best day ${mins(max)}</span></div>
      </section>`;
    const totals = scoreboard([
      ['Played', mins(m.minutes)],
      ['Sessions', String(m.sessions)],
      ['Games', String(m.games)],
    ], 'wide3');
    const topMax = S.topPlatforms[0].minutes;
    const bars = `<section class="mq-card mq-bars">${S.topPlatforms
      .map((t) => {
        const p = k.platform(t.platform);
        return `<div class="mq-bar" style="${pv(p, c)}"><span class="mq-bar-name">${esc(p.short)}</span><span class="mq-bar-track"><i style="width:${(t.minutes / topMax) * 100}%"></i></span><span class="pix">${mins(t.minutes)}</span></div>`;
      })
      .join('')}</section>`;
    const R = records();
    const row = (r, n) => {
      const g = k.game(r.game);
      const p = k.platformOf(g);
      return `<li class="${n === 0 ? 'top' : ''}"><span class="pix mq-hs-rank">${n + 1}</span>${cover(g.id, 'mq-hs-cov')}<span class="mq-row-t"><b>${esc(g.title)}</b><small>${pchip(p, c, 'sm')} ${r.count} session${r.count > 1 ? 's' : ''}</small></span><span class="pix mq-hs-long">${mins(r.longest.minutes).replace(' ', '')}</span><span class="pix mq-hs-tot">${mins(r.total).replace(' ', '')}</span></li>`;
    };
    const hs = `<section class="mq-hs">
        <div class="mq-hs-h"><span class="pix">Rank</span><span class="pix">Game</span><span class="pix">Longest</span><span class="pix">Total</span></div>
        <ol>${R.slice(0, wide(c) ? 8 : 6).map(row).join('')}</ol>
      </section>`;
    const long = R.slice().sort((a, b) => b.longest.minutes - a.longest.minutes)[0].longest;
    const recs = `<div class="mq-recs">
        <article class="mq-rec sky"><span class="pix">Longest session</span><b>${esc(k.game(long.game).title)}</b><small>${mins(long.minutes)} · ${esc(long.note)} · ${dayAgo(long.day)}</small></article>
        <article class="mq-rec coral"><span class="pix">Most sessions</span><b>${esc(k.game(R[0].game).title)}</b><small>${R[0].count} sessions on 2 devices this month</small></article>
      </div>`;
    const title = 'Stats';
    // Duo open: one wide scoreboard. The lit sign spans the fold beside the month's totals; the
    // high-score board is the big panel, with the heatmap and top consoles stacked beside it.
    if (wide(c))
      return c.sys.shell({
        tab: 'stats',
        title,
        actions: [{ icon: 'calendar', label: 'Choose month' }],
        body: `<div class="mq-pad-x mq-stats duo">
          <div class="s-sign">${marqueeSign('big')}</div>
          <div class="s-tot">${scoreboard([
            ['Played', mins(m.minutes)],
            ['Sessions', String(m.sessions)],
            ['Games', String(m.games)],
          ], 'stack')}</div>
          <div class="s-left">${heat}${head('Top consoles', { cls: 'sm' })}${bars}</div>
          <div class="s-hs">${hs.replace('<section class="mq-hs">', `<section class="mq-hs"><div class="mq-hs-title"><b>High scores</b><span class="pix">This month</span></div>`)}</div>
          <div class="s-recs">${recs}</div>
        </div>`,
      });
    return c.sys.shell({
      tab: 'stats',
      title,
      actions: [{ icon: 'calendar', label: 'Choose month' }],
      body: `<div class="mq-pad-x mq-stats">${marqueeSign('big')}${totals}${heat}${head('High scores', { cls: 'sm', more: 'This month' })}${hs}${head('Top consoles', { cls: 'sm' })}${bars}${recs}</div>`,
    });
  };

  /* ---------------- Settings ---------------- */
  screens.settings = (c) => {
    const [home, sam] = D().profiles;
    const st = D().storage;
    const parts = [
      ['Cartridges', st.roms, 'coral'],
      ['Game saves', st.saves, 'mint'],
      ['States', st.states, 'sky'],
      ['Cache', st.cache, 'grape'],
      ['Cores & BIOS', st.cores + st.bios, 'sun'],
    ];
    const used = parts.reduce((n, x) => n + x[1], 0);
    const p1 = `<section class="mq-p1">
        <div class="mq-p1-top"><span class="mq-p1-av" aria-hidden="true">R</span><span class="mq-row-t"><span class="pix">Player 1</span><b>${esc(home.user)}</b><small>${esc(home.name)} · ${esc(home.url)}</small></span>${key('', { tone: 'ink', icon: 'more', size: 'sq', aria: 'Account options' })}</div>
        <div class="mq-p1-tags"><span class="mq-stk solid ink"><i class="mq-live"></i><span>Online</span></span>${stk('synced', { label: `Synced ${k.ago(home.lastSync)}`, cls: 'onslab' })}<span class="mq-stk onslab">${esc(home.version)}</span></div>
      </section>`;
    const p2 = `<div class="mq-card mq-list mq-p2"><div class="mq-row"><span class="mq-ico grape">${i('users', 19)}</span><span class="mq-row-t"><span class="pix mq-eyebrow">Player 2</span><b>${esc(sam.name)}</b><small>${esc(sam.user)} · ${esc(sam.url)}</small></span><span class="mq-stk grape">${i('wifi-off', 13)}<span>Offline-ready</span></span></div></div>`;
    const player = `${p1}${p2}`;
    const sync = `${head('Sync', { cls: 'sm' })}<div class="mq-tkeys">
        ${tkey('Game saves', true, 'save', 'mint', 'Every session')}
        ${tkey('Resume states', true, 'history', 'sun', 'Pick up anywhere')}
        ${tkey('Cellular', false, 'signal', 'sky', 'Wi-Fi only')}
      </div>`;
    const play = `${head('Play', { cls: 'sm' })}<div class="mq-card mq-list">
        <div class="mq-row"><span class="mq-ico coral">${i('gamepad', 19)}</span><span class="mq-row-t"><b>Controls</b><small>Button mapping, touch layout, haptics</small></span>${chev()}</div>
        <div class="mq-row"><span class="mq-ico sky">${i('cpu', 19)}</span><span class="mq-row-t"><b>Cores & BIOS</b><small class="mq-tags"><span class="mq-stk sun sm">1 needs BIOS</span><span class="mq-stk sky sm">1 needs setup</span></small></span>${chev()}</div>
      </div>`;
    const mem = `${head('Memory', { cls: 'sm' })}<section class="mq-card mq-mem">
        <div class="mq-mem-top"><b class="pix">${gb(used)}</b><span>used of ${gb(st.total)} on this iPhone</span></div>
        <div class="mq-mem-bar">${parts.map(([, v, t]) => `<i class="${t}" style="flex:${Math.max(v, 420)}"></i>`).join('')}<i class="free" style="flex:${st.total - used}"></i></div>
        <ul class="mq-mem-key">${parts.map(([n, v, t]) => `<li><i class="${t}"></i>${esc(n)}<span class="pix">${gb(v)}</span></li>`).join('')}</ul>
      </section>`;
    const about = `<div class="mq-card mq-list"><div class="mq-row"><span class="mq-ico ink">${logo(20)}</span><span class="mq-row-t"><b>About Rommio</b><small>Version 2.0 · Open-source licences</small></span>${chev()}</div></div>`;
    const coresList = `${head('Cores', { count: D().cores.length, cls: 'sm' })}<div class="mq-card mq-cores">${D()
      .cores.map((x) => {
        const s = { ready: ['mint', 'Ready'], bios: ['sun', 'Needs BIOS'], setup: ['sky', 'Finish setup'], controller: ['grape', 'Controller'] }[x.state];
        return `<div class="mq-core"><span class="mq-row-t"><b>${esc(x.name)}</b><small>${esc(x.family)}${x.note ? ` · ${esc(x.note)}` : ''}</small></span><span class="mq-stk ${s[0]} sm">${esc(s[1])}</span></div>`;
      })
      .join('')}</div>`;
    const opts = { tab: 'arcade', back: 'Arcade', title: 'Settings' };
    // Duo open: one settings board — Player 1, the sync keys and the memory bar run wide across the
    // fold; Player 2, play options, cores and about stack in a narrow side column.
    if (wide(c))
      return c.sys.shell({
        ...opts,
        body: `<div class="mq-pad-x mq-set duo"><div class="t-main">${p1}${sync}${mem}</div><div class="t-side">${p2}${play}${coresList}${about}</div></div>`,
      });
    return c.sys.shell({ ...opts, body: `<div class="mq-pad-x mq-set">${player}${sync}${play}${mem}${about}</div>` });
  };

  /* ---------------- Controls ---------------- */
  screens.controls = (c) => {
    const C = D().controls;
    // The preview is this device's own landscape player: on Duo closed, the k.stage layout.
    const duoC = c.device.id === 'duo-closed';
    const land = duoC ? Studio.DEVICES['duo-closed'].landscape : { w: 874, h: 402, safe: { top: 0, bottom: 21, left: 62, right: 62 } };
    const scale = duoC ? 0.52 : wide(c) ? 0.47 : 0.41;
    const pc = { ...c, w: land.w, h: land.h, safe: land.safe, device: { ...c.device, id: duoC ? 'duo-closed' : 'iphone', duo: duoC, fold: null } };
    const preview = `<div class="mq-prev" style="--s:${scale};--pw:${land.w}px;--ph:${land.h}px" aria-label="Live preview of the touch controls">
        <div class="mq-prev-in">${playerLandscape(pc)}</div>
        <span class="mq-prev-tag pix${duoC ? ' top' : ''}">Live preview</span>
      </div>`;
    const pad = `<div class="mq-padnote">${i('gamepad', 20)}<span><b>DualSense connected.</b> Touch controls hide while it’s in use.</span></div>`;
    const cc = k.consoleColors('snes');
    const map = `<section class="mq-card mq-map">
        <div class="mq-map-top">
          <div class="mq-map-face" aria-hidden="true">${['Y', 'X', 'B', 'A'].map((l) => `<i class="${l.toLowerCase()}" style="--fc:${cc[l]}"><span class="pix">${l}</span></i>`).join('')}</div>
          <span class="mq-row-t"><b>Console colours</b><small>Face buttons wear each console’s original colours</small></span>
          ${sw(true, 'Console colours')}
        </div>
        <div class="mq-map-row">${['snes', 'psx', 'n64', 'gbc', 'gba', 'genesis']
          .map((pid) => {
            const cols = Object.values(k.consoleColors(pid)).slice(0, 4);
            return `<span class="mq-map-set"><span class="mq-map-dots">${cols.map((x) => `<i style="background:${x}"></i>`).join('')}</span><span class="pix">${esc(k.platform(pid).short)}</span></span>`;
          })
          .join('')}</div>
      </section>`;
    const sliderCard = `<section class="mq-card mq-sliders" aria-label="Look and feel">
        ${slider('Opacity', k.pct(C.opacity), C.opacity, 'contrast', 'mint')}
        ${slider('Size', k.pct(C.size), 0.5, 'scaling', 'coral')}
        ${slider('Stick deadzone', k.pct(C.deadzone), C.deadzone / 0.4, 'crosshair', 'sky')}
      </section>`;
    const sliders = `${head('Look and feel', { cls: 'sm' })}${sliderCard}`;
    const keys = `<div class="mq-tkeys" role="group" aria-label="Switches">
        ${tkey('Touch controls', C.showTouch, 'hand', 'sun', 'Show on screen')}
        ${tkey('Hide with controller', C.autoHideOnController, 'gamepad', 'sky', 'DualSense')}
        ${tkey('Left-handed', C.leftHanded, 'flip', 'grape', 'Swap sides')}
        ${tkey('Haptics', C.haptics, 'vibrate', 'coral', 'Tap feedback')}
        ${tkey('OLED black', C.oled, 'moon', 'grape', 'Around the game')}
        ${tkey('Controller type', true, 'controller', 'mint', C.controllerType).replace('<span class="mq-lamp pix">On</span>', '<span class="mq-lamp pix">Auto</span>')}
      </div>`;
    const toggles = `${head('Switches', { cls: 'sm' })}${keys}`;
    const opts = { tab: 'arcade', back: 'Settings', title: 'Controls' };
    if (wide(c)) {
      // Duo open: the preview IS the player — Super Metroid with the real k.stage layout across the
      // canvas beside the vertical bar — and the tuning floats over the game, between the rails and
      // above Select · Menu · Start, so every control stays in view around it as it changes.
      const W = c.w - 72;
      const sc = { ...c, w: W };
      const st = k.stage(sc, { aspect: k.aspect('smetroid') });
      const stage = duoStage(sc, k.platform('snes'), 'smetroid', 'b');
      const floatBottom = c.h - (st.tertiary.cy - st.tertiary.h / 2) + 12;
      const card = `<section class="mq-ctl-float" style="left:${px(st.game.x + st.game.w / 2)};bottom:${px(floatBottom)}" aria-label="Tune the touch controls">
          <header class="mq-ctl-fh"><b>Controls</b><span class="pix">Live · Super Metroid</span></header>
          ${sliderCard}${keys}${map}
        </section>`;
      return c.sys.shell({
        ...opts,
        titleMode: 'none',
        heroUnderBar: true,
        edge: false,
        body: `<div class="mq-ctl duo"><div class="mq-stage" style="width:${W}px">${stage}</div>${pad}${card}</div>`,
      });
    }
    return c.sys.shell({ ...opts, body: `<div class="mq-pad-x mq-ctl">${preview}${pad}${map}${sliders}${toggles}</div>` });
  };

  /* ---------------- Player ---------------- */
  screens.player = { tone: 'light', render: (c) => playerLandscape(c, { toast: true }) };

  screens['player-portrait'] = {
    tone: 'light',
    render: (c) => {
      const g = k.game('linksawak');
      const p = k.platformOf(g);
      const ar = k.aspect(g.id);
      const s = c.safe;
      const wordmark = `<span class="mq-pp-brand pix">Game Boy <span class="c1">C</span><span class="c2">o</span><span class="c3">l</span><span class="c4">o</span><span class="c5">r</span></span>`;
      if (isDuo(c)) {
        // Duo (open and closed): k.stage puts the game full width at the top (crossing the fold when
        // open) and the controls in the band beneath it, low. The Game Boy has no shoulder buttons, so
        // the power lamp and wordmark take the strip where L/R would sit, between screen and pad.
        // Open, a thin lens wraps the screen; closed, the outer camera sits right above it, so none.
        const st = k.stage(c, { aspect: ar });
        const G = st.game;
        const lens = c.device.id === 'duo-open' ? 5 : 0;
        const markY = st.l.y + st.l.h / 2;
        return `<div class="mq-pp duo ${c.device.id}" style="${pv(p, c)};--S:${st.size}px">
          ${lens ? `<div class="mq-pp-lens" style="${atRect({ x: G.x - lens, y: G.y - lens, w: G.w + lens * 2, h: G.h + lens * 2 })}"></div>` : ''}
          <div class="mq-pp-view" style="${atRect(G)}">${shot(g.id, 'b')}</div>
          <span class="mq-pp-mark" style="top:${px(markY)}"><i class="mq-pp-power" aria-hidden="true"></i>${wordmark}</span>
          <div class="mq-pp-ctl" role="group" aria-label="Touch controls">
            ${dpad('pp', atDisc(st.dpad))}
            ${face(p.id, 'pp', atDisc(st.face))}
            <div class="mq-pp-ss" style="${atRow(st.tertiary)}">${tpill('Select', 'slant')}${menuBtn()}${tpill('Start', 'slant')}</div>
          </div>
        </div>`;
      }
      // Game above, controls below.
      let gw = c.w - 24;
      let gh = gw / ar;
      const maxH = c.h * 0.46 - s.top - 20;
      if (gh > maxH) {
        gh = maxH;
        gw = gh * ar;
      }
      const top = s.top + 8;
      const ctlTop = top + gh + 22;
      const ctlH = c.h - s.bottom - ctlTop;
      return `<div class="mq-pp" style="${pv(p, c)};--ct:${ctlTop}px;--ch:${ctlH}px">
        <div class="mq-pp-view" style="left:${(c.w - gw) / 2}px;top:${top}px;width:${gw}px;height:${gh}px">${shot(g.id, 'b')}</div>
        <div class="mq-pp-ctl">
          <div class="mq-pp-band">${wordmark}${menuBtn()}</div>
          ${dpad('pp')}
          ${face(p.id, 'pp')}
          <div class="mq-pp-ss">${tpill('Select', 'slant')}${tpill('Start', 'slant')}</div>
        </div>
      </div>`;
    },
  };

  // Duo open: the paused game stays exactly where the player draws it (k.stage), with the floating
  // inspector over it; the Paused stamp lands in the free band under the game, centred on the part
  // of it the inspector leaves uncovered (box.clear), so it never sits on the picture.
  const pausedGame = (c, b) => {
    const G = k.stage(c, { aspect: k.aspect('smetroid') }).game;
    const cl = b.clear;
    const x0 = Math.max(G.x, cl.x);
    const x1 = Math.min(G.x + G.w, cl.x + cl.w);
    const hw = Math.min(170, (cl.x + cl.w - 12) - (x0 + x1) / 2);
    const top = G.y + G.h + 8;
    return `<div class="mq-paused" style="width:${b.w}px;height:${b.h}px">
      ${duoStage(c, k.platform('snes'), 'smetroid', 'b', { controls: false, cls: 'held' })}
      <div class="mq-paused-hud" style="left:${px((x0 + x1) / 2 - hw)};top:${px(top)};width:${px(hw * 2)};height:${px(b.h - c.safe.bottom - top)}">
        <span class="mq-paused-tag pix">Paused</span>
        <p class="mq-paused-cap"><span class="pix">Super Metroid</span><span class="pix dim">Session 42M</span></p>
      </div>
    </div>`;
  };

  screens.pause = {
    tone: 'light',
    render: (c) => {
      const pane = c.device.id === 'duo-open';
      const items = [
        ['play', 'Resume', 'on'],
        ['save', 'Save state', ''],
        ['layers', 'Load state', ''],
        ['gamepad', 'Controls', ''],
        ['fast-forward', 'Fast-forward', 'tog'],
        ['restart', 'Reset', ''],
        ['exit', 'Leave game', 'danger'],
      ];
      const list = `<nav class="mq-pz-menu" aria-label="Pause menu"><ul>${items
        .map(
          ([ic, label, cls]) => `<li><button class="mq-pz-item ${cls}"${cls === 'on' ? ' aria-current="true"' : ''}${cls === 'tog' ? ' role="switch" aria-checked="false"' : ''}>${
            cls === 'on' ? '<span class="mq-cur" aria-hidden="true"></span>' : i(ic, 19)
          }<span>${label}</span>${cls === 'tog' ? '<span class="pix x2">2×</span><span class="mq-sw sm" aria-hidden="true"><i></i></span>' : ''}</button></li>`,
        )
        .join('')}</ul></nav>`;
      const status = `<div class="mq-pz-status" role="status">
          <span class="mq-pz-session"><span class="pix lbl">Session</span><b class="pix">42M</b></span>
          <span class="mq-pz-st mint">${i('cloud-check', 15)}<span><b>Game-save data</b><small>Synced</small></span></span>
          <span class="mq-pz-st sun">${i('cloud-upload', 15)}<span><b>Resume</b><small>Waiting to sync</small></span></span>
          ${pane ? '' : `<figure class="mq-pz-freeze">${shot('smetroid', 'b')}<span class="pix">Paused</span></figure>`}
        </div>`;
      const P = D().progress;
      const quick = pane
        ? `${head('Quick load', { cls: 'sm' })}<div class="mq-pz-quick">${P.slots
            .filter((s) => !s.empty)
            .map((s) => `<button class="mq-pz-q" aria-label="Load state ${s.n}: ${esc(s.name)}">${shot('smetroid', s.frame)}<span class="pix">0${s.n}</span></button>`)
            .join('')}</div>`
        : '';
      return c.sys.sheet({
        detent: pane ? 'pane' : 'large',
        title: 'Paused',
        backdrop: (b) => (pane ? pausedGame(c, b) : playerLandscape(c)),
        body: `<div class="mq-pz${pane ? ' pane' : ''}">${status}${list}${quick}</div>`,
      });
    },
  };

  screens.states = {
    tone: 'light',
    render: (c) => {
      const pane = c.device.id === 'duo-open';
      const g = k.game('smetroid');
      const P = D().progress;
      const auto = `<article class="mq-mcx auto">
          <span class="mq-mcx-shot">${shot(g.id, P.resume.frame)}<span class="mq-mc-n pix">Auto</span></span>
          <span class="mq-row-t"><b>Automatic resume</b><small>${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}</small>${stk(P.resume.remote)}</span>
        </article>`;
      const gsave = `<article class="mq-mcx gsave">
          <span class="mq-mcx-shot icon">${i('save', 24)}<span class="mq-mc-n pix">Save</span></span>
          <span class="mq-row-t"><b>Game-save data</b><small>SRAM · 8 KB · ${esc(k.agoShort(P.gameSave.at))} ago</small>${stk(P.gameSave.remote)}</span>
        </article>`;
      // Duo open inspector: the slots become a 2 × 2 of compact memory cards so their names stay legible.
      const slotX = (s) =>
        s.empty
          ? `<button class="mq-mcx empty" aria-label="Save state to slot ${s.n}"><span class="mq-mcx-shot icon">${i('plus', 20)}<span class="mq-mc-n pix">0${s.n}</span></span><span class="mq-row-t"><b>Empty slot</b><small>Save state here</small></span></button>`
          : `<article class="mq-mcx"><span class="mq-mcx-shot">${shot(g.id, s.frame)}<span class="mq-mc-n pix">0${s.n}</span></span><span class="mq-row-t"><b>${esc(s.name)}</b><small>${esc(s.device)} · ${esc(k.agoShort(s.at))} ago</small>${stk(s.remote)}</span></article>`;
      const slots = pane
        ? `<div class="mq-slots x">${P.slots.map(slotX).join('')}</div>`
        : `<div class="mq-slots">${P.slots
            .map((s) =>
              s.empty
                ? mcard({ n: `0${s.n}`, empty: true })
                : mcard({ n: `0${s.n}`, title: s.name, meta: `${esc(s.device)} · ${esc(k.agoShort(s.at))} ago`, frame: shot(g.id, s.frame), remote: s.remote }),
            )
            .join('')}</div>`;
      const hist = pane
        ? `${head('Recovery history', { cls: 'sm', count: P.history.length, more: `${i('save', 15)}<span>Game-save history</span>` })}<ol class="mq-hist">${P.history
            .map((h) => `<li><i></i><span class="mq-row-t"><b>${esc(h.kind)}</b><small>${esc(h.device)} · ${esc(k.ago(h.at))} · ${esc(h.reason)}</small></span></li>`)
            .join('')}</ol>`
        : `<div class="mq-hist-links">${key('Recovery history', { tone: 'ghost', icon: 'history', size: 'sm', trail: 'chevron-right' })}${key('Game-save history', { tone: 'ghost', icon: 'save', size: 'sm', trail: 'chevron-right' })}</div>`;
      return c.sys.sheet({
        detent: pane ? 'pane' : 'large',
        title: 'Memory card',
        backdrop: (b) => (pane ? pausedGame(c, b) : playerLandscape(c)),
        body: `<div class="mq-st${pane ? ' pane' : ''}"><p class="mq-st-sub pix">Super Metroid · SNES · 3 of 4 slots used</p><div class="mq-st-special">${auto}${gsave}</div>${slots}${hist}</div>`,
      });
    },
  };

  screens.conflict = (c) => {
    const C = D().conflict;
    const g = k.game(C.game);
    const pane = c.device.id === 'duo-open';
    const pl = (b, n, sel) => `<button class="mq-pcard${sel ? ' sel' : ''}" role="radio" aria-checked="${sel}">
        <span class="mq-pcard-shot">${shot(g.id, b.frame)}<span class="mq-pcard-p pix">P${n}</span>${sel ? '<span class="mq-pcard-sel pix">Selected</span>' : ''}</span>
        <b>${esc(b.device)}</b>
        <small>${esc(k.ago(b.at))} · ${esc(b.reason)}</small>
        <span class="mq-pcard-where">${i('pin', 13)}${esc(b.where)}</span>
        <span class="mq-lcd sm"><span class="pix lbl">Playtime</span><b class="pix">${esc(b.playtime.replace(/ h /, 'H ').replace(/ m$/, 'M'))}</b></span>
      </button>`;
    const body = `<div class="mq-cf${pane ? ' pane' : ''}">
        <p class="pix mq-cf-eye">${esc(g.title)} · ${esc(C.kind)}</p>
        <h2>Two players, one cartridge.</h2>
        <p class="mq-cf-lede">This iPhone and Pixel 9 both moved EarthBound forward since the last sync. Choose who continues.</p>
        <div class="mq-cf-vs" role="radiogroup" aria-label="Choose which resume continues">${pl(C.branches[0], 1, true)}<span class="mq-vs big" aria-hidden="true">VS</span>${pl(C.branches[1], 2, false)}</div>
        <div class="mq-card mq-list"><div class="mq-row wrap"><span class="mq-ico grape">${i('archive', 19)}</span><span class="mq-row-t"><b>Keep the other as a recovery copy</b><small>Kept in recovery history</small></span>${sw(true, 'Keep the other as a recovery copy')}</div></div>
        <div class="mq-cf-keys">${aKey('snes', 'Continue from This iPhone', { cls: 'wide' })}${key('Cancel', { tone: 'ghost', cls: 'wide' })}</div>
      </div>`;
    return c.sys.sheet({
      detent: pane ? 'pane' : 'large',
      title: 'Conflict',
      // The sheet rises over the Arcade, where the two-player card was tapped. On Duo open it is a
      // floating inspector over the whole Arcade canvas; the vertical bar stays visible beside it.
      backdrop: () => screens.home(c),
      body,
    });
  };

  screens.dual = {
    tone: 'light',
    render: (c) => {
      const g = k.game('pkblack');
      const d = k.dual(g.id, 'a');
      const s = c.safe;
      const duo = isDuo(c);
      const st = duo ? k.stage(c, { dual: true }) : null;
      const scr = (x, y, w, cls = '', src = d.top, touch = false, h = w * 0.75) =>
        `<div class="mq-ds-scr ${cls}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px"><img class="mq-px" src="${src}" alt="">${touch ? `<span class="mq-touchtag">${i('pointer', 13)}<span class="pix">Touch</span></span>` : ''}</div>`;
      let html = '';
      if (duo) {
        // Duo (open and closed): k.stage stacks the screens (the top screen above the fold when open)
        // and gives the controls a band at the bottom. Nothing is drawn on either screen, so the
        // stylus area stays clear: the Touch tag hangs under the touch screen as its caption, L/R sit
        // beside its lower edge, and the d-pad and face buttons sit low in the corners.
        const T = st.top;
        const B = st.touch;
        html = `${scr(T.x, T.y, T.w, '', d.top, false, T.h)}${scr(B.x, B.y, B.w, '', d.bottom, false, B.h)}
          <span class="mq-touchtag out" style="left:${px(B.x + B.w / 2)};top:${px(B.y + B.h + 8)}">${i('pointer', 13)}<span class="pix">Touch screen</span></span>
          ${shoulder('L', 'abs ds', atRect(st.l))}${shoulder('R', 'abs ds', atRect(st.r))}
          ${dpad('abs ds', atDisc(st.dpad))}
          ${face('nds', 'abs ds', atDisc(st.face))}
          <div class="mq-ds-ss" style="${atRow(st.tertiary)}">${tpill('Select')}${menuBtn()}${tpill('Start')}</div>`;
      } else {
        const w = c.w - 56;
        const top = s.top + 4;
        const bandTop = top + w * 1.5 + 8 + 16;
        html = `${scr(28, top, w)}${scr(28, top + w * 0.75 + 8, w, '', d.bottom, true)}
          ${shoulder('L', 'abs', `left:16px;top:${bandTop}px`)}${shoulder('R', 'abs', `right:16px;top:${bandTop}px`)}
          ${menuBtn('abs', `left:${c.w / 2 - 22}px;top:${bandTop - 2}px`)}
          ${dpad('abs', `left:26px;top:${bandTop + 52}px;width:124px;height:124px`)}
          ${face('nds', 'abs', `right:22px;top:${bandTop + 50}px;width:128px;height:128px`)}
          <div class="mq-ds-ss" style="top:${bandTop + 190}px">${tpill('Select')}${tpill('Start')}</div>`;
      }
      return duo ? `<div class="mq-ds duo ${c.device.id}" style="--S:${st.size}px">${html}</div>` : `<div class="mq-ds">${html}</div>`;
    },
  };

  // ================================================================
  Studio.defineVersion({
    id: 'marquee',
    name: 'Marquee',
    tagline: 'Pick up and play.',
    thesis:
      'Rommio as a game console of its own. The fun is in choosing and playing, so the structure is hardware-first and habit-forming: pick a console, then a cartridge, and press the console’s own A button to play. The Arcade tab is a cabinet row of the cartridges you’re playing; Consoles is a select screen; Stats celebrates streaks, records and high scores without nagging. Loud and joyful, never childish, and always the fastest path to Play.',
    swatch: ['#FFF4E2', '#FFC83D', '#FF5A4E', '#120F24'],
    fonts: ['Unbounded:wght@500..900', 'Outfit:wght@400..800', 'Press+Start+2P'],
    tabs: [
      { id: 'arcade', label: 'Arcade', icon: 'joystick' },
      { id: 'consoles', label: 'Consoles', icon: 'cartridge' },
      { id: 'stats', label: 'Stats', icon: 'trophy' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },
    ],
    palette: [
      { name: 'Cream · day ground', hex: '#FFF4E2' },
      { name: 'Paper · day card', hex: '#FFFDF8' },
      { name: 'Ink · day text', hex: '#1C1636' },
      { name: 'Night · night ground', hex: '#120F24' },
      { name: 'Slab · night card', hex: '#1E1A3A' },
      { name: 'Moon · night text', hex: '#FFF8EC' },
      { name: 'Sun · marquee, scores', hex: '#FFC83D' },
      { name: 'Coral · conflict, P1', hex: '#FF5A4E' },
      { name: 'Mint · synced, installed', hex: '#3DDC97' },
      { name: 'Sky · syncing, controller', hex: '#43C6F7' },
      { name: 'Grape · cabinets', hex: '#8C6CFF' },
      { name: 'Arcade red · tint (day)', hex: '#D02C22' },
    ],
    type: [
      { role: 'Display, titles & keys', family: 'Unbounded', css: "'Unbounded', sans-serif", sample: 'Super Metroid' },
      { role: 'Interface', family: 'Outfit', css: "'Outfit', sans-serif", sample: 'Automatic resume · 1 min ago · This iPhone' },
      { role: 'Scores, ranks & legends', family: 'Press Start 2P', css: "'Press Start 2P', monospace", sample: '11H 24M  1ST  62%' },
    ],
    tweaks: [
      { var: '--accent', label: 'Marquee colour', type: 'color', value: '#ffc83d' },
      { var: '--drop', label: 'Hard shadow depth', type: 'range', min: 0, max: 8, step: 1, value: 4, unit: 'px' },
      { var: '--r', label: 'Card radius', type: 'range', min: 10, max: 30, step: 1, value: 22, unit: 'px' },
      { var: '--tilt', label: 'Sticker tilt', type: 'range', min: 0, max: 10, step: 1, value: 4, unit: 'deg' },
      { var: '--ring', label: 'Button ring weight', type: 'range', min: 1, max: 6, step: 0.5, value: 3.5, unit: 'px' },
      { var: '--ctl-alpha', label: 'Touch control opacity', type: 'range', min: 30, max: 100, step: 1, value: 72, unit: '%' },
    ],
    notes: {
      welcome: 'Hardware first from the first frame: a tilted wall of console tiles in their own plastics, with a ticker of every system. The copy says plainly what Rommio is and that it brings no games. On Duo open the wall and ticker run the full width and the copy sits beneath them, across the fold.',
      connect: 'Titled “Plug in”: the address field is a cartridge slot with the live check beside it. Connect is the native prominent toolbar action; access methods are chunky radio chips with the automatic choice explained. On Duo open the slot and options run across the fold with the server cart standing beside them.',
      signin: 'The verified server stays pinned as a ticket stub you can tear off to change. Sign in is the toolbar action; single sign-on is the alternative key. On Duo open the fields run across the fold with the plugged-in server cart beside them.',
      hydrate: 'Every console lights up as it caches; artwork is the only live bar. The sync promise is a device diagram, and the two choices are toggle keys rather than rows. On Duo open all 22 consoles light up in one row and the counters sit side by side.',
      home: 'The Arcade is a cabinet row: each thing you’re playing is a cartridge — plastic in its console’s colour, the resume frame as its label, playtime in an LCD window, and that console’s own A button as the Play key. The streak runs as a marquee strip; EarthBound is a two-player versus card; the loading bay is a sticker by the title. On Duo open it is the whole cabinet row: the strip runs the full width, every cartridge stands in one row across the fold (the next one peeks in), and the versus card and loading bay share the floor beneath.',
      library: 'Consoles is a select screen, not a list. The cursor (P1) rests on the console you last played, previewed big above the grid; tiers are separate grids — Touch, Controller (with the connected DualSense) and Browse only with its reason. On Duo open it is one wide select screen: the SNES band runs across the fold with its plugged-in cartridges standing on a shelf, then every console in ten columns, tier by tier.',
      platform: 'One console’s cartridge rack. Cartridges are seen end-on: installed ones are plugged in with a lit LED and their playtime; server carts sit in the box with a download slot. On Duo open the hero band runs the full width with its cabinets and emulator on the trailing side, and the rack becomes wide rows with last-played, players and rating columns.',
      collections: 'Collections become cabinets — machines you load with carts from any console. Manual cabinets are drawn as cabinets with a lit marquee; auto-fill rules and Rommio’s genre machines follow. On Duo open this is Marquee’s one list → detail: a narrow cabinet list on the shared background, and the selected cabinet opened wide — marquee and screen across the top, its cartridges racked beneath.',
      game: 'Character select: the cover is a die-cut sticker on the console’s colour with a starburst, stats as a player card and an LCD scoreboard. Resume is the prominent toolbar action; the resume point, memory card (Auto, Save, slots 01–04) and cartridge file follow. On Duo open the character card is one band across the display (sticker, name, stats, scoreboard) with the resume point and memory card beneath.',
      downloads: 'The loading bay: a Now Loading card with a segmented bar, the failed cart that needs you, a numbered conveyor (Wi-Fi wait shown in place), deliveries with lit LEDs and a fuel gauge for storage. Reached from the Arcade sticker. On Duo open the Now Loading bar runs the full width; the conveyor takes the wide lane beneath, deliveries and memory the narrow one.',
      search: 'Results are a leaderboard: ranks in pixel type, with the reason each one placed. Playable-now cartridges rank first and carry their A button; cabinets and consoles follow. Filters and recent searches stay one tap away. On Duo open it is one wide high-score table: rank, cart, why it placed and the action read straight across the fold.',
      signature: 'Stats is the signature tab: a lit marquee sign for the streak (bulbs up to your best), a scoreboard for the month, a 28-day heatmap, top consoles in their colours and a high-score board of longest and total sessions. It celebrates without nagging: no reminders, no “don’t break it”. On Duo open it is one wide scoreboard grid — the sign spans the fold beside the totals, the high-score board is the big panel.',
      settings: 'Settings opens from the account avatar as a Player 1 card, with Sam’s library as Player 2. Sync choices are toggle keys with lamps; storage is a memory bar. On Duo open it is one three-column board: Player 1 spans the fold beside Player 2, sync keys and the memory bar run wide, play options and cores fill the third column.',
      controls: 'Button mapping: a live preview of the real layout, the console-colour card (on by default in Marquee — it’s the direction’s signature), power-bar sliders and switch keys. On Duo open the preview is the real Duo player and the tuning floats over the game, between the rails and above Select · Menu · Start, so every control stays in view as it changes; on Duo closed the preview is the closed Duo layout.',
      player: 'OLED black around a 4:3 game. SNES buttons wear their Super Famicom colours as tinted glass with thick rings and pixel legends. On Duo (open and closed) the game shrinks to 4:3 between side rails — across the fold when open — so no button ever sits on it: the d-pad and face buttons low in the rails where thumbs rest, L/R just above them, Select · Menu · Start under the game and the resume notice above it. Never a game half and a pad half.',
      'player-portrait': 'Game Boy Color: the berry B/A pair, slanted Select/Start and a coloured wordmark under the screen. On Duo the game runs full width at the top (in a thin lens when open, the fold running through the picture); the power lamp and wordmark take the strip where shoulders would be, and the pad sits low beneath, clear of the screen.',
      pause: 'A native sheet with arcade content: a big menu list with a blinking cursor on Resume, the session as a score and truthful status for both kinds of progress. On Duo open it is a floating inspector over the paused game (drawn exactly where the player draws it), with quick-load cards; the Paused stamp sits in the free band under the game, beside the inspector.',
      states: 'Memory card: Automatic resume and Game-save data stand apart as special cards; slots 01–04 are memory-card blocks with their sync state; recovery and game-save histories are one tap away (listed in full on Duo open, where it floats over the whole paused game).',
      conflict: 'A versus screen. Both branches are player cards with where, when and playtime as a score; P1 is selected by default and the other is kept as a recovery copy. On Duo open it is a floating inspector over the whole Arcade canvas (where the versus card was tapped), with both previews kept in the player cards and the vertical bar still visible beside it.',
      dual: 'Pokémon Black keeps its screens stacked; buttons use the DS colours. On Duo the screens stay stacked (the top screen above the fold when open) over a control band: nothing touches either screen, the Touch tag hangs under the touch screen as its caption, L/R sit beside its lower edge, the d-pad and face buttons low in the corners and Select · Menu · Start between them.',
    },
    screens,
  });
})();
