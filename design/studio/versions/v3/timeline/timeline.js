/*
 * AURORA · TIMELINE — round 3 · "Your play is a river; your library is a history you can walk."
 *
 * DESIGN PLAN
 * ===========
 * Thesis     Aurora's Play page shows one game at a time and its Library hides the
 *            collection behind an era ruler, a coverflow and two rows of chips. Both
 *            screens throw away the one thing a retro library really has: time. This
 *            direction gives both screens the same grammar — lanes of colour running
 *            down the page — so that what you are playing and what you own read as
 *            one continuous history.
 *
 * IA         Tabs stay the app's own (Play · Library · Stats · Settings · Search).
 *              Play      "The river". A compact Now block (the freshest resume point as a
 *                        glowing screen, its moment name, meta, sync, Resume) whose lane
 *                        drops straight out of the screen into a two-week play graph.
 *                        Every game in progress and every recent player is a lane in its
 *                        own light; time runs down the page (Today first). Sessions are
 *                        beads sized by minutes, saves hang off the lane as small frames,
 *                        games in progress begin with a head chip (tap to resume), games
 *                        you stopped trail off in a dotted tail ("Put down 8 days ago").
 *                        EarthBound's conflict is drawn literally: the lane forks into a
 *                        This iPhone branch and a Pixel 9 branch that meet at a Choose node.
 *              Library   "The year river". One scroll through release years, oldest first.
 *                        A left gutter carries every console's lifespan as a thin lane
 *                        (solid for home consoles, dashed for handhelds, a double rail for
 *                        arcade), packed like a transit map so generations visibly overlap;
 *                        each lane's name sticks to the top while the lane is on screen and
 *                        leaves with it. Years are stations: a dot on each lane that has
 *                        games that year, a big year numeral, the count and the covers.
 *                        Empty stretches collapse into a thin gap marker. A Photos-style
 *                        scrubber (80 · 90 · 00 · 10) jumps anywhere. The page reopens
 *                        where you left it — here 1994, Super Metroid's year.
 *              Platform  (drill-in) The same river with one lane lit (Super Nintendo) and
 *                        the rest dimmed for context: the console's years, counts, tier and
 *                        core in a header, SNES covers only, and a Clear chip back.
 *
 * Components Now block · Stem (the Now screen's lane) · Play graph (SVG lanes, beads,
 *            head chips, save frames, dotted tails, fork + Choose node, day rules) ·
 *            Cover plate (a lit crop of the wide cover art, tagged "Cover", for a resume
 *            point with no preview — Ocarina of Time 3D) · Year river (CSS grid: lanes
 *            span grid rows, so they follow the real row heights; sticky lane names;
 *            station dots; year rows; gap markers) · Year scrubber with density ticks ·
 *            Lane header (lifespan on a decade axis, counts, tier, core, Clear).
 *
 * Owner's    Home no longer pages one game at a time: one glance shows the moment you
 * complaint  left, every game in play, how long you played, where each one was saved,
 *            which device holds what, and the one conflict, as a picture. Library drops
 *            the three stacked filters: the lanes ARE the platform filter (tap one), the
 *            river IS the era ruler, and covers appear immediately — twenty on the first
 *            screen instead of one or two. It scales from 72 games to 2,164 because it is
 *            one list addressed by year: rows wrap to two lines and end in "+N" when a year
 *            is dense, the scrubber reaches any year in one drag, and a lane tap narrows
 *            thousands to one console without leaving the page.
 *
 * Touch      The lane gutter is one control, not twenty targets: a touch lifts a loupe that
 *            lists the lanes alive at that year as 44pt rows. Covers are 48×64. Head chips
 *            are 48pt tall. The scrubber is a 38pt edge strip with a 44pt hit area.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;
  const AK = window.AuroraKit;
  const AX = window.AuroraX;
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  let SCHEME = 'light';

  // ================================================================ time
  // The data's "now" is Wednesday 23 September, 9:41 (Aurora's Moments uses the same day).
  const TODAY = new Date(2026, 8, 23);
  const WD = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const dateOf = (d) => {
    const t = new Date(TODAY);
    t.setDate(t.getDate() - d);
    return t;
  };
  const dayShort = (d) => `${WD[dateOf(d).getDay()]} ${dateOf(d).getDate()}`;
  const clock = (s) => {
    const [h, m] = s.split(':').map(Number);
    return h * 60 + m;
  };
  const fmtClock = (t) => `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
  const NOW = () => clock(D().now.time);
  // Minutes-ago → { day (0 = today), t (minutes after midnight) }.
  const when = (ago) => {
    const abs = NOW() - ago;
    const day = abs >= 0 ? 0 : Math.ceil(-abs / 1440);
    return { day, t: abs + day * 1440 };
  };
  const putDown = (d) => (d === 1 ? 'Put down yesterday' : `Put down ${d} days ago`);
  const dev = (name) => esc(name);

  // ================================================================ HOME · the river
  const WINDOW = 14; // Today and the 13 days before it
  const G = { x0: 10, dx: 18, tx: 88 }; // lane 0 x, lane spacing, text column x (graph coords)
  const X = (c) => G.x0 + c * G.dx;
  const beadR = (min) => 3.4 + Math.sqrt(min) * 0.62;

  function homeModel() {
    const S = D()
      .sessions.filter((s) => s.day < WINDOW)
      .map((s) => ({ ...s, t: clock(s.start) }));
    const events = S.map((s) => ({ kind: 'session', game: s.game, day: s.day, t: s.t, s }));
    // A game in progress that was played without a recorded session in the window: its
    // resume point sits at the time it was last played (Ocarina of Time 3D, yesterday 9:41).
    for (const id of AX.IN_PROGRESS) {
      if (S.some((s) => s.game === id)) continue;
      const g = k.game(id);
      if (g.played == null || g.played >= WINDOW * 1440) continue;
      const w = when(g.played);
      events.push({ kind: 'plate', game: id, day: w.day, t: w.t });
    }

    // Saves: the moment a session left behind (same game, day and device).
    const nowMoment = AK.lastMoment('smetroid');
    const used = new Set([nowMoment]);
    const momentFor = (s) => {
      const cands = D().moments.filter((m) => m.game === s.game && when(m.at).day === s.day && m.device === s.device && !used.has(m));
      const m = cands.find((x) => x.name === s.note) || cands[0];
      if (m) used.add(m);
      return m || null;
    };

    const rows = [{ type: 'title', h: 66 }];
    const seen = new Set(['smetroid']); // Super Metroid's head is the Now block
    for (let d = 0; d < WINDOW; d++) {
      const ev = events.filter((e) => e.day === d).sort((a, b) => b.t - a.t);
      const mins = ev.reduce((n, e) => n + (e.s ? e.s.minutes : 0), 0);
      rows.push({ type: 'rule', day: d, h: ev.length ? 46 : 30, empty: !ev.length, mins });
      for (const e of ev) {
        const ip = AX.IN_PROGRESS.includes(e.game);
        if (!seen.has(e.game)) {
          seen.add(e.game);
          if (ip) rows.push({ type: e.game === 'earthbound' ? 'choose' : 'head', game: e.game, h: e.game === 'earthbound' ? 66 : 60 });
          else e.put = true;
        }
        if (e.kind === 'plate') rows.push({ type: 'plate', game: e.game, t: e.t, day: e.day, h: 74 });
        else {
          const m = momentFor(e.s);
          rows.push({ type: 'ev', game: e.game, s: e.s, m, put: !!e.put, h: e.put ? 82 : m ? 74 : 58 });
        }
      }
      // EarthBound's two branches rejoin beneath today's sessions.
      if (d === 0) rows.push({ type: 'fork', h: 26 });
    }
    rows.push({ type: 'foot', h: 132 });
    let y = 0;
    for (const r of rows) {
      r.y = y;
      r.cy = y + r.h / 2;
      y += r.h;
    }

    // Lanes: first and last rows per game, then columns (Super Metroid 0, EarthBound 1 with
    // its Pixel 9 branch in 2 for today; everything else packs into the lowest free column).
    const lanes = [];
    rows.forEach((r, n) => {
      if (!r.game) return;
      let L = lanes.find((x) => x.id === r.game);
      if (!L) lanes.push((L = { id: r.game, top: n, bottom: n, rows: [] }));
      L.bottom = n;
      L.rows.push(n);
    });
    const occ = { 0: [], 1: [], 2: [], 3: [], 4: [], 5: [] };
    const forkRow = rows.findIndex((r) => r.type === 'fork');
    const chooseRow = rows.findIndex((r) => r.type === 'choose');
    for (const L of lanes) {
      L.ip = AX.IN_PROGRESS.includes(L.id);
      if (L.id === 'smetroid') L.col = 0;
      else if (L.id === 'earthbound') {
        L.col = 1;
        occ[2].push([chooseRow, forkRow]);
      } else {
        const lo = L.top - (L.ip ? 1 : 2);
        const hi = L.bottom + 1;
        for (let c = 2; c < 6; c++) {
          if (!occ[c].some(([a, b]) => !(hi < a || lo > b))) {
            L.col = c;
            break;
          }
        }
      }
      occ[L.col].push([L.top - 1, L.bottom + 1]);
    }
    // Colour: a lane takes its game's key light unless a lane near it in time already has
    // that hue; then it takes the game's second or third light (never a washed-out one).
    const hueDist = (a, b) => {
      const d = Math.abs(a - b) % 360;
      return d > 180 ? 360 - d : d;
    };
    // Any palette colour, toned like Aurora's key lights (vivid mid-tones for small marks).
    const tone = ([h, s, l]) =>
      SCHEME === 'dark'
        ? AK.toHex(h, s < 0.1 ? s : Math.max(s, 0.55), clamp(l, 0.5, 0.64))
        : AK.toHex(h, s < 0.1 ? s : clamp(s, 0.55, 0.95), clamp(l, 0.46, 0.54));
    const done = [];
    for (const L of [...lanes].sort((a, b) => a.top - b.top)) {
      const Lg = AK.lights(L.id);
      const near = done.filter((o) => !(o.bottom + 6 < L.top || o.top - 6 > L.bottom));
      const cands = [
        ...[Lg.k1, Lg.k2, Lg.k3].map((hex, n) => ({ hex, pen: n * 8 })),
        ...window.StudioArt.palette(L.id).map((c) => ({ hex: tone(AK.toHsl(c)), pen: 20 })),
      ];
      let best = null;
      for (const { hex, pen } of cands) {
        const [h, s] = AK.toHsl(hex);
        const dist = near.length ? Math.min(...near.map((o) => hueDist(o.h, h))) : 180;
        const score = Math.min(dist, 60) - pen - (s < 0.3 ? 80 : 0);
        if (!best || score > best.score) best = { hex, h, score };
      }
      L.hex = best.hex;
      L.h = best.h;
      done.push(L);
    }
    const sessions = S.length;
    const minutes = S.reduce((n, s) => n + s.minutes, 0);
    const games = new Set(events.map((e) => e.game)).size;
    const older = D()
      .sessions.filter((s) => s.day >= WINDOW)
      .map((s) => s.game)
      .filter((id, n, a) => a.indexOf(id) === n);
    return { rows, lanes, height: y, forkRow, chooseRow, sessions, minutes, games, older };
  }

  // A frame hung off a lane: a glowing little screen.
  const shot = (id, src, cls = '') =>
    `<figure class="tl-shot ${cls}" style="${AK.lit(id)}"><img class="tl-shot-glow" src="${src}" alt=""><span class="tl-shot-face"><img class="px" src="${src}" alt=""></span></figure>`;
  // No resume preview: a lit crop of the wide cover art, honestly tagged.
  const plate = (id) =>
    `<figure class="tl-shot tl-plate" style="${AK.lit(id)}"><img class="tl-shot-glow" src="${k.cover(id, 'wide')}" alt=""><span class="tl-shot-face"><img class="px" src="${k.cover(id, 'wide')}" alt="${esc(k.game(id).title)} cover art"><i class="tl-plate-wash"></i><em>Cover</em></span></figure>`;
  const mini = (id, cls = '') => `<img class="tl-mini px ${cls}" src="${k.cover(id, 'box')}" alt="">`;
  const quietSync = (state) => (state && state !== 'synced' ? AK.sync(state, '', 'tl-qs') : '');

  function graphSVG(M) {
    const { rows, lanes, forkRow, chooseRow } = M;
    const sid = SCHEME;
    let defs = '';
    let under = ''; // connectors
    let body = ''; // lanes
    let top = ''; // beads and nodes
    const rect = (x, y0, y1, fill, w = 3) => `<rect x="${x - w / 2}" y="${y0}" width="${w}" height="${Math.max(0, y1 - y0)}" rx="${w / 2}" fill="${fill}"/>`;
    for (const L of lanes) {
      const col = L.hex;
      const x = X(L.col);
      const gd = `tl-fd-${L.id}-${sid}`;
      const gu = `tl-fu-${L.id}-${sid}`;
      defs += `<linearGradient id="${gd}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${col}"/><stop offset="1" stop-color="${col}" stop-opacity="0"/></linearGradient>`;
      const beads = L.rows.filter((n) => rows[n].type === 'ev' || rows[n].type === 'plate');
      const firstY = rows[L.top].cy;
      const lastY = rows[L.bottom].cy;
      let g = '';

      if (L.id === 'earthbound') {
        // Fork: the lane rises alone, splits beneath today into This iPhone (col 1) and
        // Pixel 9 (col 2), and the branches meet at the Choose node.
        const yC = rows[chooseRow].cy;
        const yF = rows[forkRow].y + rows[forkRow].h;
        const x2 = X(2);
        g += rect(x, yC, lastY, col);
        g += `<path d="M${x} ${yC + 4} C ${x} ${yC + 22}, ${x2} ${yC + 14}, ${x2} ${yC + 34} L ${x2} ${yF - 30} C ${x2} ${yF - 12}, ${x} ${yF - 20}, ${x} ${yF - 2}" fill="none" stroke="${col}" stroke-width="3" stroke-linecap="round"/>`;
        g += rect(x, lastY, lastY + 20, `url(#${gd})`);
        // The contested tips: each branch warms to the conflict colour as it reaches Choose.
        const ev = L.rows.filter((n) => rows[n].type === 'ev' && rows[n].s.day === 0);
        const yA = rows[ev.find((n) => rows[n].s.device !== 'Pixel 9')].cy;
        const yB = rows[ev.find((n) => rows[n].s.device === 'Pixel 9')].cy;
        defs += `<linearGradient id="tl-ca-${sid}" gradientUnits="userSpaceOnUse" x1="${x}" y1="${yA}" x2="${x}" y2="${yC}"><stop offset="0" stop-color="${col}"/><stop offset="1" style="stop-color:var(--bad)"/></linearGradient>`;
        defs += `<linearGradient id="tl-cb-${sid}" gradientUnits="userSpaceOnUse" x1="${x2}" y1="${yB}" x2="${x2}" y2="${yC}"><stop offset="0" stop-color="${col}"/><stop offset="1" style="stop-color:var(--bad)"/></linearGradient>`;
        g += rect(x, yC, yA, `url(#tl-ca-${sid})`);
        g += `<path d="M${x} ${yC + 4} C ${x} ${yC + 22}, ${x2} ${yC + 14}, ${x2} ${yC + 34} L ${x2} ${yB}" fill="none" stroke="url(#tl-cb-${sid})" stroke-width="3" stroke-linecap="round"/>`;
      } else if (L.id === 'smetroid') {
        // The Now block's stem continues from the top of the graph.
        g += rect(x, 0, lastY, col);
        g += rect(x, lastY, lastY + 20, `url(#${gd})`);
      } else if (L.ip) {
        g += rect(x, firstY, lastY, col);
        g += rect(x, lastY, lastY + 18, `url(#${gd})`);
      } else {
        // Put down: a dotted tail trails off above the last session.
        const yb = rows[beads[0]].cy;
        defs += `<linearGradient id="${gu}" gradientUnits="userSpaceOnUse" x1="${x}" y1="${yb - 8}" x2="${x}" y2="${yb - 46}"><stop offset="0" stop-color="${col}" stop-opacity="0.95"/><stop offset="1" stop-color="${col}" stop-opacity="0"/></linearGradient>`;
        g += `<line x1="${x}" y1="${yb - 8}" x2="${x}" y2="${yb - 44}" stroke="url(#${gu})" stroke-width="3" stroke-linecap="round" stroke-dasharray="0.5 6"/>`;
        g += `<rect x="${x - 1.5}" y="${yb - 36}" width="3" height="28" fill="url(#${gu})" opacity="0.45"/>`;
        if (lastY > yb) g += rect(x, yb, lastY, col);
        g += rect(x, lastY, lastY + 16, `url(#${gd})`);
      }
      body += `<g class="tl-ln" style="color:${col}">${g}</g>`;

      // Nodes, beads and connectors.
      for (const n of L.rows) {
        const r = rows[n];
        const bx = L.id === 'earthbound' && r.s && r.s.device === 'Pixel 9' ? X(2) : x;
        if (r.type === 'head') {
          top += `<circle cx="${bx}" cy="${r.cy}" r="7" style="fill:var(--tl-hole)" stroke="${col}" stroke-width="3"/><circle cx="${bx}" cy="${r.cy}" r="2.6" fill="${col}"/>`;
          under += `<line x1="${bx + 8}" y1="${r.cy}" x2="${G.tx}" y2="${r.cy}" stroke="${col}" stroke-width="1.5" opacity="0.6"/>`;
        } else if (r.type === 'choose') {
          under += `<line x1="${bx + 12}" y1="${r.cy}" x2="${G.tx}" y2="${r.cy}" style="stroke:var(--bad)" stroke-width="1.5" opacity="0.7"/>`;
        } else if (r.type === 'ev' || r.type === 'plate') {
          const rad = r.s ? beadR(r.s.minutes) : 4.5;
          const hollow = r.type === 'plate';
          top += hollow
            ? `<circle cx="${bx}" cy="${r.cy}" r="${rad}" style="fill:var(--tl-hole)" stroke="${col}" stroke-width="2.5"/>`
            : `<circle cx="${bx}" cy="${r.cy}" r="${rad}" fill="${col}" style="stroke:var(--tl-hole)" stroke-width="2.5"/>`;
          under += `<line x1="${bx + rad + 3}" y1="${r.cy}" x2="${G.tx}" y2="${r.cy}" stroke="${col}" stroke-width="1.5" opacity="${r.put ? 0.4 : 0.6}"${r.m || r.type === 'plate' || r.put ? '' : ' stroke-dasharray="1 4" stroke-linecap="round"'}/>`;
        }
      }
    }
    return `<svg class="tl-svg" width="${G.tx}" height="${M.height}" viewBox="0 0 ${G.tx} ${M.height}" aria-hidden="true"><defs>${defs}</defs><g class="tl-under">${under}</g>${body}<g>${top}</g></svg>`;
  }

  function graphRows(M) {
    const { rows } = M;
    const laneHex = (id) => M.lanes.find((L) => L.id === id).hex;
    const out = [];
    const nodeAt = (x, y, html, cls) => `<span class="${cls}" style="left:${x}px;top:${y}px">${html}</span>`;
    for (const r of rows) {
      const pos = `top:${r.y}px;height:${r.h}px`;
      if (r.type === 'title') {
        out.push(`<div class="tl-r tl-title" style="${pos}"><h2>The last two weeks</h2><p class="tab">${AK.hm(M.minutes)} · ${M.sessions} sessions · ${M.games} games</p></div>`);
      } else if (r.type === 'rule') {
        const name = r.day === 0 ? 'Today' : r.day === 1 ? 'Yesterday' : dayShort(r.day);
        const sub = r.day === 1 ? dayShort(1) : r.day === 0 ? dayShort(0) : '';
        out.push(
          `<div class="tl-rule${r.empty ? ' quiet' : ''}" style="${pos}"><b>${esc(name)}</b>${sub ? `<span>${esc(sub)}</span>` : ''}<i></i>${r.empty ? '<small>No play</small>' : `<small class="tab">${AK.hm(r.mins)}</small>`}</div>`,
        );
      } else if (r.type === 'head') {
        const g = k.game(r.game);
        const m = AK.lastMoment(r.game);
        const sub = AX.hasPreview(r.game) && m ? `Resume · ${m.name}` : 'Resume · no preview yet';
        out.push(
          `<div class="tl-r" style="${pos}"><button class="tl-head" style="${AK.lit(r.game)};--k1:${laneHex(r.game)}" aria-label="Resume ${esc(g.title)}">${mini(r.game)}<span class="tl-head-t"><b>${esc(g.title)}</b><small>${esc(sub)}</small></span><span class="tl-head-go">${i('play', 15)}</span></button></div>`,
        );
      } else if (r.type === 'choose') {
        out.push(
          `<div class="tl-r" style="${pos}"><div class="tl-head tl-choose" style="${AK.lit('earthbound')}">${mini('earthbound')}<span class="tl-head-t"><b>EarthBound</b><small>${AK.sync('conflict', 'Two resume points', 'tl-qs')}</small></span>${AK.btn('Choose', { kind: 'primary', cls: 'sm tl-choose-btn' })}</div></div>`,
        );
        // Two branches meeting in one: the node's glyph repeats the picture.
        const glyph = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" aria-hidden="true"><path d="M6.5 20c0-6 5.5-6 5.5-11.5M17.5 20c0-6-5.5-6-5.5-11.5M12 8.5V4"/></svg>';
        out.push(nodeAt(X(1), r.cy, glyph, 'tl-cnode'));
      } else if (r.type === 'plate') {
        const g = k.game(r.game);
        out.push(
          `<div class="tl-r tl-ev" style="${pos}">${plate(r.game)}<div class="tl-cap"><b>Automatic resume</b><span>No preview yet · ${esc(k.syncLabel(g.sync))}</span><small class="tab">${fmtClock(r.t)} · played ${esc(k.hours(g.hours))} in all</small></div></div>`,
        );
      } else if (r.type === 'ev') {
        const g = k.game(r.game);
        const s = r.s;
        const m = r.m;
        const time = `${s.start} · ${AK.hm(s.minutes)} · ${dev(s.device)}`;
        let vis = '';
        let cap = '';
        if (r.put) {
          vis = m ? shot(r.game, k.frame(r.game, m.frame), 'dim') : `<span class="tl-cvslot">${mini(r.game, 'put')}</span>`;
          const what = m ? `${esc(m.name)} · ${esc(AK.kindLabel(m))}` : s.ended === 'save' ? `${esc(s.note)} · Game-save data` : `${esc(s.note)} · no save`;
          cap = `<em>${putDown(s.day)}</em><b>${esc(g.title)}</b><span>${what}</span><small class="tab">${time}</small>`;
        } else if (m) {
          vis = shot(r.game, k.frame(r.game, m.frame));
          cap = `<b>${esc(m.name)}</b><span>${esc(AK.kindLabel(m))}${quietSync(m.remote)}</span><small class="tab">${time}</small>`;
        } else {
          cap = `<b>${esc(s.note)}</b><small class="tab">${time}</small>`;
        }
        out.push(`<div class="tl-r tl-ev${r.put ? ' put' : ''}${!r.put && !m ? ' plain' : ''}" style="${pos}">${vis}<div class="tl-cap">${cap}</div></div>`);
      } else if (r.type === 'foot') {
        const names = M.older.map((id) => k.game(id).title);
        const list = names.length > 2 ? `${names.slice(0, 2).join(', ')} and ${names.length - 2} more` : names.join(' and ');
        out.push(
          `<div class="tl-r tl-foot" style="${pos}"><p>Before ${esc(dayShort(WINDOW - 1))}: ${esc(list)}</p><button class="tl-more">${i('chart', 17)}<span>All play history</span>${i('chevron-right', 16)}</button></div>`,
        );
      }
    }
    return out.join('');
  }

  function nowBlock() {
    const id = 'smetroid';
    const g = k.game(id);
    const m = AK.lastMoment(id);
    return `<section class="tl-now">
      <div class="tl-now-scr">${AK.gs(k.frame(id, m.frame), { cls: 'tl-now-gs', alt: `${g.title} — ${m.name}` })}</div>
      <div class="tl-now-cap">
        <p class="a-eyebrow"><b>${esc(g.title)}</b><span>${esc(AK.plat(g).short)}</span></p>
        <h2 class="tl-now-t">${esc(m.name)}</h2>
        <p class="tl-now-meta">${esc(AK.kindLabel(m))} · ${esc(k.ago(m.at))} · ${esc(m.device)}</p>
        ${AK.sync(m.remote, 'Resume waiting to sync')}
        <div class="tl-now-act">${AK.btn('Resume', { icon: 'play', kind: 'primary', cls: 'md grow' })}${AK.btn('', { icon: 'info', cls: 'round tl-info', aria: `${g.title} details` })}</div>
      </div>
      <i class="tl-stem" aria-hidden="true"></i>
    </section>`;
  }

  function home(c) {
    SCHEME = c.scheme;
    const M = homeModel();
    return AK.scene(
      ['smetroid'],
      `${AK.field('f-page tl-f-home', k.cover('smetroid', 'tall'))}${c.sys.shell({
        tab: 'play',
        titleMode: 'none',
        actions: [AK.ARRIVING, AK.ACCOUNT],
        edge: false,
        body: `<div class="tl-home">
          ${nowBlock()}
          <section class="tl-graph" style="height:${M.height}px" aria-label="The last two weeks of play">${graphSVG(M)}${graphRows(M)}</section>
        </div>`,
      })}`,
      'tl-s-home',
    );
  }

  // ================================================================ LIBRARY · the year river
  // Console lifespans (release → last year of the platform's life). Release years come
  // from the data; the ends are the platforms' commercial lives.
  const LANE_END = {
    arcade: 2020, nes: 1994, sms: 1991, gb: 2001, genesis: 1997, pce: 1994, lynx: 1995, snes: 1998, gg: 1997,
    psx: 2004, saturn: 2000, vb: 1996, n64: 2002, gbc: 2003, ngp: 2001, dc: 2001, ps2: 2013, gba: 2008, gc: 2007,
    nds: 2013, psp: 2014, '3ds': 2020,
  };
  const HANDHELD = new Set(['gb', 'gbc', 'gba', 'gg', 'lynx', 'ngp', 'nds', '3ds', 'psp', 'vb']);
  const RIVER = { from: 1978, to: 2020, lastGame: 2011 };
  const COVERS_PER_LINE = 4;
  const MAX_LINES = 2;
  const ROWH = { gap: 26, inline: 88, head: 50, line: 64, lineGap: 10, pad: 14 };
  const LIB_SPACER = 58; // room for the stuck lane names and the Sort control

  // Transit-map packing: each console takes the lowest column free since its release.
  let COLS = null;
  function laneCols() {
    if (COLS) return COLS;
    const ps = [...D().platforms].sort((a, b) => a.year - b.year || b.games - a.games);
    const ends = [];
    COLS = {};
    for (const p of ps) {
      let c = ends.findIndex((e) => e < p.year);
      if (c < 0) c = ends.length;
      ends[c] = LANE_END[p.id];
      COLS[p.id] = c;
    }
    COLS.n = ends.length;
    return COLS;
  }
  // Lane colours from the console's own colour, tuned for the appearance.
  function laneColour(pid) {
    const [h, s, l] = AK.toHsl(k.platform(pid).color);
    const grey = s < 0.14 || l > 0.85;
    if (SCHEME === 'dark') {
      return grey
        ? { line: AK.toHex(h, Math.min(s, 0.12), clamp(0.5 + l * 0.35, 0.58, 0.84)), ink: AK.toHex(h, Math.min(s, 0.12), 0.84) }
        : { line: AK.toHex(h, Math.max(s, 0.6), clamp(l + 0.12, 0.6, 0.7)), ink: AK.toHex(h, Math.max(s, 0.62), 0.8) };
    }
    return grey
      ? { line: AK.toHex(h, Math.min(s, 0.12), clamp(l * 0.6 + 0.15, 0.24, 0.6)), ink: AK.toHex(h, Math.min(s, 0.12), 0.3) }
      : { line: AK.toHex(h, Math.max(s, 0.52), clamp(l, 0.4, 0.52)), ink: AK.toHex(h, Math.max(s, 0.56), 0.33) };
  }

  function riverRows(games, from, to, lastGame) {
    const rows = [];
    let gap = null;
    for (let y = from; y <= to; y++) {
      const gs = y <= lastGame ? games.filter((g) => g.year === y) : [];
      if (!gs.length) {
        if (gap) gap.y1 = y;
        else rows.push((gap = { type: 'gap', y0: y, y1: y, h: ROWH.gap }));
        continue;
      }
      gap = null;
      const inline = gs.length <= 2;
      // A dense year keeps to two lines; the last tile becomes "+N" and opens the year.
      const lines = inline ? 1 : Math.min(MAX_LINES, Math.ceil(gs.length / COVERS_PER_LINE));
      const h = inline ? ROWH.inline : ROWH.head + lines * ROWH.line + (lines - 1) * ROWH.lineGap + ROWH.pad;
      rows.push({ type: 'yr', y0: y, y1: y, games: gs, inline, h });
    }
    return rows;
  }
  const rangeLabel = (a, b) => (a === b ? `${a}` : Math.floor(a / 100) === Math.floor(b / 100) ? `${a}–${String(b).slice(2)}` : `${a}–${b}`);

  function coverTile(g) {
    const ip = AX.IN_PROGRESS.includes(g.id);
    const L = laneColour(g.platform);
    return `<figure class="tl-cv${ip ? ' ip' : ''}${g.installed ? ' here' : ''}" style="--c:${L.line};${ip ? AK.lit(g.id) : ''}" aria-label="${esc(g.title)}${ip ? ', in progress' : ''}${g.installed ? ', on this iPhone' : ''}">${
      ip ? `<img class="tl-cv-glow" src="${k.cover(g.id, 'box')}" alt="">` : ''
    }<img class="tl-cv-img px" src="${k.cover(g.id, 'box')}" alt="${esc(g.title)}">${g.installed ? `<i class="tl-cv-ok">${i('check', 10)}</i>` : ''}</figure>`;
  }

  // In-progress games first, then what's on this iPhone, then the rest (kept in lane order).
  function coverRow(year, games) {
    const cap = COVERS_PER_LINE * MAX_LINES;
    if (games.length <= cap) return games.map(coverTile).join('');
    const rank = (g) => (AX.IN_PROGRESS.includes(g.id) ? 0 : g.installed ? 1 : 2);
    const shown = [...games].sort((a, b) => rank(a) - rank(b)).slice(0, cap - 1);
    return `${shown.map(coverTile).join('')}<button class="tl-cv tl-cv-more" aria-label="All ${games.length} games from ${year}"><b class="tab">+${games.length - shown.length}</b><small>${year}</small></button>`;
  }

  // The river: a CSS grid whose rows are years. Lanes are grid items spanning their years,
  // so they follow the real row heights; each lane's name is sticky inside its own lane.
  function river({ games, from, to, lastGame, only, hi, offsetBefore }) {
    const cols = laneCols();
    const rows = riverRows(games, from, to, lastGame);
    const rowOf = (y) => rows.findIndex((r) => y >= r.y0 && y <= r.y1);
    const sortCol = (a, b) => cols[a.platform] - cols[b.platform] || a.title.localeCompare(b.title);
    const parts = [];

    for (const p of D().platforms) {
      const s = Math.max(p.year, from);
      const e = Math.min(LANE_END[p.id], to);
      if (e < from || s > to) continue;
      const a = rowOf(s);
      const b = rowOf(e);
      const L = laneColour(p.id);
      const cont = p.year < from;
      const open = LANE_END[p.id] > to || e >= to;
      const cls = ['tl-lane', HANDHELD.has(p.id) ? 'hh' : '', p.id === 'arcade' ? 'arc' : '', cont ? 'cont' : '', open ? 'open' : 'ends', only ? (only === p.id ? 'on' : 'off') : ''].join(' ');
      const years = `${p.year}–${LANE_END[p.id] >= 2020 && p.id === 'arcade' ? 'today' : LANE_END[p.id]}`;
      parts.push(
        `<div class="${cls}" style="grid-column:${cols[p.id] + 1};grid-row:${a + 1} / ${b + 2};--c:${L.line};--ci:${L.ink}" role="button" aria-label="${esc(p.name)} · ${years} · show only this console"><i class="tl-lane-cap"></i><span class="tl-lane-lb">${esc(p.short)}</span></div>`,
      );
    }

    rows.forEach((r, n) => {
      const gr = n + 1;
      if (r.type === 'gap') {
        parts.push(`<div class="tl-gap" style="grid-row:${gr};height:${r.h}px"><span class="tab">${rangeLabel(r.y0, r.y1)}</span><i></i></div>`);
        return;
      }
      // Stations: one dot per console with games that year, sized by count.
      const by = {};
      for (const g of r.games) by[g.platform] = (by[g.platform] || 0) + 1;
      for (const [pid, cnt] of Object.entries(by)) {
        const L = laneColour(pid);
        parts.push(`<i class="tl-st${only && only !== pid ? ' off' : ''}" style="grid-column:${cols[pid] + 1};grid-row:${gr};--c:${L.line};--s:${cnt > 2 ? 12 : cnt > 1 ? 10 : 8}px" aria-hidden="true"></i>`);
      }
      const isHi = hi && r.y0 === hi;
      if (isHi) parts.push(`<i class="tl-band" style="grid-row:${gr};${AK.lit('smetroid')}" aria-hidden="true"></i>`);
      const n0 = r.games.length;
      const here = r.games.filter((g) => g.installed).length;
      const count = `<em class="tab">${n0} ${n0 === 1 ? 'game' : 'games'}</em>`;
      const hereTxt = here ? `${here === n0 && n0 > 1 ? 'All' : here} on this iPhone` : 'None on this iPhone';
      parts.push(
        `<section class="tl-yr${r.inline ? ' inline' : ''}${isHi ? ' hi' : ''}" style="grid-row:${gr};height:${r.h}px"${isHi ? ` aria-current="true"` : ''}>
          <header><b class="tl-yr-n tab">${r.y0}</b><span class="tl-yr-c">${count}${r.inline ? '' : `<small class="tab">${hereTxt}</small>`}</span></header>
          <div class="tl-covers">${coverRow(r.y0, [...r.games].sort(sortCol))}</div>
        </section>`,
      );
    });

    // Reopened mid-river: the rows before `offsetBefore` sit above the scroll position.
    // They are clipped at the top of the content box (as if scrolled past), so nothing
    // from 1990 shows under the bar at first; once you scroll, the clip leaves with them.
    let off = 0;
    if (offsetBefore) for (const r of rows) {
      if (r.y0 >= offsetBefore) break;
      off += r.h;
    }
    // The gutter opens at the top of the content box (the stuck lane names live there); the
    // year column opens at the first year shown.
    const top = off - LIB_SPACER; // the river's y at the top of the content box
    const gx = 10 + cols.n * 12 + 6; // gutter width inside the river's padding box
    const mask = `linear-gradient(transparent ${top - 2}px, #000 ${top + 10}px), linear-gradient(transparent ${off - 2}px, #000 ${off}px)`;
    const offCss = off
      ? `;margin-top:-${off}px;-webkit-mask-image:${mask};mask-image:${mask};-webkit-mask-size:${gx}px 100%, calc(100% - ${gx}px) 100%;mask-size:${gx}px 100%, calc(100% - ${gx}px) 100%;-webkit-mask-position:0 0, ${gx}px 0;mask-position:0 0, ${gx}px 0;-webkit-mask-repeat:no-repeat;mask-repeat:no-repeat`
      : '';
    return {
      html: `<div class="tl-river${only ? ' only' : ''}" style="--tl-cols:${cols.n};grid-template-rows:${rows.map((r) => `${r.h}px`).join(' ')}${offCss}">${parts.join('')}</div>`,
      rows,
    };
  }

  function scrubber(rows, year) {
    const A = 1978;
    const B = 2014;
    const pos = (y) => (((y - A) / (B - A)) * 100).toFixed(2);
    const dens = rows
      .filter((r) => r.type === 'yr')
      .map((r) => `<i class="tl-sc-d" style="top:${pos(r.y0)}%;width:${3 + r.games.length * 2}px"></i>`)
      .join('');
    const ticks = [1980, 1990, 2000, 2010].map((y) => `<span class="tl-sc-t tab" style="top:${pos(y)}%">${String(y).slice(2)}</span>`).join('');
    const n = rows.find((r) => r.y0 === year);
    return `<div class="tl-scrub" role="slider" aria-label="Release year" aria-valuemin="1978" aria-valuemax="2014" aria-valuenow="${year}">
      <div class="tl-sc-track">${dens}${ticks}
        <span class="tl-sc-th" style="top:${pos(year)}%"><span class="tl-sc-bub"><b class="tab">${year}</b><small class="tab">${n ? `${n.games.length} games` : ''}</small></span></span>
      </div>
    </div>`;
  }

  function library(c) {
    SCHEME = c.scheme;
    const t = k.totals();
    const vbar = !!(c.device && c.device.vbar);
    // The Library reopens where you left it: 1994, Super Metroid's year, in the 16-bit years.
    const R = river({ games: D().games, from: RIVER.from, to: RIVER.to, lastGame: RIVER.lastGame, hi: 1994, offsetBefore: vbar ? 0 : 1991 });
    const bar = `<div class="tl-lib-bar"><button class="tl-sort" aria-label="Sort: Oldest first">${i('sort', 15)}<span>Sort: <b>Oldest first</b></span>${i('chevron-down', 14)}</button></div>`;
    return AK.scene(
      ['smetroid', 'chrono', 'sor2'],
      `${AK.field('f-page tl-f-lib')}${c.sys.shell({
        tab: 'library',
        title: 'Library',
        titleMode: vbar ? 'large' : 'inline',
        subtitle: `${k.num(t.games)} games · ${t.platforms} platforms`,
        actions: [AK.ARRIVING, AK.ACCOUNT],
        body: `<div class="tl-lib">${vbar ? bar : `<div class="tl-lib-spacer" style="height:${LIB_SPACER}px"></div>`}${R.html}<p class="tl-lib-end">Each lane is a console’s lifespan. Tap one to show only its games.</p></div>`,
        overlay: `${vbar ? '' : bar}${scrubber(R.rows, 1994)}`,
      })}`,
      'tl-s-library',
    );
  }

  // ================================================================ PLATFORM · one lane lit
  function platform(c) {
    SCHEME = c.scheme;
    const p = k.platform('snes');
    const L = laneColour('snes');
    const end = LANE_END.snes;
    const A = 1978;
    const B = 2014;
    const pos = (y) => (((y - A) / (B - A)) * 100).toFixed(2);
    const R = river({ games: k.gamesOn('snes'), from: p.year, to: end, lastGame: end, only: 'snes' });
    const head = `<section class="tl-ph" style="--c:${L.line};--ci:${L.ink}">
      <div class="tl-ph-top">
        <p class="tl-ph-years tab"><b>${p.year}</b><i aria-hidden="true"></i><b>${end}</b></p>
        <button class="tl-clear" aria-label="Clear the console filter">${i('x', 14)}<span>Clear</span></button>
      </div>
      <div class="tl-ph-axis" aria-hidden="true"><i class="tl-ph-seg" style="left:${pos(p.year)}%;width:${(((end - p.year + 1) / (B - A)) * 100).toFixed(2)}%"></i>${[1980, 1990, 2000, 2010]
        .map((y) => `<span class="tab" style="left:${pos(y)}%">${String(y).slice(2)}</span>`)
        .join('')}</div>
      <p class="tl-ph-note">Eight years alongside the NES, Genesis and Game Boy lanes · ${R.rows.filter((r) => r.type === 'yr').length} years with games here</p>
      <div class="tl-ph-chips">${AK.tier(p.tier)}<span class="tl-chip">${i('cpu', 14)}<span>${esc(p.core)}</span></span><span class="tl-chip">${i('gamepad', 14)}<span>${p.buttons} buttons</span></span></div>
    </section>`;
    return AK.scene(
      ['smetroid', 'chrono', 'earthbound'],
      `${AK.field('f-page tl-f-lib')}${c.sys.shell({
        tab: 'library',
        title: p.name,
        subtitle: `${p.games} games · ${p.installed} on this iPhone`,
        back: 'Library',
        actions: [{ icon: 'more', label: 'More' }],
        body: `<div class="tl-lib tl-plat">${head}${R.html}<p class="tl-lib-end">Other lanes stay in view, dimmed, so you can see what ran alongside.</p></div>`,
      })}`,
      'tl-s-platform',
    );
  }

  Studio.defineVersion({
    id: 'timeline',
    round: 3,
    extends: 'aurora',
    name: 'Aurora · Timeline',
    tagline: 'Your play is a river; your library is a history you can walk through.',
    thesis:
      'Time is the one axis a retro library really has. Play becomes a two-week river: every game in play is a lane of its own light, sessions are beads, saves hang off the lane, games you stopped trail away, and a sync conflict is a lane that forks and waits for you to choose. The Library becomes a year river: console lifespans run down a transit-map gutter beside the years, so generations visibly overlap, every year is a station with its covers, and a lane tap narrows everything to one console.',
    swatch: ['#07070C', '#FF7A3D', '#7B6FD6'],
    tabs: AX.TABS,
    fonts: [],
    notes: {
      home:
        'The river. A compact Now block (Super Metroid’s resume frame, “Maridia glass tube”, meta, sync, Resume) drops its own lane into a two-week play graph, Today at the top. Each game in play is a lane in its light; beads are sessions sized by minutes; saves hang off as small frames; games in progress begin with a head chip (tap to resume); games you stopped trail off in a dotted tail (“Put down 8 days ago”). EarthBound forks into a This iPhone branch and a Pixel 9 branch that meet at a Choose node. Ocarina of Time 3D has no preview: its resume point is a lit crop of its cover art, tagged “Cover”.',
      library:
        'The year river, oldest first, reopened where you left it (1994, Super Metroid’s year). A transit-map gutter carries every console’s lifespan (solid home consoles, dashed handhelds, a double rail for arcade); each lane’s name sticks to the top while the lane is on screen. Years are stations: a dot on each lane with games that year, a big numeral, counts and covers (in progress glows, on this iPhone carries a check). Empty stretches collapse into gap markers; a Photos-style scrubber (80 · 90 · 00 · 10) jumps anywhere. Tap a lane to filter.',
      platform:
        'The same river with one lane lit: Super Nintendo glows, every other lane dims to context, and only SNES covers remain. The header gives the console’s life on the decade axis (1991–1998), counts, tier and core, with a Clear chip back to everything.',
    },
    screens: AX.wrap({ home, library, platform }),
  });
})();
