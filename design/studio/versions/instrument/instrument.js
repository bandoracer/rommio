/*
 * INSTRUMENT (round 2) — "A control surface for your library."
 *
 * DESIGN PLAN
 * -----------
 * Thesis    Rommio for the person who runs their own server. The app is an operational tool with the
 *           precision of pro hardware: dense, tabular and honest about state — what is installed where,
 *           what is synced, what is queued, what failed. Information is presented as readouts, tables,
 *           meters, lanes and logs; never as cards on shelves. Games appear as recessed screens (the
 *           frame you will resume into), not as box art posters.
 *
 * IA        Tabs (native): Console · Library · Devices · Transfers · Search (iOS separates Search).
 *           Console    home. A modular dashboard: status strip, Resume bank (channel 1 = Super Metroid,
 *                      channels 2–4 = other games in progress), EarthBound conflict alert, sync-state
 *                      matrix (game-save / resume / states × state), transfers, storage gauge, live log.
 *                      Settings sit behind the account avatar in its toolbar; Controls under Settings.
 *           Library    a real table: tier filter keys + Installed latch, sortable columns (Title · Sys ·
 *                      Size · Last · Sync), platform sections. Platform (SNES) and Game push from here.
 *                      Collections are saved filters (every one shows its query) behind the bookmark key.
 *           Devices    the signature surface: the fleet as vertical lanes (This iPhone · Pixel 9 · iPad)
 *                      with sync, pending, installed and storage readouts; below them a progress matrix
 *                      showing which device holds the newest progress for each game, and the EarthBound
 *                      divergence drawn as one lane branching into two.
 *           Transfers  downloads and the save/state sync queue as one transfer console: active channel
 *                      with meter, queue table, upload queue, storage, completed log.
 *           Search     typed operators (platform:snes, installed:iphone, has:states…), the parsed query,
 *                      and ranked results with the reason they rank and whether they can play now.
 *           Sheets     Pause, Save states and Conflict are native sheets (floating inspectors over the full-screen backdrop on Duo open).
 *
 * Palette   LIGHT — warm-grey chassis                      DARK — graphite chassis
 *           Chassis  #E6E4DF  page / --sys-bg             Chassis  #1C1B19
 *           Panel    #F3F2EE  module faces                Panel    #262522
 *           Key      #FBFAF7  keycaps                     Key      #34332F
 *           Well     #D9D6CF  recessed fields, meters     Well     #121211
 *           Ink      #151515  text                        Ink      #ECEAE5
 *           Ink 2    #57544E  labels (5.9:1 on chassis)   Ink 2    #A9A59D  (6.8:1 on chassis)
 *           Signal   #CF4A10  the one accent: keys, tint, meters, selection — the same in both
 *                    (white on it 4.5:1)
 *           Bezel    #0E0E0D  every recessed screen, both appearances
 *           LEDs     Synced #13A150 / #2BD673 · Waiting #D98F00 / #FFB020 · Syncing #2F6FE8 / #4D8BFF
 *                    Conflict #E0362B / #FF453A · Local only #8E8A83 / #77736C
 *                    (same hues, value-shifted per appearance the way system colours are). An LED is
 *                    always paired with its words (spec §11), except in dense table columns, which
 *                    carry a legend.
 *
 * Type      Archivo (variable width) — UI 500–600 at wdth 100; dense table titles at wdth 88;
 *           game titles and module heroes at wdth 118–125, 700. Native large titles use Archivo 700.
 *           JetBrains Mono — every printed label (10px caps, +0.08em), every number (tabular), queries
 *           and operators, log timestamps. Rule: words you press are sentence case Archivo; words you
 *           read off the instrument are mono.
 *
 * Components  module (panel + printed header strip + optional LED) · keycap (flat, 2px shade; primary is
 *           Signal) · latch key (keycap with its own LED, used for filters and on/off settings) · LED ·
 *           readout (big mono numeral + small unit) · segmented meter · arc gauge · heat matrix ·
 *           spreadsheet table (mono column headers, sort caret, sticky sections) · recessed screen with
 *           printed bezel strip · lane (device column) · branch diagram · log line · fader.
 *
 * Duo       Closed (466×678, vertical bar): the compact layouts, one column, same order as iPhone.
 *           Open (951×669): ONE instrument panel, not two halves. The fold is a crease the modules
 *           run across: dashboards use a 12-column grid whose spans never meet at the fold (Console,
 *           Game, Transfers, Devices, Settings, Search, onboarding), with full-width heroes, strips and
 *           lanes. Library is one wide table with a narrow inspector drawer at the trailing edge. Only
 *           the true list → detail screens use shell({split}) (Platform = platform index → slice,
 *           Collections = saved filters → contents), as a narrow list beside a wide detail on the same
 *           chassis. Controls previews the real Duo player with the tuning panel floating over it. Sheets
 *           are floating inspectors over the full-screen paused game (or the Console).
 *
 * Player    The phone becomes the device: the game sits in a recessed screen with a printed bezel strip
 *           (system · core · title) on a body that follows the appearance (warm-grey plastic in light,
 *           graphite in dark). Controls are flat keycaps: a cross d-pad, square face keys with corner
 *           legends (A is the single Signal key), wide shoulder and Select/Start keys. iPhone Duo (open
 *           and closed): geometry from k.stage() — every screen at its stage rect, never covered; the
 *           keys low in the rails beside or beneath it; the bezel a thin lip whose printed strip faces
 *           free space (under the game in landscape, with the session readouts in the band above
 *           Select · Menu · Start; above the screen in portrait and on the DS, clear of the camera).
 *           Pause and Save states are native sheets carrying instrument panels: session readouts,
 *           sync LEDs, a key grid and a four-slot bank.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;

  // ------------------------------------------------------------------ primitives
  const LED = { synced: 'ok', ok: 'ok', done: 'ok', online: 'ok', ready: 'ok', pending: 'wait', waiting: 'wait', signin: 'wait', bios: 'wait', setup: 'wait', syncing: 'live', running: 'live', conflict: 'bad', failed: 'bad', local: 'off', idle: 'off', queued: 'off', none: 'off', off: 'off' };
  const led = (s, cls = '') => `<i class="led led-${LED[s] || s} ${cls}" aria-hidden="true"></i>`;
  const U = (s) => esc(String(s).toUpperCase());
  const lbl = (html, cls = '') => `<span class="lbl ${cls}">${html}</span>`;
  const st = (s, label, cls = '') => `<span class="st ${cls}">${led(s)}<span>${esc(label || k.syncLabel(s))}</span></span>`;
  const gb = (mb) => (mb >= 1000 ? `${(mb / 1000).toFixed(1)} GB` : k.size(mb));
  const hrs = (h) => {
    const hh = Math.floor(h);
    const mm = Math.round((h - hh) * 60);
    return `${hh}H ${String(mm).padStart(2, '0')}M`;
  };
  const phone = (c) => (c.os === 'android' ? 'phone' : 'iPhone');
  const isOpen = (c) => c.size === 'regular' && c.device.fold && c.device.fold.x;
  const NOW = 9 * 60 + 41;
  const hm = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
  function clock(at) {
    const m = NOW - Math.round(at);
    if (m >= 0) return hm(m);
    const d = Math.ceil(-m / 1440);
    return `${d === 1 ? 'YDAY' : d + 'D'} ${hm(((m % 1440) + 1440) % 1440)}`;
  }

  function key(label, o = {}) {
    const cls = ['kc', `kc-${o.kind || 'plain'}`, o.size || '', o.block ? 'block' : '', o.cls || ''].join(' ');
    return `<button class="${cls}"${o.aria ? ` aria-label="${esc(o.aria)}"` : ''}>${o.icon ? i(o.icon, { size: o.iconSize || 17, cls: o.icon === 'play' ? 'fill' : '' }) : ''}${
      label ? `<span class="kc-l">${esc(label)}</span>` : ''
    }${o.sub ? `<small>${o.sub}</small>` : ''}${o.after || ''}</button>`;
  }
  const ikey = (icon, aria, cls = '') => `<button class="kc kc-plain ik ${cls}" aria-label="${esc(aria)}">${i(icon, 18)}</button>`;
  // Latching key: a keycap with its own LED (filters, on/off settings).
  const latch = (on, title, sub = '', cls = '') =>
    `<button class="latch ${on ? 'on' : ''} ${cls}" role="switch" aria-checked="${on}" aria-label="${esc(title)}"><i class="latch-led"></i><span class="latch-m"><b>${esc(title)}</b>${sub ? `<small>${sub}</small>` : ''}</span></button>`;

  function meter(frac, n = 32, cls = '', aria = 'Progress') {
    const on = Math.round(frac * n);
    return `<div class="meter ${cls}" role="meter" aria-label="${esc(aria)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(frac * 100)}" style="--n:${n}">${k
      .range(n)
      .map((j) => `<i${j < on ? ' class="on"' : ''}></i>`)
      .join('')}</div>`;
  }
  const ro = (v, unit, label, cls = '') => `<div class="ro ${cls}"><b>${v}${unit ? `<small>${unit}</small>` : ''}</b>${label ? lbl(esc(label)) : ''}</div>`;

  function mod(o) {
    return `<section class="mod ${o.cls || ''}" aria-label="${esc(o.aria || o.title)}"${o.style ? ` style="${o.style}"` : ''}>
      <header class="mod-h">${o.led ? led(o.led) : ''}<span class="lbl mod-t">${U(o.title)}</span><span class="mod-r">${o.right || ''}</span></header>
      <div class="mod-b">${o.body}</div>
    </section>`;
  }

  // Recessed screen with a printed bezel strip.
  function bezel(src, o = {}) {
    return `<div class="bz ${o.cls || ''}"${o.style ? ` style="${o.style}"` : ''}>${o.strip ? `<div class="bz-s">${o.strip}</div>` : ''}<div class="bz-g"${
      o.ar ? ` style="aspect-ratio:${o.ar}"` : ''
    }><img src="${src}" alt="${esc(o.alt || '')}">${o.over || ''}</div>${o.foot ? `<div class="bz-s bz-f">${o.foot}</div>` : ''}</div>`;
  }
  const thumb = (id, variant = 'a', cls = '') => `<span class="thumb ${cls}"><img src="${k.frame(id, variant)}" alt=""></span>`;

  const TIER_I = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tier = (t) => `<span class="tier t-${t}">${i(TIER_I[t], 11)}${U(k.tierLabel(t))}</span>`;
  const cap = (color) => `<i class="cap" style="--c:${color}"></i>`;

  const LOGO = `<svg class="logo" viewBox="0 0 32 32" aria-hidden="true"><rect x="1" y="1" width="30" height="30" rx="8" class="logo-body"/><path d="M8.5 16h8M12.5 12v8" class="logo-cross"/><circle cx="22.5" cy="16" r="3.2" class="logo-dot"/></svg>`;
  const wordmark = (cls = '') => `<span class="wm ${cls}">${LOGO}<span>rommio</span></span>`;

  // Per-screen root. On Duo open the inner display is one canvas; layouts use the 12-column `.g12`
  // grid and choose spans that never meet at the fold.
  const root = (c, cls, body) => `<div class="in ${cls}${isOpen(c) ? ' open' : ''}">${body}</div>`;

  // ------------------------------------------------------------------ data views
  const moments = () => D().moments;
  const newestFor = (gid) => moments().find((m) => m.game === gid);
  const tierTotals = () => {
    const out = {};
    for (const t of ['touch', 'controller', 'unsupported']) {
      const ps = k.tier(t);
      out[t] = { platforms: ps.length, games: ps.reduce((n, p) => n + p.games, 0), installed: ps.reduce((n, p) => n + p.installed, 0) };
    }
    return out;
  };
  const storageUsed = () => {
    const s = D().storage;
    return s.roms + s.saves + s.states + s.cache + s.cores + s.bios;
  };

  // Sync matrix: kinds × remote state, counted per game (a conflict is one decision).
  function syncMatrix() {
    const cols = ['synced', 'waiting', 'syncing', 'conflict', 'local'];
    const rows = [
      { kind: 'save', label: 'Game-save data' },
      { kind: 'resume', label: 'Automatic resume' },
      { kind: 'state', label: 'Save states' },
    ];
    const seen = new Set();
    const items = [...moments()];
    const gs = D().progress.gameSave;
    items.push({ game: D().progress.game, kind: 'save', remote: gs.remote });
    for (const r of rows) r.n = Object.fromEntries(cols.map((x) => [x, 0]));
    let total = 0;
    for (const m of items) {
      const key = `${m.game}:${m.kind}:${m.slot || ''}:${m.remote}`;
      if (m.remote === 'conflict' && seen.has(key)) continue;
      seen.add(key);
      const r = rows.find((x) => x.kind === m.kind);
      r.n[m.remote] += 1;
      total += 1;
    }
    return { cols, rows, total };
  }

  // ------------------------------------------------------------------ console modules
  function statusStrip(c, part) {
    const pr = D().profiles[0];
    const run = D().downloads.find((d) => d.state === 'running');
    const a = `<span class="strip-c">${led('online')}<b>ONLINE</b><span>${esc(pr.url)}</span></span>`;
    const b = `<span class="strip-c strip-r"><span>SYNCED ${U(k.agoShort(pr.lastSync))} AGO</span><i class="strip-sep"></i>${i('arrow-down', 12)}<b>${Math.round(run.progress * 100)}%</b></span>`;
    if (part === 'a') return `<div class="strip" role="status">${a}<span class="strip-c strip-r"><span>${esc(pr.version.toUpperCase())}</span></span></div>`;
    if (part === 'wide')
      return `<div class="strip strip-wide" role="status">${a}<span class="strip-c"><span>${esc(pr.version.toUpperCase())}</span><i class="strip-sep"></i><span>CLOUDFLARE ACCESS</span></span><span class="strip-c"><span>RECEIVING FINAL FANTASY VII · ${run.rate} MB/S</span></span>${b}</div>`;
    return `<div class="strip" role="status">${a}${b}</div>`;
  }

  function modResume(c, o = {}) {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    const m = newestFor('smetroid');
    const bank = ['linksawak', 'chrono', 'kirbyadv'].map((id) => {
      const x = newestFor(id);
      const gg = k.game(id);
      return `<button class="ch" aria-label="Resume ${esc(gg.title)}">
        ${thumb(id, x.frame, 'ch-scr')}
        <span class="ch-t">${esc(gg.title)}</span>
        <span class="ch-m">${led(x.remote)}<span>${U(k.agoShort(x.at))} · ${U(x.device === 'This iPhone' ? phone(c) : x.device)}</span></span>
      </button>`;
    });
    // Wide (Duo open): one hero across the fold — channel 1's screen, its readouts, then channels 2–4
    // stacked as a bank in the same row.
    const bankHTML = `<div class="bank">${bank.join('')}</div>`;
    return mod({
      title: 'Resume',
      right: `${lbl(o.wide ? `CH 1–4 · ${U(p.short)} · ${U(p.core)} · SESSION ${pr.session} MIN` : 'CH 1–4')}`,
      cls: `m-resume ${o.wide ? 'm-resume-wide' : ''} ${o.cls || ''}`,
      body: `<div class="rs">
        ${bezel(k.frame(g.id, pr.resume.frame), { cls: 'rs-scr', ar: k.aspect(g.id), strip: `<span>${U(p.short)} · ${U(p.core)}</span><span>CH 1</span>`, alt: `${g.title}, automatic resume point` })}
        <div class="rs-m">
          <h2 class="rs-t">${esc(g.title)}</h2>
          <p class="rs-w">${esc(m.name)}</p>
          <dl class="kv">
            <div><dt>Saved</dt><dd>${esc(k.ago(pr.resume.at))}</dd></div>
            <div><dt>Device</dt><dd>This ${phone(c)}</dd></div>
            <div><dt>Resume</dt><dd>${st(pr.resume.remote)}</dd></div>
          </dl>
          ${key('Resume', { kind: 'primary', icon: 'play', block: true, cls: 'rs-k' })}
        </div>
        ${o.wide ? bankHTML : ''}
      </div>
      ${o.wide ? '' : bankHTML}`,
    });
  }

  function modConflict(c, o = {}) {
    const cf = D().conflict;
    const g = k.game(cf.game);
    const [a, b] = cf.branches;
    if (o.wide) {
      // Duo open: one strip across the display — the decision, then both branches side by side.
      const arm = (x, name) => `<div class="alert-arm">${thumb(g.id, x.frame, 'alert-scr')}<span class="alert-am"><b>${esc(name)}</b><small>${esc(x.where)} · ${esc(x.playtime)}</small></span><time>${U(k.agoShort(x.at))}</time></div>`;
      return `<section class="alert alert-wide ${o.cls || ''}" aria-label="Conflict: ${esc(g.title)}">
        <svg class="fork" viewBox="0 0 40 44" aria-hidden="true"><path d="M4 22h12c6 0 8-12 16-12M16 22c6 0 8 12 16 12"/><circle cx="4" cy="22" r="3"/><circle cx="33" cy="10" r="3.5" class="hot"/><circle cx="33" cy="34" r="3.5" class="hot"/></svg>
        <div class="alert-m"><span class="alert-h">${led('conflict')}<span class="lbl">CONFLICT · ${U(cf.kind)}</span></span><b>${esc(g.title)}</b></div>
        ${arm(a, `This ${phone(c)}`)}${arm(b, b.device)}
        ${key('Resolve', { kind: 'plain', cls: 'alert-k' })}
      </section>`;
    }
    return `<section class="alert ${o.cls || ''}" aria-label="Conflict: ${esc(g.title)}">
      <svg class="fork" viewBox="0 0 40 44" aria-hidden="true"><path d="M4 22h12c6 0 8-12 16-12M16 22c6 0 8 12 16 12"/><circle cx="4" cy="22" r="3"/><circle cx="33" cy="10" r="3.5" class="hot"/><circle cx="33" cy="34" r="3.5" class="hot"/></svg>
      <div class="alert-m">
        <span class="alert-h">${led('conflict')}<span class="lbl">CONFLICT · ${U(cf.kind)}</span></span>
        <b>${esc(g.title)}</b>
        <span class="alert-d"><span>This ${phone(c)} · ${esc(a.where)}</span><time>${U(k.agoShort(a.at))}</time></span>
        <span class="alert-d"><span>${esc(b.device)} · ${esc(b.where)}</span><time>${U(k.agoShort(b.at))}</time></span>
      </div>
      ${key('Resolve', { kind: 'plain', cls: 'alert-k' })}
    </section>`;
  }

  function modMatrix(c, o = {}) {
    const mx = syncMatrix();
    const head = { synced: 'Synced', waiting: 'Waiting<br>to sync', syncing: 'Syncing', conflict: 'Conflict', local: 'Local<br>only' };
    const pending = mx.rows.reduce((n, r) => n + r.n.waiting + r.n.syncing, 0);
    return mod({
      title: 'Sync state',
      right: lbl(`${mx.total} ITEMS · ${pending} PENDING`),
      cls: `m-matrix ${o.cls || ''}`,
      body: `<div class="mx" role="table" aria-label="Sync state by kind">
        <div class="mx-r mx-h" role="row"><span role="columnheader"></span>${mx.cols.map((x) => `<span role="columnheader" class="mx-ch">${led(x)}<em>${head[x].toUpperCase()}</em></span>`).join('')}</div>
        ${mx.rows
          .map(
            (r) => `<div class="mx-r" role="row"><span class="mx-l" role="rowheader">${esc(r.label)}</span>${mx.cols
              .map((x) => `<span role="cell" class="mx-c ${r.n[x] ? `hot h-${LED[x]}` : ''}">${r.n[x] || '·'}</span>`)
              .join('')}</div>`,
          )
          .join('')}
      </div>`,
    });
  }

  function modTransfers(c, o = {}) {
    const dl = D().downloads;
    const run = dl.find((d) => d.state === 'running');
    const g = k.game(run.game);
    const q = dl.filter((d) => d.state === 'queued').length;
    const w = dl.filter((d) => d.state === 'waiting').length;
    const f = dl.filter((d) => d.state === 'failed').length;
    return mod({
      title: 'Transfers',
      led: 'running',
      right: lbl(`${run.rate} MB/S`),
      cls: `m-xfer ${o.cls || ''}`,
      body: `<div class="xf">
        <span class="xf-t">${esc(g.title)}</span>
        <div class="xf-ro"><b>${Math.round(run.progress * 100)}<small>%</small></b><span>${gb(run.done)} / ${gb(run.total)}<br>${esc(run.eta)} LEFT</span></div>
        ${meter(run.progress, 24, 'm-sig', 'Download progress')}
        <ul class="xf-q">
          <li>${led('queued')}<span>${q} queued</span></li>
          <li>${led('waiting')}<span>${w} waiting for Wi-Fi</span></li>
          <li>${led('failed')}<span>${f} failed</span></li>
        </ul>
      </div>`,
    });
  }

  function gauge(frac) {
    const R = 46;
    const cx = 60;
    const cy = 56;
    const pt = (a, r) => [cx + r * Math.cos(Math.PI - a * Math.PI), cy - r * Math.sin(Math.PI - a * Math.PI)];
    const ticks = k
      .range(17)
      .map((j) => {
        const a = j / 16;
        const [x1, y1] = pt(a, R + 4);
        const [x2, y2] = pt(a, j % 4 === 0 ? R + 11 : R + 8);
        return `<line x1="${x1.toFixed(2)}" y1="${y1.toFixed(2)}" x2="${x2.toFixed(2)}" y2="${y2.toFixed(2)}" class="${j % 4 === 0 ? 'maj' : ''}"/>`;
      })
      .join('');
    const [ex, ey] = pt(frac, R);
    const [nx, ny] = pt(frac, R - 14);
    return `<svg class="gauge" viewBox="0 0 120 64" aria-hidden="true">${ticks}
      <path d="M${cx - R} ${cy} A${R} ${R} 0 0 1 ${cx + R} ${cy}" class="g-track"/>
      <path d="M${cx - R} ${cy} A${R} ${R} 0 0 1 ${ex.toFixed(2)} ${ey.toFixed(2)}" class="g-fill"/>
      <line x1="${cx}" y1="${cy}" x2="${nx.toFixed(2)}" y2="${ny.toFixed(2)}" class="g-needle"/><circle cx="${cx}" cy="${cy}" r="4" class="g-hub"/>
    </svg>`;
  }

  function modStorage(c, o = {}) {
    const s = D().storage;
    const used = storageUsed();
    return mod({
      title: 'Storage',
      right: lbl(`${gb(s.total)}`),
      cls: `m-stor ${o.cls || ''}`,
      body: `<div class="sg">
        ${gauge(used / s.total)}
        <div class="sg-ro"><b>${(used / 1000).toFixed(1)}<small>GB</small></b>${lbl(`USED · ${gb(s.total - used)} FREE`)}</div>
        <dl class="sg-l"><div><dt>ROMs</dt><dd>${gb(s.roms)}</dd></div><div><dt>States + saves</dt><dd>${gb(s.states + s.saves)}</dd></div></dl>
      </div>`,
    });
  }

  function logLines(n, o = {}) {
    return D()
      .activity.slice(0, n)
      .map(
        (a) => `<li class="log-l"><time>${clock(a.at)}</time>${led(a.state)}<span class="log-m"><b>${esc(a.text)}</b><small>${esc(a.detail)}</small></span></li>`,
      )
      .join('');
  }
  function modLog(c, o = {}) {
    return mod({ title: 'Activity', right: lbl('LIVE'), led: 'syncing', cls: `m-log ${o.cls || ''}`, body: `<ol class="log">${logLines(o.n || 6)}</ol>` });
  }

  function modFleet(c, o = {}) {
    return mod({
      title: 'Fleet',
      right: lbl(`${D().fleet.length} DEVICES`),
      cls: `m-fleet ${o.cls || ''}`,
      body: `<ul class="fl">${D()
        .fleet.map(
          (f) => `<li>${led(f.state)}<b>${esc(f.name === 'This iPhone' ? `This ${phone(c)}` : f.name)}</b><span>${f.pending ? `${f.pending} pending` : 'Up to date'}</span><time>${f.last ? U(k.agoShort(f.last)) : 'NOW'}</time></li>`,
        )
        .join('')}</ul>`,
    });
  }

  // ------------------------------------------------------------------ screens
  const screens = {};

  // ================= Console =================
  screens.home = (c) => {
    const open = isOpen(c);
    // Duo open: one dashboard. Strip, Resume hero and the conflict alert run the full width; the
    // modules below sit on a 12-column grid with spans that straddle the fold (5 · 3 · 4, then 8 · 4).
    const body = open
      ? root(
          c,
          'con con-open',
          `${statusStrip(c, 'wide')}
          ${modResume(c, { wide: true })}
          ${modConflict(c, { wide: true })}
          <div class="g12">
            ${modMatrix(c, { cls: 's5' })}${modTransfers(c, { cls: 's3' })}${modStorage(c, { cls: 's4' })}
            ${modLog(c, { cls: 's8', n: 4 })}${modFleet(c, { cls: 's4' })}
          </div>`,
        )
      : root(
          c,
          'con',
          `${statusStrip(c)}<div class="grid">
            ${modResume(c, { cls: 'span2' })}
            ${modConflict(c, { cls: 'span2' })}
            ${modMatrix(c, { cls: 'span2' })}
            ${modTransfers(c)}${modStorage(c)}
            ${modLog(c, { cls: 'span2' })}
          </div>`,
        );
    return c.sys.shell({
      tab: 'console',
      title: 'Console',
      actions: [
        { icon: 'sync', label: 'Sync now' },
        { avatar: 'R', label: 'Account and settings' },
      ],
      body,
    });
  };

  // ================= Library =================
  const LIB_ORDER = ['snes', 'gbc', 'nes', 'gba'];
  function gameRows(games, cols, o = {}) {
    return games
      .map((g) => {
        const p = k.platformOf(g);
        const sel = o.selected === g.id;
        const cells = {
          mark: `<span class="mk ${g.installed ? 'on' : ''}" title="${g.installed ? 'On this device' : 'On server'}"></span>`,
          title: `<span class="tt"><b>${esc(g.title)}</b>${o.sub ? `<small>${esc(g.year)} · ${esc(g.publisher)}</small>` : ''}</span>`,
          sys: `<span class="pc">${esc(p.short)}</span>`,
          year: `<span class="num">${g.year}</span>`,
          size: `<span class="num">${esc(k.size(g.size))}</span>`,
          last: `<span class="num">${g.played == null ? '—' : esc(k.agoShort(g.played))}</span>`,
          sync: g.sync === 'none' ? '<span class="dash">—</span>' : `<span class="sy">${led(g.sync)}</span>`,
          syncw: g.sync === 'none' ? '<span class="dash">—</span>' : st(g.sync),
          act: g.installed
            ? `<button class="tk" aria-label="Play ${esc(g.title)}">${i('play', 15, 'fill')}</button>`
            : `<button class="tk ghost" aria-label="Download ${esc(g.title)}">${i('download', 16)}</button>`,
        };
        return `<div class="tr ${g.installed ? '' : 'remote'} ${sel ? 'sel' : ''}" role="row">${cols.map((cname) => `<span role="cell" class="td td-${cname}">${cells[cname]}</span>`).join('')}</div>`;
      })
      .join('');
  }
  const sortPlayed = (a, b) => (a.played ?? 1e9) - (b.played ?? 1e9) || a.title.localeCompare(b.title);
  function tableHead(cols, labels, sorted) {
    return `<div class="tr th" role="row">${cols
      .map((cn, n) => `<span role="columnheader" class="td td-${cn} ${cn === sorted ? 'on' : ''}">${labels[n]}${cn === sorted ? '<i class="caret-s" aria-label="sorted, newest first">↓</i>' : ''}</span>`)
      .join('')}</div>`;
  }
  function sectionRow(p, c) {
    return `<div class="tsec" role="row"><span role="cell">${cap(p.color)}<b>${esc(p.name)}</b><span class="tsec-r">${lbl(`${p.installed}/${p.games}`)}${tier(p.tier)}</span></span></div>`;
  }
  function tierKeys(c, o = {}) {
    const t = tierTotals();
    const on = o.on || { touch: true, controller: true, unsupported: false };
    const k3 = [
      ['touch', `${t.touch.platforms} SYS · ${k.num(t.touch.games)}`],
      ['controller', `${t.controller.platforms} SYS · ${k.num(t.controller.games)}`],
      ['unsupported', `NO PLAYER · ${k.num(t.unsupported.games)}`],
    ];
    return `<div class="tiers" role="group" aria-label="Support tiers">${k3.map(([id, sub]) => latch(on[id], k.tierLabel(id), sub, `tk-${id}`)).join('')}</div>`;
  }
  function libraryTable(c, o = {}) {
    const cols = o.cols || ['mark', 'title', 'sys', 'size', 'last', 'sync'];
    const labels = o.labels || ['', 'Title', 'Sys', 'Size', 'Last', 'Sync'];
    const secs = (o.platforms || LIB_ORDER)
      .map((pid) => {
        const p = k.platform(pid);
        return sectionRow(p, c) + gameRows(k.gamesOn(pid).sort(sortPlayed), cols, o);
      })
      .join('');
    return `<div class="tbl ${o.cls || ''}" role="table" aria-label="Games" style="--cols:${o.grid}">${tableHead(cols, labels, 'last')}${secs}</div>`;
  }
  const legend = () =>
    `<div class="legend" aria-label="Sync legend">${['synced', 'waiting', 'syncing', 'conflict', 'local'].map((s) => `<span>${led(s)}${esc(k.syncLabel(s))}</span>`).join('')}</div>`;

  // The inspector drawer beside the Library table (Duo open): a narrow panel, never a half.
  function inspector(c) {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    return `<div class="insp">
      <div class="insp-h">${led('ok')}${lbl('INSPECTOR')}<span class="insp-hr">${lbl('1 SELECTED')}</span></div>
      ${bezel(k.frame(g.id, pr.resume.frame), { ar: k.aspect(g.id), cls: 'insp-scr', strip: `<span>RESUME POINT</span><span>${U(k.agoShort(pr.resume.at))}</span>` })}
      <div class="insp-t"><h2>${esc(g.title)}</h2><span class="insp-tm">${lbl(`${U(p.short)} · ${g.year} · ${U(g.publisher)}`)}<span class="rating">${g.rating}</span></span></div>
      <div class="insp-k">${key('Resume', { kind: 'primary', icon: 'play', block: true })}${key('Open', { icon: 'arrow-up-right', block: true })}</div>
      <dl class="spec">
        <div><dt>File</dt><dd class="mono">Super Metroid (USA).sfc</dd></div>
        <div><dt>Installed</dt><dd>${esc(k.size(g.size))} · this ${phone(c)}</dd></div>
        <div><dt>Core</dt><dd>${esc(p.core)} · ${tier(p.tier)}</dd></div>
        <div><dt>Game-save</dt><dd>${st(pr.gameSave.remote)}</dd></div>
        <div><dt>Resume</dt><dd>${st(pr.resume.remote)}</dd></div>
        <div><dt>States</dt><dd>3 of 4 slots · ${st('syncing', '1 syncing')}</dd></div>
        <div><dt>Played</dt><dd class="mono">${hrs(g.hours)}</dd></div>
      </dl>
      <div class="gp-h">${lbl('SESSIONS')}<span class="rule"></span>${lbl('LAST 3')}</div>
      <ol class="log">${D()
        .sessions.filter((x) => x.game === g.id)
        .map(
          (x) => `<li class="log-l"><time>${x.day === 0 ? 'TODAY' : x.day === 1 ? 'YDAY' : x.day + 'D'} ${x.start.padStart(5, '0')}</time>${led(x.ended === 'resume' ? 'waiting' : 'ok')}<span class="log-m"><b>${esc(x.note)}</b><small>${esc(
            x.device === 'This iPhone' ? `This ${phone(c)}` : x.device,
          )} · ${x.minutes} min · ended with ${x.ended === 'resume' ? 'automatic resume' : x.ended === 'state' ? 'a save state' : 'game-save data'}</small></span></li>`,
        )
        .join('')}</ol>
    </div>`;
  }

  screens.library = (c) => {
    const t = k.totals();
    const tt = tierTotals();
    const shown = tt.touch.games + tt.controller.games;
    const count = `<div class="rowbar">${latch(false, 'Installed only', `${t.installed} GAMES`, 'lt-inst')}<span class="count">${lbl(`SHOWING`)}<b>${k.num(shown)}</b>${lbl(`OF ${k.num(t.games)}`)}</span></div>`;
    if (isOpen(c)) {
      // Duo open: one spreadsheet running across the fold (more columns, a subtitle per row), with a
      // narrow inspector drawer docked at the trailing edge for the selected row.
      const table = libraryTable(c, {
        cols: ['mark', 'title', 'sys', 'year', 'size', 'last', 'syncw'],
        labels: ['', 'Title', 'Sys', 'Year', 'Size', 'Last', 'Sync'],
        grid: '10px minmax(0,1fr) 38px 38px 50px 38px 124px',
        selected: 'smetroid',
        sub: true,
        platforms: ['snes', 'gbc', 'nes', 'gba'],
      });
      return c.sys.shell({
        tab: 'library',
        title: 'Library',
        actions: [
          { icon: 'bookmark', label: 'Collections' },
          { icon: 'more', label: 'View options' },
        ],
        body: `<div class="in lib lib-open"><div class="lib-main">${tierKeys(c)}${count}${table}</div><aside class="drawer" aria-label="Inspector">${inspector(c)}</aside></div>`,
      });
    }
    return c.sys.shell({
      tab: 'library',
      title: 'Library',
      actions: [
        { icon: 'bookmark', label: 'Collections' },
        { icon: 'more', label: 'View options' },
      ],
      body: `<div class="in lib">${tierKeys(c)}${count}${legend()}${libraryTable(c, { grid: '10px minmax(0,1fr) 36px 50px 38px 22px' })}</div>`,
    });
  };

  // ================= Platform =================
  // The narrow list beside the platform slice (Duo open split): core prints under the name.
  function platformIndex(c, sel) {
    const sec = (t, why) => {
      const ps = k.tier(t);
      return `<div class="tsec" role="row"><span role="cell">${tier(t)}<span class="tsec-r">${lbl(why)}</span></span></div>${ps
        .map(
          (p) => `<div class="tr ${p.id === sel ? 'sel' : ''} ${p.tier === 'unsupported' ? 'remote' : ''}" role="row">
            <span role="cell" class="td">${cap(p.color)}</span>
            <span role="cell" class="td td-title"><span class="tt"><b>${esc(p.name)}</b><small>${esc(p.blocked || p.core || '—')}</small></span></span>
            <span role="cell" class="td td-n"><span class="num">${p.games}</span></span>
            <span role="cell" class="td td-n"><span class="num ${p.installed ? '' : 'dim'}">${p.installed}</span></span>
          </div>`,
        )
        .join('')}`;
    };
    return `<div class="tbl pidx" role="table" aria-label="Platforms" style="--cols:12px minmax(0,1fr) 40px 32px">
      <div class="tr th" role="row"><span class="td"></span><span class="td">Platform · core</span><span class="td td-n">Games</span><span class="td td-n">Inst</span></div>
      ${sec('touch', `${k.tier('touch').length} SYSTEMS`)}${sec('controller', 'NEEDS A CONTROLLER')}${sec('unsupported', 'NO PLAYER YET')}
    </div>`;
  }
  function coverage(p) {
    const games = k.gamesOn(p.id);
    const played = games.filter((g) => g.played != null).length;
    const cells = k
      .range(p.games)
      .map((n) => `<i class="${n < played ? 'c-p' : n < p.installed ? 'c-i' : ''}"></i>`)
      .join('');
    return mod({
      title: 'Coverage',
      right: lbl(`${p.games} GAMES`),
      cls: 'm-cov',
      body: `<div class="cov" role="img" aria-label="${p.installed} of ${p.games} installed, ${played} with progress">${cells}</div>
        <div class="cov-l"><span><i class="c-p"></i>${played} with progress</span><span><i class="c-i"></i>${p.installed - played} installed, not started</span><span><i></i>${p.games - p.installed} on server</span></div>`,
    });
  }
  function platformDetail(c) {
    const p = k.platform('snes');
    const games = k.gamesOn('snes');
    const rows = [...games].sort(sortPlayed);
    const wide = isOpen(c);
    const cols = wide ? ['mark', 'title', 'year', 'size', 'last', 'syncw', 'act'] : ['mark', 'title', 'year', 'size', 'last', 'act'];
    const labels = wide ? ['', 'Title', 'Year', 'Size', 'Last', 'Sync', ''] : ['', 'Title', 'Year', 'Size', 'Last', ''];
    const grid = wide ? '10px minmax(0,1fr) 34px 48px 38px 120px 40px' : '10px minmax(0,1fr) 34px 48px 38px 40px';
    return `<div class="plat-ro">
        ${ro(p.games, '', 'Games')}
        ${ro(p.installed, '', 'Installed')}
        <div class="ro"><b class="ro-txt">${esc(p.core)}</b>${lbl(`${led('ready')}READY`)}</div>
        <div class="ro"><b class="ro-txt">${tier(p.tier)}</b>${lbl('Controls')}</div>
      </div>
      ${coverage(p)}
      <div class="segwrap">${c.sys.segmented([`All ${p.games}`, `Installed ${p.installed}`, `Not downloaded ${p.games - p.installed}`], 0)}</div>
      <div class="tbl" role="table" aria-label="Super Nintendo games" style="--cols:${grid}">
        ${tableHead(cols, labels, 'last')}
        ${gameRows(rows, cols)}
      </div>`;
  }
  screens.platform = (c) => {
    if (isOpen(c)) {
      return c.sys.shell({
        tab: 'library',
        title: 'Super Nintendo',
        actions: [{ icon: 'filter', label: 'Filter' }, { icon: 'sort', label: 'Sort' }],
        split: {
          primary: `<div class="in">${platformIndex(c, 'snes')}</div>`,
          primaryTitle: 'Library',
          secondary: `<div class="in plat plat-open">${platformDetail(c)}</div>`,
          secondaryTitle: 'Super Nintendo',
        },
      });
    }
    return c.sys.shell({
      tab: 'library',
      title: 'Super Nintendo',
      back: 'Library',
      actions: [
        { icon: 'filter', label: 'Filter' },
        { icon: 'more', label: 'More' },
      ],
      body: `<div class="in plat">${platformDetail(c)}</div>`,
    });
  };

  // ================= Collections (saved filters) =================
  const QUERY = {
    couch: 'list:couch-co-op',
    metroidvania: 'list:metroidvanias',
    finish: 'list:finish-2026',
    handhelds: 'list:pocket-classics',
    recent: 'added:<30d',
    unplayed: 'playtime:0',
    rpg: 'genre:rpg',
    platformer: 'genre:platformer',
    nineties: 'year:1990..1999',
  };
  function collRow(x, c, sel) {
    const games = x.games.map(k.game);
    const inst = games.filter((g) => g.installed).length;
    const lead = `<span class="strip5">${x.games
      .slice(0, 4)
      .map((id) => `<img src="${k.cover(id)}" alt="">`)
      .join('')}</span>`;
    return `<button class="cr ${sel ? 'sel' : ''}" aria-label="${esc(x.name)}, ${x.games.length} games">
      ${lead}
      <span class="cr-m"><b>${esc(x.name)}</b><span class="cr-q"><code>${esc(QUERY[x.id])}</code><small>${esc(x.rule || x.note)}</small></span></span>
      <span class="cr-r"><b class="num">${x.games.length}</b><span class="seg-m" aria-label="${inst} of ${x.games.length} on this device">${games.map((g) => `<i class="${g.installed ? 'on' : ''}"></i>`).join('')}</span></span>
    </button>`;
  }
  function collList(c, sel) {
    const all = D().collections;
    const grp = (kind, title, note) => {
      const xs = all.filter((x) => x.kind === kind);
      return `<section class="cg"><header class="cg-h">${lbl(esc(title))}<span class="rule"></span>${lbl(esc(note))}</header>${xs.map((x) => collRow(x, c, x.id === sel)).join('')}</section>`;
    };
    return `${grp('manual', 'Manual · 4', 'PICKED BY YOU')}${grp('smart', 'Smart · 2', 'RULES YOU SET')}${grp('auto', 'Automatic · 3', 'FROM METADATA')}`;
  }
  function collDetail(c, id) {
    const x = k.collection(id);
    const games = x.games.map(k.game);
    const dl = D().downloads;
    return `<div class="cd">
      <div class="cd-q"><code>${esc(QUERY[id])}</code><span class="lbl">${esc(x.note || x.rule)}</span></div>
      <div class="plat-ro cd-ro">${ro(games.length, '', 'Games')}${ro(games.filter((g) => g.installed).length, '', 'Installed')}${ro(
        games.filter((g) => g.played != null).length,
        '',
        'Started',
      )}${ro(k.hours(games.reduce((n, g) => n + g.hours, 0)).replace(' m', '').replace(' h', 'h'), '', 'Played')}</div>
      <div class="tbl" role="table" aria-label="${esc(x.name)}" style="--cols:10px minmax(0,1fr) 40px 36px 112px">
        ${tableHead(['mark', 'title', 'sys', 'last', 'syncw'], ['', 'Title', 'Sys', 'Last', 'State'], 'last')}
        ${games
          .map((g) => {
            const d = dl.find((y) => y.game === g.id);
            const state = d && d.state === 'running' ? st('syncing', `Downloading ${Math.round(d.progress * 100)}%`) : g.sync === 'none' ? `<span class="dash">Not downloaded</span>` : st(g.sync);
            return `<div class="tr ${g.installed ? '' : 'remote'}" role="row"><span class="td"><span class="mk ${g.installed ? 'on' : ''}"></span></span><span class="td td-title"><span class="tt"><b>${esc(
              g.title,
            )}</b></span></span><span class="td"><span class="pc">${esc(k.platformOf(g).short)}</span></span><span class="td"><span class="num">${g.played == null ? '—' : esc(k.agoShort(g.played))}</span></span><span class="td">${state}</span></div>`;
          })
          .join('')}
      </div>
      <div class="cd-k">${key('Download missing · 1', { icon: 'download', block: true })}${key('Edit list', { icon: 'pen', block: true })}</div>
    </div>`;
  }
  screens.collections = (c) => {
    if (isOpen(c)) {
      return c.sys.shell({
        tab: 'library',
        title: 'Collections',
        actions: [{ icon: 'plus', label: 'New collection' }, { icon: 'more', label: 'More' }],
        split: {
          primary: `<div class="in coll">${collList(c, 'finish')}</div>`,
          primaryTitle: 'Collections',
          secondary: `<div class="in">${collDetail(c, 'finish')}</div>`,
          secondaryTitle: 'Finish in 2026',
        },
      });
    }
    return c.sys.shell({
      tab: 'library',
      title: 'Collections',
      back: 'Library',
      actions: [{ icon: 'plus', label: 'New collection' }],
      body: `<div class="in coll"><p class="intro">Saved filters. Each one prints the query that fills it; the meter shows which games are on this ${phone(c)}.</p>${collList(c)}</div>`,
    });
  };

  // ================= Game =================
  function gameHead(c) {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    return `<div class="gh">
      ${bezel(k.frame(g.id, pr.resume.frame), { ar: k.aspect(g.id), cls: 'gh-scr', strip: `<span>RESUME</span><span>${U(k.agoShort(pr.resume.at))}</span>`, alt: `${g.title} resume point` })}
      <div class="gh-m">
        ${lbl(`${U(p.short)} · ${g.year}`)}
        <h1 class="gh-t">${esc(g.title)}</h1>
        <p class="gh-p">${esc(g.publisher)} · ${esc(g.genre)}</p>
        <div class="gh-ro"><span class="rating">${g.rating}</span><span class="gh-h"><b>${hrs(g.hours)}</b>${lbl('PLAYED')}</span></div>
      </div>
    </div>`;
  }
  function gameSpec(c) {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    const row = (label, value, right) => `<div class="sp-r"><dt>${esc(label)}</dt><dd>${value}</dd><span class="sp-s">${right}</span></div>`;
    return `<dl class="sp">
        ${row('Resume', `${esc(k.ago(pr.resume.at))} · This ${phone(c)}`, st(pr.resume.remote))}
        ${row('Game-save data', `${esc(k.ago(pr.gameSave.at))} · SRAM · 8 KB`, st(pr.gameSave.remote))}
        ${row('Save states', '3 of 4 slots · newest 42 min ago', st('syncing', '1 syncing'))}
        ${row('File', '<span class="mono">Super Metroid (USA).sfc</span> · 3 MB', st('synced', 'Installed'))}
        ${row('Core', `${esc(p.core)} 1.63 · Touch controls`, st('ready', 'Ready'))}
      </dl>`;
  }
  const gameKeys = (wide) => `<div class="gk">
        ${key('Start with game save', { icon: 'cartridge', block: true })}
        ${key('Sync now', { icon: 'sync', block: true })}
        ${key('Remove download', { icon: 'trash', block: true, cls: `gk-w${wide ? ' kc-stack' : ''}`, sub: 'PROGRESS KEPT' })}
      </div>`;
  function gameOverview(c, o = {}) {
    const g = k.game('smetroid');
    return `${gameSpec(c)}${gameKeys()}${o.blurb === false ? '' : `<p class="blurb">${esc(g.blurb)}</p>`}`;
  }
  // Duo open: the resume screen and the title block form one header across the fold, with the keys
  // and readouts under the title; the spec sheet and the progress bank share the row beneath.
  function gameHeadWide(c) {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    return `<div class="gh gh-wide">
      ${bezel(k.frame(g.id, pr.resume.frame), { ar: k.aspect(g.id), cls: 'gh-scr', strip: `<span>RESUME · ${U(p.short)} · ${U(p.core)}</span><span>${U(k.agoShort(pr.resume.at))}</span>`, alt: `${g.title} resume point` })}
      <div class="gh-m">
        ${lbl(`${U(p.short)} · ${g.year} · ${U(g.publisher)} · ${U(g.genre)}`)}
        <h1 class="gh-t">${esc(g.title)}</h1>
        <div class="gh-ro">
          <span class="rating">${g.rating}</span>
          ${ro(hrs(g.hours), '', 'Played')}
          ${ro(esc(k.size(g.size)), '', 'Installed')}
          <div class="ro"><b class="ro-txt">${esc(p.core)}</b>${lbl(`${led('ready')}CORE READY`)}</div>
          <div class="ro"><b class="ro-txt">${tier(p.tier)}</b>${lbl('Controls')}</div>
        </div>
        <p class="gh-b">${esc(g.blurb)}</p>
        ${gameKeys(true)}
      </div>
    </div>`;
  }
  function slotBank(c, o = {}) {
    const g = D().progress.game;
    return `<div class="slots ${o.cls || ''}">${D()
      .progress.slots.map((s) =>
        s.empty
          ? `<button class="slot empty" aria-label="Save state to slot ${s.n}"><span class="slot-g">${i('plus', 18)}</span><span class="slot-n">0${s.n}</span><span class="slot-t">Empty</span><span class="slot-m">SAVE STATE HERE</span></button>`
          : `<button class="slot" aria-label="Slot ${s.n}, ${esc(s.name)}"><span class="slot-g"><img src="${k.frame(g, s.frame)}" alt=""></span><span class="slot-n">0${s.n}</span><span class="slot-t">${esc(
              s.name,
            )}</span><span class="slot-m">${U(k.agoShort(s.at))} · ${U(s.device === 'This iPhone' ? phone(c) : s.device)}</span>${st(s.remote, null, 'slot-s')}</button>`,
      )
      .join('')}</div>`;
  }
  function gameProgress(c, o = {}) {
    const pr = D().progress;
    const g = k.game(pr.game);
    return `<div class="gp">
      ${o.wide ? `<div class="gp-h">${lbl('PROGRESS')}<span class="rule"></span>${lbl(`${pr.history.length} IN RECOVERY HISTORY`)}</div>` : ''}
      <div class="gp-pair">
        <div class="gp-i">${thumb(g.id, pr.resume.frame, 'gp-scr')}<span class="gp-m">${lbl('AUTOMATIC RESUME')}<b>${esc(k.ago(pr.resume.at))}</b><small>This ${phone(c)} · ${esc(
      pr.history[0].reason,
    )}</small>${st(pr.resume.remote)}</span></div>
        <div class="gp-i">${`<span class="thumb gp-scr cart">${i('cartridge', 22)}</span>`}<span class="gp-m">${lbl('GAME-SAVE DATA')}<b>${esc(k.ago(pr.gameSave.at))}</b><small>${esc(pr.gameSave.detail)}</small>${st(pr.gameSave.remote)}</span></div>
      </div>
      <div class="gp-h">${lbl('SAVE STATES')}<span class="rule"></span>${lbl('3 OF 4 SLOTS')}</div>
      ${slotBank(c, { cls: o.wide ? 'slots-4' : 'slots-2' })}
    </div>`;
  }
  screens.game = (c) => {
    const segs = ['Overview', 'Progress', 'Files', 'Core'];
    const open = isOpen(c);
    const g = k.game('smetroid');
    const body = open
      ? root(
          c,
          'game game-open',
          `${gameHeadWide(c)}
          <div class="g12">
            <div class="s5 game-sheet"><div class="segwrap">${c.sys.segmented(['Overview', 'Files', 'Core'], 0)}</div>${gameSpec(c)}</div>
            <div class="s7">${gameProgress(c, { wide: true })}</div>
          </div>`,
        )
      : root(c, 'game', `${gameHead(c)}<div class="segwrap">${c.sys.segmented(segs, 0)}</div>${gameOverview(c)}`);
    return c.sys.shell({
      tab: 'library',
      title: 'Super Metroid',
      titleMode: 'none',
      back: 'Super Nintendo',
      primary: { icon: 'play', label: 'Resume' },
      actions: [
        { icon: 'star', label: 'Favourite' },
        { icon: 'more', label: 'More' },
      ],
      body,
    });
  };

  // ================= Transfers =================
  function modActive(c, o = {}) {
    const run = D().downloads.find((d) => d.state === 'running');
    const g = k.game(run.game);
    const p = k.platformOf(g);
    if (o.wide) {
      // Duo open: the channel runs the full width — title, big percentage and readouts in one row,
      // and a 72-segment meter across the fold.
      return mod({
        title: 'Receiving · CH 01',
        led: 'running',
        right: lbl(`${run.rate} MB/S · WI-FI`),
        cls: 'm-active m-active-wide',
        body: `<div class="ac">
          <div class="ac-row">
            <span class="ac-t"><b>${esc(g.title)}</b>${lbl(`${U(p.short)} · ${gb(run.total)} · ${U(p.core)}`)}</span>
            <div class="ac-pct"><b>${Math.round(run.progress * 100)}</b><small>%</small></div>
            ${ro(`${(run.done / 1000).toFixed(1)}`, `/ ${gb(run.total)}`, 'Received')}${ro(run.rate, 'MB/S', 'Rate')}${ro(esc(run.eta.replace(' s', '')), 'S', 'Left')}
            <span class="ac-k">${ikey('pause', 'Pause download')}${ikey('x', 'Cancel download')}</span>
          </div>
          ${meter(run.progress, 72, 'm-sig lg', 'Download progress')}
        </div>`,
      });
    }
    return mod({
      title: 'Receiving · CH 01',
      led: 'running',
      right: lbl(`${run.rate} MB/S`),
      cls: 'm-active',
      body: `<div class="ac">
        <div class="ac-h"><span class="ac-t"><b>${esc(g.title)}</b>${lbl(`${U(p.short)} · ${gb(run.total)}`)}</span>${ikey('pause', 'Pause download')}${ikey('x', 'Cancel download')}</div>
        <div class="ac-ro">
          <div class="ac-pct"><b>${Math.round(run.progress * 100)}</b><small>%</small></div>
          <dl class="kv kv-r"><div><dt>Received</dt><dd>${gb(run.done)} / ${gb(run.total)}</dd></div><div><dt>Rate</dt><dd>${run.rate} MB/s</dd></div><div><dt>Left</dt><dd>${esc(run.eta)}</dd></div></dl>
        </div>
        ${meter(run.progress, 40, 'm-sig lg', 'Download progress')}
      </div>`,
    });
  }
  function modQueue(c, o = {}) {
    const dl = D().downloads;
    const rows = [
      ...dl.filter((d) => d.state === 'queued').map((d, n) => ({ d, n: `0${n + 2}`, state: st('queued', 'Queued') })),
      ...dl.filter((d) => d.state === 'waiting').map((d) => ({ d, n: i('wifi-off', 13), state: st('waiting', d.reason) })),
    ];
    const f = dl.find((d) => d.state === 'failed');
    return mod({
      title: 'Download queue',
      right: lbl(`${rows.length} WAITING`),
      cls: `m-queue ${o.cls || ''}`,
      body: `<div class="tbl q" role="table" aria-label="Download queue" style="--cols:22px minmax(0,1fr) 56px 118px">
        ${rows
          .map(({ d, n, state }) => {
            const g = k.game(d.game);
            return `<div class="tr" role="row"><span class="td"><span class="qn">${n}</span></span><span class="td td-title"><span class="tt"><b>${esc(g.title)}</b><small>${esc(k.platformOf(g).short)}</small></span></span><span class="td"><span class="num">${gb(
              d.total,
            )}</span></span><span class="td">${state}</span></div>`;
          })
          .join('')}
        <div class="tr fail" role="row"><span class="td"><span class="qn bad">ERR</span></span><span class="td td-title"><span class="tt"><b>${esc(k.game(f.game).title)}</b><small>Stopped at ${Math.round(f.progress * 100)}% · ${esc(f.reason.toLowerCase())}</small></span></span><span class="td"><span class="num">${gb(f.total)}</span></span><span class="td">${key('Retry', { icon: 'restart', size: 'sm', cls: 'retry' })}</span></div>
      </div>`,
    });
  }
  function modUploads(c, o = {}) {
    const ups = moments().filter((m) => m.device === 'This iPhone' && (m.remote === 'waiting' || m.remote === 'syncing'));
    const kindL = (m) => (m.kind === 'resume' ? 'Automatic resume' : m.kind === 'state' ? `Save state · Slot ${m.slot}` : 'Game-save data');
    return mod({
      title: 'Sync queue · to server',
      led: 'syncing',
      right: lbl(`${ups.length} UP`),
      cls: `m-up ${o.cls || ''}`,
      body: `<ul class="ups">${ups
        .map(
          (m) => `<li>${i('arrow-up', 14)}<span class="ups-m"><b>${esc(kindL(m))}</b><small>${esc(k.game(m.game).title)} · ${esc(k.ago(m.at))}</small></span>${st(m.remote)}</li>`,
        )
        .join('')}</ul>`,
    });
  }
  function modDone(c, o = {}) {
    const done = D().downloads.filter((d) => d.state === 'done');
    return mod({
      title: 'Completed',
      right: lbl(`${done.length} · 3 DAYS`),
      cls: `m-done ${o.cls || ''}`,
      body: `<ol class="log">${done
        .map((d) => `<li class="log-l"><time>${clock(d.at)}</time>${led('done')}<span class="log-m"><b>Installed ${esc(k.game(d.game).title)}</b><small>${esc(k.platformOf(k.game(d.game)).short)} · ${gb(d.total)}</small></span></li>`)
        .join('')}</ol>`,
    });
  }
  function modStorBar(c, o = {}) {
    const s = D().storage;
    const used = storageUsed();
    return mod({
      title: `Storage · this ${phone(c)}`,
      right: lbl(`${gb(s.total - used)} FREE`),
      cls: `m-sbar ${o.cls || ''}`,
      body: `<div class="sbar"><i class="sb-roms" style="flex:${s.roms}"></i><i class="sb-prog" style="flex:${s.saves + s.states}"></i><i class="sb-other" style="flex:${s.cache + s.cores + s.bios}"></i><i class="sb-free" style="flex:${s.total - used}"></i></div>
        <div class="sbar-l"><span><i class="sb-roms"></i>ROMs ${gb(s.roms)}</span><span><i class="sb-prog"></i>Saves + states ${gb(s.saves + s.states)}</span><span><i class="sb-other"></i>Other ${gb(s.cache + s.cores + s.bios)}</span></div>`,
    });
  }
  screens.downloads = (c) => {
    // Duo open: the active channel spans the display; queue (7) · sync queue (5), then storage (4) ·
    // completed log (8) — the spans shift row to row so no seam lines up with the fold.
    const body = isOpen(c)
      ? root(
          c,
          'xfer xfer-open',
          `${modActive(c, { wide: true })}<div class="g12">${modQueue(c, { cls: 's7' })}${modUploads(c, { cls: 's5' })}${modStorBar(c, { cls: 's4' })}${modDone(c, { cls: 's8' })}</div>`,
        )
      : root(c, 'xfer', `${modActive(c)}${modQueue(c)}${modUploads(c)}${modStorBar(c)}${modDone(c)}`);
    return c.sys.shell({
      tab: 'transfers',
      title: 'Transfers',
      actions: [{ icon: 'pause', label: 'Pause all transfers' }],
      body,
    });
  };

  // ================= Search =================
  const OPS = [
    ['installed:iphone', 'Installed on this iPhone'],
    ['has:states', 'Has save states'],
    ['platform:snes', 'SNES'],
    ['genre:rpg', 'RPG'],
    ['played:never', 'Never played'],
  ];
  function searchResults(c, o = {}) {
    const hits = ['smetroid', 'metfusion'].map(k.game);
    const col = k.collection('metroidvania');
    const head = `<div class="ops" role="group" aria-label="Filters">${OPS.map(
      ([op, label]) => `<button class="op"><code>${esc(op.replace('iphone', c.os === 'android' ? 'phone' : 'iphone'))}</code><small>${esc(label.replace('iPhone', phone(c)))}</small></button>`,
    ).join('')}</div>
      <div class="parse">${lbl('QUERY')}<code>title~"metroid"</code><span class="parse-r">${lbl('RANK')}<span>word match › playable now › last played</span></span></div>`;
    if (o.part === 'head') return head;
    return `${o.part === 'results' ? '' : head}
      <section class="rs-sec">
        <header class="rs-h">${lbl('GAMES')}<span class="rule"></span>${lbl('2')}</header>
        ${hits
          .map((g, n) => {
            const p = k.platformOf(g);
            return `<div class="hit">
              <span class="hit-n">${n + 1}</span>
              ${thumb(g.id, 'b', 'hit-scr')}
              <span class="hit-m"><b>${esc(g.title).replace(/Metroid/, '<mark>Metroid</mark>')}</b><small>${esc(p.short)} · ${esc(k.size(g.size))} · ${esc(k.ago(g.played))}</small><span class="hit-why">${lbl('TITLE · WORD MATCH')}</span></span>
              <span class="hit-r">${st('ready', 'Plays now')}<button class="tk" aria-label="Play ${esc(g.title)}">${i('play', 15, 'fill')}</button></span>
            </div>`;
          })
          .join('')}
      </section>
      <section class="rs-sec">
        <header class="rs-h">${lbl('COLLECTIONS')}<span class="rule"></span>${lbl('1')}</header>
        <div class="hit hit-c"><span class="hit-n">1</span><span class="strip5">${col.games.map((id) => `<img src="${k.cover(id)}" alt="">`).join('')}</span><span class="hit-m"><b>${esc(col.name)}</b><small><code>${QUERY.metroidvania}</code></small><span class="hit-why">${lbl('CONTAINS BOTH GAMES')}</span></span><span class="hit-r">${lbl('4/4 ON DEVICE')}</span></div>
      </section>
      <section class="rs-sec">
        <header class="rs-h">${lbl('PLATFORMS')}<span class="rule"></span>${lbl('0')}</header>
        <p class="none">No platform names match. Try <code>platform:gba</code> to narrow games.</p>
      </section>
      ${o.part === 'results' ? '' : searchRecent()}`;
  }
  const searchRecent = () => `<section class="rs-sec recent">
        <header class="rs-h">${lbl('RECENT')}<span class="rule"></span></header>
        <div class="rec">${D().search.recent.map((r) => `<button class="rec-k">${i('history', 14)}<span>${esc(r)}</span></button>`).join('')}</div>
      </section>`;
  function opReference(c) {
    const rows = [
      ['platform:', 'platform:snes', 'System short name'],
      ['tier:', 'tier:touch', 'Touch · controller · browse'],
      ['installed:', `installed:${c.os === 'android' ? 'phone' : 'iphone'}`, 'On a device, or any'],
      ['has:', 'has:states', 'states · saves · conflict'],
      ['played:', 'played:<7d', 'never · <7d · >1y'],
      ['device:', 'device:ipad', 'Newest progress lives there'],
      ['genre:', 'genre:rpg', 'From RomM metadata'],
      ['year:', 'year:1990..1999', 'A year or a range'],
    ];
    return mod({
      title: 'Operators',
      right: lbl('COMBINE WITH SPACES'),
      cls: 'm-ops',
      body: `<div class="tbl opref" role="table" aria-label="Search operators" style="--cols:78px 118px minmax(0,1fr)">${rows
        .map(([op, ex, what]) => `<div class="tr" role="row"><span class="td"><code class="op-k">${esc(op)}</code></span><span class="td"><code>${esc(ex)}</code></span><span class="td"><small>${esc(what)}</small></span></div>`)
        .join('')}</div>`,
    });
  }
  screens.search = (c) => {
    if (isOpen(c)) {
      // Duo open: one canvas. Operator keys and the parsed query run the full width; ranked results
      // (7 columns, across the fold) sit beside the operator reference and recent searches (5).
      return c.sys.shell({
        tab: 'search',
        title: 'Search',
        titleMode: 'none',
        search: { value: 'metroid', focused: true },
        body: root(
          c,
          'srch srch-open',
          `${searchResults(c, { part: 'head' })}<div class="g12"><div class="s7">${searchResults(c, { part: 'results' })}</div><div class="s5 srch-side">${opReference(c)}${searchRecent()}</div></div>`,
        ),
      });
    }
    return c.sys.shell({
      tab: 'search',
      title: 'Search',
      titleMode: 'none',
      search: { value: 'metroid', focused: true },
      body: `<div class="in srch">${searchResults(c)}</div>`,
    });
  };

  // ================= Devices (signature) =================
  const LANES = () => D().fleet;
  const K_ICON = { resume: 'history', state: 'layers', save: 'cartridge' };
  function laneCells(c) {
    // Newest progress per game per device, from moments + the Super Metroid history.
    const games = ['smetroid', 'earthbound', 'linksawak', 'chrono', 'kirbyadv', 'alttp', 'metfusion', 'aria', 'pkcrystal'];
    const all = [...moments()];
    for (const h of D().progress.history) if (h.kind === 'Automatic resume' && h.device === 'iPad') all.push({ game: D().progress.game, kind: 'resume', at: h.at, device: h.device, remote: 'synced' });
    return games.map((gid) => {
      const per = LANES().map((f) => all.filter((m) => m.game === gid && m.device === f.name).sort((a, b) => a.at - b.at)[0] || null);
      const newest = per.reduce((best, m, n) => (m && (best < 0 || m.at < per[best].at) ? n : best), -1);
      return { g: k.game(gid), per, newest, conflict: per.filter((m) => m && m.remote === 'conflict').length > 1 };
    });
  }
  function lanes(c, o = {}) {
    const fl = LANES();
    const heads = fl
      .map(
        (f, n) => `<div class="ln-h l${n + 1}" role="columnheader">
        <span class="ln-d">${i(f.kind, 15)}${led(f.state)}</span>
        <b>${esc(f.name === 'This iPhone' ? `This ${phone(c)}` : f.name)}</b>
        <small>${esc(f.model)}</small>
      </div>`,
      )
      .join('');
    const rd = (label, fn) => `<span class="ln-k" role="rowheader">${lbl(label)}</span>${fl.map((f, n) => `<span class="ln-v l${n + 1}" role="cell">${fn(f)}</span>`).join('')}`;
    const rows = laneCells(c)
      .map((r) => {
        const cells = r.per
          .map((m, n) => {
            if (!m) return `<span class="ln-c l${n + 1} empty" role="cell"><i class="ln-dot"></i></span>`;
            const cls = r.conflict ? 'bad' : n === r.newest ? 'new' : 'old';
            return `<span class="ln-c l${n + 1} ${cls}" role="cell"><span class="pm">${i(K_ICON[m.kind], 12)}<b>${U(k.agoShort(m.at))}</b></span></span>`;
          })
          .join('');
        const fork = r.conflict
          ? `<svg class="ln-fork" viewBox="0 0 300 56" preserveAspectRatio="none" aria-hidden="true"><path d="M0 28 H8 C20 28 20 17 32 17 H50 M8 28 C20 28 20 39 32 39 H150"/></svg>`
          : '';
        return `<div class="ln-row ${r.conflict ? 'conf' : ''}" role="row"><span class="ln-g" role="rowheader"><b>${esc(r.g.title)}</b>${
          r.conflict ? `<small class="bad">${led('conflict')}Diverged</small>` : `<small>${esc(k.platformOf(r.g).short)}</small>`
        }</span>${cells}${fork}</div>`;
      })
      .join('');
    return `<div class="lanes" role="table" aria-label="Devices and newest progress">
      <div class="ln-row ln-top" role="row"><span class="ln-k" role="columnheader"></span>${heads}</div>
      <div class="ln-row ln-read" role="row">${rd('Last sync', (f) => `<b class="num">${f.last ? U(k.agoShort(f.last)) : 'NOW'}</b>`)}</div>
      <div class="ln-row ln-read" role="row">${rd('Pending', (f) => `<b class="num pend">${f.pending ? led('waiting') : ''}${f.pending}</b>`)}</div>
      <div class="ln-row ln-read" role="row">${rd('Installed', (f) => `<b class="num">${f.installed}</b>`)}</div>
      <div class="ln-row ln-read" role="row">${rd('Storage', (f) => `<b class="num">${gb(f.storage)}</b>`)}</div>
      <div class="ln-row ln-sub" role="row"><span class="ln-k">${lbl('NEWEST PROGRESS')}</span><span class="ln-leg">${i('history', 11)}Resume ${i('layers', 11)}State ${i('cartridge', 11)}Game save</span></div>
      ${rows}
    </div>`;
  }
  function branch(c, o = {}) {
    const cf = D().conflict;
    const g = k.game(cf.game);
    const [a, b] = cf.branches;
    const rootS = D().sessions.find((s) => s.game === cf.game && s.day > 0);
    return mod({
      title: 'Branch · EarthBound resume',
      led: 'conflict',
      right: lbl('1 DECISION'),
      cls: `m-branch ${o.cls || ''}`,
      body: `<div class="br">
        <svg class="br-g" viewBox="0 0 26 100" preserveAspectRatio="none" aria-hidden="true">
          <path d="M0 50 C13 50 13 25 26 25" class="br-a"/><path d="M0 50 C13 50 13 75 26 75" class="br-b"/>
        </svg>
        <div class="br-root"><i class="br-node"></i>${lbl('LAST SHARED')}<b>${esc(rootS.note)}</b><small>${rootS.day} d ago · This ${phone(c)}</small></div>
        <div class="br-arm top">${thumb(g.id, a.frame, 'br-scr')}<span class="br-m"><b>This ${phone(c)}</b><small>${esc(a.where)} · ${esc(k.ago(a.at))}</small><small>${esc(a.playtime)} played</small></span>${led('conflict')}</div>
        <div class="br-arm bot">${thumb(g.id, b.frame, 'br-scr')}<span class="br-m"><b>${esc(b.device)}</b><small>${esc(b.where)} · ${esc(k.ago(b.at))}</small><small>${esc(b.playtime)} played</small></span>${led('conflict')}</div>
      </div>
      ${key('Choose resume point', { kind: 'primary', block: true, icon: 'branch' })}`,
    });
  }
  function trace(c, o = {}) {
    // Sessions over the last three days on a 0–72 h axis ending now (9:41).
    const span = 72 * 60;
    const toX = (s) => {
      const [h, m] = s.start.split(':').map(Number);
      const startMin = s.day * 1440 + (NOW - (h * 60 + m));
      return { x: ((span - startMin) / span) * 100, w: Math.max((s.minutes / span) * 100, 0.9) };
    };
    const fl = LANES();
    const lane = (f, n) => {
      const ss = D().sessions.filter((s) => s.device === f.name && s.day <= 2);
      return `<div class="tr-l"><span class="tr-n">${lbl(f.name === 'This iPhone' ? U(phone(c)) : U(f.name))}</span><div class="tr-t">${ss
        .map((s) => {
          const { x, w } = toX(s);
          return `<i class="${s.game === 'earthbound' ? 'bad' : ''}" style="left:${x.toFixed(2)}%;width:${w.toFixed(2)}%" title="${esc(k.game(s.game).title)}"></i>`;
        })
        .join('')}</div><span class="tr-c">${ss.length}</span></div>`;
    };
    return mod({
      title: 'Sessions · 72 h',
      right: lbl(`${D().sessions.filter((x) => x.day <= 2).length} SESSIONS`),
      cls: `m-trace ${o.cls || ''}`,
      body: `<div class="trace">${fl.map(lane).join('')}<div class="tr-ax"><span>−72H</span><span>−48H</span><span>−24H</span><span>NOW</span></div><div class="tr-key">${led('conflict')}EarthBound, played on both devices</div></div>`,
    });
  }
  screens.signature = (c) => {
    // Duo open: the lanes run the full width of the display (the Pixel 9 lane sits on the crease);
    // the branch, the session trace and the live log follow on the 12-column grid.
    const body = isOpen(c)
      ? root(c, 'dev dev-open', `${lanes(c)}<div class="g12">${branch(c, { cls: 's7' })}${trace(c, { cls: 's5' })}${modLog(c, { n: 5, cls: 's12' })}</div>`)
      : root(c, 'dev', `${lanes(c)}${branch(c)}${trace(c)}`);
    return c.sys.shell({
      tab: 'devices',
      title: 'Devices',
      actions: [{ icon: 'sync', label: 'Sync all devices' }],
      body,
    });
  };

  // ================= Settings =================
  function setServer(c, o = {}) {
    const [home, sam] = D().profiles;
    return `${mod({
      title: 'Server',
      led: 'online',
      right: lbl(esc(home.version.toUpperCase())),
      cls: `m-srv ${o.srv || ''}`,
      body: `<div class="acct"><span class="av">${esc(D().user.initials)}</span><span class="acct-m"><b>${esc(home.user)}</b><span class="mono">${esc(home.url)}</span></span>${key('Sync now', { icon: 'sync', size: 'sm' })}</div>
        <div class="acct-ro">${ro('<span class="ro-txt">Online</span>', '', 'State')}${ro(`<span class="ro-txt">${esc(k.ago(home.lastSync))}</span>`, '', 'Last sync')}${ro('<span class="ro-txt">Cloudflare</span>', '', 'Access')}</div>`,
    })}
    ${mod({
      title: 'Profiles',
      right: lbl('2'),
      cls: `m-prof ${o.prof || ''}`,
      body: `<div class="prof">${latch(true, home.name, `${U(home.user)} · ACTIVE`)}${latch(false, sam.name, `OFFLINE-READY · ${U(k.agoShort(sam.lastSync))}`)}</div>`,
    })}`;
  }
  function setSync(c, o = {}) {
    const tr = (title, sub, on) => `<div class="srow"><span class="srow-m"><b>${esc(title)}</b><small>${esc(sub)}</small></span>${c.sys.toggle(on, title)}</div>`;
    return `${mod({
      title: 'Sync',
      cls: `m-sync ${o.sync || ''}`,
      body: `${tr('Game-save data', 'Syncs after every session', true)}${tr('Resume states', 'Automatic resume follows you between devices', true)}${tr('Use cellular data', 'Saves and states only, never downloads', false)}`,
    })}
    ${o.entry === false ? '' : ctlEntry()}`;
  }
  const ctlEntry = () => `<button class="mod entry"><span class="entry-i">${i('gamepad', 18)}</span><span class="srow-m"><b>Player &amp; controls</b><small>Touch · ${led('online')}DualSense connected</small></span>${i('chevron-right', 16)}</button>`;
  function setStorage(c, o = {}) {
    const s = D().storage;
    const used = storageUsed();
    const parts = [
      ['ROMs', s.roms, 'roms'],
      ['Saves', s.saves, 'saves'],
      ['States', s.states, 'states'],
      ['Cache', s.cache, 'cache'],
      ['Cores', s.cores, 'cores'],
      ['BIOS', s.bios, 'bios'],
    ];
    return mod({
      title: 'Storage',
      right: lbl(`${gb(used)} OF ${gb(s.total)}`),
      cls: `m-storage ${o.cls || ''}`,
      body: `<div class="sbar">${parts.map(([, v, id]) => `<i class="sb-${id}" style="flex:${v}"></i>`).join('')}<i class="sb-free" style="flex:${s.total - used}"></i></div>
        <dl class="stor">${parts.map(([n, v, id]) => `<div><dt><i class="sb-${id}"></i>${esc(n)}</dt><dd>${gb(v)}</dd></div>`).join('')}</dl>
        <div class="stor-k">${key('Clear cache', { size: 'sm' })}${key('Manage downloads', { size: 'sm' })}</div>`,
    });
  }
  function setCores(c, o = {}) {
    const cores = D().cores;
    const att = cores.filter((x) => x.state === 'bios' || x.state === 'setup');
    const ready = cores.filter((x) => x.state === 'ready').length;
    return mod({
      title: 'Cores & BIOS',
      right: lbl(`${cores.length} CORES`),
      cls: `m-cores ${o.cls || ''}`,
      body: `<div class="tbl cores" role="table" aria-label="Cores" style="--cols:minmax(0,1fr) 62px 104px">
        ${att
          .map(
            (x) => `<div class="tr" role="row"><span class="td td-title"><span class="tt"><b>${esc(x.name)}</b><small>${esc(x.family)} · ${esc(x.note)}</small></span></span><span class="td"><span class="num">${esc(x.version)}</span></span><span class="td">${st(
              'waiting',
              x.state === 'bios' ? 'Needs BIOS' : 'Finish setup',
            )}</span></div>`,
          )
          .join('')}
        <div class="tr" role="row"><span class="td td-title"><span class="tt"><b>${ready} cores ready</b><small>Snes9x, mGBA, Gambatte, melonDS DS…</small></span></span><span class="td"></span><span class="td">${st('ready', 'Ready')}</span></div>
        <div class="tr" role="row"><span class="td td-title"><span class="tt"><b>Flycast</b><small>Dreamcast · controller only</small></span></span><span class="td"><span class="num">2.5</span></span><span class="td">${st('off', 'Controller')}</span></div>
      </div>`,
    });
  }
  const aboutRow = () => `<button class="mod entry"><span class="entry-i">${i('info', 18)}</span><span class="srow-m"><b>About Rommio</b><small>Licences, support, diagnostics</small></span>${i('chevron-right', 16)}</button>`;
  screens.settings = (c) => {
    // Duo open: one system panel. Server plate (8) · profiles (4), sync (5) · storage (7), cores (7) ·
    // entries (5): the seams move each row and the fold falls inside a module every time.
    const body = isOpen(c)
      ? root(
          c,
          'set set-open',
          `<div class="g12">${setServer(c, { srv: 's8', prof: 's4' })}${setSync(c, { sync: 's5', entry: false })}${setStorage(c, { cls: 's7' })}${setCores(c, { cls: 's7' })}<div class="s5 set-entries">${ctlEntry()}${aboutRow()}</div></div>`,
        )
      : root(c, 'set', `${setServer(c)}${setSync(c)}${setStorage(c)}${setCores(c)}${aboutRow()}`);
    return c.sys.shell({ tab: 'console', title: 'Settings', back: 'Console', body });
  };

  // ================= Controls =================
  function fader(label, value, frac, unit = '%') {
    const ticks = k.range(11).map((j) => `<i class="${j % 5 === 0 ? 'maj' : ''}" style="bottom:${j * 10}%"></i>`).join('');
    return `<div class="fd" role="slider" tabindex="0" aria-label="${esc(label)}" aria-valuenow="${value}" aria-valuetext="${value}${unit}">
      <div class="fd-ro"><b>${value}</b><small>${unit}</small></div>
      <div class="fd-tr">${ticks}<span class="fd-fill" style="height:${(frac * 100).toFixed(1)}%"></span><span class="fd-cap" style="bottom:${(frac * 100).toFixed(1)}%"></span></div>
      ${lbl(esc(label))}
      <div class="fd-k"><button aria-label="Decrease ${esc(label)}">${i('minus', 16)}</button><button aria-label="Increase ${esc(label)}">${i('plus', 16)}</button></div>
    </div>`;
  }
  function ctlPreview(c, w) {
    if (c.device.duo) {
      // Duo closed: the preview is this device's own landscape player (678×466, the k.stage layout).
      const pw = 678;
      const ph = 466;
      const s = w / pw;
      return mod({
        title: 'Live preview',
        right: lbl('SUPER METROID · SNES'),
        cls: 'm-prev',
        body: `<div class="prev" style="height:${Math.round(ph * s)}px"><div class="prev-in" style="width:${pw}px;height:${ph}px;transform:scale(${s.toFixed(4)})">${landscapeBody(c, {
          variant: 'c',
          w: pw,
          h: ph,
          safe: { left: 20, right: 20, bottom: 16, top: 0 },
        })}</div></div>`,
      });
    }
    const pw = 874;
    const ph = 402;
    const s = w / pw;
    return mod({
      title: 'Live preview',
      right: lbl('SUPER METROID · SNES'),
      cls: 'm-prev',
      body: `<div class="prev" style="height:${Math.round(ph * s)}px"><div class="prev-in" style="transform:scale(${s.toFixed(4)});--safe-left:62px;--safe-right:62px;--safe-bottom:21px">${landscapeBody(
        c,
        { variant: 'c', w: pw, h: ph, safe: { left: 62, right: 62, bottom: 21, top: 0 } },
      )}</div></div>`,
    });
  }
  function ctlFaders(c) {
    const ct = D().controls;
    return mod({
      title: 'Tuning',
      cls: 'm-fad',
      body: `<div class="fds">${fader('Opacity', Math.round(ct.opacity * 100), ct.opacity)}${fader('Size', Math.round(ct.size * 100), (ct.size - 0.5) / 1)}${fader('Deadzone', Math.round(ct.deadzone * 100), ct.deadzone / 0.4)}</div>`,
    });
  }
  function ctlLatches(c) {
    const ct = D().controls;
    return mod({
      title: 'Behaviour',
      cls: 'm-beh',
      body: `<div class="latches">
        ${latch(ct.showTouch, 'Touch controls', 'SHOWN IN GAME')}
        ${latch(ct.autoHideOnController, 'Hide with controller', `${led('online')}DUALSENSE`)}
        ${latch(ct.leftHanded, 'Left-handed', 'SWAP SIDES')}
        ${latch(ct.haptics, 'Haptics', 'TICK ON PRESS')}
        ${latch(ct.oled, 'OLED black', 'AROUND THE GAME')}
        ${latch(ct.consoleColors, 'Console colours', 'ORIGINAL PAD')}
      </div>
      <div class="ctype">${lbl('CONTROLLER TYPE')}${c.sys.segmented(['Automatic', 'Xbox', 'PlayStation', 'Nintendo'], 0)}</div>`,
    });
  }
  screens.controls = (c) => {
    if (isOpen(c)) {
      // Duo open: the preview IS the Duo player (the k.stage layout), rendered at its real 951×669 size
      // and scaled to the canvas; the tuning panel floats over the middle of the game, and every change
      // shows on the keys in the rails either side of it.
      const L = c.sys.layout({ back: true, titleMode: 'none' });
      const pw = 951;
      const ph = 669;
      const s = Math.min(L.width / pw, c.h / ph);
      const safe = { left: 0, right: 0, bottom: 20, top: 16 };
      const ct = D().controls;
      // The panel sits over the game, its foot just above Select · Menu · Start so every key stays visible.
      const st = k.stage(stageCtx(c, { w: pw, h: ph, safe }), { aspect: k.aspect('smetroid') });
      const keysTop = Math.round(c.h / 2 + (st.tertiary.cy - st.tertiary.h / 2 - ph / 2) * s);
      const tune = `<section class="tune" aria-label="Controls" style="top:auto;bottom:${c.h - keysTop + 12}px;transform:translateX(-50%)">
        <header class="tune-h">${lbl('CONTROLS')}<span class="tune-hr">${led('ok')}${lbl('LIVE · SUPER METROID · SNES')}</span></header>
        <div class="fds">${fader('Opacity', Math.round(ct.opacity * 100), ct.opacity)}${fader('Size', Math.round(ct.size * 100), (ct.size - 0.5) / 1)}${fader('Deadzone', Math.round(ct.deadzone * 100), ct.deadzone / 0.4)}</div>
        ${ctlLatches(c)}
      </section>`;
      return c.sys.shell({
        tab: 'console',
        title: 'Controls',
        titleMode: 'none',
        back: 'Settings',
        heroUnderBar: true,
        edge: false,
        body: root(
          c,
          'ctl ctl-open',
          `<div class="stage" style="--s:${s.toFixed(4)};--pw:${pw}px;--ph:${ph}px"><div class="stage-in">${landscapeBody(c, { variant: 'c', w: pw, h: ph, safe })}</div></div>${tune}`,
        ),
      });
    }
    const L = c.sys.layout({ back: true });
    const body = root(c, 'ctl', `${ctlPreview(c, L.width - 24 - 2)}${ctlFaders(c)}${ctlLatches(c)}`);
    return c.sys.shell({ tab: 'console', title: 'Controls', back: 'Settings', body });
  };

  // ================= Onboarding =================
  function steps(n) {
    const names = ['Server', 'Account', 'Library'];
    return `<ol class="steps" aria-label="Step ${n} of 3">${names
      .map((s, j) => `<li class="${j + 1 === n ? 'on' : j + 1 < n ? 'done' : ''}">${j + 1 < n ? led('ok') : `<i class="led ${j + 1 === n ? 'led-sig' : ''}"></i>`}<em>0${j + 1}</em>${U(s)}</li>`)
      .join('')}</ol>`;
  }
  const dock = (html) => `<div class="dock">${html}</div>`;
  // Compact: one column with the keys docked at the bottom. Duo open: each step composes its own
  // single canvas (`wide`), with the keys set at the trailing end of the last row.
  function onb(c, cls, a, b, keys, wide) {
    if (isOpen(c)) return root(c, `onb onb-open ${cls}`, wide);
    return root(c, `onb ${cls}${c.h < 700 ? ' short' : ''}`, `${a}${b}${dock(keys)}`);
  }

  screens.welcome = (c) => {
    const t = tierTotals();
    const systems = t.touch.platforms + t.controller.platforms;
    const top = `<header class="w-top">${wordmark('wm-lg')}${lbl('NATIVE CLIENT FOR ROMM')}</header>`;
    const panel = `<section class="panel-front" aria-label="Rommio status">
        <div class="pf-h">${lbl('SELF-TEST')}<span class="pf-pwr">${led('ok')}${lbl('READY')}</span></div>
        <div class="lcd">
          <div class="lcd-r"><span>SERVER</span><em class="blink">NOT CONNECTED_</em></div>
          <div class="lcd-r"><span>LIBRARY</span><em>— — —</em></div>
          <div class="lcd-r"><span>PLAYER</span><em class="ok">CORES FOR ${systems} SYSTEMS</em></div>
          <div class="lcd-r"><span>SYNC</span><em>GAME SAVES · RESUME STATES</em></div>
        </div>
        <div class="pf-ch">${['Browse', 'Play offline', 'Sync'].map((x, n) => `<span class="pf-c"><i class="led ${n === 1 ? 'led-ok' : ''}"></i>${U(x)}</span>`).join('')}</div>
      </section>`;
    const headline = '<h1 class="w-h">A control surface for your RomM library.</h1>';
    const a = `${top}${panel}${headline}`;
    const b = `<ol class="w-caps">
        <li><em>01</em><span><b>Browse your server</b><small>Every platform and game on the RomM server you run.</small></span></li>
        <li><em>02</em><span><b>Play offline</b><small>Download once; built-in cores play it with no signal.</small></span></li>
        <li><em>03</em><span><b>Progress that follows you</b><small>Game saves and resume states sync through your server.</small></span></li>
      </ol>`;
    const keys = `${key('Connect to a server', { kind: 'primary', size: 'lg', block: true, icon: 'server' })}
        ${key('Scan setup code', { size: 'lg', block: true, icon: 'qr' })}
        <p class="fine">Rommio provides no games. It connects to a RomM server you run.</p>`;
    // Duo open: the front panel runs the full width, the headline sets across the fold, the three
    // capabilities read as one row, and the keys close the composition.
    const wide = `<div class="w-wide">${top}${panel}${headline}${b}<div class="w-keys">${key('Connect to a server', { kind: 'primary', size: 'lg', icon: 'server' })}${key('Scan setup code', {
      size: 'lg',
      icon: 'qr',
    })}<p class="fine">Rommio provides no games. It connects to a RomM server you run.</p></div></div>`;
    return c.sys.shell({ tabBar: false, titleMode: 'none', title: 'Welcome', edge: false, body: onb(c, 'welcome', a, b, keys, wide) });
  };

  screens.connect = (c) => {
    const s = D().server.discovered;
    const open = isOpen(c);
    const a = `${steps(1)}
      ${mod({
        title: 'Server address',
        led: 'ok',
        right: lbl('VERIFIED'),
        cls: `m-addr ${open ? 's12' : ''}`,
        body: `<div class="field ok">${i('lock', 16)}<span class="field-v mono">romm.homelab.dev</span><i class="caret"></i><button class="field-x" aria-label="Clear address">${i('x', 14)}</button></div>
          <ul class="check">
            <li>${led('ok')}<b>Reachable</b><span class="mono">https · 443</span></li>
            <li>${led('ok')}<b>Protected by Cloudflare Access</b><span class="mono">sign-in next</span></li>
            <li>${led('ok')}<b>RomM 5.2.0</b><span class="mono">compatible</span></li>
          </ul>`,
      })}`;
    const b = `${mod({
        title: 'Access method',
        right: lbl('TRIES EACH IN TURN'),
        cls: `m-access ${open ? 's7' : ''}`,
        body: `<div class="sel4" role="radiogroup" aria-label="Access method">${['Automatic', 'Direct', 'Cloudflare Access', 'Service token'].map((x, n) => latch(n === 0, x, '', 'radio')).join('')}</div>`,
      })}
      ${mod({
        title: 'Saved and nearby',
        right: lbl(String(s.length)),
        cls: `m-near ${open ? 's5' : ''}`,
        body: s
          .map(
            (x) => `<button class="near"><span class="near-i">${i(x.secure ? 'lock' : 'wifi', 16)}</span><span class="srow-m"><b class="mono">${esc(x.name)}</b><small>${esc(x.note)}</small></span>${
              x.secure ? '' : '<span class="chip">HTTP</span>'
            }${i('chevron-right', 16)}</button>`,
          )
          .join(''),
      })}`;
    const keys = key('Continue', { kind: 'primary', size: 'lg', block: true, icon: 'arrow-right' });
    // Duo open: the address and its live check run the full width; access method (7) and saved
    // servers (5) share the next row; Continue closes it at the trailing end.
    const wide = `<div class="g12">${a.replace(steps(1), `<div class="s12">${steps(1)}</div>`)}${b}<div class="s12 keys-row"><p class="fine left">Rommio checks the address before asking you to sign in.</p>${key('Continue', {
      kind: 'primary',
      size: 'lg',
      icon: 'arrow-right',
    })}</div></div>`;
    return c.sys.shell({ tabBar: false, title: 'Connect', back: 'Welcome', body: onb(c, 'connect', a, b, keys, wide) });
  };

  screens.signin = (c) => {
    const pr = D().profiles[0];
    const a = `${steps(2)}
      <section class="plate">
        <div class="plate-h">${led('ok')}${lbl('SERVER VERIFIED')}<button class="link">Change</button></div>
        <b class="plate-host mono">${esc(pr.url)}</b>
        <div class="plate-ro">${lbl(esc(pr.version.toUpperCase()))}${lbl('CLOUDFLARE ACCESS')}</div>
      </section>`;
    const b = `<div class="fields">
        <label class="fl-l">${lbl('USERNAME')}<span class="field">${i('user', 16)}<span class="field-v">ryan</span></span></label>
        <label class="fl-l">${lbl('PASSWORD')}<span class="field">${i('key', 16)}<span class="field-v dots">••••••••••••</span><button class="field-x" aria-label="Show password">${i('eye', 16)}</button></span></label>
      </div>
      <p class="fine left">Your password goes to ${esc(pr.url)} only. Rommio keeps a session token on this device, never the password.</p>`;
    const keys = `${key('Sign in', { kind: 'primary', size: 'lg', block: true, icon: 'log-in' })}<div class="or"><span></span>${lbl('OR')}<span></span></div>${key('Continue with single sign-on', {
      size: 'lg',
      block: true,
      icon: 'shield',
    })}`;
    // Duo open: a sign-in is a short form, so it sets as one centred column across the fold rather
    // than being spread into two panels.
    const wide = `<div class="s12">${steps(2)}</div><div class="si-col">${a.replace(steps(2), '')}${b}<div class="keys">${keys}</div></div>`;
    return c.sys.shell({ tabBar: false, title: 'Sign in', back: 'Server', body: onb(c, 'signin', a, b, keys, wide) });
  };

  screens.hydrate = (c) => {
    const t = k.totals();
    const open = isOpen(c);
    const a = `${steps(3)}
      ${mod({
        title: 'Caching for offline use',
        led: 'syncing',
        right: lbl('3 OF 4 DONE'),
        cls: `m-cache ${open ? 's12' : ''}`,
        body: `<div class="hy">
          <div class="hy-r">${led('ok')}<span>Platforms</span><b class="num">${t.platforms}</b></div>
          <div class="hy-r">${led('ok')}<span>Games</span><b class="num">${k.num(t.games)}</b></div>
          <div class="hy-r">${led('ok')}<span>Collections</span><b class="num">${D().collections.length}</b></div>
          <div class="hy-r live">${led('syncing')}<span>Artwork</span><b class="num">61<small>%</small></b></div>
          ${meter(0.61, 40, 'm-sig', 'Artwork cached')}
        </div>`,
      })}`;
    const b = `${mod({
        title: 'How progress travels',
        cls: `m-how ${open ? 's7' : ''}`,
        body: `<div class="chain" aria-hidden="true"><span class="node">${i('smartphone', 14)}THIS ${U(phone(c))}</span><i></i><span class="node srv">${i('server', 14)}ROMM</span><i></i><span class="node">${i('devices', 14)}PIXEL 9 · IPAD</span></div>
          <p class="how">Game saves and resume states sync between your devices through your own server — no third-party cloud.</p>`,
      })}
      ${mod({
        title: 'Options',
        cls: `m-opt ${open ? 's5' : ''}`,
        body: `<div class="srow"><span class="srow-m"><b>Keep artwork offline</b><small>Covers and screenshots · about 640 MB</small></span>${c.sys.toggle(true, 'Keep artwork offline')}</div>
          <div class="srow"><span class="srow-m"><b>Use cellular data</b><small>Saves and states only</small></span>${c.sys.toggle(false, 'Use cellular data')}</div>`,
      })}`;
    const keys = `${key('Start playing', { kind: 'primary', size: 'lg', block: true, icon: 'play' })}<p class="fine">Artwork keeps caching in the background.</p>`;
    // Duo open: the cache readouts and the artwork meter run the full width; the explanation (7) and
    // options (5) follow; Start playing closes the last row.
    const wide = `<div class="g12"><div class="s12">${steps(3)}</div>${a.replace(steps(3), '')}${b}<div class="s12 keys-row"><p class="fine left">Artwork keeps caching in the background.</p>${key('Start playing', {
      kind: 'primary',
      size: 'lg',
      icon: 'play',
    })}</div></div>`;
    return c.sys.shell({ tabBar: false, title: 'First sync', body: onb(c, 'hyd', a, b, keys, wide) });
  };

  // ================= Player =================
  const DPAD =
    'M40 7a7 7 0 0 1 7-7h26a7 7 0 0 1 7 7v33h33a7 7 0 0 1 7 7v26a7 7 0 0 1-7 7H80v33a7 7 0 0 1-7 7H47a7 7 0 0 1-7-7V80H7a7 7 0 0 1-7-7V47a7 7 0 0 1 7-7h33z';
  const dpad = (cls = '') => `<div class="dpad ${cls}" role="group" aria-label="Directional pad">
    <svg viewBox="-1 -1 122 125" aria-hidden="true"><path d="${DPAD}" class="dp-sh" transform="translate(0 3)"/><path d="${DPAD}" class="dp-f"/>
      <path d="M60 12l-6 8h12zM60 108l-6-8h12zM12 60l8-6v12zM108 60l-8-6v12z" class="dp-a"/></svg>
    <button class="dph u" aria-label="Up"></button><button class="dph d" aria-label="Down"></button><button class="dph l" aria-label="Left"></button><button class="dph r" aria-label="Right"></button>
  </div>`;
  const fk = (l, cls = '') => `<button class="fk ${cls} fk-${l.toLowerCase()}" aria-label="${l}"><span>${l}</span></button>`;
  const diamond = (cls = '') => `<div class="diamond ${cls}" role="group" aria-label="Face buttons">${fk('X')}${fk('Y')}${fk('A')}${fk('B')}</div>`;
  const wk = (l, cls = '') => `<button class="wk ${cls}" aria-label="${esc(l)}"><span>${U(l)}</span></button>`;
  const menuK = () => `<button class="wk menu" aria-label="Menu">${i('menu', 16)}</button>`;

  // Landscape player body; also rendered, scaled, as the Controls preview and the pause backdrop.
  function landscapeBody(c, o = {}) {
    if (c.device.duo) return stageLandscape(c, o);
    const W = o.w || c.w;
    const H = o.h || c.h;
    const g = k.game(o.game || 'smetroid');
    const p = k.platformOf(g);
    const safe = o.safe || c.safe;
    const ar = k.aspect(g.id);
    const gh = Math.round(Math.min(H - 46, (W - 2 * (safe.left + 150) - 14) / ar));
    const gw = Math.round(gh * ar);
    const rail = (W - gw - 14) / 2;
    return `<div class="pl ${o.paused ? 'paused' : ''}" style="--gw:${gw}px;--gh:${gh}px;--rail:${rail}px;--sl:${safe.left}px;--sr:${safe.right}px;--sb:${safe.bottom}px">
      <div class="pl-scr">
        <div class="bz-s"><span>${o.paused ? `${i('pause', 10)} PAUSED` : `${U(p.short)} · ${U(p.core)}`}</span><span>${U(g.title)}</span></div>
        <div class="pl-g"><img src="${k.frame(g.id, o.variant || 'b')}" alt="${esc(g.title)} gameplay"></div>
      </div>
      <div class="pl-ctl">
        <div class="rail l">${wk('L', 'sh')}${dpad()}<div class="tert">${menuK()}${wk('Select')}</div></div>
        <div class="rail r">${wk('R', 'sh')}${diamond()}<div class="tert">${wk('Start')}</div></div>
      </div>
      ${o.toast ? `<div class="toast" role="status">${led('ok')}<span>Automatic resume updated</span></div>` : ''}
    </div>`;
  }

  // ---------- iPhone Duo: the stage ----------
  // On Duo (open and closed) every player surface takes its geometry from k.stage(): each game screen
  // is drawn exactly at its rect, the keys sit low in the rails beside or beneath it and never cover
  // it. The bezel is a thin lip that always stops ≥ 8pt short of a key; its printed strip faces free
  // space (under the game in landscape, above it in portrait) and leaves the camera clear.
  const at = (r) => `left:${r.x}px;top:${r.y}px;width:${r.w}px;height:${r.h}px`;
  const disc = (p) => ({ x: Math.round(p.cx - p.size / 2), y: Math.round(p.cy - p.size / 2), w: p.size, h: p.size });
  const band = (t) => ({ x: Math.round(t.cx - t.w / 2), y: Math.round(t.cy - t.h / 2), w: Math.round(t.w), h: t.h });
  // Geometry for a player drawn at w × h on this device (the Controls preview draws one off-screen).
  const stageCtx = (c, o = {}) => ({ w: o.w || c.w, h: o.h || c.h, safe: o.safe || c.safe, device: c.device });
  // One lip all round a screen: up to `max`, and ≥ 8pt short of every key beside or beneath it.
  function lip(scr, keys, max = 6) {
    let m = max;
    for (const q of keys) {
      const xs = q.x < scr.x + scr.w && q.x + q.w > scr.x;
      const ys = q.y < scr.y + scr.h && q.y + q.h > scr.y;
      if (xs && q.y >= scr.y + scr.h) m = Math.min(m, q.y - scr.y - scr.h - 8);
      if (xs && q.y + q.h <= scr.y) m = Math.min(m, scr.y - q.y - q.h - 8);
      if (ys && q.x >= scr.x + scr.w) m = Math.min(m, q.x - scr.x - scr.w - 8);
      if (ys && q.x + q.w <= scr.x) m = Math.min(m, scr.x - q.x - q.w - 8);
    }
    return Math.max(0, m);
  }
  const grow = (r, t, rgt, b, l) => ({ x: r.x - l, y: r.y - t, w: r.w + l + rgt, h: r.h + t + b });
  // The keys, each at its stage position and size. Game Boy has no shoulders and a diagonal B/A.
  function stageKeys(s, o = {}) {
    const face = o.gb
      ? `<div class="sg-face" style="${at(disc(s.face))};--k:${Math.round(s.size * 0.41)}px;--d:${Math.round(s.size * 0.13)}px"><div class="ab" role="group" aria-label="Face buttons">${fk('B')}${fk('A')}</div></div>`
      : `<div class="sg-face" style="${at(disc(s.face))};--k:${Math.floor(s.size / 3) - 1}px">${diamond()}</div>`;
    return `<div class="sg-ctl" role="group" aria-label="Controls">
      ${o.gb ? '' : `<div class="sg-sh" style="${at(s.l)}">${wk('L', 'sh')}</div><div class="sg-sh" style="${at(s.r)}">${wk('R', 'sh')}</div>`}
      <div class="sg-pad" style="${at(disc(s.dpad))}">${dpad()}</div>
      ${face}
      <div class="sg-tert" style="${at(band(s.tertiary))}">${wk('Select')}${menuK()}${wk('Start')}</div>
    </div>`;
  }
  const stageToast = (s) => `<div class="toast sg-toast" role="status" style="left:${Math.round(s.toast.cx)}px;top:${Math.round(s.toast.cy)}px">${led('ok')}<span>Automatic resume updated</span></div>`;

  // Landscape (SNES): the game centred between the rails, its bezel's printed foot beneath it, the
  // session readouts in the free band above Select · Menu · Start.
  function stageLandscape(c, o = {}) {
    const g = k.game(o.game || 'smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    const G = stageCtx(c, o);
    const s = k.stage(G, { aspect: k.aspect(g.id) });
    const sm = G.w < 900;
    const gm = s.game;
    const e = lip(gm, [s.l, s.r, disc(s.dpad), disc(s.face)]);
    const bz = grow(gm, e, e, sm ? 22 : 24, e);
    const RH = sm ? 38 : 46;
    const top = bz.y + bz.h;
    const room = s.tertiary.cy - s.tertiary.h / 2 - top;
    const ro =
      room >= RH + 12
        ? `<div class="pl-ro sg-ro" style="${at({ x: gm.x, y: Math.round(top + (room - RH) / 2), w: gm.w, h: RH })}"><span>${lbl('SESSION')}<b>${pr.session} MIN</b></span><span>${lbl('RESUME')}${st(pr.resume.remote)}</span><span>${lbl('GAME-SAVE')}${st(pr.gameSave.remote)}</span></div>`
        : '';
    return `<div class="pl pl-stage${o.paused ? ' paused' : ''}${sm ? ' sm' : ''}">
      <div class="sg-bz foot" style="${at(bz)};--lip:${e}px"><div class="bz-s"><span>${o.paused ? `${i('pause', 10)} PAUSED · ` : ''}${U(p.short)} · ${U(p.core)}</span><span>${U(g.title)}</span></div></div>
      <div class="sg-g" style="${at(gm)}"><img src="${k.frame(g.id, o.variant || 'b')}" alt="${esc(g.title)} gameplay"></div>
      ${ro}
      ${stageKeys(s)}
      ${o.toast ? stageToast(s) : ''}
    </div>`;
  }

  // Portrait (GBC): the screen across the top (and the fold, when open) with its strip above it; the
  // keys low in the band beneath, the wordmark printed between the clusters.
  function stagePortrait(c) {
    const g = k.game('linksawak');
    const p = k.platformOf(g);
    const m = newestFor('linksawak');
    const s = k.stage(c, { aspect: k.aspect(g.id) });
    const sm = c.w < 600;
    const gm = s.game;
    const e = lip(gm, [disc(s.dpad), disc(s.face), band(s.tertiary)], 7);
    const bz = grow(gm, sm ? 22 : 24, e, e, e);
    return `<div class="pl pp-stage${sm ? ' sm' : ''}">
      <div class="sg-bz head" style="${at(bz)};--lip:${e}px"><div class="bz-s"><span>${U(p.short)} · ${U(p.core)}${sm ? '' : ` · ${U(g.title)}`}</span><span>${led(m.remote)}RESUME ${U(k.syncLabel(m.remote))}</span></div></div>
      <div class="sg-g" style="${at(gm)}"><img src="${k.frame(g.id, 'b')}" alt="${esc(g.title)} gameplay"></div>
      ${stageKeys(s, { gb: true })}
      <div class="sg-badge" style="left:${Math.round(c.w / 2)}px;top:${Math.round(s.dpad.cy)}px">${wordmark('wm-body')}<small>${U(p.name)}</small></div>
      ${stageToast(s)}
    </div>`;
  }

  // DS: two recessed screens on a hinge (on the fold when open); TOUCH SCREEN is printed on the body
  // under the lower one, and the keys sit in the band at the bottom.
  function stageDual(c) {
    const g = k.game('pkblack');
    const p = k.platformOf(g);
    const d = k.dual(g.id, 'a');
    const s = k.stage(c, { dual: true });
    const sm = c.w < 600;
    const keys = [s.l, s.r, disc(s.dpad), disc(s.face), band(s.tertiary)];
    const et = lip(s.top, keys);
    const eb = lip(s.touch, keys);
    const gap = s.touch.y - s.top.y - s.top.h;
    const hl = Math.max(0, Math.min(6, Math.floor((gap - 2) / 2)));
    const bzTop = grow(s.top, sm ? 22 : 24, et, hl, et);
    const bzTouch = grow(s.touch, Math.min(hl, eb), eb, eb, eb);
    const seam = Math.round(s.top.y + s.top.h + gap / 2);
    return `<div class="pl du-stage${sm ? ' sm' : ''}">
      <div class="sg-bz head" style="${at(bzTop)};--lip:${Math.min(et, hl)}px"><div class="bz-s"><span>${U(p.short)} · ${U(p.core)}</span><span>${U(g.title)}</span></div></div>
      <div class="sg-g" style="${at(s.top)}"><img src="${d.top}" alt="${esc(g.title)} top screen"></div>
      ${gap >= 12 ? `<div class="hinge sg-hinge" aria-hidden="true" style="left:${s.touch.x}px;top:${seam - 6}px;width:${s.touch.w}px"><i></i><i></i></div>` : ''}
      <div class="sg-bz" style="${at(bzTouch)};--lip:${eb}px"></div>
      <div class="sg-g touch" style="${at(s.touch)}"><img src="${d.bottom}" alt="${esc(g.title)} touch screen"><span class="tap" aria-hidden="true"></span></div>
      <div class="sg-tl" style="left:${Math.round(s.touch.x + s.touch.w / 2)}px;top:${bzTouch.y + bzTouch.h + 6}px">${i('pointer', 10)}TOUCH SCREEN${sm ? '' : ' · STYLUS · FINGER'}</div>
      ${stageKeys(s)}
      ${stageToast(s)}
    </div>`;
  }

  screens.player = {
    render: (c) => root(c, 'play', landscapeBody(c, { toast: true })),
  };

  function portraitBody(c) {
    if (c.device.duo) return stagePortrait(c);
    const g = k.game('linksawak');
    const p = k.platformOf(g);
    const m = newestFor('linksawak');
    const top = c.safe.top + 8;
    const gw0 = Math.min(c.w - 28 - 12, 480);
    const gh = Math.round(gw0 / k.aspect(g.id));
    const gw = Math.round(gh * k.aspect(g.id));
    return `<div class="pp" style="--gw:${gw}px;--gh:${gh}px;--top:${top}px">
      <div class="pl-scr">
        <div class="bz-s"><span>${U(p.short)} · ${U(p.core)}</span><span>${led(m.remote)}RESUME ${U(k.syncLabel(m.remote))}</span></div>
        <div class="pl-g"><img src="${k.frame(g.id, 'b')}" alt="${esc(g.title)} gameplay"></div>
      </div>
      <div class="pp-ctl">
        <div class="pp-band">${wordmark('wm-body')}${menuK()}</div>
        <div class="pp-dpad">${dpad()}</div>
        <div class="pp-ab">${fk('B')}${fk('A')}</div>
        <div class="pp-ss">${wk('Select')}${wk('Start')}</div>
      </div>
    </div>`;
  }
  screens['player-portrait'] = { render: (c) => root(c, 'play', portraitBody(c)) };

  function dualBody(c) {
    if (c.device.duo) return stageDual(c);
    const g = k.game('pkblack');
    const p = k.platformOf(g);
    const d = k.dual(g.id, 'a');
    const sw = 300;
    return `<div class="du" style="--sw:${sw}px;--bw:${sw}px">
      <div class="du-scr top">
        <div class="bz-s"><span>${U(p.short)} · ${U(p.core)}</span><span>${U(g.title)}</span></div>
        <div class="du-g"><img src="${d.top}" alt="${esc(g.title)} top screen"></div>
      </div>
      <div class="hinge" aria-hidden="true"><i></i><i></i></div>
      <div class="du-scr bot">
        <div class="du-g touch"><img src="${d.bottom}" alt="${esc(g.title)} touch screen"><span class="tap" aria-hidden="true"></span></div>
        <div class="bz-s bz-f"><span>${i('pointer', 10)} TOUCH SCREEN</span><span>STYLUS · FINGER</span></div>
      </div>
      <div class="du-ctl">
        <div class="du-l">${wk('L', 'sh')}${dpad()}${wk('Select')}</div>
        <div class="du-r">${wk('R', 'sh')}${diamond()}${wk('Start')}</div>
        <div class="du-m">${menuK()}</div>
      </div>
    </div>`;
  }
  screens.dual = { render: (c) => root(c, 'play', dualBody(c)) };

  // ---------- sheets over the game ----------
  // The paused game, whole: on Duo open the sheet floats over the full-screen player (box is the
  // whole display), so the backdrop is the same body as the running game.
  function pausedBackdrop(c) {
    return root(c, 'play', landscapeBody(c, { paused: true }));
  }
  function pauseBody(c) {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    const s3 = pr.slots[2];
    const open = isOpen(c);
    return `<div class="in pz ${open ? 'pz-open' : ''}">
      <div class="pz-ro">
        ${ro(pr.session, 'MIN', 'Session')}
        ${ro(hrs(g.hours), '', 'Played')}
        ${ro(U(p.core), '', 'Core')}
      </div>
      <div class="pz-st">${st(pr.gameSave.remote, 'Game-save data synced')}${st(pr.resume.remote, 'Resume waiting to sync')}</div>
      <div class="pz-k">
        ${key('Save state', { icon: 'save', block: true })}
        ${key('Load state', { icon: 'layers', block: true, sub: 'SLOT 3 · 42M' })}
        ${key('Controls', { icon: 'gamepad', block: true })}
        <button class="latch ff" role="switch" aria-checked="false" aria-label="Fast-forward"><i class="latch-led"></i>${i('fast-forward', 17)}<span class="latch-m"><b>Fast-forward</b></span></button>
        ${key('Reset', { icon: 'restart', block: true })}
        ${key('Leave game', { icon: 'exit', block: true, kind: 'quiet' })}
      </div>
      ${
        open
          ? `<div class="gp-h">${lbl('SLOT BANK')}<span class="rule"></span>${lbl(`LAST · SLOT ${s3.n}`)}</div>${slotBank(c, { cls: 'slots-4' })}
        <div class="gp-h">${lbl('RECOVERY HISTORY')}<span class="rule"></span>${lbl(`${pr.history.length}`)}</div>
        <ol class="log hist">${pr.history
          .slice(0, 2)
          .map((h) => `<li class="log-l"><time>${clock(h.at)}</time>${led('off')}<span class="log-m"><b>${esc(h.kind)}</b><small>${esc(h.device === 'This iPhone' ? `This ${phone(c)}` : h.device)} · ${esc(h.reason)}</small></span></li>`)
          .join('')}</ol>`
          : ''
      }
    </div>`;
  }
  screens.pause = (c) =>
    c.sys.sheet({
      detent: 'medium',
      title: 'Paused',
      done: { label: 'Resume', icon: 'play' },
      backdrop: () => pausedBackdrop(c),
      body: pauseBody(c),
    });

  function statesBody(c) {
    const pr = D().progress;
    const g = k.game(pr.game);
    const open = isOpen(c);
    return `<div class="in sts ${open ? 'sts-open' : ''}">
      <div class="gp-pair">
        <div class="gp-i">${thumb(g.id, pr.resume.frame, 'gp-scr')}<span class="gp-m">${lbl('AUTOMATIC RESUME')}<b>${esc(k.ago(pr.resume.at))}</b>${st(pr.resume.remote)}</span></div>
        <div class="gp-i">${`<span class="thumb gp-scr cart">${i('cartridge', 20)}</span>`}<span class="gp-m">${lbl('GAME-SAVE DATA')}<b>${esc(k.ago(pr.gameSave.at))}</b>${st(pr.gameSave.remote)}</span></div>
      </div>
      <div class="gp-h">${lbl('SAVE STATES')}<span class="rule"></span>${lbl('3 OF 4 SLOTS')}</div>
      ${slotBank(c, { cls: 'slots-4' })}
      <div class="gp-h">${lbl('RECOVERY HISTORY')}<span class="rule"></span></div>
      <div class="segwrap sm">${c.sys.segmented(['All', 'Save states', 'Game-save data'], 0)}</div>
      <ol class="log hist">${pr.history
        .map(
          (h) => `<li class="log-l"><time>${clock(h.at)}</time>${led('off')}<span class="log-m"><b>${esc(h.kind)}</b><small>${esc(h.device === 'This iPhone' ? `This ${phone(c)}` : h.device)} · ${esc(h.reason)}</small></span><button class="tk ghost" aria-label="Restore ${esc(h.kind)}">${i('rotate-ccw', 15)}</button></li>`,
        )
        .join('')}</ol>
    </div>`;
  }
  screens.states = (c) =>
    c.sys.sheet({
      detent: isOpen(c) ? 'pane' : 'large',
      title: 'Save states',
      done: { label: 'Save state', icon: 'save' },
      backdrop: () => pausedBackdrop(c),
      body: statesBody(c),
    });

  function conflictBody(c) {
    const cf = D().conflict;
    const g = k.game(cf.game);
    const rootS = D().sessions.find((s) => s.game === cf.game && s.day > 0);
    const compact = isOpen(c) || c.h < 700;
    const arm = (b, on) => `<button class="cf-b ${on ? 'on' : ''}" role="radio" aria-checked="${on}">
        <span class="cf-bh"><span>${i('smartphone', 15)}<b>${esc(b.device === 'This iPhone' ? `This ${phone(c)}` : b.device)}</b></span><i class="latch-led"></i></span>
        ${bezel(k.frame(g.id, b.frame), { ar: k.aspect(g.id), cls: 'cf-scr', strip: `<span>${U(b.where)}</span><span>${U(k.agoShort(b.at))}</span>` })}
        <dl class="kv"><div><dt>Saved</dt><dd>${esc(k.ago(b.at))}</dd></div><div><dt>Place</dt><dd>${esc(b.where)}</dd></div><div><dt>Playtime</dt><dd>${esc(b.playtime)}</dd></div>${compact ? '' : `<div><dt>Reason</dt><dd>${esc(b.reason)}</dd></div>`}</dl>
      </button>`;
    return `<div class="in cf ${compact ? 'compact' : ''}">
      <div class="cf-h">${led('conflict')}${lbl(`CONFLICT · ${U(cf.kind)}`)}</div>
      <h2 class="cf-t">${esc(g.title)}</h2>
      <p class="cf-p">Two devices played on from the same resume point. Choose the one to continue; the other stays as a recovery copy.</p>
      <div class="cf-tree" aria-hidden="true">
        <span class="cf-root"><i class="br-node"></i>${lbl(`LAST SHARED · ${U(rootS.note)} · ${rootS.day} D AGO`)}</span>
        <svg viewBox="0 0 200 28" preserveAspectRatio="none"><path d="M100 0 V8 C100 16 50 12 50 28 M100 8 C100 16 150 12 150 28"/></svg>
      </div>
      <div class="cf-arms" role="radiogroup" aria-label="Resume point">${arm(cf.branches[0], true)}${arm(cf.branches[1], false)}</div>
      <div class="srow cf-keep"><span class="srow-m"><b>Keep the other as a recovery copy</b><small>Find it later in Recovery history</small></span>${c.sys.toggle(true, 'Keep the other as a recovery copy')}</div>
      <div class="cf-k">${key(`Continue from This ${phone(c)}`, { kind: 'primary', size: 'lg', block: true, icon: 'play' })}${key('Cancel', { kind: 'quiet', block: true })}</div>
    </div>`;
  }
  screens.conflict = (c) =>
    c.sys.sheet({
      detent: isOpen(c) ? 'pane' : 'large',
      title: 'Resume conflict',
      // The whole Console stays behind the sheet (on Duo open it spans the display; the inspector floats).
      backdrop: () => screens.home(c),
      body: conflictBody(c),
    });

  // ------------------------------------------------------------------ register
  Studio.defineVersion({
    id: 'instrument',
    name: 'Instrument',
    tagline: 'A control surface for your library.',
    thesis:
      'Rommio for the person who runs their own server: an operational tool with the precision of pro hardware. It is dense, tabular and honest about state — what is installed where, what is synced, what is queued, what failed — so information arrives as readouts, tables, lanes, meters and logs rather than cards and shelves. The home is a console of modules, the library is a real spreadsheet, and the signature surface is the device fleet: lanes that show which device holds the newest progress, and where it diverged.',
    swatch: ['#E6E4DF', '#1C1B19', '#CF4A10'],
    fonts: ['Archivo:wdth,wght@62..125,100..900', 'JetBrains+Mono:wght@400..700'],
    tabs: [
      { id: 'console', label: 'Console', icon: 'gauge' },
      { id: 'library', label: 'Library', icon: 'table' },
      { id: 'devices', label: 'Devices', icon: 'devices', badge: '1' },
      { id: 'transfers', label: 'Transfers', icon: 'sort', badge: '1' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },
    ],
    palette: [
      { name: 'Chassis · light', hex: '#E6E4DF' },
      { name: 'Panel · light', hex: '#F3F2EE' },
      { name: 'Ink · light', hex: '#151515' },
      { name: 'Ink 2 · light', hex: '#57544E' },
      { name: 'Chassis · dark', hex: '#1C1B19' },
      { name: 'Panel · dark', hex: '#262522' },
      { name: 'Ink · dark', hex: '#ECEAE5' },
      { name: 'Ink 2 · dark', hex: '#A9A59D' },
      { name: 'Signal', hex: '#CF4A10' },
      { name: 'Bezel', hex: '#0E0E0D' },
      { name: 'LED · Synced', hex: '#13A150' },
      { name: 'LED · Waiting to sync', hex: '#D98F00' },
      { name: 'LED · Syncing', hex: '#2F6FE8' },
      { name: 'LED · Conflict', hex: '#E0362B' },
    ],
    type: [
      { role: 'Display · Archivo Expanded 700', family: 'Archivo', css: "'Archivo'; font-stretch: 122%; font-weight: 700", sample: 'Super Metroid' },
      { role: 'UI & tables · Archivo 500–600', family: 'Archivo', css: "'Archivo'", sample: 'Start with game save' },
      { role: 'Labels, readouts, queries · JetBrains Mono', family: 'JetBrains Mono', css: "'JetBrains Mono'", sample: 'platform:snes · 62% · 18.4 MB/s' },
    ],
    tweaks: [
      { var: '--signal', label: 'Signal colour', type: 'color', value: '#cf4a10' },
      { var: '--r-key', label: 'Key radius', type: 'range', min: 2, max: 16, value: 9, unit: 'px' },
      { var: '--row-h', label: 'Table row height', type: 'range', min: 44, max: 56, value: 44, unit: 'px' },
      { var: '--led-glow', label: 'LED halo', type: 'range', min: 0, max: 5, step: 0.5, value: 2.5, unit: 'px' },
      { var: '--ctl-opacity', label: 'Touch key opacity', type: 'range', min: 0.3, max: 1, step: 0.02, value: 0.72 },
    ],
    notes: {
      welcome: 'Power-on, not a poster: a self-test panel shows what is ready (cores for 20 systems) and what is not (no server). Three numbered capabilities and a plain statement that Rommio provides no games. Two keys, no navigation chrome.',
      connect: 'A native pushed step with its position switch (01 Server). The address is the input; the live check reads back directly beneath it as three LED lines, so errors appear exactly where the value is typed. Access method is a four-position latch bank.',
      signin: 'The verified server is a spec plate before any credential is asked for, with Change beside it. Sign in and single sign-on are equal-width keys so the alternative is never hidden.',
      hydrate: 'Caching as a checklist of readouts with one live meter (artwork 61%). A three-node chain explains how progress travels. Start playing is available before caching finishes.',
      home: 'The Console is a modular dashboard, not a feed. A status strip reports server and transfer state; the Resume module is a four-channel bank (channel 1 is Super Metroid in its recessed screen, one tap to Resume); the EarthBound conflict is an alert module; a matrix counts every save, resume and state by sync state; transfers, storage and the live log follow. Duo open is one dashboard: the strip, a full-width Resume hero (channel 1 beside channels 2–4) and a wide conflict strip span the display, then a 12-column grid whose modules straddle the crease (sync 5 · transfers 3 · storage 4, log 8 · fleet 4).',
      library: 'A real table. Support tiers are latching filter keys with counts (Browse only says why: no player yet); Installed is its own latch. Columns are sortable, platforms are table sections, and sync is an LED column with a legend. Duo open is one spreadsheet running across the fold with extra columns (year, sync in words, a subtitle per row) and a narrow inspector drawer docked at the trailing edge for the selected game.',
      platform: 'One slice in depth: a readout strip (games, installed, core, tier), a coverage map of all 241 games (lit = on this device, bright = has progress), then a segmented filter (All · Installed · Not downloaded) over one sortable table where a filled mark means on this device. On Duo open a narrow platform index (grouped by tier, core under each name) sits beside the wide slice on the same chassis; the slice gains a sync column and runs across the fold.',
      collections: 'Collections are saved filters: every one prints its query (list:, added:, genre:, year:) so you can see why a game is in it, plus a one-segment-per-game meter of what is on this device. Manual lists first, then smart, then automatic.',
      game: 'A spec sheet. The header is the resume frame in its recessed screen beside the title block; a segmented control (Overview · Progress · Files · Core) pages the sheet. Every line pairs a fact with its state. Resume is the prominent toolbar action. Duo open is one composition: the resume screen and a large title block (readouts and keys beneath it) form a header across the fold; the spec sheet (5 columns) and the progress bank with all four slots (7 columns, across the crease) share the row beneath.',
      downloads: 'Transfers unify downloads and the sync queue as one console: the active channel with a big percentage and meter, a numbered download queue with the failure and Retry in line, the upload queue of resume points and states, storage, and a completed log. Duo open: the active channel spans the display with its readouts in one row and a 72-segment meter across the fold; queue · sync queue and storage · completed follow with spans that shift row to row.',
      search: 'Typed operators instead of vague suggestions: each suggestion is a key that inserts an operator (installed:iphone, platform:snes). The parsed query and ranking rule are printed; each result says why it matched and whether it plays now. Duo open is one canvas: all five operator keys and the parsed query run the full width; ranked results (across the fold) sit beside the operator reference and recent searches.',
      signature: 'Devices is the signature: the fleet as vertical lanes (This iPhone, Pixel 9, iPad) with last sync, pending operations, installed count and storage as readouts, then a matrix of which device holds the newest progress for each game. EarthBound is drawn as one line branching into two lanes, repeated as a branch module with both frames, a 72-hour session trace per device and, on Duo open, the live activity log. On Duo open the lanes run the full width of the display, the middle lane on the crease.',
      settings: 'A system panel: the server plate with readouts, profiles as a two-position latch, sync with native switches, a stacked storage meter with its legend, and cores that need attention listed first in a table. Duo open lays the modules on one 12-column grid (server 8 · profiles 4, sync 5 · storage 7, cores 7 · entries 5) so no seam sits on the fold.',
      controls: 'Tuning on a control surface: the real player rendered as a live preview, three faders with steppers (opacity 72%, size 100%, deadzone 12%), and behaviour as latching keys with LEDs. On Duo the preview is this device’s own player layout (game between the rails, keys low beside it). Duo open: that player fills the canvas and the faders and behaviour keys float over the middle of the game, so changes show on the keys in the rails either side.',
      player: 'The phone becomes the device. The game sits in a recessed screen printed with system, core and title; flat keycaps on the body: cross d-pad, square face keys with corner legends (A is the Signal key), wide shoulder and Select/Start keys. iPhone Duo (open and closed): the game is centred between two side rails (across the fold when open) and nothing touches it; the d-pad and face keys sit low in the rails with L/R just above them, Select · Menu · Start under the game, and the session, resume and game-save readouts in the band between the bezel’s printed foot and those keys. The resume notice appears above the game.',
      'player-portrait': 'Game Boy proportions: recessed screen on top (its bezel reports that the resume is syncing), wordmark and Menu in the band, d-pad and diagonal B/A below, Select/Start centred. iPhone Duo (open and closed): the screen takes the full width at the top (across the fold when open), its printed strip above it and clear of the camera; the keys sit low in the band beneath — d-pad and diagonal B/A with the wordmark printed between them, Select · Menu · Start at the foot — and the resume notice in the gap under the screen.',
      pause: 'A native sheet carrying an instrument panel: session readouts, two sync LEDs with truthful wording, and a key grid where Fast-forward is a latching key and Leave game is always in view. Resume is the sheet’s prominent button. On Duo open it is a floating inspector over the whole paused player (game and keys stay full-screen behind it) and adds the slot bank and the latest recovery history.',
      states: 'Automatic resume and game-save data are their own items; slots 01–04 read like a sampler bank with frames, device, age and sync LED; recovery history is a log filtered by a segmented control so both histories are reachable. On Duo open it floats as an inspector over the full-screen paused game.',
      conflict: 'The fork is drawn: last shared point, then two matching branch columns with the same readouts (saved, place, playtime, reason). Keep the other as a recovery copy is on by default; Continue and Cancel are full-width keys. On Duo open it floats as an inspector over the whole Console.',
      dual: 'Two recessed screens on a hinge; the lower bezel is printed TOUCH SCREEN. iPhone Duo (open and closed): the top screen sits above the touch screen (above the fold when open, the hinge on the crease); both stay whole and uncovered, so the stylus area is clear. TOUCH SCREEN is printed on the body beneath it; the keys sit in the band at the bottom — L/R above the d-pad and face keys, Select · Menu · Start between them.',
    },
    screens,
  });
})();
