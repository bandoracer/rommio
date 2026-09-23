/*
 * VOID (round 2) — "One thing at a time."
 *
 * DESIGN PLAN
 * ---------------------------------------------------------------------------
 * Thesis    Rommio is a quiet, text-first instrument for getting back into a
 *           game. It assumes you are continuing something, not shopping, so
 *           every surface is organised by time and intent: what you are
 *           playing now, what is next, and what happened. Type, alignment and
 *           whitespace carry the structure. Art appears only where it carries
 *           meaning: the frame you will return to, or the state you are about
 *           to load. Everything else is words, numbers and small marks.
 *
 * IA        Native tabs: Now · Library · Timeline · Search. The account
 *           avatar on every tab opens Settings.
 *           Now        the resume frame at its true aspect with Resume; an
 *                      Up next queue (numbered, reorderable, each row says
 *                      *how* you will get back in); the EarthBound conflict as
 *                      one calm sentence with Choose; a status line for
 *                      online/offline state and the download queue (opens the
 *                      Downloads sheet).
 *           Library    one A–Z index with hanging letters and a letter
 *                      scrubber. Rows are annotated with your progress (where
 *                      you are, when), so games in play read as two lines and
 *                      the rest as one. Scope and platform tokens filter.
 *                      Install state and support tier are marks in a right
 *                      column. Collections from the toolbar.
 *             Platform one platform ordered by *last played* (Today, This
 *                      week, This month, Earlier, Never played).
 *             Collections  named lists read as queues: a stop-by-stop rail
 *                      shows how far through each list you are and what is
 *                      next. Up next is the first list.
 *             Game     a document: title, the progress history for this game
 *                      (automatic resume, game-save data, states), then file
 *                      and core, then about. Resume is the toolbar action.
 *           Timeline   (signature) a version history of your progress across
 *                      devices: every checkpoint (resume points, save states,
 *                      game-save data) and every transfer on one spine, newest
 *                      first, each restorable in place. The EarthBound
 *                      conflict appears as a fork. On Duo open a wide detail
 *                      beside the spine previews the selected checkpoint and
 *                      which devices hold it.
 *           Search     ranked, explained results (why each matched), what can
 *                      play now, refine tokens and recent searches.
 *           Sheets     Downloads (from the Now status line / Timeline),
 *                      Conflict, Pause, Save states.
 *           Pushed     Settings (avatar) → Player & controls.
 *
 * Palette   LIGHT — paper and graphite
 *             Paper #F7F7F5 · Graphite #17171A · Graphite 2 #5A5A62 (6.4:1)
 *             Graphite 3 #6F6F77 (4.6:1) · Hairline 11% graphite
 *             Iris #3A4BC8 — the one cool tint: next action, selection,
 *             live state (6.5:1 on paper, white label 6.9:1)
 *             Signal #C23D2A — conflict and failure only (4.9:1)
 *           DARK — pure OLED
 *             Void #000000 · Ink #F4F4F5 · Ink 2 #A3A3AA · Ink 3 #7C7C84 (5:1)
 *             Hairline 11% white · Mist #B9C6FF (content tint) ·
 *             Iris #5565EC (system tint; white label 4.7:1) ·
 *             Signal #FF8A7A
 *           PLAYER — #000 in both appearances; controls are Mist hairlines
 *             with a 9% Mist fill; tertiary controls at 60%.
 *
 * Type      Geist for everything you read (titles 600–700 at −0.035em, body
 *           15–17). Geist Mono for everything you measure: times, sizes,
 *           counts, sync state, section labels. No third face.
 *
 * Components  status line · resume frame · Up next queue row · decision line
 *           · scope/platform token rail · hanging-letter index row with a
 *           mark column · letter scrubber · checkpoint row on a spine (node
 *           shape = kind: ● resume point, ◆ save state, ■ game-save data,
 *           · transfer) · fork · device holdings · list rail (stops) ·
 *           definition rows · hairline slider · sync mark (● synced,
 *           ○ waiting, ◐ syncing, red ● conflict, – local only).
 *
 * Duo       Closed: the iPhone structure at 394pt beside the vertical bar.
 *           Open: one canvas, not two halves — the fold is a crease. Pages
 *           reflow wider on a shared background (`cv`): Now puts the resume
 *           frame across the fold with its caption beside it and Up next as
 *           a row of four; Game is a document with the frame across the
 *           fold, the progress history as a horizontal spine and columns
 *           beneath; Search is one wide ranked table; Settings is three
 *           columns under a full-width account band; onboarding is one
 *           composition per step. The native split is kept only where a
 *           list selects a detail, as a narrow list beside a wide detail:
 *           Library index → quick look, Platforms → platform, Collections →
 *           list, Timeline → checkpoint preview. Sheets float as an
 *           inspector over the full-screen page or game.
 *
 * Player    Pure black in both appearances. Controls are hairline shapes
 *           with a faint Mist fill and mono legends; Menu/Select/Start sit
 *           dimmer. The game is never covered. On phones it sits between
 *           rails. On Duo (open and closed) every player takes k.stage's
 *           geometry: the game drawn exactly at its rect (across the fold
 *           when open), the d-pad and buttons low in the rails where thumbs
 *           rest, L/R just above them, Select · Menu · Start clear of the
 *           game and the resume note in free space — so no control needs an
 *           underlay. Pause and Save states are native sheets that keep the
 *           player's night appearance over the paused game, drawn where the
 *           player draws it (a floating inspector on Duo open).
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = window.StudioData;

  /* ============================== time ============================== */
  const NOW = 9 * 60 + 41; // Wednesday 23 September 2026, 9:41
  const WD = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const MO = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const hm = (t) => {
    const m = ((Math.round(t) % 1440) + 1440) % 1440;
    return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`;
  };
  const when = (ago) => {
    const t = NOW - Math.round(ago);
    return { day: t >= 0 ? 0 : Math.ceil(-t / 1440), time: hm(t) };
  };
  const dayHead = (d) => {
    const dt = new Date(2026, 8, 23 - d);
    const wd = WD[dt.getDay()];
    const dm = `${dt.getDate()} ${MO[dt.getMonth()]}`;
    if (d === 0) return { name: 'Today', date: `${wd} ${dm}` };
    if (d === 1) return { name: 'Yesterday', date: `${wd} ${dm}` };
    return { name: wd, date: dm };
  };

  /* ============================== atoms ============================== */
  const MK = { synced: 'ok', syncing: 'live', running: 'live', pending: 'wait', waiting: 'wait', conflict: 'bad', failed: 'bad', local: 'local' };
  const mk = (s) => `<i class="mk ${MK[s] || 'none'}" aria-hidden="true"></i>`;
  const sy = (s, label) => `<span class="sy">${mk(s)}<span>${esc(label || k.syncLabel(s))}</span></span>`;
  const im = (on) => `<i class="im ${on ? 'on' : 'off'}" aria-hidden="true"></i>`;
  const fr = (id, v = 'a', cls = '') => `<img class="fr ${cls}" src="${k.frame(id, v)}" alt="" style="aspect-ratio:${k.aspect(id)}">`;
  const hd = (label, right = '') => `<h2 class="hd"><span>${esc(label)}</span>${right}</h2>`;
  const tb = (label, cls = '') => `<button class="tb ${cls}">${esc(label)}</button>`;
  const tok = (label, n, on, extra = '') => `<button class="tok${on ? ' on' : ''}"${on ? ' aria-pressed="true"' : ''}>${extra}${esc(label)}${n != null ? `<sup>${esc(n)}</sup>` : ''}</button>`;
  const chev = () => i('chevron-right', 16, 'chev');
  const ttl = (t, sub) => `<header class="ttl"><h1>${esc(t)}</h1>${sub ? `<p>${sub}</p>` : ''}</header>`;
  const sub = (html) => `<p class="lede-s">${html}</p>`;
  const wordmark = () => `<span class="wm"><i class="wm-m" aria-hidden="true"></i>rommio</span>`;
  const open = (c) => c.size === 'regular';
  const account = { avatar: 'R', label: 'Account and settings' };
  // Duo open is one canvas: `cv` widens the margins and lets a screen reflow across the fold.
  const cv = (html, cls = '') => `<div class="cv ${cls}">${html}</div>`;
  const row = (title, subline, acc = '', cls = '') => `<li class="set ${cls}"><div class="set-m"><b>${title}</b>${subline ? `<span>${subline}</span>` : ''}</div>${acc}</li>`;
  const dl = (pairs) => `<dl class="dfl">${pairs.map(([a, b]) => `<div><dt>${esc(a)}</dt><dd>${b}</dd></div>`).join('')}</dl>`;

  /* ============================== data views ============================== */
  const T = k.totals();
  const sortKey = (t) => t.replace(/^The /, '');
  const byTitle = [...D.games].sort((a, b) => sortKey(a.title).localeCompare(sortKey(b.title)));
  const latest = (id) => D.moments.filter((m) => m.game === id).sort((a, b) => a.at - b.at)[0];
  // Where you are in a game and when you were last there.
  const placeOf = (id) => {
    const g = k.game(id);
    const m = latest(id);
    if (m) return { name: m.name, at: m.at, device: m.device, kind: m.kind, remote: m.remote };
    const s = D.sessions.find((x) => x.game === id);
    if (s && g.played != null) return { name: s.note, at: g.played, device: s.device, kind: s.ended };
    return null;
  };
  const conflictGame = D.conflict.game;
  const IN_PROGRESS = D.games.filter((g) => placeOf(g.id)).length;
  const run = D.downloads.find((d) => d.state === 'running');
  const count = (st) => D.downloads.filter((d) => d.state === st).length;
  const storageParts = () => {
    const s = D.storage;
    return [
      ['ROMs', s.roms, 'rom'],
      ['Save states', s.states, 'prog'],
      ['Game saves', s.saves, 'prog'],
      ['Artwork & cache', s.cache, 'misc'],
      ['Cores', s.cores, 'misc'],
      ['BIOS', s.bios, 'misc'],
    ];
  };
  const used = () => storageParts().reduce((n, p) => n + p[1], 0);

  /* ============================== Now pieces ============================== */
  const HERO = D.moments[0]; // Super Metroid · automatic resume · Maridia glass tube

  const statusLine = () => `
    <button class="stl" aria-label="Connection and downloads">
      <span class="stl-r">${mk('synced')}<span>Online · synced ${esc(k.ago(D.profiles[0].lastSync))} · ${T.installed} games ready offline</span></span>
      <span class="stl-r">${i('downloads', 13)}<span>Downloading <b>${esc(k.game(run.game).title)}</b> · ${k.pct(run.progress)} · ${esc(run.eta)}</span><em>${count('queued') + count('waiting') + count('failed')} more</em>${i('chevron-right', 13)}</span>
      <span class="stl-bar" style="--p:${Math.round(run.progress * 100)}%" aria-hidden="true"></span>
    </button>`;

  const resumeBlock = (c) => {
    const g = k.game(HERO.game);
    return `<section class="hero" aria-label="Continue ${esc(g.title)}">
      <div class="hero-fr">${fr(g.id, HERO.frame)}</div>
      <div class="hero-cap">
        <div class="hero-t"><h2>${esc(g.title)}</h2><p>${esc(HERO.name)} · ${esc(k.ago(HERO.at))}</p></div>
        ${c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'md' })}
      </div>
      <div class="hero-meta">${sy(HERO.remote, 'Resume waiting to sync')}${tb('Start with game save')}</div>
    </section>`;
  };

  const decisionLine = (c) => {
    const [a, b] = D.conflict.branches;
    return `<div class="dec" role="group" aria-label="EarthBound needs a decision">
      ${mk('conflict')}
      <p><b>EarthBound</b> has two resume points, on ${esc(a.device)} and ${esc(b.device)}.</p>
      ${c.sys.button('Choose', { style: 'bordered', size: 'sm' })}
    </div>`;
  };

  const QUEUE = [
    { id: 'linksawak', how: 'Resume', m: D.moments.find((m) => m.game === 'linksawak') },
    { id: 'metfusion', how: 'Resume', m: D.moments.find((m) => m.game === 'metfusion') },
    { id: 'chrono', how: 'State on iPad', m: D.moments.find((m) => m.game === 'chrono'), note: 'waiting to sync' },
    { id: 'pkblack', how: 'Start with game save', s: D.sessions.find((s) => s.game === 'pkblack') },
  ];
  const queueRow = (q, n) => {
    const g = k.game(q.id);
    const where = q.m ? q.m.name : q.s.note;
    const ago = q.m ? k.ago(q.m.at) : k.ago(g.played);
    const tail = q.note ? `${mk('waiting')}${esc(q.note)}` : esc(ago);
    return `<li class="q">
      <span class="q-n">${String(n + 1).padStart(2, '0')}</span>
      <div class="q-m"><b>${esc(g.title)}</b><span><em>${esc(q.how)}</em> · ${esc(where)} · ${tail}</span></div>
      <span class="q-g" aria-label="Reorder ${esc(g.title)}">${i('grip', 18)}</span>
    </li>`;
  };
  const queue = (c, withHead = true) => `
    ${withHead ? hd('Up next', `<button class="hd-a">Edit</button>`) : ''}
    <ol class="qs">${QUEUE.map(queueRow).join('')}</ol>`;

  /* ============================== Library pieces ============================== */
  const platformRail = (sel, wrap = false) => {
    const touch = k.tier('touch');
    const ctl = k.tier('controller');
    const bo = k.tier('unsupported');
    return `<div class="rail${wrap ? ' wrap' : ''}"><div class="rail-in">
      ${tok('All', k.num(T.games), sel === 'all')}${tok('In progress', IN_PROGRESS, sel === 'prog')}${tok('On this iPhone', T.installed, sel === 'inst')}
      <i class="rail-sep" aria-hidden="true"></i>
      ${touch.map((p) => tok(p.short, p.games, sel === p.id)).join('')}
      <i class="rail-sep" aria-hidden="true"></i>
      ${ctl.map((p) => tok(p.short, p.games, sel === p.id, i('gamepad', 13))).join('')}
      ${bo.map((p) => `<button class="tok bo">${esc(p.short)}<sup>${p.games}</sup></button>`).join('')}
    </div></div>`;
  };
  const legend = () =>
    `<p class="lgd" aria-label="Legend"><span>${im(true)}On this iPhone</span><span>${im(false)}Not downloaded</span><span>${i('gamepad', 13)}Controller only</span></p>`;

  const ixRow = (g, sel) => {
    const p = k.platformOf(g);
    const bo = p.tier === 'unsupported';
    const pl = placeOf(g.id);
    let line = '';
    if (g.id === conflictGame) line = `<span class="ix-s bad">${mk('conflict')}Two resume points · choose one</span>`;
    else if (pl) line = `<span class="ix-s">${esc(pl.name)} · ${esc(k.ago(pl.at))}</span>`;
    else if (bo) line = `<span class="ix-s">${esc(p.blocked.replace(/^Browse only — /, ''))}</span>`;
    return `<li class="ix${bo ? ' bo' : ''}${line ? ' two' : ''}${sel === g.id ? ' sel' : ''}">
      <span class="ix-m"><b>${esc(g.title)}</b>${line}</span>
      <span class="ix-p">${esc(p.short)}${p.tier === 'controller' ? i('gamepad', 13) : ''}</span>
      ${bo ? '<span class="ix-bo">Browse only</span>' : im(g.installed)}
    </li>`;
  };
  const index = (list, sel) => {
    const groups = [];
    for (const g of list) {
      const L = sortKey(g.title)[0].toUpperCase();
      let grp = groups[groups.length - 1];
      if (!grp || grp.L !== L) groups.push((grp = { L, items: [] }));
      grp.items.push(g);
    }
    return `<div class="ixs">${groups.map((grp) => `<section class="ixg"><b class="ixg-l">${grp.L}</b><ul>${grp.items.map((g) => ixRow(g, sel)).join('')}</ul></section>`).join('')}</div>`;
  };
  const scrubber = (on) => {
    const have = new Set(byTitle.map((g) => sortKey(g.title)[0].toUpperCase()));
    return `<nav class="scrub" aria-label="Jump to letter">${[...'ABCDEFGHIJKLMNOPQRSTUVWXYZ']
      .map((L) => `<span class="${L === on ? 'on' : ''}${have.has(L) ? '' : ' no'}">${L}</span>`)
      .join('')}<span class="no">#</span></nav>`;
  };

  // Game quick look: the wide detail beside the Library index on Duo open. The frame and the way
  // back in sit side by side so progress and file stay in view.
  const quickLook = (c, id) => {
    const g = k.game(id);
    const p = k.platformOf(g);
    const pr = D.progress;
    return `<div class="ql">
      <p class="eyebrow">${esc(p.short)} · ${g.year} · ${esc(g.publisher)}</p>
      <h2 class="ql-t">${esc(g.title)}</h2>
      <div class="ql-top">
        <div class="ql-fr">${fr(g.id, HERO.frame)}</div>
        <div class="ql-side">
          <p class="ql-w"><b>${esc(HERO.name)}</b>${esc(k.ago(HERO.at))} on ${esc(HERO.device)}</p>
          <p>${sy(HERO.remote, 'Resume waiting to sync')}</p>
          <div class="ql-a">${c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'md' })}${c.sys.button('Open', { style: 'glass', size: 'md' })}</div>
        </div>
      </div>
      ${hd('Progress')}
      <ul class="sets tight">
        ${row('Automatic resume', `${esc(k.ago(pr.resume.at))} · ${esc(pr.resume.device)}`, sy(pr.resume.remote))}
        ${row('Game-save data', `${esc(k.ago(pr.gameSave.at))} · ${esc(pr.gameSave.detail)}`, sy(pr.gameSave.remote))}
        ${row('Save states', `3 of 4 slots · latest ${esc(k.ago(42))}`, sy('syncing', 'Syncing'))}
      </ul>
      ${hd('File')}
      ${dl([
        ['File', 'Super Metroid (USA).sfc'],
        ['Size', `${esc(k.size(g.size))} · on this iPhone`],
        ['Core', `${esc(p.core)} · ready · Touch`],
      ])}
    </div>`;
  };

  const platformList = (sel) => {
    const rowP = (p) =>
      `<li class="pl${sel === p.id ? ' sel' : ''}${p.tier === 'unsupported' ? ' bo' : ''}"><span class="pl-m"><b>${esc(p.name)}</b><span>${p.tier === 'unsupported' ? esc(p.blocked.replace(/^Browse only — /, '')) : `${esc(p.core)} · ${p.installed} on this iPhone`}</span></span><span class="pl-n">${p.games}</span></li>`;
    return `<div class="pls">
      <ul class="pl-scope">${['All games', 'In progress', 'On this iPhone'].map((l, n) => `<li class="pl"><span class="pl-m"><b>${l}</b></span><span class="pl-n">${[k.num(T.games), IN_PROGRESS, T.installed][n]}</span></li>`).join('')}</ul>
      ${hd('Touch', `<span>${k.tier('touch').length}</span>`)}<ul>${k.tier('touch').map(rowP).join('')}</ul>
      ${hd('Controller', `<span>Pair a controller</span>`)}<ul>${k.tier('controller').map(rowP).join('')}</ul>
      ${hd('Browse only')}<ul>${k.tier('unsupported').map(rowP).join('')}</ul>
    </div>`;
  };

  // A platform ordered by last played.
  const platformSlice = (c, pid) => {
    const p = k.platform(pid);
    const last = (g) => (placeOf(g.id) ? placeOf(g.id).at : g.played);
    const games = k.gamesOn(pid).sort((a, b) => (last(a) ?? 1e12) - (last(b) ?? 1e12));
    const buckets = [
      ['Today', (g) => last(g) != null && last(g) < 1440],
      ['This week', (g) => last(g) != null && last(g) >= 1440 && last(g) < 1440 * 7],
      ['This month', (g) => last(g) != null && last(g) >= 1440 * 7 && last(g) < 1440 * 30],
      ['Earlier', (g) => last(g) != null && last(g) >= 1440 * 30],
      ['Never played', (g) => last(g) == null],
    ];
    const r = (g) => {
      const pl = placeOf(g.id);
      const right = last(g) != null ? `<span class="ps-t">${esc(k.ago(last(g)))}</span>` : `<span class="ps-t">${esc(k.size(g.size))}</span>`;
      const line = g.id === conflictGame ? `<span class="ix-s bad">${mk('conflict')}Two resume points · choose one</span>` : pl ? `<span class="ix-s">${esc(pl.name)}</span>` : '';
      return `<li class="ix${line ? ' two' : ''}"><span class="ix-m"><b>${esc(g.title)}</b>${line}</span>${right}${im(g.installed)}</li>`;
    };
    return `
      <p class="facts">${p.games} games · ${p.installed} on this iPhone · ${esc(p.core)} · ${esc(k.tierLabel(p.tier))}</p>
      <div class="rail"><div class="rail-in">${tok('All', p.games, true)}${tok('On this iPhone', p.installed)}${tok('Not downloaded', p.games - p.installed)}${tok('Favourites', null, false, i('heart', 13))}</div></div>
      <p class="sortline">${i('sort', 13)}Ordered by last played</p>
      ${buckets
        .map(([label, f]) => {
          const list = games.filter(f);
          if (label === 'Never played') list.sort((a, b) => sortKey(a.title).localeCompare(sortKey(b.title)));
          if (!list.length) return '';
          return `<section class="bk">${hd(label, `<span>${label === 'Never played' ? 'A–Z' : list.length}</span>`)}<ul class="bk-l">${list.map(r).join('')}</ul></section>`;
        })
        .join('')}`;
  };

  /* ============================== Collections pieces ============================== */
  const stops = (ids) =>
    `<ol class="stops" aria-hidden="true">${ids
      .map((id) => {
        const g = k.game(id);
        return `<li class="${g.hours > 0 ? 'on' : ''}${g.installed ? '' : ' far'}"></li>`;
      })
      .join('')}</ol>`;
  const colBlock = (col, n, sel) => {
    const gs = col.games.map(k.game);
    const played = gs.filter((g) => g.hours > 0).length;
    const inst = gs.filter((g) => g.installed).length;
    const next = gs.find((g) => !(g.hours > 0));
    return `<li class="cb${sel === col.id ? ' sel' : ''}">
      <div class="cb-h"><span class="cb-n">${String(n + 1).padStart(2, '0')}</span><b>${esc(col.name)}</b><span class="cb-c">${gs.length}</span></div>
      <p class="cb-note">${esc(col.note)}</p>
      ${stops(col.games)}
      <p class="cb-meta"><span>${played} of ${gs.length} started · ${inst} on this iPhone</span>${next ? `<span>Next: <b>${esc(next.title)}</b></span>` : ''}</p>
    </li>`;
  };
  const ruleRow = (col) => `<li class="rr"><div><b>${esc(col.name)}</b><span>${esc(col.rule)}</span></div><span class="rr-n">${col.games.length}</span>${chev()}</li>`;

  /* ============================== Timeline pieces ============================== */
  // One stream of checkpoints and transfers, newest first.
  const STREAM = (() => {
    const out = [];
    for (const m of D.moments) {
      if (m.game === conflictGame && m.device === 'This iPhone') out.push({ type: 'fork', at: m.at, m });
      else out.push({ type: 'cp', at: m.at, m });
    }
    // Game-save data synced for Super Metroid is a checkpoint of its own.
    const save = D.activity.find((a) => a.kind === 'save');
    out.push({ type: 'cp', at: save.at, m: { game: 'smetroid', kind: 'save', name: D.progress.gameSave.detail, at: save.at, device: 'This iPhone', remote: 'synced' } });
    for (const a of D.activity) if (a.kind === 'download' || a.kind === 'failed') out.push({ type: 'ev', at: a.at, a });
    return out.sort((a, b) => a.at - b.at);
  })();
  const KIND = { resume: 'Automatic resume', state: 'Save state', save: 'Game-save data' };
  const cpLabel = (m) => (m.kind === 'state' ? `Save state · Slot ${m.slot}` : KIND[m.kind]);
  // Can this checkpoint be restored on this device right now?
  const restorable = (m) => m.remote !== 'conflict' && !(m.device !== 'This iPhone' && m.remote === 'waiting');
  const goLabel = (m) => (m.kind === 'resume' ? 'Resume from here' : m.kind === 'state' ? `Load state ${m.slot}` : 'Start with game save');
  // A checkpoint's frame with its restore action on it.
  const thumb = (id, m, cls = '') =>
    `<span class="th ${cls}">${fr(id, m.frame)}${restorable(m) ? `<button class="th-go" aria-label="${esc(goLabel(m))}">${i('play', 11)}</button>` : ''}</span>`;
  const restoreNote = (m) => {
    if (m.remote === 'conflict') return `<p class="tl-act"><span class="tl-na">Decide on Now to continue</span></p>`;
    if (!restorable(m)) return `<p class="tl-act"><span class="tl-na">Not on this iPhone yet</span></p>`;
    if (m.kind === 'save') return `<p class="tl-act">${tb('Start with game save')}</p>`;
    return '';
  };
  const tlRow = (e, opts = {}) => {
    const w = when(e.at);
    const time = e.at < 1 ? 'now' : w.time;
    if (e.type === 'ev') {
      const a = e.a;
      const failed = a.kind === 'failed';
      const title = failed ? 'Download failed' : a.state === 'running' ? 'Downloading' : 'Installed';
      const game = a.text.replace(/^(Downloading|Installed|Download failed · )\s*/, '');
      return `<li class="tl ev${failed ? ' bad' : ''}"><time>${time}</time><i class="nd ${failed ? 'n-bad' : a.state === 'running' ? 'n-live' : 'n-ev'}"></i>
        <div class="tl-b"><p class="tl-e"><span>${esc(title)} · <b>${esc(game)}</b></span>${failed ? tb('Retry') : ''}</p><p class="tl-m">${esc(a.detail)}${a.state === 'running' ? ` · ${run.rate} MB/s · ${esc(run.eta)} left` : ''}</p>
        ${a.state === 'running' ? `<span class="tl-bar" style="--p:${Math.round(run.progress * 100)}%"></span>` : ''}</div></li>`;
    }
    if (e.type === 'fork') {
      const [a, b] = D.conflict.branches;
      const g = k.game(conflictGame);
      return `<li class="tl frk"><time>${time}</time><i class="nd n-bad"></i>
        <div class="tl-b"><p class="tl-h"><b>${esc(g.title)}</b></p><p class="tl-k bad">Two resume points · Conflict</p>
          <div class="fk"><div class="fk-b">${fr(g.id, a.frame)}<span><b>${esc(a.device)}</b>${esc(a.where)}</span></div><i class="fk-or">or</i><div class="fk-b">${fr(g.id, b.frame)}<span><b>${esc(b.device)}</b>${esc(b.where)}</span></div></div>
          <p class="tl-act">${tb('Choose which continues')}</p></div></li>`;
    }
    const m = e.m;
    const g = k.game(m.game);
    const hasFrame = m.kind !== 'save' && opts.frames !== false;
    const cls = { resume: 'n-res', state: 'n-st', save: 'n-sv' }[m.kind];
    return `<li class="tl cp${opts.sel === e ? ' sel' : ''}${m.remote === 'conflict' ? ' cf' : ''}"><time>${time}</time><i class="nd ${cls}"></i>
      <div class="tl-b${hasFrame ? ' wf' : ''}">
        <div class="tl-x"><p class="tl-h"><b>${esc(g.title)}</b></p>
        <p class="tl-k">${esc(cpLabel(m))} · ${esc(m.name)}</p>
        <p class="tl-m">${esc(m.device)} · ${sy(m.remote)}</p>
        ${restoreNote(m)}</div>
        ${hasFrame ? thumb(g.id, m, 'tl-f') : ''}
      </div></li>`;
  };
  const timeline = (opts = {}) => {
    const days = [];
    for (const e of STREAM) {
      const d = when(e.at).day;
      if (opts.maxDay != null && d > opts.maxDay) continue;
      let grp = days[days.length - 1];
      if (!grp || grp.d !== d) days.push((grp = { d, items: [] }));
      grp.items.push(e);
    }
    return days
      .map((grp) => {
        const h = dayHead(grp.d);
        return `<section class="tld"><h3 class="tld-h"><b>${esc(h.name)}</b><span>${esc(h.date)}</span></h3><ol class="tls">${grp.items.map((e) => tlRow(e, opts)).join('')}</ol></section>`;
      })
      .join('');
  };

  /* ============================== Player pieces ============================== */
  function plusPath(s, a, r, ri) {
    const c = s / 2;
    const h = a / 2;
    return [
      `M${c - h + r},0`, `H${c + h - r}`, `A${r},${r} 0 0 1 ${c + h},${r}`, `V${c - h - ri}`, `A${ri},${ri} 0 0 0 ${c + h + ri},${c - h}`,
      `H${s - r}`, `A${r},${r} 0 0 1 ${s},${c - h + r}`, `V${c + h - r}`, `A${r},${r} 0 0 1 ${s - r},${c + h}`, `H${c + h + ri}`,
      `A${ri},${ri} 0 0 0 ${c + h},${c + h + ri}`, `V${s - r}`, `A${r},${r} 0 0 1 ${c + h - r},${s}`, `H${c - h + r}`, `A${r},${r} 0 0 1 ${c - h},${s - r}`,
      `V${c + h + ri}`, `A${ri},${ri} 0 0 0 ${c - h - ri},${c + h}`, `H${r}`, `A${r},${r} 0 0 1 0,${c + h - r}`, `V${c - h + r}`, `A${r},${r} 0 0 1 ${r},${c - h}`,
      `H${c - h - ri}`, `A${ri},${ri} 0 0 0 ${c - h},${c - h - ri}`, `V${r}`, `A${r},${r} 0 0 1 ${c - h + r},0`, 'Z',
    ].join(' ');
  }
  const R = Math.round;
  const dpad = (size, x, y) => {
    const a = size * 0.36;
    const c = size / 2;
    const t = size * 0.065;
    const chevron = (px, py, rot) => `<path d="M${px - t},${py + t * 0.55} L${px},${py - t * 0.55} L${px + t},${py + t * 0.55}" transform="rotate(${rot} ${px} ${py})"/>`;
    const plus = plusPath(size, a, size * 0.075, size * 0.05);
    return `<div class="dp" style="left:${R(x)}px;top:${R(y)}px;width:${R(size)}px;height:${R(size)}px" role="group" aria-label="Directional pad"><svg viewBox="-1 -1 ${size + 2} ${size + 2}" width="${R(size)}" height="${R(size)}"><path class="dp-u" d="${plus}"/><path class="dp-b" d="${plus}"/><g class="dp-a">${chevron(c, size * 0.15, 0)}${chevron(size * 0.85, c, 90)}${chevron(c, size * 0.85, 180)}${chevron(size * 0.15, c, 270)}</g><circle class="dp-h" cx="${c}" cy="${c}" r="${size * 0.055}"/></svg></div>`;
  };
  const face = (label, x, y, d) => `<button class="fb" style="left:${R(x)}px;top:${R(y)}px;width:${R(d)}px;height:${R(d)}px;font-size:${R(d * 0.3)}px" aria-label="${label} button">${label}</button>`;
  // Nintendo diamond centred on (cx, cy): Y left, X top, B bottom, A right.
  const diamond = (cx, cy, size, d) => {
    const x0 = cx - size / 2;
    const y0 = cy - size / 2;
    const o = (size - d) / 2;
    return [
      ['Y', 0, o],
      ['X', o, 0],
      ['B', o, size - d],
      ['A', size - d, o],
    ]
      .map(([l, x, y]) => face(l, x0 + x, y0 + y, d))
      .join('');
  };
  const pill = (label, x, y, w, cls = '') => `<button class="pc ${cls}" style="left:${R(x)}px;top:${R(y)}px;width:${R(w)}px" aria-label="${label}">${label === 'Menu' ? i('more', 18) : esc(label)}</button>`;
  // A shoulder filling its rect (Duo: k.stage's l / r); `h` may trim it from the bottom.
  const shoulder = (label, r, h = r.h, cls = '') => `<button class="pc sh ${cls}" style="left:${R(r.x)}px;top:${R(r.y)}px;width:${R(r.w)}px;height:${R(h)}px;font-size:${R(Math.min(16, h * 0.36))}px" aria-label="${label} shoulder">${esc(label)}</button>`;
  const viewport = (src, x, y, w, h, alt, cls = '') => `<img class="vp ${cls}" src="${src}" alt="${esc(alt)}" style="left:${R(x)}px;top:${R(y)}px;width:${R(w)}px;height:${R(h)}px">`;
  const toast = (x, y, text, cls = '') => `<div class="toast ${cls}" style="left:${R(x)}px;top:${R(y)}px" role="status">${i('check', 13)}<span>${esc(text)}</span></div>`;
  // A notice centred on a point (Duo: k.stage's toast). `wrap` sets its last word on a second
  // line so it fits a narrow gap and still hugs its text.
  const toastAt = (p, text, cls = '', wrap = false) => {
    const words = esc(text).split(' ');
    const body = wrap ? `${words.slice(0, -1).join(' ')}<br>${words[words.length - 1]}` : words.join(' ');
    return `<div class="toast at ${cls}${wrap ? ' wrap' : ''}" style="left:${R(p.cx)}px;top:${R(p.cy)}px" role="status">${i('check', 13)}<span>${body}</span></div>`;
  };

  // Landscape SNES on phones: the game centred between side rails, never covered.
  function landscapeScene(W, H, safe, opts = {}) {
    const g = 'smetroid';
    const ar = k.aspect(g);
    const src = k.frame(g, 'a');
    let out = '';
    const inner = W < 760 ? 124 : 136;
    const r = Math.max(safe.left, safe.right) + inner;
    const vw = Math.min((H - 24) * ar, W - 2 * r);
    const vh = vw / ar;
    const vx = (W - vw) / 2;
    const vy = (H - vh) / 2;
    const lc = (safe.left + vx) / 2;
    const rc = (vx + vw + W - safe.right) / 2;
    const ds = inner - 18;
    const dm = inner - 12;
    out += viewport(src, vx, vy, vw, vh, 'Super Metroid');
    if (opts.toast !== false) out += toast(lc, 66, 'Automatic resume updated', 'rail');
    out += `<div class="ctl">`;
    out += pill('L', lc - 44, 16, 88, 'sh') + pill('R', rc - 44, 16, 88, 'sh');
    out += dpad(ds, lc - ds / 2, H / 2 - ds / 2 + 10);
    out += diamond(rc, H / 2 + 10, dm, R(dm * 0.37));
    out += pill('Menu', lc - 26, H - safe.bottom - 40, 52, 'tert');
    out += pill('Select', rc - 60, H - safe.bottom - 40, 56, 'tert sm') + pill('Start', rc + 4, H - safe.bottom - 40, 56, 'tert sm');
    out += `</div>`;
    return out;
  }

  // Portrait Game Boy Color on phones.
  function portraitScene(W, H, safe) {
    const g = 'linksawak';
    const ar = k.aspect(g);
    const src = k.frame(g, 'a');
    let vw = W - 24;
    let vh = vw / ar;
    const maxH = H - safe.top - safe.bottom - 330;
    if (vh > maxH) {
      vh = maxH;
      vw = vh * ar;
    }
    const vx = (W - vw) / 2;
    const vy = safe.top + 10;
    const y0 = vy + vh + 14;
    const y1 = H - safe.bottom - 6;
    const Rh = y1 - y0;
    const ds = Math.min(164, W * 0.42, Rh * 0.52);
    const d = ds * 0.46;
    const dy = y0 + Rh * 0.16;
    let out = viewport(src, vx, vy, vw, vh, "Link's Awakening DX");
    out += `<div class="ctl">`;
    out += dpad(ds, Math.max(22, W * 0.06), dy);
    out += face('A', W - Math.max(22, W * 0.06) - d, dy + ds * 0.06, d);
    out += face('B', W - Math.max(22, W * 0.06) - d * 2.15, dy + ds * 0.06 + d * 0.72, d);
    const sy0 = dy + ds + Rh * 0.08;
    out += pill('Select', W / 2 - 74, sy0, 68, 'tert sm') + pill('Start', W / 2 + 6, sy0, 68, 'tert sm');
    out += pill('Menu', W / 2 - 28, Math.min(y1 - 40, sy0 + 58), 56, 'tert');
    out += `</div>`;
    return out;
  }

  // Nintendo DS on phones: always stacked, controls in the band beneath.
  function dualScene(W, H, safe) {
    const g = 'pkblack';
    const dd = k.dual(g, 'a');
    let out = '';
    const band = safe.top + 6;
    const ctlH = W < 440 ? 206 : 196;
    const avail = H - band - 48 - safe.bottom - ctlH;
    let sh = Math.min((W - 40) * 0.75, (avail - 8) / 2);
    let sw = sh / 0.75;
    const sx = (W - sw) / 2;
    const y1 = band + 48;
    out += viewport(dd.top, sx, y1, sw, sh, 'Pokémon Black top screen');
    out += viewport(dd.bottom, sx, y1 + sh + 8, sw, sh, 'Pokémon Black touch screen', 'touch');
    const c0 = y1 + 2 * sh + 18;
    const cEnd = H - safe.bottom - 4;
    out += `<div class="ctl">`;
    out += pill('L', 20, band + 4, 84, 'sh') + pill('Menu', W / 2 - 26, band + 6, 52, 'tert') + pill('R', W - 20 - 84, band + 4, 84, 'sh');
    const ds = Math.min(126, cEnd - c0 - 46);
    out += dpad(ds, 22, c0 + 4);
    out += diamond(W - 22 - ds / 2 - 2, c0 + 4 + ds / 2, ds + 4, R((ds + 4) * 0.37));
    const sy0 = Math.min(cEnd - 32, c0 + ds + 12);
    out += pill('Select', W / 2 - 66, sy0, 60, 'tert sm') + pill('Start', W / 2 + 6, sy0, 60, 'tert sm');
    out += `</div>`;
    return out;
  }

  // iPhone Duo players, open and closed. The geometry is k.stage's: the game (or both DS screens)
  // drawn exactly at its rect and never under a control, the d-pad and buttons low in the rails
  // where the thumbs rest, L/R just above them, Select · Menu · Start clear of the game and the
  // notice in free space. No underlays — nothing sits on the picture, so the controls stay bare
  // hairlines on black. `g` is a player context ({ w, h, safe, device }): the screen's own, or a
  // stand-in for the Controls preview. sys: 'snes' (landscape) · 'gbc' (portrait) · 'nds' (dual).
  const DUO_GAME = { snes: 'smetroid', gbc: 'linksawak', nds: 'pkblack' };
  function duoScene(g, sys, opts = {}) {
    const id = DUO_GAME[sys];
    const st = sys === 'nds' ? k.stage(g, { dual: true }) : k.stage(g, { aspect: k.aspect(id) });
    const big = st.size >= 140;
    const shoulders = sys !== 'gbc'; // the Game Boy Color has no L/R
    let out = '';
    if (sys === 'nds') {
      const dd = k.dual(id, 'a');
      out += viewport(dd.top, st.top.x, st.top.y, st.top.w, st.top.h, 'Pokémon Black top screen');
      out += viewport(dd.bottom, st.touch.x, st.touch.y, st.touch.w, st.touch.h, 'Pokémon Black touch screen', 'touch');
    } else {
      out += viewport(k.frame(id, 'a'), st.game.x, st.game.y, st.game.w, st.game.h, sys === 'snes' ? 'Super Metroid' : "Link's Awakening DX");
    }

    // Shoulders and clusters share a column. Where the stage packs them tight the shoulder gives
    // up height first (down to 30pt), then the cluster draws a little smaller about its centre.
    const S0 = st.size;
    const cTop = st.dpad.cy - S0 / 2;
    let shH = st.l.h;
    let S = S0;
    if (shoulders && st.l.y < cTop) {
      if (st.l.y + shH + 6 > cTop) shH = Math.max(30, cTop - 6 - st.l.y);
      S = Math.min(S0, 2 * (st.dpad.cy - (st.l.y + shH + 6)));
    }

    if (opts.toast !== false) {
      // Keep the notice inside the free span at its height: between the shoulders, or between
      // the d-pad's up arm and the top face button when it sits level with the clusters.
      const half = 22;
      const hits = (y0, y1) => y1 > st.toast.cy - half && y0 < st.toast.cy + half;
      let left = 0;
      let right = g.w;
      if (shoulders && hits(st.l.y, st.l.y + shH)) {
        left = st.l.x + st.l.w;
        right = st.r.x;
      }
      if (hits(st.dpad.cy - S / 2, st.dpad.cy + S / 2)) {
        left = Math.max(left, st.dpad.cx + S * 0.18);
        right = Math.min(right, st.face.cx - S * 0.18);
      }
      const room = Math.min(right - st.toast.cx, st.toast.cx - left) * 2 - 16;
      out += toastAt(st.toast, 'Automatic resume updated', big ? 'lg' : '', room < (big ? 212 : 196));
    }

    out += `<div class="ctl">`;
    if (shoulders) out += shoulder('L', st.l, shH, big ? 'big' : '') + shoulder('R', st.r, shH, big ? 'big' : '');
    out += dpad(S, st.dpad.cx - S / 2, st.dpad.cy - S / 2);
    if (sys === 'gbc') {
      // B low-left, A high-right, on a diagonal centred on the cluster.
      const d = S * 0.46;
      out += face('B', st.face.cx - 1.075 * d, st.face.cy - 0.14 * d, d);
      out += face('A', st.face.cx + 0.075 * d, st.face.cy - 0.86 * d, d);
    } else {
      out += diamond(st.face.cx, st.face.cy, S, R(S * 0.36));
    }
    // Select · Menu · Start as one short row centred in the tertiary band (dimmer than the rest).
    const t = st.tertiary;
    const ph = big ? 40 : 32;
    const [ws, wm, gp] = big ? [88, 64, 10] : [64, 52, 8];
    const x0 = t.cx - (2 * ws + wm + 2 * gp) / 2;
    const y = t.cy - ph / 2;
    const cls = big ? 'tert big' : 'tert';
    out += pill('Select', x0, y, ws, cls) + pill('Menu', x0 + ws + gp, y, wm, cls) + pill('Start', x0 + ws + wm + 2 * gp, y, ws, cls);
    out += `</div>`;
    return out;
  }

  // The Duo player's own landscape geometry, for previews drawn inside app screens.
  const duoPlayer = (c) => {
    const L = window.Studio.DEVICES[c.device.id].landscape;
    return { w: L.w, h: L.h, safe: L.safe, device: { ...c.device, fold: L.fold || null } };
  };

  const stage = (inner, cls = '') => `<div class="stage ${cls}" style="--ctl-o:${D.controls.opacity}">${inner}</div>`;

  // The paused game behind a sheet. On Duo it is drawn exactly where the Duo player draws it
  // (k.stage's game rect), and the sheet or inspector floats over it.
  const pausedGame = (box, c) => {
    const ar = k.aspect('smetroid');
    let r;
    if (c && c.device.duo) {
      r = k.stage(c, { aspect: ar }).game;
    } else {
      const vw = Math.min(box.w - 40, (box.h - 40) * ar);
      const vh = vw / ar;
      r = { x: (box.w - vw) / 2, y: (box.h - vh) / 2, w: vw, h: vh };
    }
    return `<div class="stage paused" style="width:${box.w}px;height:${box.h}px">${viewport(k.frame('smetroid', 'a'), r.x, r.y, r.w, r.h, 'Super Metroid, paused')}</div>`;
  };

  // An app screen drawn at full size behind a sheet (a pane only uncovers part of it).
  const behind = (c, html) => `<div class="behind" style="width:${c.w}px;height:${c.h}px">${html}</div>`;

  // The player's night appearance for sheets presented over the game.
  const night = (html) => `<div class="night">${html}</div>`;

  /* ============================== screens ============================== */
  const screens = {};

  /* ---------- onboarding ---------- */
  screens.welcome = (c) => {
    const steps = `<ol class="wl-steps">
        <li><i class="nd n-res"></i><p><b>Connect</b> the RomM server you already run.</p></li>
        <li><i class="nd n-st"></i><p><b>Play</b> natively, online or offline.</p></li>
        <li><i class="nd n-sv"></i><p><b>Continue</b> on any of your devices, right where you stopped.</p></li>
      </ol>`;
    const cta = `<div class="wl-cta">
        ${c.sys.button('Connect to a server', { style: 'prominent', size: 'lg' })}
        ${c.sys.button('Scan setup code', { style: 'glass', size: 'lg', icon: 'qr' })}
        <p class="fine">Rommio provides no games. It plays the ones on a RomM server you run.</p>
      </div>`;
    const head = `<h1 class="wl-h">Pick up exactly where you left off.</h1>
        <p class="wl-p">Rommio is a native client for your own RomM library. Browse it, play it offline, and your progress follows you.</p>`;
    // Duo open: one composition — the promise runs across the fold, the three steps become a
    // horizontal spine beneath it, and the actions sit in a row at the foot.
    const body = open(c)
      ? `<div class="wl wide">${wordmark()}<div class="wl-main">${head}</div>${steps.replace('wl-steps', 'wl-steps row')}${cta.replace('wl-cta', 'wl-cta row')}</div>`
      : `<div class="wl">${wordmark()}<div class="wl-main">${head}</div>${steps}${cta}</div>`;
    return c.sys.shell({ tabBar: false, titleMode: 'none', edge: false, body });
  };

  const accessTokens = () =>
    `<div class="acc"><div class="rail wrap"><div class="rail-in">${['Automatic', 'Direct', 'Cloudflare Access', 'Service token'].map((l, n) => tok(l, null, n === 0)).join('')}</div></div>
      <p class="acc-n">Automatic uses Cloudflare Access, detected on this server.</p></div>`;
  const accessList = () => {
    const opts = [
      ['Automatic', 'Cloudflare Access detected'],
      ['Direct', 'Connect straight to the server'],
      ['Cloudflare Access', 'Sign in through your Access team'],
      ['Service token', 'Client ID and secret'],
    ];
    return `<ul class="radios" role="radiogroup" aria-label="Access method">${opts
      .map(([l, s], n) => `<li class="rad${n === 0 ? ' on' : ''}" role="radio" aria-checked="${n === 0}"><i></i><div><b>${esc(l)}</b><span>${esc(s)}</span></div></li>`)
      .join('')}</ul>`;
  };
  screens.connect = (c) => {
    const field = `<div class="fld on">
        <span class="fld-l">Server address</span>
        <span class="fld-box"><span class="fld-pre">https://</span><span class="fld-v">romm.homelab.dev</span><i class="caret"></i></span>
        <span class="fld-ok">${mk('synced')}<span><b>RomM 5.2.0</b> found · protected by Cloudflare Access</span></span>
      </div>`;
    const saved = `${hd('Saved and nearby')}<ul class="sets">${D.server.discovered
      .map((s) => row(`<span class="mono">${esc(s.name)}</span>`, `${esc(s.note)}${s.secure ? '' : ' · not encrypted'}`, i(s.secure ? 'lock' : 'wifi', 16, 'set-i')))
      .join('')}</ul>`;
    const cta = `<div class="cta">${c.sys.button('Continue', { style: 'prominent', size: 'lg' })}</div>`;
    const left = `${sub('The address you use for RomM in a browser.')}${field}${hd('Access')}`;
    // Duo open: the field runs the full width, access methods sit in one row, saved servers are
    // wide rows beneath it, Continue closes the page at the trailing edge.
    if (open(c))
      return c.sys.shell({
        tabBar: false,
        back: true,
        titleMode: 'none',
        body: cv(
          `${ttl('Connect', 'The address you use for RomM in a browser.')}<div class="flow">${field}${hd('Access')}${accessList().replace('class="radios"', 'class="radios row"')}<p class="acc-n">Automatic uses Cloudflare Access, detected on this server.</p>${saved}${cta.replace('class="cta"', 'class="cta end"')}</div>`,
        ),
      });
    return c.sys.shell({ tabBar: false, back: true, title: 'Connect', body: `<div class="flow">${left}${accessTokens()}${saved}${cta}</div>` });
  };

  screens.signin = (c) => {
    const server = `<div class="srv">
        <div><b class="mono">romm.homelab.dev</b><span>${mk('synced')}RomM 5.2.0 · Cloudflare Access verified</span></div>
        ${c.sys.button('Change', { style: 'plain', size: 'sm' })}
      </div>`;
    const form = `
        <div class="fld"><span class="fld-l">Username</span><span class="fld-box"><span class="fld-v">ryan</span></span></div>
        <div class="fld on"><span class="fld-l">Password</span><span class="fld-box"><span class="fld-v dots">••••••••••••</span><i class="caret"></i><span class="fld-end">${i('eye', 18)}</span></span></div>
        <div class="stack">${c.sys.button('Sign in', { style: 'prominent', size: 'lg' })}
        <p class="or"><span>or</span></p>
        ${c.sys.button('Continue with single sign-on', { style: 'glass', size: 'lg', icon: 'key' })}</div>
        <p class="fine left">Your password goes only to romm.homelab.dev.</p>`;
    // Duo open: one centred column that runs across the fold, the verified server pinned on top.
    if (open(c)) return c.sys.shell({ tabBar: false, back: 'Connect', titleMode: 'none', body: cv(`<div class="solo">${ttl('Sign in', 'Use your RomM account on this server.')}${server}${form}</div>`) });
    return c.sys.shell({ tabBar: false, back: 'Connect', title: 'Sign in', body: `<div class="flow">${server}${form}</div>` });
  };

  screens.hydrate = (c) => {
    const ledger = `<ul class="ledger">
        <li><span>Platforms</span><b>22</b>${i('check', 16, 'ok')}</li>
        <li><span>Games</span><b>2,164</b>${i('check', 16, 'ok')}</li>
        <li><span>Collections</span><b>9</b>${i('check', 16, 'ok')}</li>
        <li class="live"><span>Artwork</span><b>61%</b><i class="spin" aria-hidden="true"></i><span class="lg-bar" style="--p:61%"></span></li>
      </ul>`;
    const explain = `<div class="expl"><ol class="wl-steps small"><li><i class="nd n-sv"></i><p><b>Game-save data</b> syncs after every session.</p></li><li><i class="nd n-res"></i><p><b>Resume states</b> let you stop on one device and continue on another.</p></li></ol></div>`;
    const choices = `${hd('While it finishes')}<ul class="sets">${row('Keep artwork offline', 'Covers stay available without a connection', c.sys.toggle(true, 'Keep artwork offline'))}${row('Sync on cellular', 'Otherwise waits for Wi-Fi', c.sys.toggle(false, 'Sync on cellular'))}</ul>`;
    const cta = `<div class="cta">${c.sys.button('Start playing', { style: 'prominent', size: 'lg' })}<p class="fine">Caching continues in the background.</p></div>`;
    const lede = sub('Signed in as <b>ryan</b> on romm.homelab.dev.');
    // Duo open: the ledger becomes one wide row of four counters; the explanation is a short
    // horizontal spine; choices are full-width rows; Start playing closes the page.
    if (open(c)) {
      const cells = [
        ['Platforms', '22', true],
        ['Games', '2,164', true],
        ['Collections', '9', true],
        ['Artwork', '61%', false],
      ];
      const lgr = `<ul class="lgr">${cells
        .map(([l, v, done]) => `<li class="${done ? '' : 'live'}"><span>${l}</span><b>${v}</b>${done ? i('check', 16, 'ok') : '<i class="spin" aria-hidden="true"></i><span class="lg-bar" style="--p:61%"></span>'}</li>`)
        .join('')}</ul>`;
      const spine = `<ol class="wl-steps row small"><li><i class="nd n-sv"></i><p><b>Game-save data</b> syncs after every session.</p></li><li><i class="nd n-res"></i><p><b>Resume states</b> let you stop on one device and continue on another.</p></li></ol>`;
      return c.sys.shell({
        tabBar: false,
        titleMode: 'none',
        body: cv(`${ttl('Getting your library ready', 'Signed in as <b>ryan</b> on romm.homelab.dev.')}<div class="flow">${lgr}<div class="expl">${spine}</div>${choices}${cta.replace('class="cta"', 'class="cta end"')}</div>`),
      });
    }
    return c.sys.shell({ tabBar: false, title: 'Getting your library ready', body: `<div class="flow">${lede}${ledger}${explain}${choices}${cta}</div>` });
  };

  /* ---------- Now ---------- */
  screens.home = (c) => {
    // Duo open: one page. The masthead (title + status line) runs the full width; the resume
    // frame crosses the fold with its caption, the decision and earlier today beside it; Up next
    // becomes one row of four stops along the foot.
    if (open(c)) {
      const g = k.game(HERO.game);
      const earlier = STREAM.filter((e) => e.type === 'cp' && e.at > 2 && e.at < 600).slice(0, 3);
      const body = `<div class="nw">
        <header class="nw-h"><h1>Now</h1>${statusLine()}</header>
        <section class="nw-m" aria-label="Continue ${esc(g.title)}">
          <div class="hero-fr">${fr(g.id, HERO.frame)}</div>
          <div class="nw-s">
            <div class="hero-t"><h2>${esc(g.title)}</h2><p>${esc(HERO.name)} · ${esc(k.ago(HERO.at))}</p></div>
            <div class="nw-a">${c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'md' })}${tb('Start with game save')}</div>
            <p class="nw-sy">${sy(HERO.remote, 'Resume waiting to sync')}</p>
            ${decisionLine(c)}
            ${hd('Earlier today', '<button class="hd-a">Timeline</button>')}<ol class="mini">${earlier
              .map((e) => `<li><time>${when(e.at).time}</time>${mk(e.m.remote)}<span><b>${esc(k.game(e.m.game).title)}</b> · ${esc(cpLabel(e.m))}</span></li>`)
              .join('')}</ol>
          </div>
        </section>
        ${hd('Up next', `<span>${QUEUE.length} games, in the order you’ll play them · <button class="hd-a">Edit</button></span>`)}
        <ol class="qs row">${QUEUE.map(queueRow).join('')}</ol>
      </div>`;
      return c.sys.shell({ tab: 'now', titleMode: 'none', actions: [account], body: cv(body) });
    }
    return c.sys.shell({
      tab: 'now',
      title: 'Now',
      titleMode: 'inline',
      actions: [account],
      body: `${statusLine()}${resumeBlock(c)}${decisionLine(c)}${queue(c)}`,
    });
  };

  /* ---------- Library ---------- */
  screens.library = (c) => {
    if (open(c)) {
      return c.sys.shell({
        tab: 'library',
        title: 'Library',
        actions: [{ icon: 'collections', label: 'Collections' }, account],
        split: {
          primaryTitle: 'Library',
          primary: `${platformRail('all')}${legend()}${index(byTitle, 'smetroid')}${scrubber('S')}`,
          secondary: quickLook(c, 'smetroid'),
        },
      });
    }
    return c.sys.shell({
      tab: 'library',
      title: 'Library',
      actions: [{ icon: 'collections', label: 'Collections' }, account],
      body: `${sub(`${k.num(T.games)} games on ${T.platforms} platforms · ${T.installed} on this iPhone`)}${platformRail('all')}${legend()}${index(byTitle)}${scrubber('A')}`,
      contentClass: 'has-scrub',
    });
  };

  screens.platform = (c) => {
    if (open(c)) {
      return c.sys.shell({
        tab: 'library',
        back: 'Library',
        title: 'Super Nintendo',
        actions: [{ icon: 'sort', label: 'Sort' }, { icon: 'search', label: 'Search Super Nintendo' }],
        split: { primaryTitle: 'Platforms', primary: platformList('snes'), secondaryTitle: 'Super Nintendo', secondary: platformSlice(c, 'snes') },
      });
    }
    return c.sys.shell({
      tab: 'library',
      back: 'Library',
      title: 'Super Nintendo',
      actions: [{ icon: 'sort', label: 'Sort' }, { icon: 'search', label: 'Search Super Nintendo' }],
      body: platformSlice(c, 'snes'),
    });
  };

  screens.collections = (c) => {
    const manual = D.collections.filter((x) => x.kind === 'manual');
    const smart = D.collections.filter((x) => x.kind === 'smart');
    const auto = D.collections.filter((x) => x.kind === 'auto');
    const upNext = `<div class="un"><div><b>Up next</b><span>${QUEUE.length} games · on Now · ${esc(QUEUE.map((q) => k.game(q.id).title).slice(0, 2).join(', '))}…</span></div>${chev()}</div>`;
    const lists = `${upNext}${hd('Your lists', `<span>${manual.length}</span>`)}<ol class="cbs">${manual.map((col, n) => colBlock(col, n, open(c) ? 'metroidvania' : null)).join('')}</ol>`;
    const rules = `${hd('Smart')}<ul class="rrs">${smart.map(ruleRow).join('')}</ul>${hd('Automatic')}<ul class="rrs">${auto.map(ruleRow).join('')}</ul>`;
    if (open(c)) {
      const col = k.collection('metroidvania');
      const gs = col.games.map(k.game);
      const hours = gs.reduce((n, g) => n + g.hours, 0);
      const detail = `<div class="cold">
        <p class="cold-note">${esc(col.note)}</p>
        <p class="facts nopad">${gs.length} games · all on this iPhone · ${esc(k.hours(hours))} played</p>
        <div class="cold-a">${c.sys.button('Play in order', { style: 'prominent', icon: 'play', size: 'md' })}${c.sys.button('Add to Up next', { style: 'glass', size: 'md' })}</div>
        ${hd('In order', '<button class="hd-a">Reorder</button>')}
        <ol class="qs">${gs
          .map((g, n) => {
            const pl = placeOf(g.id);
            return `<li class="q"><span class="q-n">${String(n + 1).padStart(2, '0')}</span><div class="q-m"><b>${esc(g.title)}</b><span>${esc(k.platformOf(g).short)} · ${pl ? `${esc(pl.name)} · ${esc(k.ago(pl.at))}` : 'Not started'}</span></div>${im(g.installed)}</li>`;
          })
          .join('')}</ol>
        ${hd('Also in your lists')}
        <ul class="rrs">${D.collections
          .filter((x) => x.kind === 'auto' && x.games.includes('smetroid'))
          .map(ruleRow)
          .join('')}</ul>
      </div>`;
      return c.sys.shell({
        tab: 'library',
        back: 'Library',
        title: 'Collections',
        actions: [{ icon: 'plus', label: 'New list' }],
        split: { primaryTitle: 'Collections', primary: `${lists}${rules}`, secondaryTitle: 'Metroidvanias', secondary: detail },
      });
    }
    return c.sys.shell({ tab: 'library', back: 'Library', title: 'Collections', actions: [{ icon: 'plus', label: 'New list' }], body: `${lists}${rules}` });
  };

  /* ---------- Game ---------- */
  const progressList = (opts = {}) => {
    const P = D.progress;
    const items = [
      { kind: 'resume', label: 'Automatic resume', name: HERO.name, at: P.resume.at, device: P.resume.device, remote: P.resume.remote, frame: P.resume.frame, act: 'Resume' },
      { kind: 'save', label: 'Game-save data', name: P.gameSave.detail, at: P.gameSave.at, device: P.gameSave.device, remote: P.gameSave.remote, act: 'Start with game save' },
      ...P.slots
        .filter((s) => !s.empty)
        .sort((a, b) => a.at - b.at)
        .map((s) => ({ kind: 'state', label: `Save state · Slot ${s.n}`, name: s.name, at: s.at, device: s.device, remote: s.remote, frame: s.frame, act: 'Load' })),
    ];
    const cls = { resume: 'n-res', state: 'n-st', save: 'n-sv' };
    return `<ol class="pgs">${items
      .map((it, n) => {
        const lead = n === 0 && opts.big;
        const m = { kind: it.kind, slot: it.label.replace(/\D/g, ''), remote: it.remote, device: it.device, frame: it.frame };
        return `<li class="pg${lead ? ' lead' : ''}"><i class="nd ${cls[it.kind]}"></i>
          <div class="pg-b">
            <p class="pg-k">${esc(it.label)}</p>
            <b>${esc(it.name)}</b>
            <p class="pg-m">${esc(k.ago(it.at))} · ${esc(it.device)}</p>
            <p class="pg-s">${sy(it.remote)}</p>
            ${it.kind === 'save' ? `<p class="pg-a">${tb(it.act)}</p>` : ''}
          </div>
          ${it.frame ? thumb('smetroid', m, 'pg-f') : ''}
        </li>`;
      })
      .join('')}
      <li class="pg empty"><i class="nd n-empty"></i><div class="pg-b"><p class="pg-k">Save state · Slot 4</p><b class="dim">Empty — save a state here from the pause menu</b></div></li>
    </ol>
    <p class="pg-foot">${c_btn('Sync now', 'sync')}${tb('Recovery copies')}</p>`;
  };
  const c_btn = (label, icon) => `<button class="tb">${i(icon, 15)}${esc(label)}</button>`;
  // The same progress history laid along a horizontal spine (Game on Duo open): one stop per
  // checkpoint, newest first, node shape by kind, each with its way back in.
  const progressStrip = () => {
    const P = D.progress;
    const items = [
      { kind: 'resume', label: 'Automatic resume', name: HERO.name, at: P.resume.at, device: P.resume.device, remote: P.resume.remote, frame: P.resume.frame },
      { kind: 'save', label: 'Game-save data', name: P.gameSave.detail, at: P.gameSave.at, device: P.gameSave.device, remote: P.gameSave.remote },
      ...P.slots
        .filter((s) => !s.empty)
        .sort((a, b) => a.at - b.at)
        .map((s) => ({ kind: 'state', slot: s.n, label: `State · Slot ${s.n}`, name: s.name, at: s.at, device: s.device, remote: s.remote, frame: s.frame })),
    ];
    const cls = { resume: 'n-res', state: 'n-st', save: 'n-sv' };
    const stop = (it) => {
      const m = { kind: it.kind, slot: it.slot, remote: it.remote, device: it.device, frame: it.frame };
      const vis = it.frame ? thumb('smetroid', m, 'pst-f') : `<span class="pst-f ic">${i('save', 18)}<button class="th-go" aria-label="Start with game save">${i('play', 11)}</button></span>`;
      return `<li class="pst"><i class="nd ${cls[it.kind]}"></i>${vis}<p class="pg-k">${esc(it.label)}</p><b>${esc(it.name)}</b><p class="pg-m">${esc(k.agoShort(it.at))} · ${esc(it.device)}</p><p class="pg-s">${sy(it.remote)}</p></li>`;
    };
    return `<ol class="pstrip">${items.map(stop).join('')}
      <li class="pst empty"><i class="nd n-empty"></i><span class="pst-f ic">${i('plus', 18)}</span><p class="pg-k">State · Slot 4</p><b class="dim">Empty</b><p class="pg-m">Free slot</p></li></ol>`;
  };
  const fileBlock = (g, p, compact) => `
    ${hd('File and core')}
    ${dl([
      ['File', '<span class="mono">Super Metroid (USA).sfc</span>'],
      ['Size', `${esc(k.size(g.size))} · on this iPhone`],
      ['Core', `${esc(p.core)} · ready`],
      ['Controls', `${esc(k.tierLabel(p.tier))} · DualSense connected`],
      ...(compact ? [] : [['Played', esc(k.hours(g.hours))]]),
    ])}
    <p class="rm${compact ? ' row' : ''}">${tb('Remove download', 'danger')}<span>Progress stays on your server.</span></p>`;
  const aboutBlock = (g) => `
    ${hd('About')}
    <p class="blurb">${esc(g.blurb)}</p>
    ${dl([
      ['Genre', esc(g.genre)],
      ['Players', String(g.players)],
      ['Rating', `${g.rating} / 100`],
    ])}`;

  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const facts = `<p class="facts">${esc(p.name)} · ${g.year} · ${esc(g.publisher)}</p>`;
    const shell = {
      tab: 'library',
      back: 'Library',
      title: g.title,
      primary: { icon: 'play', label: 'Resume' },
      actions: [{ icon: 'heart', label: 'Favourite' }, { icon: 'more', label: 'More' }],
    };
    // Duo open: one document across the display. The frame crosses the fold with the title and
    // the way back in beside it; the progress history runs beneath as a horizontal spine; file,
    // core and about follow as columns further down.
    if (open(c)) {
      const P = D.progress;
      const body = `<div class="gm">
        <section class="gm-top">
          <div class="gm-fr">${fr(g.id, HERO.frame)}</div>
          <div class="gm-id">
            <p class="eyebrow">${esc(p.name)} · ${g.year} · ${esc(g.publisher)}</p>
            <h1>${esc(g.title)}</h1>
            <p class="gm-w"><b>${esc(HERO.name)}</b> · ${esc(k.ago(HERO.at))} on ${esc(HERO.device)}</p>
            <p class="gm-s">${sy(P.resume.remote, 'Resume waiting to sync')}${sy(P.gameSave.remote, 'Game-save data synced')}</p>
            <p class="gm-a">${tb('Start with game save')}${c_btn('Sync now', 'sync')}</p>
            <p class="blurb">${esc(g.blurb)}</p>
          </div>
        </section>
        ${hd('Progress', `<span>${esc(k.hours(g.hours))} played · 3 of 4 slots · ${tb('Recovery copies')}</span>`)}
        ${progressStrip()}
        <div class="gm-cols">
          <div>${hd('About')}${dl([
            ['Genre', esc(g.genre)],
            ['Players', String(g.players)],
            ['Rating', `${g.rating} / 100`],
          ])}</div>
          <div>${fileBlock(g, p, true)}</div>
        </div>
      </div>`;
      return c.sys.shell({ ...shell, titleMode: 'none', body: cv(body) });
    }
    return c.sys.shell({ ...shell, body: `${facts}${hd('Progress', `<span>${esc(k.hours(g.hours))} played</span>`)}${progressList({ big: true })}${fileBlock(g, p)}${aboutBlock(g)}` });
  };

  /* ---------- Downloads (sheet over Now) ---------- */
  screens.downloads = (c) => {
    const g = (d) => k.game(d.game);
    const pS = (d) => {
      const p = k.platformOf(g(d));
      return `${esc(p.short)}${p.tier === 'controller' ? i('gamepad', 12) : ''}`;
    };
    const body = `<div class="dls">
      <section class="dl-run">
        <p class="dl-k">${mk('running')}Downloading</p>
        <div class="dl-h"><b>${esc(g(run).title)}</b><span>${k.pct(run.progress)}</span></div>
        <span class="dl-bar" style="--p:${Math.round(run.progress * 100)}%"></span>
        <p class="dl-m"><span>${pS(run)} · ${esc(k.size(run.done))} of ${esc(k.size(run.total))}</span><span>${run.rate} MB/s · ${esc(run.eta)} left</span></p>
        <div class="dl-a">${c.sys.button('Pause', { style: 'glass', size: 'sm', icon: 'pause' })}</div>
      </section>
      ${hd('Queue', '<button class="hd-a">Reorder</button>')}
      <ol class="dq">${D.downloads
        .filter((d) => d.state === 'queued')
        .map((d, n) => `<li><span class="q-n">${n + 1}</span><b>${esc(g(d).title)}</b><span class="dq-p">${pS(d)}</span><span class="dq-s">${esc(k.size(d.total))}</span></li>`)
        .join('')}
        ${D.downloads
          .filter((d) => d.state === 'waiting')
          .map((d) => `<li><span class="q-n">${i('wifi-off', 13)}</span><b>${esc(g(d).title)}</b><span class="dq-p">${pS(d)}</span><span class="dq-s">${esc(d.reason)}</span></li>`)
          .join('')}</ol>
      ${hd('Needs attention')}
      <ol class="dq">${D.downloads
        .filter((d) => d.state === 'failed')
        .map((d) => `<li class="bad"><span class="q-n">${mk('failed')}</span><div class="dq-m"><b>${esc(g(d).title)}</b><span>${esc(d.reason)} at ${k.pct(d.progress)}</span></div>${c.sys.button('Retry', { style: 'bordered', size: 'sm' })}</li>`)
        .join('')}</ol>
      ${hd('Finished', '<button class="hd-a">Clear</button>')}
      <ol class="dq">${D.downloads
        .filter((d) => d.state === 'done')
        .map((d) => `<li><span class="q-n">${i('check', 13)}</span><b>${esc(g(d).title)}</b><span class="dq-p">${pS(d)}</span><span class="dq-s">${esc(k.size(d.total))} · ${esc(k.ago(d.at))}</span></li>`)
        .join('')}</ol>
      <p class="dl-foot"><span>${esc(k.size(used()))} used on this iPhone · ${esc(k.size(D.storage.total - used()))} free</span>${tb('Storage')}</p>
    </div>`;
    return c.sys.sheet({
      detent: open(c) ? 'pane' : 'large',
      title: 'Downloads',
      backdrop: () => behind(c, screens.home(c)),
      body,
    });
  };

  /* ---------- Search ---------- */
  const hl = (t) => esc(t).replace(/(metroid)/gi, '<mark>$1</mark>');
  const searchResults = (c) => {
    const top = k.game('smetroid');
    const rest = [
      { t: 'game', id: 'metfusion', why: 'Title match' },
      { t: 'col', id: 'metroidvania', why: 'List name' },
      { t: 'game', id: 'sotn', why: 'In Metroidvanias' },
      { t: 'game', id: 'aria', why: 'In Metroidvanias' },
    ];
    const r = (x, n) => {
      if (x.t === 'col') {
        const col = k.collection(x.id);
        return `<li class="sr"><span class="q-n">${n + 2}</span><div class="sr-m"><b>${hl(col.name)}</b><span>List · ${col.games.length} games · all on this iPhone</span></div><span class="sr-w">${esc(x.why)}</span></li>`;
      }
      const g = k.game(x.id);
      const pl = placeOf(g.id);
      return `<li class="sr"><span class="q-n">${n + 2}</span><div class="sr-m"><b>${hl(g.title)}</b><span>${esc(k.platformOf(g).short)} · ${g.installed ? 'Plays now' : 'Not downloaded'}${pl ? ` · ${esc(pl.name)}` : ''}</span></div><span class="sr-w">${esc(x.why)}</span>${im(g.installed)}</li>`;
    };
    return `
      <div class="refine">${D.search.suggestions.map((s) => tok(s, null, false)).join('')}</div>
      <p class="recent"><span>Recent</span>${D.search.recent
        .filter((s) => s !== 'metroid')
        .map((s) => `<button class="tb">${i('history', 14)}${esc(s)}</button>`)
        .join('')}</p>
      ${hd('Top result', '<span>1</span>')}
      <div class="tophit">
        <span class="top-f">${fr(top.id, HERO.frame)}</span>
        <div class="top-m"><b>${hl(top.title)}</b><span>SNES · ${esc(HERO.name)} · ${esc(k.ago(HERO.at))}</span>
        ${c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'sm' })}</div>
      </div>
      ${hd('More results', '<span>4</span>')}
      <ol class="srs">${rest.map(r).join('')}</ol>
      <p class="rank">${i('info', 14)}<span>Ranked by title match, then what plays on this iPhone, then how recently you played. No platforms match “metroid”.</span></p>
`;
  };
  // Duo open: one wide page — the field and refine tokens across the top, the top result as a
  // band with the reasons it ranked first, then the rest as wide rows whose columns say what,
  // where you are, whether it plays now and why it matched.
  const searchWide = (c) => {
    const top = k.game('smetroid');
    const rest = [
      { t: 'game', id: 'metfusion', why: 'Title match' },
      { t: 'col', id: 'metroidvania', why: 'List name' },
      { t: 'game', id: 'sotn', why: 'In Metroidvanias' },
      { t: 'game', id: 'aria', why: 'In Metroidvanias' },
    ];
    const r = (x, n) => {
      if (x.t === 'col') {
        const col = k.collection(x.id);
        return `<li class="sw"><span class="q-n">${n + 2}</span><b>${hl(col.name)}</b><span class="sw-p">List</span><span class="sw-w">${col.games.length} games · all on this iPhone</span><span class="sw-y">${esc(x.why)}</span><span></span></li>`;
      }
      const g = k.game(x.id);
      const p = k.platformOf(g);
      const pl = placeOf(g.id);
      return `<li class="sw"><span class="q-n">${n + 2}</span><b>${hl(g.title)}</b><span class="sw-p">${esc(p.short)}</span><span class="sw-w">${pl ? `${esc(pl.name)} · ${esc(k.ago(pl.at))}` : 'Not started'}</span><span class="sw-y">${esc(x.why)}</span>${im(g.installed)}</li>`;
    };
    return `<div class="sfield">${c.sys.search({ value: 'metroid', focused: true })}</div>
      <div class="refine">${D.search.suggestions.map((s) => tok(s, null, false)).join('')}<span class="refine-r">Recent${D.search.recent
        .filter((s) => s !== 'metroid')
        .map((s) => `<button class="tb">${i('history', 14)}${esc(s)}</button>`)
        .join('')}</span></div>
      ${hd('Top result', '<span>1</span>')}
      <div class="tophit wide">
        <span class="top-f">${fr(top.id, HERO.frame)}</span>
        <div class="top-m"><b>${hl(top.title)}</b><span>SNES · ${esc(HERO.name)} · ${esc(k.ago(HERO.at))} on ${esc(HERO.device)}</span>
          <p class="top-why">First because: title match · plays on this iPhone · played ${esc(k.ago(HERO.at))}</p>
          <div class="top-a">${c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'sm' })}${c.sys.button('Open', { style: 'glass', size: 'sm' })}</div></div>
      </div>
      ${hd('More results', '<span>4</span>')}
      <ol class="sws">${rest.map(r).join('')}</ol>
      <p class="rank">${i('info', 14)}<span>Ranked by title match, then what plays on this iPhone, then how recently you played. No platforms match “metroid”.</span></p>`;
  };
  screens.search = (c) => {
    if (open(c)) return c.sys.shell({ tab: 'search', title: 'Search', titleMode: 'none', edge: false, body: cv(searchWide(c), 'srch') });
    return c.sys.shell({ tab: 'search', title: 'Search', titleMode: 'none', edge: false, search: { value: 'metroid', focused: true }, body: searchResults(c) });
  };

  /* ---------- Timeline (signature) ---------- */
  const devicesHolding = (m) => {
    const fl = D.fleet;
    return `<ul class="hold">${fl
      .map((d) => {
        const here = d.name === m.device;
        const st = here ? 'synced' : m.remote === 'synced' ? 'synced' : 'waiting';
        const label = here ? 'Saved here' : m.remote === 'synced' ? 'Has it' : 'After sync';
        return `<li><b>${esc(d.name)}</b><span class="hold-l">${esc(k.ago(d.last) === 'Just now' ? 'Online' : `Seen ${k.ago(d.last)}`)}</span>${sy(st, label)}</li>`;
      })
      .join('')}</ul>`;
  };
  screens.signature = (c) => {
    const filters = `<div class="rail"><div class="rail-in">${tok('Everything', null, true)}${tok('Resume points', null, false)}${tok('Save states', null, false)}${tok('Game saves', null, false)}${tok('Transfers', null, false)}</div></div>`;
    const cap = `<p class="tl-cap"><span>${mk('waiting')}2 checkpoints waiting to sync from this iPhone</span></p>`;
    if (open(c)) {
      const sel = STREAM.find((e) => e.type === 'cp' && e.m.kind === 'resume' && e.m.game === 'smetroid');
      const m = sel.m;
      const g = k.game(m.game);
      const others = STREAM.filter((e) => e.type === 'cp' && e.m.game === 'smetroid' && e !== sel);
      const detail = `<div class="pv">
        <p class="eyebrow">Today · ${when(m.at).time} · after a 42 min session</p>
        <h2 class="pv-t">${esc(g.title)}</h2>
        <p class="pv-k">${esc(cpLabel(m))} · <b>${esc(m.name)}</b></p>
        <div class="pv-top">
          <div class="pv-fr">${fr(g.id, m.frame)}</div>
          <div class="pv-side">${hd('On your devices')}${devicesHolding(m)}</div>
        </div>
        <div class="pv-a">${c.sys.button('Resume from here', { style: 'prominent', icon: 'play', size: 'md' })}${c.sys.button('Copy to slot 4', { style: 'glass', size: 'md' })}</div>
        ${hd('Other checkpoints', `<span>${others.length}</span>`)}
        <ol class="mini">${others
          .map((e) => `<li><time>${when(e.at).day === 0 ? when(e.at).time : esc(dayHead(when(e.at).day).name)}</time>${mk(e.m.remote)}<span><b>${esc(cpLabel(e.m))}</b> · ${esc(e.m.name)} · ${esc(e.m.device)}</span></li>`)
          .join('')}</ol>
      </div>`;
      return c.sys.shell({
        tab: 'timeline',
        title: 'Timeline',
        actions: [{ icon: 'filter', label: 'Filter' }, account],
        split: { primaryTitle: 'Timeline', primary: `${filters}${cap}${timeline({ sel, frames: false, maxDay: 2 })}`, secondary: detail },
      });
    }
    return c.sys.shell({
      tab: 'timeline',
      title: 'Timeline',
      actions: [{ icon: 'filter', label: 'Filter' }, account],
      body: `${filters}${cap}${timeline({ maxDay: 3 })}`,
    });
  };

  /* ---------- Settings ---------- */
  screens.settings = (c) => {
    const P = D.profiles[0];
    const S = D.profiles[1];
    const parts = storageParts();
    const u = used();
    const acctId = `<section class="acct">
        <div class="acct-id"><span class="acct-av" aria-hidden="true">R</span><div><h2>ryan</h2><p>${esc(P.name)} · <span class="mono">${esc(P.url)}</span></p></div></div>
        <p class="acct-st">${sy('synced', `Online · synced ${k.ago(P.lastSync)} · ${P.version}`)}</p>
      </section>`;
    const profiles = `<ul class="sets">${row(esc(S.name), `<span class="mono">${esc(S.url)}</span> · offline-ready`, c.sys.button('Switch', { style: 'bordered', size: 'sm' }))}${row('Add a server', null, i('plus', 18, 'set-i'))}</ul>`;
    const acct = `${acctId}${profiles}`;
    const sync = `${hd('Sync')}<ul class="sets">
        ${row('Game-save data', 'Syncs after every session', c.sys.toggle(true, 'Sync game-save data'))}
        ${row('Resume states', 'Continue on any of your devices', c.sys.toggle(true, 'Sync resume states'))}
        ${row('Use cellular data', 'Otherwise waits for Wi-Fi', c.sys.toggle(false, 'Use cellular data'))}
      </ul>`;
    const needs = D.cores.filter((x) => x.state === 'bios' || x.state === 'setup');
    const play = `${hd('Player')}<ul class="sets">
        ${row('Player & controls', 'Touch · OLED black · haptics on', chev())}
        ${row('Cores & BIOS', `${D.cores.length} cores · <em class="warn">${needs.length} need attention</em>`, chev())}
      </ul>
      <ul class="notes">${needs.map((x) => `<li>${mk('failed')}<span><b>${esc(x.name)}</b> · ${esc(x.family)} — ${esc(x.note)}</span></li>`).join('')}</ul>`;
    const storage = `${hd('Storage', `<span>${esc(k.size(u))} of ${esc(k.size(D.storage.total))}</span>`)}
      <div class="sbar" role="img" aria-label="Storage used">${parts.map((p) => `<i class="${p[2]}" style="flex:${Math.max(p[1], 420)}"></i>`).join('')}<i class="free" style="flex:${D.storage.total - u}"></i></div>
      ${dl(parts.map((p) => [p[0], `${esc(k.size(p[1]))}`]))}`;
    const about = `${hd('About')}<ul class="sets">${row('About Rommio', null, chev())}${row('Acknowledgements', null, chev())}${row('<span class="danger">Sign out of Home server</span>', null, '')}</ul>`;
    const shell = { tab: 'now', back: 'Now', title: 'Settings' };
    // Duo open: one page, not two apps. The account runs across the top with the other servers
    // beside it; below, three columns keep the scope split legible — what follows your account
    // (sync, about) and what belongs to this iPhone (player, storage).
    if (open(c)) {
      const body = `${ttl('Settings')}<div class="stg">
        <div class="stg-acct"><div>${acctId}<p class="acct-so">${tb('Sign out of Home server', 'danger')}</p></div><div class="stg-pr">${profiles}</div></div>
        <p class="stg-g" style="grid-column:1">Follows your account</p><p class="stg-g" style="grid-column:2 / 4">This iPhone</p>
        <div class="stg-c">${sync}${hd('About')}<ul class="sets">${row('About Rommio', null, chev())}${row('Acknowledgements', null, chev())}</ul></div>
        <div class="stg-c">${play}</div>
        <div class="stg-c">${storage}</div>
      </div>`;
      return c.sys.shell({ ...shell, titleMode: 'none', body: cv(body) });
    }
    return c.sys.shell({ ...shell, body: `${acct}${sync}<h2 class="grp">This iPhone</h2>${play}${storage}${about}` });
  };

  /* ---------- Controls ---------- */
  const slider = (label, v, min, max, unit = '%') => {
    const p = ((v - min) / (max - min)) * 100;
    return `<li class="sl" style="--p:${p}%"><div class="sl-h"><b>${esc(label)}</b><output>${v}${unit}</output></div><span class="sl-t" role="slider" aria-label="${esc(label)}" aria-valuenow="${v}" aria-valuemin="${min}" aria-valuemax="${max}"><i></i><b></b></span></li>`;
  };
  screens.controls = (c) => {
    const s = D.controls;
    const pw = c.w - (c.device.vbar ? 72 : 0) - 40;
    // On Duo the preview is this device's own player (k.stage geometry); on phones, the iPhone one.
    const pg = c.device.duo ? duoPlayer(c) : null;
    const sc = pw / (pg ? pg.w : 874);
    const preview = pg
      ? `<div class="pv-box" style="height:${R(pg.h * sc)}px"><div class="pv-in" style="width:${pg.w}px;height:${pg.h}px;transform:scale(${sc})">${stage(duoScene(pg, 'snes', { toast: false }))}</div></div>`
      : `<div class="pv-box" style="height:${R(402 * sc)}px"><div class="pv-in" style="transform:scale(${sc})">${stage(landscapeScene(874, 402, { top: 0, bottom: 21, left: 62, right: 62 }, { toast: false }))}</div></div>`;
    const caption = `<p class="pv-cap"><span>Live preview · Super Nintendo layout</span><span>Opacity ${R(s.opacity * 100)}% · Size ${R(s.size * 100)}%</span></p>`;
    const toggles = `<ul class="sets">
        ${row('Show touch controls', null, c.sys.toggle(s.showTouch, 'Show touch controls'))}
        ${row('Hide with a controller', `${mk('synced')} ${esc(s.connectedController)} connected`, c.sys.toggle(s.autoHideOnController, 'Hide with a controller'))}
        ${row('Left-handed', 'Swap the d-pad and buttons', c.sys.toggle(s.leftHanded, 'Left-handed'))}
      </ul>`;
    const sliders = `${hd('Feel')}<ul class="sls">${slider('Opacity', R(s.opacity * 100), 0, 100)}${slider('Size', R(s.size * 100), 60, 140)}${slider('Stick deadzone', R(s.deadzone * 100), 0, 40)}</ul>`;
    const look = `${hd('Look')}<ul class="sets">
        ${row('Haptics', null, c.sys.toggle(s.haptics, 'Haptics'))}
        ${row('OLED black', 'Pure black around the game', c.sys.toggle(s.oled, 'OLED black'))}
        ${row('Console colours', 'Colour face buttons like the original pad', c.sys.toggle(s.consoleColors, 'Console colours'))}
        ${row('Controller type', null, `<span class="set-v">${esc(s.controllerType)}${i('chevron-up', 14)}</span>`)}
      </ul>`;
    const shell = { tab: 'now', back: 'Settings', title: 'Player & controls' };
    // Duo open: the preview is the real Duo player (k.stage geometry: the game centred across
    // the fold, the controls low in the rails) filling the page, and the settings float over the
    // game between the rails, so the d-pad, buttons and shoulders stay in view while they change.
    if (open(c)) {
      const cw = c.w - 72;
      const ps = cw / pg.w;
      const player = `<div class="cx-pv" style="--ph:${R(pg.h * ps)}px"><div class="pv-in duo" style="width:${pg.w}px;height:${pg.h}px;transform:scale(${ps})">${stage(duoScene(pg, 'snes', { toast: false }))}</div></div>`;
      const panel = `<aside class="cx-pn" aria-label="Player and controls settings">
        <header class="cx-h"><h1>Player &amp; controls</h1><p>${mk('live')}Live preview · Super Nintendo on this iPhone</p></header>
        <ul class="sets">
          ${row('Show touch controls', null, c.sys.toggle(s.showTouch, 'Show touch controls'))}
          ${row('Hide with a controller', `${esc(s.connectedController)} connected`, c.sys.toggle(s.autoHideOnController, 'Hide with a controller'))}
          ${row('Left-handed', null, c.sys.toggle(s.leftHanded, 'Left-handed'))}
        </ul>
        ${sliders}
        ${hd('Look')}<ul class="sets">
          ${row('Haptics', null, c.sys.toggle(s.haptics, 'Haptics'))}
          ${row('OLED black', null, c.sys.toggle(s.oled, 'OLED black'))}
          ${row('Console colours', null, c.sys.toggle(s.consoleColors, 'Console colours'))}
          ${row('Controller type', null, `<span class="set-v">${esc(s.controllerType)}${i('chevron-up', 14)}</span>`)}
        </ul>
      </aside>`;
      return c.sys.shell({ ...shell, titleMode: 'none', heroUnderBar: true, edge: false, body: `<div class="cx">${player}${panel}</div>` });
    }
    return c.sys.shell({ ...shell, titleMode: 'inline', body: `<div class="pvw">${preview}${caption}</div>${toggles}${sliders}${look}` });
  };

  /* ---------- Player ---------- */
  screens.player = {
    tone: 'light',
    render: (c) => stage(c.device.duo ? duoScene(c, 'snes') : landscapeScene(c.w, c.h, c.safe)),
  };
  screens['player-portrait'] = {
    tone: 'light',
    render: (c) => stage(c.device.duo ? duoScene(c, 'gbc') : portraitScene(c.w, c.h, c.safe), 'portrait'),
  };
  screens.dual = {
    tone: 'light',
    render: (c) => stage(c.device.duo ? duoScene(c, 'nds') : dualScene(c.w, c.h, c.safe), 'portrait'),
  };

  screens.pause = {
    tone: 'light',
    render: (c) => {
      const tile = (icon, label, subl, extra = '', cls = '') => `<button class="pt ${cls}">${i(icon, 19)}<span><b>${esc(label)}</b>${subl ? `<small>${esc(subl)}</small>` : ''}</span>${extra}</button>`;
      const body = `<div class="ps">
        <div class="ps-top"><div><p class="ps-id"><b>Super Metroid</b> · Session 42 min</p>
        <p class="ps-s">${sy('synced', 'Game-save data synced')}${sy('waiting', 'Resume waiting to sync')}</p></div>
        ${c.sys.ios ? '' : c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'md' })}</div>
        <div class="ps-g">
          ${tile('save', 'Save state', 'Slot 4 is free')}
          ${tile('layers', 'Load state', '3 states')}
          ${tile('gamepad', 'Controls', 'Touch · 72%')}
          ${tile('fast-forward', 'Fast-forward', 'Off · up to 4×')}
          ${tile('restart', 'Reset')}
          ${tile('exit', 'Leave game', null, '', 'danger')}
        </div>
        ${
          c.device.id === 'duo-open'
            ? `${hd('Latest checkpoints', tb('All states'))}<ol class="mini">${STREAM.filter((e) => e.type === 'cp' && e.m.game === 'smetroid')
                .slice(0, 4)
                .map((e) => `<li><time>${esc(k.agoShort(e.at))}</time>${mk(e.m.remote)}<span><b>${esc(cpLabel(e.m))}</b> · ${esc(e.m.name)}</span></li>`)
                .join('')}</ol>
              <p class="ps-f">Snes9x · Touch controls at 72% · DualSense connected</p>`
            : ''
        }
      </div>`;
      return night(
        c.sys.sheet({
          detent: c.sys.ios ? 'medium' : 'large',
          title: 'Paused',
          done: { label: 'Resume', icon: 'play' },
          backdrop: (box) => pausedGame(box, c),
          body,
        }),
      );
    },
  };

  screens.states = {
    tone: 'light',
    render: (c) => {
      const P = D.progress;
      const pane = c.device.id === 'duo-open';
      const slot = (s) =>
        s.empty
          ? `<button class="slot empty"><span class="slot-f">${i('plus', 18)}</span><b>Save state here</b><span>Slot ${s.n} · empty</span></button>`
          : `<button class="slot"><span class="slot-f">${fr('smetroid', s.frame)}<i>${s.n}</i></span><b>${esc(s.name)}</b><span>${mk(s.remote)}${esc(k.ago(s.at).replace(' ago', ''))}${s.device === 'This iPhone' ? '' : ` · ${esc(s.device)}`}</span></button>`;
      const body = `<div class="sts${pane ? ' pane' : ''}">
        <ul class="st-rows">
          <li class="st-r"><span class="st-th">${fr('smetroid', P.resume.frame)}</span><div><b>Automatic resume <em>${esc(HERO.name)}</em></b><span>${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)} · ${sy(P.resume.remote)}</span></div>${tb('Resume')}</li>
          <li class="st-r"><span class="st-th ic">${i('save', 18)}</span><div><b>Game-save data <em>${esc(P.gameSave.detail)}</em></b><span>${esc(k.ago(P.gameSave.at))} · ${esc(P.gameSave.device)} · ${sy(P.gameSave.remote)}</span></div>${tb('History')}</li>
        </ul>
        ${hd('Save states', '<span>3 of 4</span>')}
        <div class="slots">${P.slots.map(slot).join('')}</div>
        ${
          pane
            ? `${hd('Recovery history')}<ol class="mini">${P.history
                .map((h) => `<li><time>${esc(k.agoShort(h.at))}</time>${mk('synced')}<span><b>${esc(h.kind)}</b> · ${esc(h.device)} · ${esc(h.reason)}</span></li>`)
                .join('')}</ol><p class="pg-foot">${tb('Game-save history')}${tb('All recovery copies')}</p>`
            : `<p class="st-rec"><span>Recovery: <b>${esc(P.history[2].kind)}</b> · ${esc(P.history[2].device)} · ${esc(k.ago(P.history[2].at))}</span>${tb('History')}</p>`
        }
      </div>`;
      return night(
        c.sys.sheet({
          detent: pane ? 'pane' : 'large',
          title: 'Saved progress',
          backdrop: (box) => pausedGame(box, c),
          body,
        }),
      );
    },
  };

  /* ---------- Conflict ---------- */
  screens.conflict = (c) => {
    const cf = D.conflict;
    const g = k.game(cf.game);
    const br = (b, on) => `<button class="br${on ? ' on' : ''}" role="radio" aria-checked="${on}">
        <span class="br-f">${fr(g.id, b.frame)}</span>
        <span class="br-t"><i class="rd" aria-hidden="true"></i><b>${esc(b.device)}</b></span>
        <span class="br-w">${esc(b.where)}</span>
        <span class="br-m">${esc(k.ago(b.at))}<br>${esc(b.playtime)} played</span>
      </button>`;
    const body = `<div class="cfx">
      <p class="eyebrow">${esc(g.title)} · ${esc(cf.kind)}</p>
      <h2 class="cf-h">Which one continues?</h2>
      <p class="cf-s">This iPhone and Pixel 9 both played since the last sync, so EarthBound has two resume points.</p>
      <div class="fork" role="radiogroup" aria-label="Resume point to continue from">
        <div class="fork-b">${br(cf.branches[0], true)}${br(cf.branches[1], false)}</div>
        <svg class="fork-g" viewBox="0 0 200 34" preserveAspectRatio="none" aria-hidden="true"><path d="M50 0 C50 20, 100 14, 100 30 M150 0 C150 20, 100 14, 100 30"/></svg>
        <p class="fork-base"><i></i>Last sync</p>
      </div>
      <ul class="sets">${row('Keep the other as a recovery copy', 'Restore it later from EarthBound’s progress', c.sys.toggle(true, 'Keep the other as a recovery copy'))}</ul>
      <div class="cf-a">${c.sys.button('Continue from This iPhone', { style: 'prominent', size: 'lg' })}${c.sys.button('Not now', { style: 'plain', size: 'md' })}</div>
    </div>`;
    return c.sys.sheet({ detent: open(c) ? 'pane' : 'large', title: 'Resume conflict', backdrop: () => behind(c, screens.home(c)), body });
  };

  /* ============================== register ============================== */
  Studio.defineVersion({
    id: 'void',
    name: 'Void',
    tagline: 'One thing at a time: now, next, and what happened.',
    thesis:
      'Rommio as a quiet, text-first instrument for getting back into a game. It assumes you are continuing something, not shopping, so every surface is organised by time and intent — what you are playing now, what is next, and what happened — and art appears only where it carries meaning: the frame you will return to.',
    swatch: ['#000000', '#b9c6ff', '#f7f7f5'],
    fonts: ['Geist:wght@300..700', 'Geist+Mono:wght@400..600'],
    tabs: [
      { id: 'now', label: 'Now', icon: 'circle-play', badge: '1' },
      { id: 'library', label: 'Library', icon: 'library' },
      { id: 'timeline', label: 'Timeline', icon: 'history' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },
    ],
    palette: [
      { name: 'Paper', hex: '#F7F7F5' },
      { name: 'Graphite', hex: '#17171A' },
      { name: 'Graphite 3', hex: '#6F6F77' },
      { name: 'Iris (light)', hex: '#3A4BC8' },
      { name: 'Void', hex: '#000000' },
      { name: 'Ink', hex: '#F4F4F5' },
      { name: 'Ink 3', hex: '#7C7C84' },
      { name: 'Iris (dark)', hex: '#5565EC' },
      { name: 'Mist', hex: '#B9C6FF' },
      { name: 'Signal', hex: '#C23D2A' },
      { name: 'Signal (dark)', hex: '#FF8A7A' },
    ],
    type: [
      { role: 'Titles & reading', family: 'Geist', css: "'Geist', sans-serif", sample: 'Maridia glass tube' },
      { role: 'Everything measured', family: 'Geist Mono', css: "'Geist Mono', monospace", sample: '9:40 · 1 min ago · 3 MB' },
    ],
    tweaks: [
      { var: '--ctl-tint', label: 'Control tint', type: 'color', value: '#b9c6ff' },
      { var: '--ctl-alpha', label: 'Control fill', type: 'range', min: 0, max: 30, step: 1, value: 9, unit: '%' },
      { var: '--ctl-edge', label: 'Control edge', type: 'range', min: 10, max: 70, step: 1, value: 34, unit: '%' },
      { var: '--radius', label: 'Frame radius', type: 'range', min: 0, max: 24, value: 12, unit: 'px' },
      { var: '--pad', label: 'Side margin', type: 'range', min: 14, max: 32, value: 20, unit: 'px' },
    ],
    notes: {
      welcome: 'Type does the work: one promise, three steps drawn on the same spine the Timeline uses (● resume point, ◆ state, ■ game save), and the plain statement that Rommio provides no games. Duo open: one composition — the promise runs across the fold, the steps become a horizontal spine, the actions sit in a row at the foot.',
      connect: 'The live check sits under the field it describes, not in a toast. Access is a short radio list with the detected method explained, so "Automatic" is never a mystery. Duo open: a full-width field, the four access methods in one row, saved servers as wide rows, Continue at the trailing foot.',
      signin: 'The verified server stays pinned above the form with Change beside it. SSO is the secondary action; the fine print says where the password goes. Duo open: one centred column that runs across the fold.',
      hydrate: 'A four-line ledger instead of a progress screen, then the two things worth deciding now. Start playing is always available. Duo open: the ledger becomes one row of four counters, with the choices as full-width rows beneath.',
      home: 'Now answers one question at a time, top to bottom: am I connected (status line, which opens Downloads), where was I (the frame at its true aspect, Resume), is anything waiting on me (EarthBound as one sentence with Choose), what next (a numbered, reorderable queue whose rows say how you get back in — Resume, a state on another device, or Start with game save). No shelves. Duo open: one page — a full-width masthead, the resume frame across the fold with its caption, the decision and earlier today beside it, and Up next as a row of four along the foot.',
      library: 'One A–Z index with hanging letters and a scrubber — no covers. Rows are annotated with your progress, so games in play read as two lines and the rest as one; install state and support tier live in a right-hand mark column (● on this iPhone, ○ not downloaded, controller glyph, "Browse only" with its reason). Duo open: a narrow index beside a wide quick look (frame and way back in side by side, then progress and file) on one background.',
      platform: 'The same row grammar, but ordered by when you last played — Today, This week, This month, Earlier, Never played — so a platform reads as your history with it. Not-downloaded games show their size instead of a time. Duo open: a narrow platform list (grouped by tier with reasons) selects the wide slice beside it.',
      collections: 'Collections are lists you play through. Each shows a stop-by-stop rail (filled = started, dim = not downloaded) and what is next; Up next is the first list. Smart and automatic lists state their rule. Duo open: a narrow column of lists selects the wide list beside it, read as an ordered queue.',
      game: 'A document, not a hero page: facts, then the progress history for this game on one spine (automatic resume with its frame, game-save data, each state with its thumbnail and action), then file and core, then about. Resume lives in the toolbar. Duo open: one document — the frame across the fold with the title and the way back in beside it, the history as a horizontal spine of stops, file and about in columns beneath.',
      downloads: 'A sheet from the Now status line (or any transfer in Timeline), not a tab. Active transfer first with one hairline bar, then queue, what needs attention, finished, and the storage it all costs. Duo open: a floating inspector over the full Now page.',
      search: 'Results explain themselves: a top result that can play now, then ranked rows each tagged with why they matched, and a one-line note on how ranking works. Refine tokens come from D.search. Duo open: one wide page — the top result says why it ranked first, the rest are wide rows with columns for platform, where you are, why it matched and install state.',
      signature: 'Timeline is a version history of progress, not a diary: every checkpoint across every device on one spine, newest first, node shape by kind, each restorable in place (or honestly "Not on this iPhone yet"). Transfers sit on the same spine. The EarthBound conflict appears as a fork. Duo open: the narrow spine selects a wide preview — the frame beside the devices that hold it, then the other checkpoints.',
      settings: 'Split by scope: what follows your account (profiles, sync) versus what belongs to This iPhone (player, cores that need attention, storage). Storage is one bar and a definition list. Duo open: one page — the account band across the top, then three columns, one for your account and two for this iPhone.',
      controls: 'Tuning with a live preview of the real player. Toggles, then hairline sliders for feel, then look. On Duo the preview is this device\'s own player (closed: in the page; open: filling it, with the settings floating over the game between the rails) so the d-pad, buttons and shoulders stay visible as they change.',
      player: 'Pure black in both appearances. Hairline controls with a 9% Mist fill and mono legends; Menu, Select and Start are dimmer. The game is never covered. On phones "Automatic resume updated" lives in the rail. Duo (open and closed): k.stage geometry — the game centred between the rails (across the fold when open), the d-pad and buttons low in the rails where thumbs rest, L and R just above them, Select · Menu · Start under the game and the note above it. Nothing needs an underlay because nothing sits on the picture.',
      'player-portrait': 'Game on top at its native 10:9, controls below; Menu is centred and dim. Duo (open and closed): k.stage geometry — the game full width at the top (across the fold when open), the controls in the band beneath it, low; nothing touches the game. No L/R: the Game Boy Color has none.',
      pause: 'A native sheet that keeps the player\'s night appearance in both modes. Truthful status first, then six tiles; Leave game is always one of them. On Duo the paused game is drawn exactly where the Duo player draws it (k.stage), and on Duo open the sheet is a floating inspector over it.',
      states: 'Automatic resume and game-save data are their own rows above the four slots; slot 4 invites a save. Recovery is one line on iPhone and a full history in the Duo inspector, which floats over the paused game, drawn where the Duo player draws it.',
      conflict: 'The decision drawn as what it is — a fork from the last sync — with both branches side by side at the same size, the other kept as a recovery copy by default. Duo open: a floating inspector over the full Now page.',
      dual: 'Screens stay stacked at 4:3 with the touch screen outlined. Duo (open and closed): k.stage geometry — the top screen above the fold when open, the touch screen beneath it, and the controls in a band at the foot (shoulders over the clusters, Select · Menu · Start between them); nothing overlaps either screen, so the stylus area stays clear.',
    },
    screens,
  });
})();
