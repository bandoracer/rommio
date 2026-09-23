/*
 * RISO (round 2) — "Your library, edited."
 *
 * DESIGN PLAN
 * ---------------------------------------------------------------------------
 * Thesis    A library of two thousand games needs an editor, not more grids.
 *           Rommio edits your own collection like a small independent
 *           magazine: a weekly Issue made from what you actually play, a
 *           back-of-book Index for everything else, and a Journal that keeps
 *           the record of every session. Editorial hierarchy — lead story,
 *           editor's note, columns, index entries, captions, colophon —
 *           replaces app-standard cards, shelves and dashboards.
 *
 * IA        Tabs (native): Issue · Index · Journal · Search.
 *           Issue    front page. Lead = resume Super Metroid (one tap). The
 *                    EarthBound conflict is a flagged Editor's note that
 *                    opens the decision sheet. Briefs = other games in
 *                    progress. Column = "Finish in 2026". Ears under the
 *                    nameplate = print queue + offline state. Toolbar: Print
 *                    queue (downloads) and the account avatar (colophon).
 *           Index    Contents: platforms are numbered chapters in three Parts
 *                    (Touch · Controller · Browse only) set chronologically
 *                    with decade dividers and dot leaders. Segmented:
 *                    Chapters · Series · A–Z. A chapter (platform) opens as a
 *                    chapter opener + a true alphabetical index (articles
 *                    inverted, bold = on this iPhone). Collections are Series.
 *                    Game = a feature article.
 *           Journal  the signature: D.sessions as diary entries by day with
 *                    printed thumbnails and spec-worded outcomes, plus the
 *                    month in ink (D.stats) and where you played (D.fleet).
 *           Search   an index lookup: entries, chapters, series, journal
 *                    mentions, "see also" suggestions and recent lookups.
 *           Pushed   Print queue (downloads) and Colophon (settings) from the
 *                    Issue toolbar; Controls from the Colophon.
 *           Sheets   Pause = a manual page; Save states = a contact sheet;
 *                    Conflict = an editor's decision between two proofs.
 *
 * Palette   LIGHT — bright paper, fluorescent inks
 *             Paper #FBFAF6 · Ink #1B1B1B · Muted #5E5D5A (6.3:1)
 *             Fluorescent Pink #FF48B0 (fills, plates; never small text)
 *             Riso Blue #0078BF (tint, fills, large type; 4.5:1 on paper)
 *             Yellow #FFE800 (fills only) · Green #00A95C (fills only)
 *             Overprint pink × blue #002183
 *           DARK — riso on black stock, inks glow
 *             Black stock #121211 · Paper-white ink #F2F0EA (16:1)
 *             Muted #A5A29B (7.4:1) · Pink #FF48B0 · Aqua #5EC8E5 (blue plate)
 *             Yellow #FFE800 · Fluorescent green #44D62C
 *             Plates screen (add light) instead of multiply.
 *           Tint (native chrome): Riso Blue #0078BF in both (white label 4.7:1).
 *
 * Type      Display  Big Shoulders Display 800–900, uppercase: headlines,
 *                    nameplate, numerals.
 *           Text     Newsreader: large titles (--sys-title-font), decks,
 *                    body, captions, index entries.
 *           Data     IBM Plex Mono: datelines, kickers, folios, sizes, times,
 *                    sync stamps.
 *
 * Components  nameplate + dateline + ears · lead story · editor's note ·
 *           briefs · numbered column · chapter row with dot leaders · index
 *           entry · chapter opener · feature opener · "where you left off"
 *           box · numbered sidebar · spec box · diary entry · month in ink ·
 *           sync stamp · tier stamp · rz() two-plate printed cover · snap()
 *           clean frame (game frames are never inked) · ink button.
 *
 * Duo       Closed: the same single column, full width beside the vertical
 *           bar (nameplate rises to the top edge). Open: one broadsheet, not
 *           a spread. A 12-column editorial grid runs across the whole inner
 *           display and the fold is only a crease: nameplates, headlines,
 *           art and the lead story cross it; indexes and the diary run in
 *           three flowing columns; sidebars sit in an off-centre rail (the
 *           last third), never on the fold. Only Chapter keeps a list →
 *           detail split: a narrow Contents list beside the wide chapter.
 *           Controls floats its settings over the real player preview.
 *           Sheets float as an inspector over the full-screen backdrop.
 *
 * Player    The frame is always clean. Controls are flat fluorescent ink
 *           shapes on OLED-black stock with a 2px off-register plate, and
 *           they never print over a game screen, so they stay at full ink.
 *           On the Duo (open and closed) every player takes its geometry
 *           from k.stage(): the game steps in to leave rails (landscape) or
 *           a band (portrait, DS) for the controls, the clusters sit low
 *           where thumbs rest, Select · Menu · Start sit clear of the game,
 *           and the notice has its own free spot. Pause and Save states are
 *           native sheets styled as a manual page and a contact sheet, over
 *           the paused frame drawn exactly where the player draws it.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const A = window.StudioArt;
  const D = () => window.StudioData;

  const LIB = k.totals();
  const n = (x) => k.num(x);
  const plat = (g) => k.platformOf(g);
  const game = (id) => k.game(id);

  // Paper grain for the page stock, generated once.
  try {
    const st = document.createElement('style');
    st.textContent = `.v-riso{--grain:url(${A.grain(0.06, 180)})}`;
    document.head.appendChild(st);
  } catch (e) {
    /* grain is decoration only */
  }

  // ---------------------------------------------------------------------------
  // dates: the studio's "now" is 9:41 on Wednesday 23 September 2026 (ISO week 39)
  // ---------------------------------------------------------------------------
  const WD = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const MO = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const dayOf = (d) => {
    const x = new Date(2026, 8, 23 - d);
    return { wd: WD[x.getDay()], d: x.getDate(), mo: MO[x.getMonth()] };
  };
  const dayName = (d) => (d === 0 ? 'Today' : d === 1 ? 'Yesterday' : dayOf(d).wd);
  const dayLong = (d) => {
    const x = dayOf(d);
    return `${x.wd} ${x.d} ${x.mo}`;
  };
  const dayShort = (d) => {
    const x = dayOf(d);
    return `${x.wd.slice(0, 3)} ${x.d} ${x.mo.slice(0, 3)}`;
  };
  const ISSUE = { no: 39, dateline: 'Wednesday 23 September 2026' };
  const mins = (m) => (m < 60 ? `${m} min` : `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ''}`);
  const hm = (m) => `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} m`;
  const clock = (t) => (t.length === 4 ? '0' + t : t);

  // ---------------------------------------------------------------------------
  // components
  // ---------------------------------------------------------------------------

  // The printed cover: two plates of the same art screened into inks and
  // overprinted (multiply on paper, screen on black stock). proof → one plate.
  const rz = (id, kind = 'box', o = {}) => {
    const u = k.cover(id, kind);
    const cls = ['rz', `rz-${kind}`, o.proof ? 'rz-proof' : '', o.cls || ''].join(' ').trim();
    const label = o.alt ? ` role="img" aria-label="${esc(o.alt)}"` : ' aria-hidden="true"';
    return `<span class="${cls}"${o.style ? ` style="${o.style}"` : ''}${label}><span class="rz-p rz-a"><span class="rz-s"><img src="${u}" alt=""></span></span><span class="rz-p rz-b"><span class="rz-s"><img src="${u}" alt=""></span></span></span>`;
  };

  // A clean game frame. Never inked, never cropped away from its aspect.
  const snap = (id, variant, o = {}) =>
    `<span class="snap ${o.cls || ''}" style="--ar:${o.ar || k.aspect(id)}"><img src="${k.frame(id, variant)}" alt="${esc(o.alt || '')}"${o.alt ? '' : ' aria-hidden="true"'}></span>`;

  // Sync stamp — wording from save-sync-spec §11 via k.syncLabel.
  const SYNC = { synced: 'ok', pending: 'wait', waiting: 'wait', syncing: 'live', conflict: 'bad', local: 'local', signin: 'bad', none: 'none', failed: 'bad' };
  const stamp = (state, o = {}) => {
    const t = SYNC[state] || 'none';
    const glyph = t === 'live' ? i('sync', 11) : t === 'bad' ? i('alert', 11) : '<i class="chip"></i>';
    return `<span class="stamp st-${t}${o.cls ? ' ' + o.cls : ''}">${glyph}<span>${esc(o.label || k.syncLabel(state))}</span></span>`;
  };

  // Support tiers stay distinct: Touch · Controller · Browse only.
  const TIER_ICON = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tier = (t, o = {}) => `<span class="tier tier-${t}${o.cls ? ' ' + o.cls : ''}">${i(TIER_ICON[t], 12)}<span>${esc(k.tierLabel(t))}</span></span>`;

  // Ink buttons (content actions; navigation actions stay in c.sys chrome).
  const btn = (label, o = {}) =>
    `<button class="rb ${o.kind || 'ink'}${o.full ? ' full' : ''}${o.size ? ' ' + o.size : ''}${o.cls ? ' ' + o.cls : ''}"${o.aria ? ` aria-label="${esc(o.aria)}"` : ''}>${o.icon ? i(o.icon, o.isz || 18) : ''}<span>${esc(label)}</span>${o.trail ? i(o.trail, 18) : ''}</button>`;

  // Misregistered headline: an ink plate copy multiplied off register.
  const hed = (tag, text, cls = '') => `<${tag} class="mr ${cls}" data-t="${esc(text)}">${esc(text)}</${tag}>`;

  const kick = (label, extra = '', cls = '') => `<p class="kick ${cls}"><span class="kick-l">${esc(label)}</span>${extra ? `<span class="kick-r">${extra}</span>` : ''}</p>`;

  const sec = (label, right = '', cls = '') => `<div class="sec ${cls}"><h3>${esc(label)}</h3><span class="sec-rule"></span>${right ? `<span class="sec-r">${right}</span>` : ''}</div>`;

  const bar = (pct, label, cls = '') =>
    `<span class="bar ${cls}" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(pct * 100)}" aria-label="${esc(label)}"><i style="width:${(pct * 100).toFixed(1)}%"></i></span>`;

  const toggle = (c, on, label) => c.sys.toggle(on, label);

  const check = (on, label) => `<span class="ck${on ? ' on' : ''}" role="checkbox" aria-checked="${on}" aria-label="${esc(label)}">${on ? i('check', { size: 16, stroke: 3 }) : ''}</span>`;

  // Where content should start under an iPhone glass bar (content scrolls
  // under it; Android bars are opaque, so content stays below them).
  const heroOK = (c) => c.os === 'ios';
  const regular = (c) => c.size === 'regular';

  // ---------------------------------------------------------------------------
  // derived data
  // ---------------------------------------------------------------------------
  const prof = () => D().profiles.find((p) => p.active);
  const OUTCOME = { resume: 'Automatic resume updated', state: 'Save state saved', save: 'Game-save data saved', quit: 'Left game · no resume point' };
  const sessionMoment = (s) => D().moments.find((m) => m.game === s.game && m.name === s.note && m.device === s.device);
  const sessionRemote = (s) => {
    if (s.ended === 'quit') return null;
    const m = sessionMoment(s);
    if (m) return m.remote;
    return game(s.game).sync === 'local' ? 'local' : 'synced';
  };
  const sessionOutcome = (s) => {
    const m = sessionMoment(s);
    if (s.ended === 'state' && m && m.slot) return `Save state saved · Slot ${m.slot}`;
    return OUTCOME[s.ended];
  };
  const byDay = () => {
    const out = [];
    for (const s of D().sessions) {
      let g = out.find((x) => x.day === s.day);
      if (!g) out.push((g = { day: s.day, list: [] }));
      g.list.push(s);
    }
    const t = (x) => {
      const [h, m] = x.start.split(':').map(Number);
      return h * 60 + m;
    };
    out.forEach((g) => g.list.sort((a, b) => t(b) - t(a)));
    return out;
  };
  const dl = () => D().downloads;
  const running = () => dl().find((d) => d.state === 'running');
  const pendingJobs = () => dl().filter((d) => d.state !== 'done').length;

  // ---------------------------------------------------------------------------
  // shared blocks
  // ---------------------------------------------------------------------------

  // Nameplate. On iPhone it shares the top row with the glass toolbar buttons.
  const nameplate = (c, o = {}) => {
    const bar = c.os === 'ios' && !c.device.vbar;
    return `<header class="np${bar ? ' np-bar' : ''}${o.cls ? ' ' + o.cls : ''}">
      <div class="np-row">
        ${hed('h1', 'Rommio', 'np-wm')}
        <p class="np-tag">Your library,<br>edited.</p>
      </div>
      <p class="np-date"><span>${ISSUE.dateline}</span><span>No. ${ISSUE.no}</span></p>
    </header>`;
  };

  // Ears: page furniture for the print queue and offline state.
  const earQueue = () => {
    const r = running();
    const g = game(r.game);
    const rest = dl().filter((d) => ['queued', 'waiting'].includes(d.state)).length;
    const failed = dl().filter((d) => d.state === 'failed').length;
    return `<button class="ear" aria-label="Print queue: ${esc(g.title)} ${k.pct(r.progress)}, ${rest} waiting, ${failed} failed">
        <span class="ear-k">${i('downloads', 13)}Print queue</span>
        <b>${esc(g.title)} · ${k.pct(r.progress)}</b>
        <small>${rest} waiting · ${failed} failed</small>
        ${bar(r.progress, 'Download progress', 'ear-bar')}
      </button>`;
  };
  const earOffline = () => {
    const p = prof();
    return `<button class="ear" aria-label="Offline: ${LIB.installed} games on this iPhone">
        <span class="ear-k">${i('hard-drive-download', 13)}Offline</span>
        <b>${LIB.installed} games ready</b>
        <small>Online · synced ${esc(k.ago(p.lastSync).replace(' ago', ''))} ago</small>
      </button>`;
  };
  const ears = () => `<div class="ears">${earQueue()}${earOffline()}</div>`;

  // Duo open: the broadsheet nameplate. The ears flank the wordmark, as on a
  // newspaper, and the dateline rule runs the full width across the fold.
  const nameplateWide = () => `<header class="np np-wide span-all">
      <div class="np-row">${earQueue()}${hed('h1', 'Rommio', 'np-wm')}${earOffline()}</div>
      <p class="np-date"><span>${ISSUE.dateline}</span><span class="np-motto">Your library, edited.</span><span>No. ${ISSUE.no}</span></p>
    </header>`;

  // Duo open: one broadsheet page on a 12-column grid (see riso.css, .bs).
  const pageHead = (title, sub, right = '') =>
    `<header class="bs-head span-all"><div class="bs-ht"><h1 class="pg-title">${esc(title)}</h1><p class="pg-sub">${sub}</p></div>${right ? `<div class="bs-hr">${right}</div>` : ''}</header>`;

  const leadStory = (c, o = {}) => {
    const g = game('smetroid');
    const pr = D().progress;
    const sess = D().sessions[0];
    return `<article class="lead${o.big ? ' big' : ''}" aria-label="Lead story">
      ${kick('The lead', `${mins(sess.minutes)} this morning · ${esc(sess.device)}`, 'pinkk')}
      ${hed('h2', g.title, 'lead-hed')}
      <div class="lead-body">
        <figure class="lead-fig">${snap(g.id, pr.resume.frame, { alt: 'Super Metroid, where you left off' })}
          <figcaption>${esc(sess.note)}. Automatic resume, ${esc(k.ago(pr.resume.at).toLowerCase())}.</figcaption></figure>
        <div class="lead-side">
          <p class="lead-dek">You left Samus in the ${esc(sess.note)}, ${mins(sess.minutes)} into this morning’s session.</p>
          <p class="lead-st"><span>Automatic resume</span>${stamp(pr.resume.remote)}</p>
          ${btn('Resume', { icon: 'play', full: true, cls: 'lead-btn', aria: 'Resume Super Metroid' })}
        </div>
      </div>
    </article>`;
  };

  const editorsNote = () => {
    const cf = D().conflict;
    const [a, b] = cf.branches;
    return `<aside class="note" aria-label="Editor's note: decision needed">
      <p class="note-k">${i('alert', 13)}<span>Editor’s note · Decision needed</span></p>
      <h3 class="note-hed">EarthBound was continued on two devices</h3>
      <p class="note-body"><b>${esc(a.device)}</b> reached ${esc(a.where)} ${esc(k.ago(a.at))}. <b>${esc(b.device)}</b> reached ${esc(b.where)} ${esc(k.ago(b.at))}. Choose which continues; the other is kept as a recovery copy.</p>
      <button class="note-go"><span>Choose which continues</span>${i('arrow-right', 18)}</button>
    </aside>`;
  };

  const briefs = (ids) =>
    `<div class="briefs">${ids
      .map((id) => {
        const g = game(id);
        const m = D().moments.find((x) => x.game === id && (x.kind === 'resume' || x.kind === 'state'));
        return `<article class="brief">
          ${snap(id, m.frame, { cls: 'brief-fig' })}
          <p class="brief-k">${esc(plat(g).short)} · ${esc(k.ago(m.at))}</p>
          <h4>${esc(g.title)}</h4>
          <p class="brief-d">${esc(m.name)}, on ${esc(m.device)}.</p>
          ${stamp(m.remote)}
        </article>`;
      })
      .join('')}</div>`;

  const finishColumn = () => {
    const col = k.collection('finish');
    const games = col.games.map(game);
    const begun = games.filter((g) => g.hours > 0);
    const hours = begun.reduce((s, g) => s + g.hours, 0);
    const r = running();
    const row = (g, idx) => {
      let v;
      if (g.hours) v = k.hours(g.hours);
      else if (r.game === g.id) v = `Downloading ${k.pct(r.progress)}`;
      else v = g.installed ? 'Not started' : 'Not downloaded';
      return `<li class="${g.hours ? 'on' : ''}"><span class="fin-n">${idx + 1}</span><span class="fin-t">${esc(g.title)}</span><span class="fin-v">${esc(v)}</span></li>`;
    };
    return `<section class="finish" aria-label="${esc(col.name)}">
      ${sec(col.name, `${begun.length} of ${games.length} begun`)}
      <p class="finish-dek">${esc(col.note)} ${k.hours(hours)} logged so far.</p>
      <ol class="fin">${games.map(row).join('')}</ol>
    </section>`;
  };

  const numbersBox = () => {
    const s = D().stats;
    const top = s.topPlatforms[0];
    return `<section class="numbers" aria-label="The month in numbers">
      <h3>The month in numbers</h3>
      <dl>
        <div><dt>Played</dt><dd>${hm(s.month.minutes)}</dd></div>
        <div><dt>Sessions</dt><dd>${s.month.sessions}</dd></div>
        <div><dt>Games</dt><dd>${s.month.games}</dd></div>
      </dl>
      <p>Most played: <b>${esc(k.platform(top.platform).name)}</b>, ${hm(top.minutes)}.</p>
    </section>`;
  };

  // ---------------------------------------------------------------------------
  // player components (dark stock)
  // ---------------------------------------------------------------------------
  const DPAD = 'M38 4h24a4 4 0 0 1 4 4v30h30a4 4 0 0 1 4 4v24a4 4 0 0 1-4 4H66v30a4 4 0 0 1-4 4H38a4 4 0 0 1-4-4V70H4a4 4 0 0 1-4-4V42a4 4 0 0 1 4-4h30V8a4 4 0 0 1 4-4z';
  const dpad = (size, style = '') =>
    `<div class="dp" style="--sz:${size}px;${style}" role="group" aria-label="D-pad">
      <svg viewBox="-2 0 104 104" aria-hidden="true">
        <path class="dp-b" d="${DPAD}" transform="translate(2.5 2.5)"/>
        <path class="dp-a" d="${DPAD}"/>
        <circle class="dp-pivot" cx="50" cy="54" r="11"/>
        <path class="dp-ar" d="M50 12l-7 10h14zM50 96l-7-10h14zM8 54l10-7v14zM92 54l-10-7v14z"/>
      </svg>
      <button class="dp-hit up" aria-label="Up"></button><button class="dp-hit dn" aria-label="Down"></button><button class="dp-hit lt" aria-label="Left"></button><button class="dp-hit rt" aria-label="Right"></button>
    </div>`;
  // SNES diamond: X top, Y left, A right, B bottom.
  const diamond = (size, gap, style = '', labels = ['Y', 'X', 'B', 'A']) => {
    const [y, x, b, a] = labels;
    return `<div class="fb" style="--b:${size}px;--g:${gap}px;${style}" role="group" aria-label="Face buttons">
      <button class="fbtn f-top" aria-label="${x} button">${x}</button>
      <button class="fbtn f-left" aria-label="${y} button">${y}</button>
      <button class="fbtn f-right" aria-label="${a} button">${a}</button>
      <button class="fbtn f-bot" aria-label="${b} button">${b}</button>
    </div>`;
  };
  const pill = (label, cls = '', style = '', aria = '') => `<button class="pill ${cls}"${style ? ` style="${style}"` : ''}${aria ? ` aria-label="${esc(aria)}"` : ''}>${label}</button>`;
  const menuBtn = (cls = '') => pill(i('menu', 18), 'menu ' + cls, '', 'Menu');
  // Landscape rails: L + d-pad + Menu/Select on the left, R + diamond + Start on
  // the right (iPhone and Android; the controls never touch the frame).
  const landRails = (o) =>
    `<div class="rail rail-l ctl">${pill('L', 'sh sh-l')}${dpad(o.dp)}<div class="tert">${menuBtn()}${pill('Select', 'ss')}</div></div>
      <div class="rail rail-r ctl">${pill('R', 'sh sh-r')}${diamond(o.fb, o.fg)}<div class="tert">${pill('Start', 'ss')}</div></div>`;

  // ---- iPhone Duo stage: every rect comes from k.stage(), nothing prints over a screen ----
  const at = (r) => `left:${r.x}px;top:${r.y}px;width:${r.w}px;height:${r.h}px`;
  const around = (p, w = p.size, h = p.size) => `left:${Math.round(p.cx - w / 2)}px;top:${Math.round(p.cy - h / 2)}px`;
  const toastAt = (p) => `<div class="toast" role="status" style="left:${Math.round(p.cx)}px;top:${Math.round(p.cy)}px">${i('check', { size: 14, stroke: 3 })}<span>Automatic resume updated</span></div>`;
  const dpadAt = (st) => dpad(st.size, around(st.dpad));
  // SNES / DS diamond sized to the cluster: button + two gaps = the stage size
  // (buttons never under 44pt).
  const diamondAt = (st) => {
    const b = Math.max(44, Math.round(st.size * 0.365));
    return diamond(b, (st.size - b) / 2, around(st.face));
  };
  const tertAt = (st) => `<div class="dtert" style="left:${Math.round(st.tertiary.cx)}px;top:${Math.round(st.tertiary.cy)}px">${pill('Select', 'ss')}${menuBtn()}${pill('Start', 'ss')}</div>`;
  const slug = (c, text) => `<p class="slug" aria-hidden="true" style="left:${c.safe.left}px">${esc(text)}</p>`;
  // Landscape SNES player on the Duo. `c` may be a stand-in context (the Controls preview).
  const duoLand = (c, o = {}) => {
    const g = game('smetroid');
    const p = plat(g);
    const st = k.stage(c, { aspect: k.aspect(g.id) });
    return `<div class="stage duo-st land-st dev-${c.device.id}">
      <div class="vp" style="${at(st.game)}"><img src="${k.frame(g.id, 'a')}" alt="Super Metroid, ${o.preview ? 'live preview' : 'in game'}"></div>
      ${o.preview ? '' : slug(c, `${p.name} · ${p.core}`)}
      <div class="ctl">
        ${pill('L', 'sh sh-l', at(st.l))}${pill('R', 'sh sh-r', at(st.r))}
        ${dpadAt(st)}${diamondAt(st)}
        ${tertAt(st)}
      </div>
      ${o.preview ? '' : toastAt(st.toast)}
    </div>`;
  };
  const DUO_LAND = (c) => {
    const geo = window.Studio.DEVICES[c.device.id].landscape;
    return { w: geo.w, h: geo.h, safe: geo.safe, device: c.device };
  };
  // The real Duo landscape player, scaled to `width` (Controls preview).
  const duoReplica = (c, width, style = '') => {
    const L = DUO_LAND(c);
    const s = width / L.w;
    return `<div class="rep" style="width:${Math.round(L.w * s)}px;height:${Math.round(L.h * s)}px;${style}">
      <div class="rep-in" style="width:${L.w}px;height:${L.h}px;transform:scale(${s.toFixed(4)})">${duoLand(L, { preview: true })}</div>
    </div>`;
  };

  // ---------------------------------------------------------------------------
  // screens
  // ---------------------------------------------------------------------------
  const screens = {};

  // ---------- Welcome: the cover of issue No. 1 ----------
  screens.welcome = (c) => {
    const plates = `<div class="wel-plates" aria-hidden="true">
      <span class="wel-sun"></span>
      ${['chrono', 'smetroid', 'linksawak'].map((id, x) => `<span class="wel-plate p${x}">${rz(id, 'box')}</span>`).join('')}
      <span class="wel-sticker"><b>No games<br>included</b><small>Bring your<br>own server</small></span>
    </div>`;
    const lines = `<ul class="wel-lines">
      <li><b>Browse</b><span>every game on your own RomM server, edited into an issue you can read.</span></li>
      <li><b>Play offline</b><span>in a built-in player, with touch controls or a controller.</span></li>
      <li><b>Pick up anywhere</b><span>game saves and resume points follow you between devices.</span></li>
    </ul>`;
    const cta = `<div class="wel-cta">
      ${btn('Connect to a server', { icon: 'server', full: true, trail: 'arrow-right' })}
      ${btn('Scan setup code', { kind: 'line', icon: 'qr', full: true })}
      <p class="fine">Rommio is a native client for RomM. It provides no games.</p>
    </div>`;
    const head = `<p class="wel-top"><span>Issue No. 1</span><span>A native client for RomM</span></p>
      ${hed('h1', 'Rommio', 'wel-mast')}
      <p class="wel-dek">Your library, edited.</p>`;
    if (regular(c)) {
      // One cover: the nameplate runs across the fold; the plates cross it too.
      return c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs wel-bs">
          <p class="wel-top span-all"><span>Issue No. 1</span><span>A native client for RomM</span></p>
          <div class="wel-np span-all">${hed('h1', 'Rommio', 'wel-mast')}<p class="wel-dek">Your library,<br>edited.</p></div>
          <div class="wel-art c1-7">${plates}</div>
          <div class="wel-copy c8-12"><p class="wel-in">In this issue</p>${lines}${cta}</div>
        </div>`,
      });
    }
    return c.sys.shell({
      tabBar: false,
      titleMode: 'none',
      heroUnderBar: heroOK(c),
      edge: false,
      body: `<div class="rp wel">${head}${plates}${lines}${cta}</div>`,
    });
  };

  // ---------- Connect ----------
  const step = (x, label) => `<p class="step"><span>Step ${x} of 3</span><span>${esc(label)}</span></p>`;
  screens.connect = (c) => {
    const [saved, found] = D().server.discovered;
    const p = prof();
    const methods = [
      ['Automatic', 'Detects what the server needs', true],
      ['Direct', 'Plain HTTPS to the server', false],
      ['Cloudflare Access', 'Sign in through Access first', false],
      ['Service token', 'Client ID and secret', false],
    ];
    const top = `${step(1, 'Server')}${hed('h1', 'Where does your library live?', 'ob-hed')}`;
    const form = `
      <p class="lede">Rommio reads from the RomM server you run. It checks the address as you type.</p>
      <div class="field ok">
        <label for="rz-server">Server address</label>
        <div class="field-box"><span class="pre">https://</span><input id="rz-server" value="${esc(p.url)}" autocomplete="url"><span class="field-ok" aria-hidden="true">${i('check', { size: 16, stroke: 3 })}</span></div>
        <p class="field-proof" role="status">${i('badge-check', 16)}<span><b>${esc(p.version)}</b> · protected by ${esc(p.access)}</span></p>
      </div>
      <fieldset class="access"><legend>Access method</legend>
        <div class="access-g" role="radiogroup" aria-label="Access method">${methods
          .map(([t, d, on]) => `<button class="acc${on ? ' on' : ''}" role="radio" aria-checked="${on}"><i class="radio"></i><b>${esc(t)}</b><small>${esc(d)}</small></button>`)
          .join('')}</div>
      </fieldset>
      ${btn('Connect', { full: true, trail: 'arrow-right' })}`;
    const nearby = `
      ${sec('Saved and nearby', `${D().server.discovered.length} servers`)}
      <ul class="srv">
        <li><span class="srv-i">${i('lock', 18)}</span><span class="srv-m"><b>${esc(saved.name)}</b><small>${esc(saved.note)}</small></span>${stamp('synced', { label: 'HTTPS' })}</li>
        <li><span class="srv-i">${i('wifi', 18)}</span><span class="srv-m"><b>${esc(found.name)}</b><small>${esc(found.note)}</small></span><span class="stamp st-none"><i class="chip"></i><span>No HTTPS</span></span></li>
      </ul>`;
    if (regular(c))
      return c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        back: 'Welcome',
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs ob ob-bs"><div class="span-all">${top}</div><div class="c1-8">${form}</div><div class="c9-12 ob-rail">${nearby}${help('connect')}</div></div>`,
      });
    return c.sys.shell({ tabBar: false, titleMode: 'none', back: 'Welcome', body: `<div class="rp ob">${top}${form}${nearby}</div>` });
  };

  const help = (which) =>
    which === 'connect'
      ? `<aside class="aside-note"><h4>What Rommio needs</h4><p>A RomM server you can reach from this device: at home, over a VPN, or behind Cloudflare Access. Rommio signs in with your RomM account and never uploads games.</p></aside>`
      : `<aside class="aside-note"><h4>Signing in</h4><p>Rommio keeps you signed in on this device. Your password is sent only to your server and stored in the Keychain.</p></aside>`;

  // ---------- Sign in ----------
  screens.signin = (c) => {
    const p = prof();
    const card = `<div class="srvcard">
      <span class="srvcard-i">${i('server', 22)}</span>
      <span class="srvcard-m"><small>Verified server</small><b>${esc(p.url)}</b><span>${esc(p.version)} · ${esc(p.access)}</span></span>
      <button class="srvcard-x">Change</button>
    </div>`;
    const fields = `
      ${hed('h1', 'Sign in', 'ob-hed xl')}
      <p class="lede">Use your RomM account on ${esc(p.url)}.</p>
      <div class="field"><label for="rz-user">Username</label><div class="field-box"><input id="rz-user" value="${esc(p.user)}" autocomplete="username"></div></div>
      <div class="field"><label for="rz-pass">Password</label><div class="field-box"><input id="rz-pass" type="password" value="correct-horse-battery" autocomplete="current-password"><button class="field-eye" aria-label="Show password">${i('eye', 18)}</button></div></div>
      ${btn('Sign in', { full: true, trail: 'arrow-right' })}`;
    const sso = `<div class="or"><span>or</span></div>
      ${btn('Continue with single sign-on', { kind: 'line', icon: 'key', full: true })}`;
    if (regular(c))
      return c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        back: 'Server',
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs ob ob-bs si-bs"><div class="span-all">${step(2, 'Sign in')}${card}</div><div class="c1-8">${fields}</div><div class="c9-12 ob-rail">${sso}${help('signin')}</div></div>`,
      });
    return c.sys.shell({ tabBar: false, titleMode: 'none', back: 'Server', body: `<div class="rp ob">${step(2, 'Sign in')}${card}${fields}${sso}${help('signin')}</div>` });
  };

  // ---------- First sync ----------
  screens.hydrate = (c) => {
    const devs = D().fleet;
    const list = `<ul class="press" aria-label="First sync progress">
      <li class="done"><b>Platforms</b><span class="ldr"></span><span class="press-v">${LIB.platforms}</span>${check(true, 'Platforms cached')}</li>
      <li class="done"><b>Games</b><span class="ldr"></span><span class="press-v">${n(LIB.games)}</span>${check(true, 'Games cached')}</li>
      <li class="done"><b>Collections</b><span class="ldr"></span><span class="press-v">Done</span>${check(true, 'Collections cached')}</li>
      <li class="run"><b>Artwork</b><span class="ldr"></span><span class="press-v">61%</span><span class="ck run" aria-hidden="true"></span>${bar(0.61, 'Artwork cached', 'press-bar')}</li>
    </ul>`;
    const travel = `<section class="travel">
      <h3>Your progress travels</h3>
      <p>Game saves and resume states sync through your server: stop on one device, continue on another.</p>
      <div class="travel-map" aria-hidden="true">
        <span class="tm on">${i('smartphone', 15)}${esc(devs[0].name)}</span><span class="tm-l"></span>
        <span class="tm srv">${i('server', 15)}RomM</span><span class="tm-l"></span>
        <span class="tm">${i('smartphone', 15)}${esc(devs[1].name)}</span>
      </div>
    </section>`;
    const opts = `<ul class="opts">
      <li><span><b>Keep artwork offline</b><small>Covers stay on this iPhone</small></span>${toggle(c, true, 'Keep artwork offline')}</li>
      <li><span><b>Use cellular data</b><small>Otherwise syncing waits for Wi&#8209;Fi</small></span>${toggle(c, false, 'Use cellular data')}</li>
    </ul>`;
    const cta = `<div class="hy-cta">${btn('Start playing', { full: true, trail: 'arrow-right' })}<p class="fine">Artwork keeps caching in the background.</p></div>`;
    const top = `${step(3, 'First sync')}${hed('h1', 'Going to press', 'ob-hed')}`;
    const lede = `<p class="lede">Your library is being cached on this iPhone so you can browse and play offline.</p>`;
    const head = top + lede;
    if (regular(c)) {
      const fleet = `${sec('Also signed in', `${devs.length - 1} devices`)}<ul class="fleet">${devs
        .slice(1)
        .map((f) => `<li><span class="row-i">${i(f.kind === 'tablet' ? 'tablet' : 'smartphone', 18)}</span><span class="row-m"><b>${esc(f.name)}</b><small>${esc(f.model)} · ${f.installed} games · last seen ${esc(k.ago(f.last).toLowerCase())}</small></span></li>`)
        .join('')}</ul>`;
      return c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        back: 'Sign in',
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs ob ob-bs hy-bs"><div class="span-all">${top}</div><div class="c1-8">${lede}${list}${cta}</div><div class="c9-12 ob-rail">${travel}${opts}${fleet}</div></div>`,
      });
    }
    return c.sys.shell({ tabBar: false, titleMode: 'none', back: 'Sign in', body: `<div class="rp ob hy">${head}${list}${travel}${opts}${cta}</div>` });
  };

  // ---------- Issue (home): the front page ----------
  const issueActions = [
    { icon: 'downloads', label: 'Print queue', badge: String(pendingJobs()) },
    { avatar: 'R', label: 'Colophon and account' },
  ];
  const inside = () => {
    const today = D().sessions.filter((x) => x.day === 0).length;
    const items = [
      ['2', 'Journal', `${today} entries today`],
      ['3', 'Index', `${n(LIB.games)} entries`],
      ['4', 'Print queue', `${pendingJobs()} jobs`],
    ];
    return `<nav class="inside" aria-label="Inside this issue"><h3>Inside</h3><ul>${items
      .map(([pg, t, d]) => `<li><button><span class="in-p">p.${pg}</span><span class="in-m"><b>${t}</b><small>${d}</small></span></button></li>`)
      .join('')}</ul></nav>`;
  };
  const frontLeft = (c, big) => `${nameplate(c)}${ears()}${leadStory(c, { big })}${big ? inside() : ''}`;

  // Duo open: a broadsheet front page. Nameplate and dateline across the full
  // width; the lead runs across the fold (8 of 12 columns) with the Editor's
  // note and briefs in the rail; the column and page furniture follow below.
  const frontPage = (c) => `<div class="rp bs front front-bs">
      ${nameplateWide()}
      <div class="c1-8 fr-lead">${leadStory(c, { big: true })}${finishColumn()}</div>
      <div class="c9-12 fr-rail">${editorsNote()}${sec('Also in progress', '3 stories')}${briefs(['linksawak', 'chrono', 'metfusion'])}</div>
      <div class="c1-5">${numbersBox()}</div>
      <div class="c6-12">${inside()}</div>
      <p class="folio span-all"><span>Rommio · No. ${ISSUE.no}</span><span>Page 1</span></p>
    </div>`;

  screens.home = (c) => {
    if (regular(c)) {
      return c.sys.shell({
        tab: 'issue',
        titleMode: 'none',
        heroUnderBar: true,
        edge: false,
        actions: issueActions,
        body: frontPage(c),
      });
    }
    return c.sys.shell({
      tab: 'issue',
      titleMode: 'none',
      heroUnderBar: heroOK(c),
      edge: false,
      actions: issueActions,
      body: `<div class="rp front">${frontLeft(c, false)}${editorsNote()}
        <div class="cols">
          <div class="col-a">${sec('Also in progress')}${briefs(['linksawak', 'chrono'])}</div>
          <div class="col-b">${finishColumn()}</div>
        </div>
        ${numbersBox()}
        <p class="folio"><span>Rommio · No. ${ISSUE.no}</span><span>Page 1</span></p>
      </div>`,
    });
  };

  // ---------- Index (library): contents ----------
  const DECADE = (y) => `${Math.floor(y / 10) * 10}s`;
  const chapters = () => {
    const order = ['touch', 'controller', 'unsupported'];
    const list = [];
    for (const t of order) D().platforms.filter((p) => p.tier === t).sort((a, b) => a.year - b.year || a.name.localeCompare(b.name)).forEach((p) => list.push(p));
    return list.map((p, x) => ({ ...p, ch: x + 1 }));
  };
  const chapterOf = (pid) => chapters().find((p) => p.id === pid);
  const PARTS = {
    touch: ['Part one', 'Plays with touch', 'Touch controls on screen; a controller works too.'],
    controller: ['Part two', 'Needs a controller', 'Playable here once a controller is connected.'],
    unsupported: ['Part three', 'Browse only', 'On your server, but no player on this device.'],
  };
  const chapterRow = (p, sel) => `<li class="ch${sel ? ' sel' : ''}${p.tier === 'unsupported' ? ' off' : ''}">
      <button class="ch-b" aria-label="Chapter ${p.ch}: ${esc(p.name)}, ${p.games} games, ${p.installed} on this iPhone">
        <span class="ch-n">${String(p.ch).padStart(2, '0')}</span>
        <span class="ch-t"><b>${esc(p.name)}</b>${p.blocked ? `<small>${esc(p.blocked.replace(/^Browse only — (.)/, (m, x) => x.toUpperCase()))}</small>` : `<small>${esc(p.maker)} · ${p.year}</small>`}</span>
        <span class="ldr"></span>
        <span class="ch-c">${n(p.games)}</span>
        <span class="ch-i${p.installed ? '' : ' zero'}">${p.installed || '–'}</span>
      </button>
    </li>`;
  const contents = (c, o = {}) => {
    const ch = chapters();
    let html = '';
    for (const t of ['touch', 'controller', 'unsupported']) {
      const part = ch.filter((p) => p.tier === t);
      const [pn, pt, pd] = PARTS[t];
      html += `<section class="part part-${t}">
        <header class="part-h"><p class="part-k">${pn}</p><h3>${pt}</h3>${tier(t)}<p class="part-d">${pd}</p></header>
        <ol class="chs">`;
      let dec = '';
      for (const p of part) {
        if (t === 'touch' && DECADE(p.year) !== dec) {
          dec = DECADE(p.year);
          html += `<li class="dec" aria-hidden="true"><span>${dec}</span></li>`;
        }
        html += chapterRow(p, o.sel === p.id);
      }
      html += `</ol></section>`;
    }
    // flow: Duo open, the contents run in three columns across the page.
    // tight: a narrow list column (Duo open Chapter). Both let long names wrap.
    if (o.flow) return `<div class="contents flow tight">${html}</div>`;
    return `<div class="contents${o.tight ? ' tight' : ''}">
      <p class="toc-key"><span>Chapter</span><span>Entries</span><span>Installed</span></p>
      ${html}
    </div>`;
  };
  const indexSeg = (c, x) => `<div class="seg-wrap">${c.sys.segmented(['Chapters', 'Series', 'A–Z'], x)}</div>`;
  const indexSub = `${LIB.platforms} chapters · ${n(LIB.games)} entries · ${LIB.installed} on this iPhone`;

  // A back-of-book index of titles: articles inverted, bold = on this iPhone.
  const invert = (t) => {
    const m = t.match(/^(A|An|The) (.+)$/);
    return m ? `${m[2]}, ${m[1]}` : t;
  };
  const entryValue = (g) => {
    if (g.hours) return `<span class="e-v">${esc(k.hours(g.hours))}</span>`;
    if (g.installed) return `<span class="e-v quiet">Not played</span>`;
    return `<span class="e-v quiet">${esc(k.size(g.size))}</span>`;
  };
  const entry = (g, o = {}) => `<li class="e${g.installed ? ' in' : ' out'}">
      <span class="e-t"><b>${esc(invert(g.title))}</b>${g.favorite ? `<i class="e-fav" aria-label="Favourite">${i('star', 12)}</i>` : ''}${o.ref ? `<span class="e-ref">${esc(plat(g).short)}</span>` : ''}${g.sync === 'conflict' ? `<span class="e-cf">Conflict</span>` : ''}</span>
      <span class="ldr"></span>
      ${entryValue(g)}
      ${g.installed ? `<span class="e-a" aria-hidden="true"></span>` : `<button class="e-dl" aria-label="Download ${esc(g.title)}">${i('download', 17)}</button>`}
    </li>`;
  const indexOf = (games, o = {}) => {
    const sorted = games.slice().sort((a, b) => invert(a.title).localeCompare(invert(b.title)));
    let html = '';
    let letter = '';
    for (const g of sorted) {
      const L = invert(g.title)[0].toUpperCase();
      if (L !== letter) {
        if (letter) html += `</ol></div>`;
        letter = L;
        html += `<div class="lg"><span class="lg-l" aria-hidden="true">${L}</span><ol class="es">`;
      }
      html += entry(g, o);
    }
    if (letter) html += `</ol></div>`;
    return `<div class="idx">${html}</div>`;
  };
  const idxKey = `<p class="idx-key"><b>Bold</b> on this iPhone · ${i('download', 12)} not downloaded · ${i('star', 12)} favourite</p>`;

  screens.library = (c) => {
    const actions = [{ icon: 'sort', label: 'Order' }, { avatar: 'R', label: 'Colophon and account' }];
    if (regular(c)) {
      // Contents set like the back of a book: three columns running across the fold.
      const legend = `<p class="toc-leg span-all"><span>Chapters in release order, in three parts by how they play</span><span class="toc-leg-k"><span>Entries</span><i class="ch-i">On this iPhone</i></span></p>`;
      return c.sys.shell({
        tab: 'index',
        titleMode: 'none',
        actions,
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs indexpage idx-bs">${pageHead('Index', indexSub, indexSeg(c, 0))}${legend}<div class="span-all">${contents(c, { flow: true })}</div></div>`,
      });
    }
    return c.sys.shell({
      tab: 'index',
      title: 'Index',
      subtitle: indexSub,
      actions,
      body: `<div class="rp indexpage">${indexSeg(c, 0)}${contents(c)}</div>`,
    });
  };

  // ---------- Chapter (platform) ----------
  const chapterPage = (c, p, o = {}) => {
    const games = k.gamesOn(p.id);
    const plates = games
      .filter((g) => g.hours)
      .sort((a, b) => b.hours - a.hours)
      .slice(0, 4);
    const played = games.filter((g) => g.hours).length;
    return `<div class="rp chapter${o.cls ? ' ' + o.cls : ''}">
      <header class="chop">
        <p class="chop-k"><span>Chapter ${p.ch}</span><span>${esc(PARTS[p.tier][0])} · ${esc(PARTS[p.tier][1])}</span></p>
        ${hed('h1', p.name, 'chop-hed')}
        <p class="chop-dek">${esc(p.maker)}, ${p.year}. <b>${n(p.games)}</b> entries, <b>${p.installed}</b> on this iPhone. Plays with ${esc(p.core)}.</p>
        <p class="chop-st">${tier(p.tier)}<span class="chop-core">${i('cpu', 13)}${esc(p.core)} ready</span></p>
      </header>
      <figure class="plates" aria-label="Most played in this chapter">
        <div class="plates-row">${plates.map((g) => `<span class="plate">${rz(g.id, 'box')}<b>${esc(g.title)}</b><small>${esc(k.hours(g.hours))}</small></span>`).join('')}</div>
        <figcaption>Plates · most played in this chapter</figcaption>
      </figure>
      <div class="filters">${c.sys.segmented([`All ${p.games}`, `On this iPhone ${p.installed}`, `Played ${played}`], 0)}<button class="order" aria-label="Order: A to Z">${i('sort', 16)}A–Z</button></div>
      ${idxKey}
      ${indexOf(games)}
    </div>`;
  };

  screens.platform = (c) => {
    const p = chapterOf('snes');
    const actions = [{ icon: 'search', label: 'Search this chapter' }, { icon: 'more', label: 'More' }];
    if (regular(c)) {
      // The one real list → detail on Duo open: a narrow Contents list (scrolled to
      // the open chapter) beside the wide chapter, on one page background.
      return c.sys.shell({
        tab: 'index',
        titleMode: 'none',
        actions,
        heroUnderBar: true,
        edge: false,
        split: {
          primaryWidth: 292,
          primary: `<div class="rp indexpage scrolled"><div class="scroll-y" style="--y:-330px"><h1 class="pg-title">Index</h1><p class="pg-sub">${indexSub}</p>${indexSeg(c, 0)}${contents(c, { sel: 'snes', tight: true })}</div></div>`,
          secondary: chapterPage(c, p, { cls: 'in-col wide' }),
        },
        body: '',
      });
    }
    return c.sys.shell({ tab: 'index', titleMode: 'none', back: 'Index', actions, body: chapterPage(c, p) });
  };

  // ---------- Series (collections) ----------
  const fan = (ids, cls = '') => `<span class="fan ${cls}" aria-hidden="true">${ids
    .slice(0, 3)
    .map((id, x) => rz(id, 'box', { cls: 'f' + x, proof: !game(id).installed }))
    .join('')}</span>`;
  const seriesCard = (col, x, sel) => {
    const gs = col.games.map(game);
    const on = gs.filter((g) => g.installed).length;
    return `<li class="ser${sel === true ? ' sel' : ''}">
      <button class="ser-b" aria-label="${esc(col.name)}, ${gs.length} volumes"${sel === true ? ' aria-expanded="true"' : ''}>
        ${fan(col.games)}
        <span class="ser-k">Series ${x + 1}</span>
        <b class="ser-t">${esc(col.name)}</b>
        <em class="ser-n">${esc(col.note)}</em>
        <small class="ser-c">${gs.length} volumes · ${on} on this iPhone</small>
      </button>
    </li>`;
  };
  const ruleRow = (col) => `<li class="rule-row"><span class="rr-m"><b>${esc(col.name)}</b><small>${i(col.kind === 'smart' ? 'filter' : 'sparkles', 12)}<span>${esc(col.rule)}</span></small></span><span class="ldr"></span><span class="rr-n">${col.games.length}</span></li>`;
  const seriesPage = () => {
    const all = D().collections;
    const mine = all.filter((x) => x.kind === 'manual');
    const smart = all.filter((x) => x.kind === 'smart');
    const auto = all.filter((x) => x.kind === 'auto');
    return `
      ${sec('Edited by you', `${mine.length} series`)}
      <ul class="sers">${mine.map(seriesCard).join('')}</ul>
      ${sec('Standing orders', 'Smart · by rule')}
      <ul class="rules">${smart.map(ruleRow).join('')}</ul>
      ${sec('From the catalogue', 'Automatic · metadata')}
      <ul class="rules">${auto.map(ruleRow).join('')}</ul>`;
  };
  const volume = (g, x) => {
    const m = D().moments.find((mm) => mm.game === g.id);
    return `<li class="vol${g.installed ? '' : ' out'}">
      <span class="vol-n">${x + 1}</span>
      ${rz(g.id, 'box', { cls: 'vol-c', proof: !g.installed })}
      <span class="vol-m"><b>${esc(g.title)}</b><small>${esc(plat(g).short)} · ${g.year} · ${g.hours ? esc(k.hours(g.hours)) : 'Not played'}</small>${m ? `<span class="vol-s"><span>${esc(m.name)}</span>${stamp(m.remote)}</span>` : `<span class="vol-s">${g.installed ? stamp(g.sync) : stamp('none')}</span>`}</span>
    </li>`;
  };
  screens.collections = (c) => {
    const actions = [{ icon: 'plus', label: 'New series' }, { avatar: 'R', label: 'Colophon and account' }];
    if (regular(c)) {
      // One page: the four series run across the fold; the open series grows out
      // of its card as a band beneath, and the rule-made series follow.
      const all = D().collections;
      const mine = all.filter((x) => x.kind === 'manual');
      const col = k.collection('metroidvania');
      const open = `<section class="so span-all" aria-label="${esc(col.name)}, open">
        <div class="so-copy">
          ${kick(`Series ${mine.indexOf(col) + 1} · Edited by you`, `${col.games.length} volumes`)}
          ${hed('h2', col.name, 'chop-hed')}
          <p class="chop-dek"><em>${esc(col.note)}</em> Four games about maps that open up as you play.</p>
        </div>
        <ol class="vols">${k.collectionGames('metroidvania').map(volume).join('')}</ol>
      </section>`;
      return c.sys.shell({
        tab: 'index',
        titleMode: 'none',
        actions,
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs indexpage ser-bs">
          ${pageHead('Index', `${all.length} series · ${mine.length} edited by you`, indexSeg(c, 1))}
          ${sec('Edited by you', `${mine.length} series`, 'span-all')}
          <ul class="sers span-all">${mine.map((x, idx) => seriesCard(x, idx, x.id === col.id)).join('')}</ul>
          ${open}
          <div class="c1-4">${sec('Standing orders', 'Smart · by rule')}<ul class="rules">${all.filter((x) => x.kind === 'smart').map(ruleRow).join('')}</ul></div>
          <div class="c5-12">${sec('From the catalogue', 'Automatic · metadata')}<ul class="rules two">${all.filter((x) => x.kind === 'auto').map(ruleRow).join('')}</ul></div>
        </div>`,
      });
    }
    return c.sys.shell({ tab: 'index', title: 'Index', subtitle: `${D().collections.length} series · 4 edited by you`, actions, body: `<div class="rp indexpage">${indexSeg(c, 1)}${seriesPage()}</div>` });
  };

  // ---------- Game: the feature ----------
  const featureOpener = (c, g) => {
    const p = plat(g);
    const [stand] = g.blurb.split(/(?<=\.)\s+/);
    return `<header class="feat">
      <div class="feat-art">${rz(g.id, 'wide', { alt: g.title + ' cover art' })}</div>
      <div class="feat-copy">
        ${kick('Feature', `${esc(p.short)} · ${g.year} · Chapter ${chapterOf(p.id).ch}`, 'pinkk')}
        ${hed('h1', g.title, 'feat-hed')}
        <p class="feat-stand">${esc(stand)}</p>
        <p class="feat-by"><span>${esc(g.publisher)}</span><span>${esc(g.genre)}</span><span>${esc(k.hours(g.hours))} played</span></p>
      </div>
    </header>`;
  };
  const wylo = (c, g) => {
    const pr = D().progress;
    return `<section class="wylo" aria-label="Where you left off">
      <header class="wylo-h"><span>Where you left off</span><span>${esc(k.ago(pr.resume.at))}</span></header>
      <div class="wylo-body">
        <figure class="wylo-fig">${snap(g.id, pr.resume.frame, { alt: 'Automatic resume frame' })}<figcaption>${esc(D().sessions[0].note)} · ${esc(pr.resume.device)}</figcaption></figure>
        <dl class="wylo-dl">
          <div><dt>Automatic resume</dt><dd>${esc(k.ago(pr.resume.at))}</dd><dd>${stamp(pr.resume.remote)}</dd></div>
          <div><dt>Game-save data</dt><dd>${esc(pr.gameSave.detail.replace('SRAM · ', 'SRAM '))} · ${esc(k.ago(pr.gameSave.at).toLowerCase())}</dd><dd>${stamp(pr.gameSave.remote)}</dd></div>
        </dl>
      </div>
      <div class="wylo-act">${btn('Start with game save', { kind: 'line', size: 'sm', icon: 'save', isz: 16 })}${btn('Sync now', { kind: 'line', size: 'sm', icon: 'sync', isz: 16 })}</div>
    </section>`;
  };
  const statesSidebar = () => {
    const pr = D().progress;
    const used = pr.slots.filter((s) => !s.empty).length;
    return `<aside class="sb" aria-label="Save states">
      <h3 class="sb-h"><span>Save states</span><small>${used} of ${pr.slots.length} slots</small></h3>
      <ol class="sb-l">${pr.slots
        .map((s) =>
          s.empty
            ? `<li class="empty"><span class="sb-n">${s.n}</span><span class="sb-m"><b>Empty slot</b><small>Save state here from the pause menu</small></span></li>`
            : `<li><span class="sb-n">${s.n}</span><span class="sb-m"><b>${esc(s.name)}</b><small>${esc(s.device)} · ${esc(k.ago(s.at))}</small></span>${stamp(s.remote)}</li>`,
        )
        .join('')}</ol>
      <button class="sb-go"><span>Open save states</span>${i('chevron-right', 16)}</button>
    </aside>`;
  };
  const fromJournal = (gid) => {
    const list = D().sessions.filter((s) => s.game === gid).slice(0, 3);
    return `<section class="fj" aria-label="From the Journal">
      <h3 class="fj-h">From the Journal</h3>
      <ul>${list.map((s) => `<li><span class="fj-d">${esc(dayName(s.day))}</span><span class="fj-t">${mins(s.minutes)} on ${esc(s.device)} — ${esc(s.note)}.</span></li>`).join('')}</ul>
    </section>`;
  };
  const specBox = (g) => {
    const p = plat(g);
    return `<section class="spec" aria-label="Specifications">
      <h3 class="spec-h">Specifications</h3>
      <dl>
        <div><dt>File</dt><dd class="mono">Super Metroid (USA).sfc<small>${esc(k.size(g.size))} · on this iPhone</small></dd></div>
        <div><dt>Player</dt><dd>${esc(p.core)} · ready ${tier(p.tier)}</dd></div>
        <div><dt>Published</dt><dd>${esc(g.publisher)}, ${g.year} · ${esc(g.genre)}</dd></div>
        <div><dt>Players</dt><dd>${g.players} · rating ${g.rating}</dd></div>
      </dl>
      <div class="spec-f">${btn('Remove download', { kind: 'quiet', icon: 'trash', isz: 16, size: 'sm' })}<p>Game-save data, save states and your resume point are kept.</p></div>
    </section>`;
  };
  const pullQuote = (g) => {
    const parts = g.blurb.split(/(?<=\.)\s+/);
    return `<blockquote class="pull"><p>${esc(parts.slice(1).join(' '))}</p></blockquote>`;
  };

  screens.game = (c) => {
    const g = game('smetroid');
    const actions = [{ icon: 'heart', label: 'Favourite' }, { icon: 'more', label: 'More' }];
    const primary = { icon: 'play', label: 'Resume' };
    if (regular(c)) {
      // One opener across the display: the printed cover runs edge to edge over
      // the fold, the headline spans it, and the article reflows into columns.
      const p = plat(g);
      const [stand] = g.blurb.split(/(?<=\.)\s+/);
      return c.sys.shell({
        tab: 'index',
        titleMode: 'none',
        back: 'Super Nintendo',
        primary,
        actions,
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs gm gm-bs">
          <div class="feat-art span-all">${rz(g.id, 'wide', { alt: g.title + ' cover art' })}</div>
          <header class="feat-head span-all">
            ${kick('Feature', `${esc(p.short)} · ${g.year} · Chapter ${chapterOf(p.id).ch}`, 'pinkk')}
            <div class="feat-hrow">${hed('h1', g.title, 'feat-hed')}<p class="feat-by"><span>${esc(g.publisher)}</span><span>${esc(g.genre)}</span><span>${esc(k.hours(g.hours))} played</span></p></div>
          </header>
          <div class="c1-3 feat-col"><p class="feat-stand">${esc(stand)}</p>${pullQuote(g)}</div>
          <div class="c4-9 feat-main">${wylo(c, g)}${specBox(g)}</div>
          <div class="c10-12 feat-side">${statesSidebar()}${fromJournal(g.id)}</div>
        </div>`,
      });
    }
    const html = c.sys.shell({
      tab: 'index',
      titleMode: 'none',
      back: 'Super Nintendo',
      primary,
      actions,
      heroUnderBar: heroOK(c),
      edge: false,
      body: `<div class="rp gm">${featureOpener(c, g)}${wylo(c, g)}${pullQuote(g)}${statesSidebar()}${fromJournal(g.id)}${specBox(g)}</div>`,
    });
    // The printed cover sits under the iPhone status bar: use light status text.
    return c.os === 'ios' && !c.device.vbar ? { html, tone: 'light' } : html;
  };

  // ---------- Print queue (downloads) ----------
  const queuePage = (c, part) => {
    const r = running();
    const rg = game(r.game);
    const queued = dl().filter((d) => d.state === 'queued');
    const waiting = dl().filter((d) => d.state === 'waiting');
    const failed = dl().filter((d) => d.state === 'failed');
    const done = dl().filter((d) => d.state === 'done');
    const job = (d, lead, right, extra = '', cls = '') => {
      const g = game(d.game);
      return `<li class="job ${d.state} ${cls}"><span class="job-n">${lead}</span><span class="job-m"><b>${esc(g.title)}</b><small>${esc(plat(g).short)}${extra}</small></span><span class="job-r">${right}</span></li>`;
    };
    const now = `<section class="press-now" aria-label="Now downloading">
      ${kick('Now printing', `${k.pct(r.progress)} · ${esc(r.eta)} left`, 'yelk')}
      <div class="pn">
        ${rz(rg.id, 'box', { cls: 'pn-c' })}
        <div class="pn-m">
          ${hed('h2', rg.title, 'pn-hed')}
          <p class="pn-d">${esc(plat(rg).name)} · ${esc(k.size(r.done))} of ${esc(k.size(r.total))}</p>
          <p class="pn-d">${r.rate} MB/s · ${esc(r.eta)} left</p>
        </div>
      </div>
      ${bar(r.progress, rg.title + ' download progress', 'pn-bar')}
      <div class="pn-act">${btn('Pause', { kind: 'line', size: 'sm', icon: 'pause', isz: 16 })}${btn('Cancel', { kind: 'quiet', size: 'sm', icon: 'x', isz: 16 })}</div>
    </section>`;
    const upNext = `
      ${sec('Up next', `${queued.length} queued`)}
      <ol class="jobs">${queued.map((d, x) => job(d, String(x + 1), esc(k.size(d.total)))).join('')}</ol>`;
    const held = `
      ${sec('Held', 'Waiting for Wi-Fi')}
      <ul class="jobs">${waiting.map((d) => job(d, i('wifi-off', 16), esc(k.size(d.total)), ` · ${esc(d.reason)}`)).join('')}</ul>`;
    const next = upNext + held;
    const fail = `
      ${sec('Failed', `${failed.length}`)}
      <ul class="jobs">${failed.map((d) => job(d, i('alert', 16), btn('Retry', { kind: 'pink', size: 'xs', icon: 'restart', isz: 15 }), ` · Connection closed at ${k.pct(d.progress)}`, 'bad')).join('')}</ul>`;
    const st = D().storage;
    const used = st.roms + st.saves + st.states + st.cache + st.cores + st.bios;
    const parts = [
      ['ROMs', st.roms, 'blue'],
      ['Save states', st.states, 'pink'],
      ['Game saves', st.saves, 'green'],
      ['Cache', st.cache, 'yellow'],
      ['Cores and BIOS', st.cores + st.bios, 'ink'],
    ];
    const paper = `<section class="stock" aria-label="Storage">
      ${sec('Paper stock', `${k.size(st.total - used)} free`)}
      <div class="stock-bar" role="img" aria-label="Rommio uses ${k.size(used)} of ${k.size(st.total)}">${parts.map(([l, v, cc]) => `<i class="ink-${cc}" style="flex:${v}"></i>`).join('')}<i class="free" style="flex:${st.total - used}"></i></div>
      <ul class="stock-key">${parts.map(([l, v, cc]) => `<li><i class="ink-${cc}"></i><span>${l}</span><b>${k.size(v)}</b></li>`).join('')}</ul>
    </section>`;
    const printed = `
      ${sec('Printed this week', `${done.length}`)}
      <ul class="jobs">${done.map((d) => job(d, i('check', { size: 16, stroke: 2.6 }), esc(k.ago(d.at)), ` · ${esc(k.size(d.total))}`)).join('')}</ul>`;
    // Duo open: the running job is the lead across the fold, failures and paper
    // stock sit in the rail, and the rest of the queue runs in three columns.
    if (part === 'wide')
      return `<div class="c1-8 q-lead">${now}</div>
        <div class="c9-12 q-rail">${fail}${paper}</div>
        <div class="c1-4">${upNext}</div>
        <div class="c5-8">${held}</div>
        <div class="c9-12">${printed}</div>`;
    return now + fail + next + printed + paper;
  };
  screens.downloads = (c) => {
    const sub = `${pendingJobs()} jobs · downloads to this iPhone`;
    if (regular(c)) {
      return c.sys.shell({
        tab: 'issue',
        titleMode: 'none',
        back: 'Issue',
        actions: [{ icon: 'pause', label: 'Pause all' }],
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs queue q-bs">${pageHead('Print queue', sub)}${queuePage(c, 'wide')}</div>`,
      });
    }
    return c.sys.shell({ tab: 'issue', title: 'Print queue', subtitle: sub, back: 'Issue', actions: [{ icon: 'pause', label: 'Pause all' }], body: `<div class="rp queue">${queuePage(c)}</div>` });
  };

  // ---------- Search: an index lookup ----------
  screens.search = (c) => {
    const q = 'metroid';
    const hits = D().games.filter((g) => g.title.toLowerCase().includes(q));
    hits.sort((a, b) => (b.installed - a.installed) || ((a.played ?? 1e9) - (b.played ?? 1e9)));
    const ser = D().collections.filter((col) => col.games.some((id) => hits.find((h) => h.id === id)));
    const sess = D().sessions.filter((s) => hits.find((h) => h.id === s.game));
    const hit = (g) => {
      const m = D().moments.find((x) => x.game === g.id && x.kind === 'resume');
      const chap = chapterOf(g.platform);
      return `<li class="hit">
        ${rz(g.id, 'box', { cls: 'hit-c', proof: !g.installed })}
        <span class="hit-m">
          <b>${hl(g.title, q)}</b>
          <small>Ch. ${chap.ch} · ${esc(plat(g).short)} · ${g.installed ? 'on this iPhone' : 'not downloaded'}</small>
          <span class="hit-s">${m ? `${esc(m.name)} · ${esc(k.ago(m.at).toLowerCase())}` : `${esc(k.hours(g.hours))} played`}</span>
        </span>
        ${g.installed ? `<button class="hit-play" aria-label="${m ? 'Resume' : 'Play'} ${esc(g.title)}">${i('play', 18)}</button>` : ''}
      </li>`;
    };
    const summary = `<p class="look-sum"><b>${hits.length} entries</b> for “${esc(q)}”. <span>Ranked by what you can play now, then by when you last played.</span></p>
      <div class="chips" aria-label="See also"><span class="chips-k">See also</span>${D().search.suggestions.map((x) => `<button class="chipb">${esc(x)}</button>`).join('')}</div>`;
    const results = `${sec('Playable now', `${hits.filter((h) => h.installed).length}`)}
      <ul class="hits">${hits.map(hit).join('')}</ul>`;
    const xref = `${sec('Chapters and series')}
      <ul class="xref">
        ${[...new Set(hits.map((h) => h.platform))].map((pid) => `<li><span class="xr-k">Chapter ${chapterOf(pid).ch}</span><b>${esc(k.platform(pid).name)}</b><span class="ldr"></span><span class="xr-v">${hits.filter((h) => h.platform === pid).length} entry</span></li>`).join('')}
        ${ser.map((s) => `<li><span class="xr-k">Series</span><b>${esc(s.name)}</b><span class="ldr"></span><span class="xr-v">${s.games.filter((id) => hits.find((h) => h.id === id)).length} of ${s.games.length}</span></li>`).join('')}
      </ul>`;
    const journal = `${sec('In the Journal', `${sess.length} entries`)}
      <ul class="xj">${sess
        .slice(0, regular(c) ? 5 : 3)
        .map((s) => `<li><span class="xj-d">${esc(dayName(s.day))}</span><span class="xj-t"><b>${esc(game(s.game).title)}</b> · ${mins(s.minutes)} on ${esc(s.device)} — ${esc(s.note)}</span></li>`)
        .join('')}</ul>`;
    const recent = `${sec('Recent lookups')}
      <ul class="recent">${D().search.recent.map((x) => `<li>${i('history', 15)}<span>${esc(x)}</span></li>`).join('')}</ul>`;
    if (regular(c)) {
      // One lookup page: the query and ranked entries across the fold (entries in
      // two columns), cross-references in the rail.
      return c.sys.shell({
        tab: 'search',
        titleMode: 'none',
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs look look-bs">
          <div class="c1-8">${c.sys.search({ value: q, focused: true })}${summary}${results}<div class="look-j">${journal}</div></div>
          <div class="c9-12 look-rail">${xref}${recent}</div>
        </div>`,
      });
    }
    return c.sys.shell({ tab: 'search', title: 'Search', titleMode: 'none', search: { value: q, focused: true }, body: `<div class="rp look">${summary}${results}${xref}${journal}${recent}</div>` });
  };
  const hl = (t, q) => {
    const x = t.toLowerCase().indexOf(q);
    if (x < 0) return esc(t);
    return `${esc(t.slice(0, x))}<mark>${esc(t.slice(x, x + q.length))}</mark>${esc(t.slice(x + q.length))}`;
  };

  // ---------- Journal (signature) ----------
  const diaryEntry = (s) => {
    const g = game(s.game);
    const m = sessionMoment(s);
    const remote = sessionRemote(s);
    const variant = m ? m.frame : 'a';
    return `<li class="de">
      <span class="de-time">${esc(s.start)}</span>
      <span class="de-m">
        <b class="de-g">${esc(g.title)}</b>
        <span class="de-s">${mins(s.minutes)} on ${esc(s.device)} — ${esc(s.note)}.</span>
        <span class="de-o"><span>${esc(sessionOutcome(s))}</span>${remote ? stamp(remote) : ''}</span>
      </span>
      ${snap(g.id, variant, { cls: 'de-fig' })}
    </li>`;
  };
  // short: narrow diary columns (Duo open) set the date as "23 Sep".
  const diary = (days, o = {}) =>
    days
      .map(
        (d) => `<section class="day" aria-label="${esc(dayLong(d.day))}">
        <header class="day-h"><h3>${esc(dayName(d.day))}</h3><span>${o.short ? `${dayOf(d.day).d} ${dayOf(d.day).mo.slice(0, 3)}` : d.day < 2 ? esc(dayLong(d.day)) : `${dayOf(d.day).d} ${dayOf(d.day).mo}`}</span><span class="day-n">${d.list.length} ${d.list.length === 1 ? 'entry' : 'entries'}</span></header>
        <ol class="des">${d.list.map(diaryEntry).join('')}</ol>
      </section>`,
      )
      .join('');
  const monthInInk = (o = {}) => {
    const s = D().stats;
    const max = Math.max(...s.days);
    const devices = D().fleet.map((f) => ({ ...f, n: D().sessions.filter((x) => x.device === f.name).length }));
    const tops = s.topPlatforms;
    const tmax = tops[0].minutes;
    const lede = `<p class="month-lede"><b>${hm(s.month.minutes)}</b> across <b>${s.month.sessions} sessions</b> and <b>${s.month.games} games</b>. A ${s.month.streak}-day streak; your best is ${s.month.bestStreak}.</p>`;
    const strip = `<div class="ink-days" role="img" aria-label="Minutes played per day, last 28 days">${s.days.map((m) => `<i style="--h:${((m / max) * 100).toFixed(1)}%"${m ? '' : ' class="z"'}></i>`).join('')}</div>
      <p class="ink-axis"><span>4 weeks ago</span><span>This week</span></p>`;
    const byChapter = `<div><h4>By chapter</h4><ul class="tops">${tops.map((t) => `<li><span>${esc(k.platform(t.platform).short)}</span><i style="--w:${((t.minutes / tmax) * 100).toFixed(1)}%"></i><b>${hm(t.minutes)}</b></li>`).join('')}</ul></div>`;
    const byDevice = `<div><h4>Sessions by device</h4><ul class="where">${devices.map((f) => `<li>${i(f.kind === 'tablet' ? 'tablet' : 'smartphone', 15)}<span>${esc(f.name)}</span><span class="ldr"></span><b>${f.n}</b></li>`).join('')}</ul></div>`;
    // Duo open: the halftone strip runs the full width of the page, across the fold.
    if (o.wide)
      return `<section class="month wide" aria-label="The month in ink">
        <header class="month-h"><h3>The month in ink</h3><span>Last 28 days</span></header>
        <div class="month-w">${lede}${byChapter}${byDevice}</div>
        ${strip}
      </section>`;
    return `<section class="month${o.full ? ' full' : ''}" aria-label="The month in ink">
      <header class="month-h"><h3>The month in ink</h3><span>Last 28 days</span></header>
      ${lede}${strip}
      ${o.full ? `<div class="month-g">${byChapter}${byDevice}</div>` : ''}
    </section>`;
  };
  screens.signature = (c) => {
    const days = byDay();
    const sub = `${D().sessions.length} entries this month · kept automatically`;
    const actions = [{ icon: 'calendar', label: 'Jump to a date' }, { avatar: 'R', label: 'Colophon and account' }];
    if (regular(c)) {
      // The month in ink runs the full width; the diary is set in three columns
      // that flow across the fold, day after day.
      return c.sys.shell({
        tab: 'journal',
        titleMode: 'none',
        actions,
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs journal jr-bs">${pageHead('Journal', sub)}<div class="span-all">${monthInInk({ wide: true })}</div><div class="span-all diary-flow">${diary(days.slice(0, 5), { short: true })}</div></div>`,
      });
    }
    return c.sys.shell({
      tab: 'journal',
      title: 'Journal',
      subtitle: sub,
      actions,
      body: `<div class="rp journal">${monthInInk()}${diary(days.slice(0, 4))}</div>`,
    });
  };

  // ---------- Colophon (settings) ----------
  const imprint = (c) => {
    const [home] = D().profiles;
    return `<section class="imprint" aria-label="Account">
      <p class="imp-k">Colophon</p>
      <p class="imp-l">This edition is printed from</p>
      ${hed('h2', home.name, 'imp-srv')}
      <p class="imp-l"><b>${esc(home.user)}</b> at ${esc(home.url)}<br>${esc(home.version)} · via ${esc(home.access)}</p>
      <p class="imp-st">${stamp('synced', { label: `Online · synced ${k.ago(home.lastSync)}` })}</p>
      <div class="imp-act">${btn('Account', { kind: 'line', size: 'xs', icon: 'user', isz: 15 })}${btn('Sign out', { kind: 'quiet', size: 'xs', icon: 'exit', isz: 15 })}</div>
    </section>`;
  };
  const row = (o) => `<li class="row${o.cls ? ' ' + o.cls : ''}">${o.icon ? `<span class="row-i">${i(o.icon, 18)}</span>` : ''}<span class="row-m"><b>${esc(o.title)}</b>${o.sub ? `<small>${o.sub}</small>` : ''}</span>${o.right || ''}</li>`;
  const chev = i('chevron-right', 18, 'row-chev');
  const settingsBody = (c, part) => {
    const [, sam] = D().profiles;
    const st = D().storage;
    const cores = D().cores;
    const bios = cores.filter((x) => x.state === 'bios');
    const setup = cores.filter((x) => x.state === 'setup');
    const used = st.roms + st.saves + st.states + st.cache + st.cores + st.bios;
    const parts = [
      ['ROMs', st.roms, 'blue'],
      ['Save states', st.states, 'pink'],
      ['Game saves', st.saves, 'green'],
      ['Cache', st.cache, 'yellow'],
      ['Cores', st.cores, 'ink'],
      ['BIOS', st.bios, 'ink'],
    ];
    const editions = `
      ${sec('Other editions', '1 profile')}
      <ul class="rows">${row({ icon: 'archive', title: sam.name, sub: `${esc(sam.url)} · offline-ready · synced ${esc(k.ago(sam.lastSync).toLowerCase())}`, right: btn('Switch', { kind: 'line', size: 'xs' }) })}</ul>`;
    const sync = `
      ${sec('Sync', 'How progress travels')}
      <ul class="rows">
        ${row({ title: 'Game saves', sub: 'In-game save data, between devices', right: toggle(c, true, 'Sync game saves') })}
        ${row({ title: 'Resume states', sub: 'Automatic resume, between devices', right: toggle(c, true, 'Sync resume states') })}
        ${row({ title: 'Use cellular data', sub: 'Otherwise sync waits for Wi&#8209;Fi', right: toggle(c, false, 'Use cellular data') })}
      </ul>`;
    const player = `
      ${sec('Player')}
      <ul class="rows">
        ${row({ icon: 'gamepad', title: 'Player and controls', sub: 'Touch layout, controller, display', right: chev })}
        ${row({ icon: 'cpu', title: 'Cores and BIOS', sub: `${cores.length} cores · ${bios.length} needs BIOS · ${setup.length} needs setup`, right: `<span class="dotn" aria-label="2 need attention">2</span>${chev}` })}
      </ul>
      <p class="row-note">${i('alert', 13)}<span>${esc(bios[0].name)} ${esc(bios[0].note.toLowerCase())}. ${esc(setup[0].name)}: ${esc(setup[0].note.toLowerCase())}.</span></p>`;
    const storage = `
      ${sec('Storage', `${k.size(used)} of ${k.size(st.total)}`)}
      <div class="stock">
        <div class="stock-bar" role="img" aria-label="Storage used by Rommio">${parts.map(([l, v, cc]) => `<i class="ink-${cc}" style="flex:${v}"></i>`).join('')}<i class="free" style="flex:${st.total - used}"></i></div>
        <ul class="stock-key">${parts.map(([l, v, cc]) => `<li><i class="ink-${cc}"></i><span>${l}</span><b>${k.size(v)}</b></li>`).join('')}</ul>
      </div>`;
    const about = `
      ${sec('About')}
      <ul class="rows">${row({ icon: 'info', title: 'About Rommio', sub: 'Licences, diagnostics, support', right: chev })}</ul>
      <p class="colo-fine">Set in Big Shoulders Display, Newsreader and IBM Plex Mono. Printed on demand from your own server.</p>`;
    // Duo open: the imprint is a band across the whole page; the settings run
    // beneath it in three columns, like the small print on a back page.
    if (part === 'wide')
      return `<div class="span-all">${imprint(c)}</div>
        <div class="c1-4">${editions}${sync}</div>
        <div class="c5-8">${player}</div>
        <div class="c9-12">${storage}${about}</div>`;
    return imprint(c) + editions + sync + player + storage + about;
  };
  screens.settings = (c) => {
    if (regular(c)) {
      return c.sys.shell({
        tab: 'issue',
        titleMode: 'none',
        back: 'Issue',
        heroUnderBar: true,
        edge: false,
        body: `<div class="rp bs colo colo-bs">${settingsBody(c, 'wide')}</div>`,
      });
    }
    return c.sys.shell({ tab: 'issue', title: 'Colophon', titleMode: 'none', back: 'Issue', body: `<div class="rp colo">${settingsBody(c)}</div>` });
  };

  // ---------- Controls ----------
  const slider = (label, value, text, o = {}) => {
    const min = o.min ?? 0;
    const max = o.max ?? 100;
    const pct = ((value - min) / (max - min)) * 100;
    const ticks = Array.from({ length: 11 }, (_, x) => `<i style="left:${x * 10}%"${x % 5 === 0 ? ' class="maj"' : ''}></i>`).join('');
    return `<div class="sl"><div class="sl-h"><b>${esc(label)}</b><output>${esc(text)}</output></div>
      <div class="sl-track" role="slider" aria-label="${esc(label)}" aria-valuemin="${min}" aria-valuemax="${max}" aria-valuenow="${value}" aria-valuetext="${esc(text)}"><span class="sl-ticks">${ticks}</span><span class="sl-fill" style="width:${pct.toFixed(1)}%"></span><span class="sl-knob" style="left:${pct.toFixed(1)}%"></span></div>
      ${o.note ? `<small>${esc(o.note)}</small>` : ''}</div>`;
  };
  // On the Duo the card holds the real Duo player in miniature (k.stage geometry).
  const controlCard = (c, o = {}) => `<figure class="card${o.cls ? ' ' + o.cls : ''}" aria-label="Live preview of the touch controls">
      ${
        c.device.duo
          ? `<div class="card-stage card-duo">${duoReplica(c, c.w - 72 - 32)}</div>`
          : `<div class="card-stage">
        <span class="card-vp">${snap('smetroid', 'a', { alt: 'Super Metroid, live preview' })}</span>
        <div class="card-l ctl">${pill('L', 'sh sh-l')}${dpad(o.dp || 70)}${pill('Select', 'ss')}</div>
        <div class="card-r ctl">${pill('R', 'sh sh-r')}${diamond(o.fb || 25, o.fg || 22)}${pill('Start', 'ss')}</div>
      </div>`
      }
      <figcaption><span>Live preview · Super Metroid</span><span>Opacity 72% · Size 100%</span></figcaption>
    </figure>`;
  const controlsBody = (c, part) => {
    const ct = D().controls;
    const conn = `<p class="conn">${i('gamepad', 16)}<span><b>${esc(ct.connectedController)}</b> connected. Touch controls are hidden while it is.</span></p>`;
    const touch = `
      ${sec('Touch controls')}
      <ul class="rows">
        ${row({ title: 'Show touch controls', right: toggle(c, ct.showTouch, 'Show touch controls') })}
        ${row({ title: 'Hide with a controller', sub: 'When a controller connects', right: toggle(c, ct.autoHideOnController, 'Hide with a controller') })}
        ${row({ title: 'Left-handed', sub: 'Swaps the d-pad and buttons', right: toggle(c, ct.leftHanded, 'Left-handed') })}
      </ul>`;
    const opNote = c.device.duo
      ? 'Used wherever a control overlaps the game. On the Duo the frame steps in to leave rails for the controls, so they print at full ink.'
      : 'Used wherever a control overlaps the game. Riso keeps controls off the frame, so they print at full ink.';
    const tune = `
      ${sec('Tuning')}
      <div class="sls">
        ${slider('Opacity', Math.round(ct.opacity * 100), k.pct(ct.opacity), { min: 20, note: opNote })}
        ${slider('Size', Math.round(ct.size * 100), k.pct(ct.size), { min: 60, max: 140 })}
        ${slider('Stick deadzone', Math.round(ct.deadzone * 100), k.pct(ct.deadzone), { max: 40 })}
      </div>`;
    const feel = `
      ${sec('Feel and display')}
      <ul class="rows">
        ${row({ title: 'Haptics', right: toggle(c, ct.haptics, 'Haptics') })}
        ${row({ title: 'OLED black', sub: 'Pure black around the game', right: toggle(c, ct.oled, 'OLED black') })}
        ${row({ title: 'Console colours', sub: 'Each console’s own button colours', right: toggle(c, ct.consoleColors, 'Console colours') })}
        ${row({ title: 'Controller type', right: `<button class="selb">${esc(ct.controllerType)}${i('chevron-down', 15)}</button>` })}
      </ul>`;
    if (part === 'panel') return conn + touch + tune + feel;
    return controlCard(c) + conn + touch + tune + feel;
  };
  screens.controls = (c) => {
    if (regular(c)) {
      // The preview is the real open-Duo player (k.stage geometry) scaled to the
      // page, printed as a proof. The settings float over the middle of the
      // frame, stopping above Select · Menu · Start, so both rails and the
      // tertiary row stay in view while they change.
      const W = c.w - 72 - 24;
      const L = DUO_LAND(c);
      const s = W / L.w;
      const st = k.stage(L, { aspect: k.aspect('smetroid') });
      const tertTop = (c.h - L.h * s) / 2 + (st.tertiary.cy - st.tertiary.h / 2) * s;
      const preview = `<figure class="ctl-proof" aria-label="Live preview of the touch controls">${duoReplica(c, W)}</figure>`;
      return c.sys.shell({
        tab: 'issue',
        titleMode: 'none',
        back: 'Colophon',
        heroUnderBar: true,
        edge: false,
        body: `<div class="ctl-duo">${preview}<section class="ctl-panel" aria-label="Touch control settings" style="bottom:${Math.round(c.h - tertTop + 12)}px"><div class="rp ctlpage"><p class="ctl-k"><span>Live preview</span><span>Super Metroid · Snes9x</span></p><h1 class="pg-title">Controls</h1><p class="pg-sub">Tuned on the player itself: every change shows in the rails behind.</p>${controlsBody(c, 'panel')}</div></section></div>`,
      });
    }
    return c.sys.shell({ tab: 'issue', title: 'Controls', titleMode: 'inline', back: 'Colophon', body: `<div class="rp ctlpage">${controlsBody(c)}</div>` });
  };

  // ---------- Player (landscape) ----------
  screens.player = {
    tone: 'light',
    render: (c) => {
      // Duo (open and closed): the frame steps in between two rails (across the
      // fold when open); the ink clusters sit low in the rails, L/R above them,
      // Select · Menu · Start under the frame. Nothing prints over the game.
      if (c.device.duo) return duoLand(c);
      const g = game('smetroid');
      const toast = `<div class="toast" role="status">${i('check', { size: 14, stroke: 3 })}<span>Automatic resume updated</span></div>`;
      return `<div class="stage land">
        <div class="vp vp-land" style="--ar:${k.aspect(g.id)}"><img src="${k.frame(g.id, 'a')}" alt="Super Metroid, in game"></div>
        ${landRails({ dp: 112, fb: 44, fg: 37 })}
        ${toast}
      </div>`;
    },
  };

  // ---------- Player (portrait, Game Boy Color) ----------
  screens['player-portrait'] = {
    tone: 'light',
    render: (c) => {
      const g = game('linksawak');
      const p = plat(g);
      if (c.device.duo) {
        // The frame runs full width at the top (across the fold when open); the
        // controls live in the band beneath it, low. A Game Boy has no shoulder
        // buttons, so the L/R slots stay empty and the notice sits between them.
        const st = k.stage(c, { aspect: k.aspect(g.id) });
        const b = Math.round(st.size * 0.48);
        return `<div class="stage duo-st port-st dev-${c.device.id}">
          <div class="vp" style="${at(st.game)}"><img src="${k.frame(g.id, 'a')}" alt="${esc(g.title)}, in game"></div>
          <p class="slug" aria-hidden="true" style="top:${st.game.y + st.game.h + 14}px">${esc(p.name)} · ${esc(p.core)}</p>
          <div class="ctl">
            ${dpadAt(st)}
            <div class="ba" role="group" aria-label="Face buttons" style="--b:${b}px;${around(st.face)};width:${st.size}px;height:${st.size}px"><button class="fbtn f-b" aria-label="B button">B</button><button class="fbtn f-a" aria-label="A button">A</button></div>
            ${tertAt(st)}
          </div>
          ${toastAt(st.toast)}
        </div>`;
      }
      const band = `<div class="pband"><span class="pband-t"><b>${esc(g.title)}</b><small>${esc(p.name)} · ${esc(p.core)}</small></span>${pill(i('menu', 18) + '<span>Menu</span>', 'menu wide', '', 'Menu')}</div>`;
      return `<div class="stage port">
        <div class="pp-top">
          ${band}
          <div class="vp vp-port" style="--ar:${k.aspect(g.id)}"><img src="${k.frame(g.id, 'a')}" alt="${esc(g.title)}, in game"></div>
        </div>
        <div class="pp-bot ctl">
          <div class="pss">${pill('Select', 'ss')}${pill('Start', 'ss')}</div>
          ${dpad(150)}
          <div class="ba"><button class="fbtn big f-b" aria-label="B button">B</button><button class="fbtn big f-a" aria-label="A button">A</button></div>
        </div>
      </div>`;
    },
  };

  // ---------- Pause: a manual page (native sheet) ----------
  const pauseBody = (c) => {
    const g = game('smetroid');
    const pr = D().progress;
    const android = c.os !== 'ios';
    const items = [
      ['save', 'Save state', 'Slot 4 is free'],
      ['layers', 'Load state', '3 states'],
      ['gamepad', 'Controls', 'Touch · 72%'],
      ['fast-forward', 'Fast-forward', 'toggle'],
      ['restart', 'Reset', 'Restarts the game'],
      ['exit', 'Leave game', 'Resume point is kept'],
    ];
    return `<div class="man">
      <p class="man-k"><span>Pause menu</span><span>${esc(plat(g).short)} · ${esc(plat(g).core)}</span></p>
      ${hed('h2', g.title, 'man-hed')}
      <p class="man-meta"><span>Session ${mins(pr.session)}</span><span>${esc(k.hours(g.hours))} in total</span></p>
      ${android ? btn('Resume', { icon: 'play', full: true, cls: 'man-resume' }) : ''}
      <ol class="man-g">${items
        .map(([ic, t, d], x) =>
          d === 'toggle'
            ? `<li class="mg tog"><span class="mg-n">${x + 1}</span><span class="mg-t">${i(ic, 17)}<b>${t}</b></span>${c.sys.toggle(false, t)}</li>`
            : `<li><button class="mg${ic === 'exit' ? ' leave' : ''}"><span class="mg-n">${x + 1}</span><span class="mg-t">${i(ic, 17)}<b>${t}</b></span><small>${d}</small></button></li>`,
        )
        .join('')}</ol>
      <p class="man-st">${stamp(pr.gameSave.remote, { label: 'Game-save data synced' })}${stamp(pr.resume.remote, { label: 'Resume waiting to sync' })}</p>
    </div>`;
  };
  const pausedGame = (c, box, frame = 'a') => {
    const g = game('smetroid');
    // Duo: the paused frame stays exactly where the player draws it (k.stage).
    const place = c.device.duo ? ` style="${at(k.stage(c, { aspect: k.aspect(g.id) }).game)}"` : ` style="--ar:${k.aspect(g.id)}"`;
    return `<div class="paused${c.device.duo ? ' duo-paused' : ''}" style="width:${box.w}px;height:${box.h}px"><span class="vp"${place}><img src="${k.frame(g.id, frame)}" alt="Super Metroid, paused"></span></div>`;
  };
  screens.pause = {
    tone: 'light',
    render: (c) =>
      c.sys.sheet({
        detent: 'medium',
        title: 'Paused',
        close: false,
        done: c.os === 'ios' ? { label: 'Resume', icon: 'play' } : undefined,
        backdrop: (box) => pausedGame(c, box),
        body: pauseBody(c),
      }),
  };

  // ---------- Save states: a contact sheet ----------
  const statesBody = (c) => {
    const g = game('smetroid');
    const pr = D().progress;
    const frame = (s) =>
      s.empty
        ? `<li class="cs empty"><span class="cs-empty"><span class="cs-no">${s.n}</span>${i('plus', 18)}<span>Save state here</span></span><b>Empty slot</b><small>Free on every<br>device</small></li>`
        : `<li class="cs"><span class="cs-f">${snap(g.id, s.frame)}<span class="cs-no">${s.n}</span></span><b>${esc(s.name)}</b><small>${esc(s.device)}<br>${esc(k.ago(s.at))}</small>${stamp(s.remote)}</li>`;
    return `<div class="contact">
      <p class="man-k"><span>Contact sheet</span><span>${esc(g.title)} · ${esc(plat(g).short)}</span></p>
      <div class="cs-key">
        <div class="ck-i"><span class="cs-f">${snap(g.id, pr.resume.frame)}</span><span class="ck-m"><span class="cs-k">${esc(pr.resume.label)}</span><b>${esc(D().sessions[0].note)}</b><small>${esc(pr.resume.device)} · ${esc(k.ago(pr.resume.at))}</small>${stamp(pr.resume.remote)}</span></div>
        <div class="ck-i"><span class="sram"><b>SRAM</b><span>8 KB<br>3 files</span></span><span class="ck-m"><span class="cs-k">${esc(pr.gameSave.label)}</span><b>In-game save</b><small>${esc(pr.gameSave.device)} · ${esc(k.ago(pr.gameSave.at))}</small>${stamp(pr.gameSave.remote)}</span></div>
      </div>
      <ol class="cs-grid">${pr.slots.map(frame).join('')}</ol>
      <div class="hist">
        <div class="hist-h"><h3>Recovery history</h3><span class="hist-a">${btn('Game-save history', { kind: 'line', size: 'xs' })}${btn('State history', { kind: 'line', size: 'xs' })}</span></div>
        <ol>${pr.history.map((h) => `<li><b>${esc(h.kind)}</b><span>${esc(h.device)}</span><span>${esc(h.reason)}</span><span>${esc(k.ago(h.at))}</span></li>`).join('')}</ol>
      </div>
    </div>`;
  };
  screens.states = {
    tone: 'light',
    render: (c) =>
      c.sys.sheet({
        detent: c.device.id === 'duo-open' ? 'pane' : 'large',
        title: 'Save states',
        backdrop: (box) => pausedGame(c, box),
        body: statesBody(c),
      }),
  };

  // ---------- Conflict: the editor's decision ----------
  const conflictBody = (c) => {
    const cf = D().conflict;
    const g = game(cf.game);
    const [a, b] = cf.branches;
    const proof = (x, on) => `<li class="proof${on ? ' on' : ''}">
      <button class="proof-b" role="radio" aria-checked="${on}" aria-label="Continue from ${esc(x.device)}">
        <span class="proof-f">${snap(g.id, x.frame, { alt: `${g.title} at ${x.where}` })}${on ? '<span class="proof-pick">Continues</span>' : ''}</span>
        <span class="proof-dev"><i class="radio${on ? ' on' : ''}"></i>${esc(x.device)}</span>
        <b class="proof-w">${esc(x.where)}</b>
        <span class="proof-d">${esc(k.ago(x.at))} · ${esc(x.reason)}</span>
        <span class="proof-d">${esc(x.playtime)} played</span>
      </button>
    </li>`;
    return `<div class="rp cf">
      ${kick('Editor’s decision', `${esc(g.title)} · ${esc(cf.kind)}`, 'pinkk')}
      ${hed('h1', 'Which version continues?', 'cf-hed')}
      <p class="lede">${esc(a.device)} and ${esc(b.device)} both played EarthBound since the last sync. Pick the one to keep playing from.</p>
      <ul class="proofs" role="radiogroup" aria-label="Choose which continues">${proof(a, true)}${proof(b, false)}</ul>
      <label class="keep">${check(true, 'Keep the other as a recovery copy')}<span><b>Keep the other as a recovery copy</b><small>Restore it any time from Recovery history.</small></span></label>
      <div class="cf-act">${btn(`Continue from ${a.device}`, { full: true, trail: 'arrow-right' })}${c.os !== 'ios' ? btn('Cancel', { kind: 'quiet', full: true }) : ''}<p class="cf-fine">Nothing is deleted. Closing this keeps both until you choose.</p></div>
    </div>`;
  };
  screens.conflict = (c) => {
    const pane = c.device.id === 'duo-open';
    // Duo open: the whole front page (and its vertical bar) stays behind the
    // floating inspector — the decision sits over the page it came from.
    return c.sys.sheet({
      detent: pane ? 'pane' : 'large',
      title: 'Resume conflict',
      backdrop: (box) => (pane ? `<div class="scr-page" style="width:${box.w}px;height:${box.h}px">${screens.home(c)}</div>` : screens.home(c)),
      body: conflictBody(c),
    });
  };

  // ---------- Dual screen (DS) ----------
  screens.dual = {
    tone: 'light',
    render: (c) => {
      const g = game('pkblack');
      const d = k.dual(g.id, 'a');
      const id = c.device.id;
      if (c.device.duo) {
        // Two screens stacked (the top screen above the fold when open), the
        // controls in a band at the bottom. The touch screen stays clear for the
        // stylus: its tag is a margin note beside it, never on it.
        const st = k.stage(c, { dual: true });
        return `<div class="stage duo-st dual-st dev-${id}">
          <div class="ds ds-top" style="${at(st.top)}"><img src="${d.top}" alt="${esc(g.title)}, top screen"></div>
          <div class="ds ds-bot" style="${at(st.touch)}"><img src="${d.bottom}" alt="${esc(g.title)}, touch screen"></div>
          <span class="ds-tag" style="right:${c.w - st.touch.x + 10}px;top:${st.touch.y}px">${i('pointer', 12)}Touch</span>
          <div class="ctl">
            ${pill('L', 'sh sh-l', at(st.l))}${pill('R', 'sh sh-r', at(st.r))}
            ${dpadAt(st)}${diamondAt(st)}
            ${tertAt(st)}
          </div>
          ${toastAt(st.toast)}
        </div>`;
      }
      return `<div class="stage dualst dev-${id}">
        <div class="ds ds-top"><img src="${d.top}" alt="${esc(g.title)}, top screen"></div>
        <div class="ds ds-bot"><img src="${d.bottom}" alt="${esc(g.title)}, touch screen"><span class="ds-tag">${i('pointer', 12)}Touch</span></div>
        <div class="dctl ctl">
          ${pill('L', 'sh sh-l')}${pill('R', 'sh sh-r')}
          ${menuBtn('mid')}
          ${dpad(116)}
          ${diamond(40, 37)}
          <div class="dss">${pill('Select', 'ss')}${pill('Start', 'ss')}</div>
        </div>
      </div>`;
    },
  };

  // ---------------------------------------------------------------------------
  Studio.defineVersion({
    id: 'riso',
    name: 'Riso',
    tagline: 'Your library, edited.',
    thesis:
      'A library of thousands of games needs an editor, not more grids. Rommio edits your own collection like a small independent magazine: a weekly Issue made from what you actually play, a back-of-book Index for everything else, and a Journal that keeps the record of every session. Lead stories, editor’s notes, columns, index entries, captions and a colophon replace cards and shelves — printed in fluorescent riso inks on bright paper, or glowing on black stock.',
    swatch: ['#FF48B0', '#0078BF', '#FBFAF6'],
    fonts: ['Big+Shoulders+Display:wght@700..900', 'Newsreader:ital,opsz,wght@0,6..72,400..700;1,6..72,400..600', 'IBM+Plex+Mono:wght@400;500;600'],
    tabs: [
      { id: 'issue', label: 'Issue', icon: 'newspaper' },
      { id: 'index', label: 'Index', icon: 'list-ordered' },
      { id: 'journal', label: 'Journal', icon: 'pen' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },
    ],
    palette: [
      { name: 'Paper (light)', hex: '#FBFAF6' },
      { name: 'Ink (light)', hex: '#1B1B1B' },
      { name: 'Muted (light)', hex: '#5E5D5A' },
      { name: 'Black stock (dark)', hex: '#121211' },
      { name: 'Paper-white ink (dark)', hex: '#F2F0EA' },
      { name: 'Muted (dark)', hex: '#A5A29B' },
      { name: 'Fluorescent Pink', hex: '#FF48B0' },
      { name: 'Riso Blue · tint', hex: '#0078BF' },
      { name: 'Aqua (blue plate on black)', hex: '#5EC8E5' },
      { name: 'Yellow', hex: '#FFE800' },
      { name: 'Green', hex: '#00A95C' },
      { name: 'Overprint · pink × blue', hex: '#002183' },
    ],
    type: [
      { role: 'Headline', family: 'Big Shoulders Display 900', css: "'Big Shoulders Display', sans-serif; font-weight: 900; text-transform: uppercase", sample: 'SUPER METROID' },
      { role: 'Titles and text', family: 'Newsreader', css: "'Newsreader', serif", sample: 'You left Samus in the Maridia glass tube.' },
      { role: 'Datelines and data', family: 'IBM Plex Mono', css: "'IBM Plex Mono', monospace", sample: 'NO. 39 · 1.2 OF 1.9 GB' },
    ],
    tweaks: [
      { var: '--pink', label: 'Editor’s ink (fills)', type: 'color', value: '#ff48b0' },
      { var: '--plate', label: 'Plate ink (misregistration)', type: 'color', value: '#ff48b0' },
      { var: '--stock', label: 'Paper stock (light)', type: 'color', value: '#fbfaf6' },
      { var: '--mis', label: 'Misregistration', type: 'range', min: 0, max: 6, step: 0.5, value: 2, unit: 'px' },
      { var: '--halftone', label: 'Halftone dot', type: 'range', min: 20, max: 90, step: 5, value: 55, unit: '%' },
      { var: '--yellow', label: 'Highlight ink', type: 'color', value: '#ffe800' },
    ],
    notes: {
      welcome: 'The cover of issue No. 1. The nameplate is the product; three printed plates and a yellow sticker carry the one fact people ask first: no games included. Cover lines state what Rommio does. On Duo open it is one cover: the nameplate runs the full width across the fold, the plates cross it, and the cover lines and actions sit in the rail.',
      connect: 'A form set like a reply card: the live check prints as a proof line under the field, the four access methods are a radio grid rather than a hidden menu, and saved and nearby servers are listed below. On Duo open the headline spans the page, the form runs across the fold and servers plus what Rommio needs sit in the rail.',
      signin: 'The verified server sits on a masthead card with Change beside it, so identity never leaves the page. Password is filled; single sign-on is the alternative, not a second screen. On Duo open the server card runs the full width above the form.',
      hydrate: 'A printer’s checklist with dot leaders: each part of the library is ticked as it lands, artwork shows real progress, and Start playing is available before caching finishes. On Duo open the checklist runs across the fold with how progress travels in the rail.',
      home: 'A front page, not a dashboard. The nameplate and dateline carry the issue number; two ears hold the print queue and offline state as page furniture. The lead story is Super Metroid with its clean resume frame and a one-tap Resume. The EarthBound conflict is the pink Editor’s note — the only thing on the page that asks for a decision. Briefs and the Finish in 2026 column follow. On Duo open it is one broadsheet: the ears flank the nameplate, the dateline rule runs the full width, the lead crosses the fold in eight of twelve columns, and the Editor’s note and briefs sit in the rail.',
      library: 'Contents, not a grid: platforms are numbered chapters in three Parts by support tier (Touch · Needs a controller · Browse only, each with its stamp and reason), set in release order with decade dividers and dot leaders to entry and on-device counts. On Duo open the contents run in three columns across the fold, like the back of a book, so the whole index reads at once.',
      platform: 'A chapter opener — number, part, tier stamp, core — then plates of the most-played games, and the chapter’s index: alphabetical, articles inverted (Link to the Past, A), bold entries on this iPhone, downloads at the end of the line. On Duo open it is the one list → detail: a narrow Contents list, scrolled to the open chapter, beside the wide chapter whose index runs in two columns.',
      collections: 'Collections become Series. Yours come first as numbered series with printed covers; smart collections are Standing orders that print their rule; automatic ones come From the catalogue. On Duo open the four series run across the page and the open series grows out of its card as a band of numbered volumes.',
      game: 'A feature article: printed cover opener, kicker, headline, standfirst and byline; the Where you left off box holds the clean resume frame, both progress states in spec wording, Start with game save and Sync now. Resume is the toolbar’s prominent action. A numbered sidebar is used only where numbers are real — the save state slots. On Duo open the printed cover runs edge to edge across the fold, the headline spans it, and the article reflows into three columns beneath.',
      downloads: 'The print queue: one job printing with a halftone progress bar, numbered jobs up next, a Wi-Fi hold, a failure with Retry, what printed this week, and storage as paper stock. On Duo open the running job is the lead across the fold and the rest of the queue runs in columns.',
      search: 'Search behaves like looking something up in an index: a summary that says how results are ranked, playable entries first with a play button, the chapters and series they appear in, journal mentions, See also suggestions and recent lookups. On Duo open the entries set in two columns across the fold with cross-references in the rail.',
      signature: 'The Journal is Riso’s own surface: every session becomes a diary entry — time in the margin, the game, “42 min on This iPhone — Maridia glass tube”, a printed thumbnail and the outcome in spec wording with its sync stamp. The month in ink summarises D.stats as a halftone strip. On Duo open the month in ink runs the full width as a halftone strip and the diary is set in three columns that flow across the fold.',
      settings: 'The Colophon: who printed this edition (server, account, version, access, sync state) set like the back page of a magazine, then other editions (Sam’s library), sync, player, cores with the two that need attention spelled out, storage and about. On Duo open the imprint is a band across the page with the settings in three columns beneath.',
      controls: 'Tuning with a live preview printed as a control card; the connected DualSense is stated in words. Sliders are printed rulers. On the Duo the preview is the real Duo player in miniature (the card when closed; a full-width proof when open, with the settings floating over the middle of the frame and stopping above Select · Menu · Start, so both rails and the tertiary row stay visible as they change).',
      player: 'OLED-black stock, the game untouched. Controls are flat fluorescent ink shapes with a 2px off-register plate; the yellow pivot overprints the pink d-pad. On the Duo (open and closed) the 4:3 frame steps in between two rails (across the fold when open) and nothing prints over it, so the inks stay full: d-pad and diamond low in the rails where thumbs rest, L/R just above them, Select · Menu · Start under the frame, the notice above it and a printer’s slug in the left rail.',
      'player-portrait': 'Game above, controls below, Menu in the title band. On the Duo the frame runs full width at the top (across the fold when open) and the controls live in the band beneath it, low: d-pad and B/A, Select · Menu · Start at the foot, the notice between them and the frame, the printer’s slug in the margin.',
      pause: 'A native sheet set as a manual page: numbered items, Leave game always visible, and the truthful footer — Game-save data synced · Resume waiting to sync. On Duo open it floats as an inspector over the whole paused game, drawn exactly where the player draws it.',
      states: 'A contact sheet: the automatic resume and game-save data are their own items at the top, then numbered slot frames with device, time and sync, the empty slot 4, and recovery history with both histories one tap away. On Duo open it floats over the whole paused game, drawn exactly where the player draws it.',
      conflict: 'An editor’s decision between two proofs of the same game. The chosen proof is stamped Continues; the other is kept as a recovery copy by default. On Duo open the decision floats over the whole front page it came from.',
      dual: 'Top screen above the touch screen, always stacked, controls printed below. On the Duo both screens stay clean for the stylus (top screen above the fold when open): the controls print in a band at the bottom — shoulders above the clusters, d-pad and diamond low at the edges, Select · Menu · Start between them — and the Touch tag is a margin note beside the lower screen.',
    },
    screens,
  });
})();
