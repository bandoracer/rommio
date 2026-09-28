/*
 * AURORA · TONIGHT — round 3 exploration.
 *
 * DESIGN PLAN
 * ===========
 * Thesis     Start from the evening you have, not the file you want. Home is a concierge:
 *            it asks how long you've got and deals a hand of three picks, each with the
 *            reason it was picked. The Library is explored by association: pull a thread
 *            from a game you love and follow it (genre → maker → players → …) through the
 *            collection. Platforms stay reachable, but they're one thread among many.
 *
 * Complaint  Today's Play is one game at a time (a paged carousel, a grey box when there
 *            is no preview). Tonight shows three honest options and every game in
 *            progress on one page: resuming Super Metroid is still one tap, above the
 *            fold. Today's Library stacks three filters (era ruler, platform facets,
 *            tier chips) in front of a one-cover coverflow. Threads puts many covers on
 *            screen at once and gives each rail a reason, so browsing becomes following.
 *
 * IA         Tabs: Play · Library · Stats · Settings · Search (the app's).
 *              Play      "Tonight". Kicker (the evening, and a flag if a resume point
 *                        needs a choice) → "How long have you got?" → four choice chips
 *                        (A few minutes · About an hour · All evening · With a friend)
 *                        → the hand: Keep going (large), Something new + Quick hit (pair)
 *                        → Deal again → In progress (every game, compact rows) → status.
 *              Library   "Threads". Jump tiles (Consoles · On this iPhone · Collections)
 *                        → Pull a thread (a chain of covers joined by labelled links,
 *                        ending in "Next" options) → thread rails: Series you've
 *                        started, Genres (big glass tiles), Makers, Two players, Never
 *                        played, Short sessions, Your collections → Consoles (tiers).
 *                        The page light comes from the thread's frontier game, so it
 *                        changes as you pull.
 *              Thread    (screen `collections`) one thread, e.g. Metroidvanias: a header
 *                        lit by its games (fan of covers, count, description, where the
 *                        thread comes from); its games in progress order (in progress →
 *                        played → never played), each with why it belongs (series, maker,
 *                        year); "one thread over" when a section is empty; then Keep
 *                        pulling — neighbouring threads that lead outside this one.
 *
 * Honesty    Every reason is computed from core/data.js here, never written by hand:
 *              Keep going     first game in progress (with a preview, not in conflict)
 *                             whose average session (D.sessions) fits the chosen time.
 *              Something new  never played, Touch tier, not already arriving; ranked by
 *                             this month's minutes in the same platform + genre family.
 *              Quick hit      on this iPhone, average session ≤ 20 min; one with a saved
 *                             high score first.
 *              Threads        counts and hours from D.games; series and makers from
 *                             AuroraX.SERIES / publisher; chain links are checked (a link
 *                             that isn't true throws, so the studio shows an error).
 *
 * Components Choice chip (glass pill, selected = solid ink with the game's glow)
 *            Pick card (lit glass in the game's colours: frame or cover, role, reason with
 *              an icon, one action) · Row (still, name, moment, sync, round action)
 *            Still: the resume frame; with no preview (oot3d) a *title card* built from
 *              the cover — the art full-bleed, washed in the game's light, matted with a
 *              hairline and captioned with its platform, so it reads as art, not as a
 *              missing screenshot · Two-up still for EarthBound's two resume points.
 *            Chain (covers in a row; each link is a thread strung over two covers, tied to
 *              their tops, half in one game's light and half in the next, its label on the
 *              span — three covers fit on a phone; the frontier ringed; a dashed open end
 *              down to "+") · Next option chip
 *            Thread header (title, count, reason, See all) · Series tile (stacked covers,
 *              a dot per game: filled = played) · Genre tile (big glass, three fanned
 *              covers) · Maker tile (wordmark in Bricolage) · Cover card · Platform chip.
 *
 * Scale      72 games or 2,164: every rail is capped (8–10) with See all; Genres shows
 *            the six largest; a thread needs at least two games to exist (so a small
 *            library doesn't fill with singletons), and rails rank by your play (recency,
 *            hours) so they stay personal as the library grows.
 *
 * Metadata   When RomM has no genre or series for a game (no IGDB match), that game
 *            simply isn't in genre or series threads; it still appears in the threads
 *            built from what every game has — platform, year (when known), players, and
 *            your own play (Never played, Short sessions). Chain links only use fields
 *            both games have and fall back to "Also on SNES" / "The 16-bit years".
 *            Unmatched games get their own last thread ("Not matched in RomM") so none
 *            disappear. The thread page names its source ("Games RomM lists as …").
 *
 * Material   Aurora's: page light from the game in focus, glass, Bricolage Grotesque
 *            (display, condensed) + Figtree (UI), Daylight and Night tokens.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;
  const AK = window.AuroraKit;
  const X = window.AuroraX;

  // ================================================================ data: honest signals
  const G = (id) => k.game(id);
  const isPlayed = (g) => g.played != null;
  const sessionsOf = (id) => D().sessions.filter((s) => s.game === id);
  const avgSession = (id) => {
    const s = sessionsOf(id);
    return s.length ? s.reduce((n, x) => n + x.minutes, 0) / s.length : null;
  };
  const about = (min) => `about ${Math.max(5, Math.round(min / 5) * 5)} min`;
  const hoursOf = (games) => games.reduce((n, g) => n + (g.hours || 0), 0);
  const hoursLabel = (h) => (h >= 1 ? `${Math.round(h)} h` : k.hours(h));
  const byRecent = (a, b) => (a.played ?? 1e12) - (b.played ?? 1e12);
  const tierOf = (g) => k.platformOf(g).tier;

  // Genre families (RomM's IGDB genres, grouped as a person would name them).
  const GENRES = [
    { id: 'platformer', name: 'Platformers', one: 'Platformer', noun: 'platformers', test: (x) => /Platformer/.test(x) },
    { id: 'rpg', name: 'Role-playing', one: 'Role-playing', noun: 'role\u2011playing', test: (x) => /RPG/.test(x) },
    { id: 'action', name: 'Action', one: 'Action', noun: 'action', test: (x) => ['Action', 'Run and gun', "Beat 'em up", 'Fighting'].includes(x) },
    { id: 'metroidvania', name: 'Metroidvanias', one: 'Metroidvania', noun: 'metroidvanias', test: (x) => x === 'Action · Exploration' },
    { id: 'adventure', name: 'Adventure', one: 'Adventure', noun: 'adventure', test: (x) => /Adventure/.test(x) },
    { id: 'racing', name: 'Racing', one: 'Racing', noun: 'racing', test: (x) => x === 'Racing' },
    { id: 'shooter', name: 'Shooters', one: 'Shooter', noun: 'shooters', test: (x) => x === 'Shooter' },
    { id: 'puzzle', name: 'Puzzle and maze', one: 'Puzzle', noun: 'puzzle', test: (x) => x === 'Puzzle' || x === 'Maze' },
    { id: 'strategy', name: 'Strategy', one: 'Strategy', noun: 'strategy', test: (x) => x === 'Strategy' },
  ];
  const genreOf = (g) => GENRES.find((x) => x.test(g.genre)) || null;
  const genreGames = (gid) => D().games.filter((g) => (genreOf(g) || {}).id === gid);
  const seriesOf = (id) => X.SERIES[id] || null;
  const seriesGames = (name) => D().games.filter((g) => X.SERIES[g.id] === name);
  const makerGames = (m) => D().games.filter((g) => g.publisher === m);
  const monthMinutes = (pred) => D().sessions.filter((s) => s.day < 30 && pred(G(s.game))).reduce((n, s) => n + s.minutes, 0);
  const ERA16 = AK.ERAS[1];
  const eraGames = (e) => D().games.filter((g) => e.plats.includes(g.platform));

  // ================================================================ the deal (Home)
  const TIMES = [
    { id: 'few', label: 'A few minutes', icon: 'timer', max: 20 },
    { id: 'hour', label: 'About an hour', icon: 'hourglass', max: 75 },
    { id: 'evening', label: 'All evening', icon: 'moon-star', max: 600 },
    { id: 'friend', label: 'With a friend', icon: 'users', max: 600, players: 2 },
  ];
  function deal(tid) {
    const t = TIMES.find((x) => x.id === tid);
    const keep = X.IN_PROGRESS.find((id) => X.hasPreview(id) && AK.lastMoment(id).remote !== 'conflict' && (avgSession(id) ?? Infinity) <= t.max);
    const arriving = new Set(D().downloads.filter((d) => d.state !== 'done').map((d) => d.game));
    const taste = (g) => monthMinutes((h) => h.platform === g.platform && genreOf(h) === genreOf(g));
    const fresh = D()
      .games.filter((g) => !isPlayed(g) && !arriving.has(g.id) && tierOf(g) === 'touch')
      .map((g) => ({ g, min: taste(g) }))
      .sort((a, b) => b.min - a.min || b.g.rating - a.g.rating)[0];
    const quick = D()
      .games.filter((g) => g.installed && g.id !== keep && (avgSession(g.id) ?? Infinity) <= 20)
      .sort((a, b) => (AK.lastMoment(b.id) ? 1 : 0) - (AK.lastMoment(a.id) ? 1 : 0) || b.rating - a.rating)[0];
    return { t, keep, fresh, quick };
  }

  // ================================================================ stills
  // The resume frame, or — with no preview — a title card made from the cover art.
  function still(id, cls = '') {
    if (id === 'earthbound') {
      const [a, b] = D().conflict.branches;
      return `<span class="tn-still tn-two ${cls}"><img class="px" src="${k.frame(id, a.frame)}" alt=""><img class="px" src="${k.frame(id, b.frame)}" alt=""></span>`;
    }
    if (X.hasPreview(id)) return `<span class="tn-still ${cls}"><img class="px" src="${k.frame(id, AK.lastMoment(id).frame)}" alt=""></span>`;
    return titleCard(id, cls);
  }
  const titleCard = (id, cls = '', { title = false } = {}) => {
    const g = G(id);
    return `<span class="tn-still tn-tc ${cls}" style="${AK.lit(id)}"><img class="px" src="${k.cover(id, 'wide')}" alt=""><i class="tn-tc-wash" aria-hidden="true"></i>${
      title ? `<b class="tn-tc-title">${esc(g.title)}</b>` : ''
    }<small class="tn-tc-plat">${esc(AK.plat(g).short)}</small></span>`;
  };

  const role = (icon, label) => `<span class="tn-role">${i(icon, 13)}<span>${label}</span></span>`;
  const why = (icon, html) => `<p class="tn-why">${i(icon, 15)}<span>${html}</span></p>`;

  // ================================================================ HOME
  function chip(t, on) {
    return `<button class="tn-chip${on ? ' on' : ''}" role="radio" aria-checked="${on}">${i(t.icon, 17)}<span>${esc(t.label)}</span>${on ? i('check', 15, 'tn-chip-ok') : ''}</button>`;
  }

  function keepCard(id) {
    const g = G(id);
    const m = AK.lastMoment(id);
    return `<article class="tn-pick tn-keep" style="${AK.lit(id)}">
      <div class="tn-keep-img">
        <img class="px" src="${k.frame(id, m.frame)}" alt="${esc(g.title)} at ${esc(m.name)}">
        ${role('history', 'Keep going')}
        <span class="tn-keep-sync">${AK.sync(m.remote, m.remote === 'waiting' ? 'Resume waiting to sync' : '')}</span>
        <div class="tn-keep-cap">
          <p class="tn-eyebrow"><b>${esc(g.title)}</b><span>${esc(AK.plat(g).short)}</span></p>
          <h2>${esc(m.name)}</h2>
          <p>${esc(AK.kindLabel(m))} · ${esc(k.ago(m.at))} · ${esc(m.device)}</p>
        </div>
      </div>
      <div class="tn-pick-body">
        ${why('clock', `Your sessions run ${about(avgSession(id))} · ${esc(k.hours(g.hours))} in`)}
        <div class="tn-act">${AK.btn(AK.KIND[m.kind].verb, { icon: 'play', kind: 'primary', cls: 'lg grow' })}${AK.btn('', { icon: 'info', cls: 'round lg', aria: `${g.title} details` })}</div>
      </div>
    </article>`;
  }

  function freshCard({ g, min }) {
    const p = AK.plat(g);
    const gen = genreOf(g);
    const kin = makerGames(g.publisher).filter(isPlayed).sort((a, b) => b.hours - a.hours)[0];
    return `<article class="tn-pick tn-half" style="${AK.lit(g.id)}">
      <div class="tn-half-img"><img class="px" src="${k.cover(g.id, 'wide')}" alt="">${role('sparkles', 'Something new')}</div>
      <div class="tn-pick-body">
        <div class="tn-half-t"><h3>${esc(g.title)}</h3><p>${esc(p.short)} · ${g.year} · Never played</p></div>
        ${why('activity', `${esc(AK.hm(min))} of ${esc(p.short)} ${gen.noun} this month`)}
        <p class="tn-facts"><span>Rated ${g.rating} · ${g.players > 1 ? `up to ${g.players} players` : '1 player'}</span>${kin ? `<span>By ${esc(g.publisher)}, like ${esc(kin.title)}</span>` : ''}</p>
        ${AK.btn(`Download · ${k.size(g.size)}`, { icon: 'download', kind: 'glass', cls: 'md wide tn-tinted' })}
      </div>
    </article>`;
  }

  function quickCard(g) {
    const m = AK.lastMoment(g.id);
    const best = m && /^High score/.test(m.name) ? m : null;
    return `<article class="tn-pick tn-half" style="${AK.lit(g.id)}">
      <div class="tn-half-img">${m ? `<img class="px" src="${k.frame(g.id, m.frame)}" alt="">` : `<img class="px" src="${k.cover(g.id, 'wide')}" alt="">`}${role('zap', 'Quick hit')}</div>
      <div class="tn-pick-body">
        <div class="tn-half-t"><h3>${esc(g.title)}</h3><p>${esc(AK.plat(g).short)} · ${g.year} · On this iPhone</p></div>
        ${best ? why('trophy', `${esc(best.name)} — beat it`) : why('timer', `Your games run ${about(avgSession(g.id))}`)}
        <p class="tn-facts"><span>${best ? `Runs last ${about(avgSession(g.id))}` : `${g.players} players`}</span>${m ? `<span>Game-save data</span><span class="tn-fact-sync">${AK.sync(m.remote)}</span>` : ''}</p>
        ${AK.btn('Play', { icon: 'play', kind: 'glass', cls: 'md wide tn-tinted' })}
      </div>
    </article>`;
  }

  // Compact row for a game in progress.
  function progRow(id) {
    const g = G(id);
    const m = AK.lastMoment(id);
    let line2;
    let line3;
    let act;
    if (id === 'earthbound') {
      const [a, b] = D().conflict.branches;
      line2 = 'Two resume points';
      line3 = `${AK.sync('conflict')}<span>${esc(a.where)} or ${esc(b.where)}</span>`;
      act = `<button class="tn-rbtn bad" aria-label="Choose a resume point for EarthBound">${i('split', 18)}</button>`;
    } else if (!X.hasPreview(id)) {
      line2 = `${k.hours(g.hours)} played`;
      line3 = `${AK.sync(g.sync)}<span>${esc(k.ago(g.played))}</span>`;
      act = `<button class="tn-rbtn" aria-label="Resume ${esc(g.title)}">${i('play', 17)}</button>`;
    } else {
      line2 = esc(m.name);
      line3 = `${AK.sync(m.remote)}<span>${esc(k.ago(m.at))}</span>`;
      act = `<button class="tn-rbtn" aria-label="${esc(AK.KIND[m.kind].verb)} ${esc(g.title)}">${i('play', 17)}</button>`;
    }
    return `<div class="tn-row${id === 'earthbound' ? ' needs' : ''}" style="${AK.lit(id)}">
      ${still(id)}
      <span class="tn-row-main"><b>${esc(g.title)}</b><span class="tn-row-m">${line2}</span><span class="tn-row-s">${line3}</span></span>
      ${act}
    </div>`;
  }

  function homeBody() {
    const d = deal('hour');
    const needs = X.IN_PROGRESS.filter((id) => AK.lastMoment(id) && AK.lastMoment(id).remote === 'conflict');
    return `<div class="tn-home">
      <section class="tn-ask">
        <div class="tn-top">${AK.kicker('Wednesday evening')}${
          needs.length ? `<button class="tn-flag">${AK.sync('conflict', `${esc(G(needs[0]).title)} needs a choice`)}</button>` : ''
        }</div>
        <h1 class="tn-q">How long have you got?</h1>
        <div class="tn-chips" role="radiogroup" aria-label="How long have you got?">${TIMES.map((t) => chip(t, t.id === d.t.id)).join('')}</div>
      </section>
      <section class="tn-hand" aria-label="Three picks for about an hour">
        ${keepCard(d.keep)}
        <div class="tn-pair">${freshCard(d.fresh)}${quickCard(d.quick)}</div>
        <div class="tn-deal">${AK.btn('Deal again', { icon: 'shuffle', kind: 'glass', cls: 'md' })}<p>Dealt from your sessions, ratings and what’s on this iPhone</p></div>
      </section>
      <section class="tn-prog">
        ${AK.kicker('In progress', `<span class="tab">${X.IN_PROGRESS.length} games · ${needs.length} needs a choice</span>`)}
        <div class="tn-rows">${X.IN_PROGRESS.map(progRow).join('')}</div>
      </section>
      ${AK.statusLine()}
    </div>`;
  }

  function homeScreen(c) {
    const keep = deal('hour').keep;
    return AK.scene(
      [keep],
      `${AK.field('f-page tn-f-home', k.cover(keep, 'tall'))}${c.sys.shell({
        tab: 'play',
        titleMode: 'none',
        actions: [AK.ARRIVING, AK.ACCOUNT],
        edge: false,
        body: homeBody(),
      })}`,
      'a-s-play tn-s-home',
    );
  }

  // ================================================================ LIBRARY (threads)
  const LINKS = {
    genre: (a, b) => genreOf(a) && genreOf(a) === genreOf(b) && { icon: 'shapes', label: genreOf(a).one },
    maker: (a, b) => a.publisher === b.publisher && { icon: 'tag', label: a.publisher },
    players: (a, b) => a.players >= 2 && b.players >= 2 && { icon: 'users', label: '2 players' },
    series: (a, b) => seriesOf(a.id) && seriesOf(a.id) === seriesOf(b.id) && { icon: 'layers', label: `${seriesOf(a.id)} series` },
    year: (a, b) => a.year === b.year && { icon: 'calendar', label: String(a.year) },
  };
  // The thread pulled from Super Metroid (a favourite). Each link is checked against the data.
  const CHAIN = [{ id: 'smetroid' }, { id: 'sotn', via: 'genre' }, { id: 'contra3', via: 'maker' }, { id: 'sor2', via: 'players' }];
  function chainLinks() {
    return CHAIN.map((n, x) => {
      if (!x) return { ...n };
      const link = LINKS[n.via](G(CHAIN[x - 1].id), G(n.id));
      if (!link) throw new Error(`Tonight: ${CHAIN[x - 1].id} → ${n.id} is not linked by ${n.via}`);
      return { ...n, link };
    });
  }
  function nextHops() {
    const seen = new Set(CHAIN.map((n) => n.id));
    const last = G(CHAIN[CHAIN.length - 1].id);
    const gen = genreOf(last);
    return [
      { icon: 'layers', label: `${seriesOf(last.id)} series`, games: seriesOf(last.id) ? seriesGames(seriesOf(last.id)) : [] },
      { icon: 'tag', label: `Also by ${last.publisher}`, games: makerGames(last.publisher) },
      { icon: 'calendar', label: String(last.year), games: D().games.filter((g) => g.year === last.year) },
      { icon: 'shapes', label: gen ? gen.name : '', games: gen ? genreGames(gen.id) : [] },
    ]
      .map((o) => ({ ...o, games: o.games.filter((g) => !seen.has(g.id)) }))
      .filter((o) => o.games.length)
      .slice(0, 3);
  }

  // Covers sit in a row; each link is a thread strung over two covers (tied to the top of
  // each) with its label on the span, so three covers and their links fit on a phone.
  function chain() {
    const nodes = chainLinks();
    const last = nodes.length - 1;
    const node = (n, x) => {
      const g = G(n.id);
      return `<li class="tn-node${x === last ? ' here' : ''}${g.installed ? '' : ' unlit'}" style="${AK.lit(n.id)}">
        <span class="tn-node-art"><img class="tn-node-glow" src="${k.cover(n.id, 'box')}" alt=""><img class="tn-node-img" src="${k.cover(n.id, 'box')}" alt="${esc(g.title)}"></span>
        <span class="tn-node-cap"><b>${esc(g.title)}</b><span>${esc(AK.plat(g).short)} · ${g.year}</span></span>
      </li>`;
    };
    const arc = (a, b, x) =>
      `<span class="tn-arc" style="--i:${x};--a:${AK.lights(a.id).k1};--b:${AK.lights(b.id).k1}"><button class="tn-link-pill" aria-label="${esc(b.link.label)} thread">${i(b.link.icon, 13)}<span>${esc(b.link.label)}</span></button></span>`;
    const arcs = nodes.slice(1).map((n, x) => arc(nodes[x], n, x + 1)).join('');
    const open = `<span class="tn-arc open" style="--i:${nodes.length};--a:${AK.lights(nodes[last].id).k1};--b:${AK.lights(nodes[last].id).k1}" aria-hidden="true"></span>`;
    return `<div class="tn-chain" aria-label="A thread from ${esc(G(nodes[0].id).title)}">
      <ol class="tn-chain-row">${nodes.map(node).join('')}<li class="tn-pull-li"><button class="tn-pull" aria-label="Pull the thread further">${i('plus', 20)}</button></li></ol>
      ${arcs}${open}
    </div>`;
  }

  function pullHero() {
    const first = G(CHAIN[0].id);
    const last = G(CHAIN[CHAIN.length - 1].id);
    const hops = nextHops();
    return `<section class="tn-pullsec" aria-label="Pull a thread">
      <header class="tn-pull-h">
        <div><h2>Pull a thread</h2><p>From ${esc(first.title)}, a favourite · ${esc(k.hours(first.hours))} played</p></div>
        <button class="tn-iconbtn" aria-label="Start from another game">${i('shuffle', 18)}</button>
      </header>
      ${chain()}
      <p class="tn-next-l">Next from ${esc(last.title)}</p>
      <div class="tn-next">
        ${hops.map((o) => `<button class="tn-opt" style="${AK.lit(o.games[0].id)}">${i(o.icon, 14)}<span>${esc(o.label)}</span><em class="tab">${o.games.length}</em></button>`).join('')}
      </div>
    </section>`;
  }

  const threadHead = (title, n, reason, all = true) =>
    `<header class="tn-th"><div><h3>${title}<span class="tn-n tab">${n}</span></h3><p>${reason}</p></div>${all ? `<button class="tn-all">See all${i('chevron-right', 15)}</button>` : ''}</header>`;

  // Series you've started, most recently played first.
  function seriesRail() {
    const list = [...new Set(Object.values(X.SERIES))]
      .map((name) => ({ name, games: seriesGames(name) }))
      .filter((s) => s.games.length >= 2 && s.games.some(isPlayed))
      .map((s) => ({ ...s, recent: Math.min(...s.games.filter(isPlayed).map((g) => g.played)) }))
      .sort((a, b) => a.recent - b.recent);
    const tile = (s) => {
      const played = s.games.filter(isPlayed).length;
      const face = [...s.games].sort(byRecent);
      return `<article class="tn-ser" style="${AK.lit(face[0].id)}">
        <span class="tn-ser-covers">${face.slice(0, 4).map((g) => `<img class="${g.installed ? '' : 'unlit'}" src="${k.cover(g.id, 'box')}" alt="">`).join('')}</span>
        <b>${esc(s.name)}</b>
        <span class="tn-ser-meta"><span class="tn-dots" aria-hidden="true">${face.map((g) => `<i class="${isPlayed(g) ? 'on' : ''}"></i>`).join('')}</span><small class="tab">${played} of ${s.games.length} played</small></span>
      </article>`;
    };
    return `<section class="tn-thread">${threadHead('Series you’ve started', list.length, 'Most recently played first')}<div class="tn-rail">${list.map(tile).join('')}</div></section>`;
  }

  function genreGrid() {
    const tiles = GENRES.map((x) => ({ x, games: genreGames(x.id) }))
      .filter((t) => t.games.length >= 2)
      .sort((a, b) => b.games.length - a.games.length);
    const tile = (t) => {
      const face = [...t.games].sort((a, b) => b.hours - a.hours || b.rating - a.rating).slice(0, 3);
      const h = hoursOf(t.games);
      return `<article class="tn-gen" style="${AK.lit(face.map((g) => g.id))}">
        <span class="tn-fan" aria-hidden="true">${face.map((g) => `<img src="${k.cover(g.id, 'box')}" alt="">`).join('')}</span>
        <span class="tn-gen-t"><b>${esc(t.x.name)}</b><small class="tab">${t.games.length} games · ${h ? `${hoursLabel(h)} played` : 'none played yet'}</small></span>
      </article>`;
    };
    return `<section class="tn-thread">${threadHead('Genres', tiles.length, 'Biggest first · hours are yours')}<div class="tn-genres">${tiles.slice(0, 6).map(tile).join('')}</div></section>`;
  }

  function makerRail() {
    const makers = [...new Set(D().games.map((g) => g.publisher))]
      .map((m) => ({ m, games: makerGames(m) }))
      .sort((a, b) => b.games.length - a.games.length || hoursOf(b.games) - hoursOf(a.games));
    const tile = (x) => {
      const face = [...x.games].sort((a, b) => b.hours - a.hours || b.rating - a.rating);
      const h = hoursOf(x.games);
      return `<article class="tn-mk" style="${AK.lit(face[0].id)}">
        <b>${esc(x.m)}</b>
        <small class="tab">${x.games.length} games${h ? ` · ${hoursLabel(h)}` : ''}</small>
        <span class="tn-mk-covers">${face.slice(0, 4).map((g) => `<img class="${g.installed ? '' : 'unlit'}" src="${k.cover(g.id, 'box')}" alt="">`).join('')}</span>
      </article>`;
    };
    return `<section class="tn-thread">${threadHead('Makers', makers.length, 'The studios behind your library, most games first')}<div class="tn-rail">${makers.slice(0, 6).map(tile).join('')}</div></section>`;
  }

  // A cover card with a caption; unlit when not on this iPhone.
  function coverCard(g, sub) {
    const t = tierOf(g);
    return `<figure class="tn-cv${g.installed ? '' : ' unlit'}" style="${AK.lit(g.id)}">
      <span class="tn-cv-art"><img src="${k.cover(g.id, 'box')}" alt="">${g.installed ? '' : `<span class="tn-cv-dl">${i('cloud-download', 13)}</span>`}</span>
      <figcaption><b>${esc(g.title)}</b><small>${sub}</small>${t !== 'touch' ? AK.tier(t) : ''}</figcaption>
    </figure>`;
  }

  function playersRail() {
    const games = D().games.filter((g) => g.players >= 2).sort((a, b) => byRecent(a, b) || b.rating - a.rating);
    const together = D().sessions.find((s) => / with /.test(s.note));
    const reason = together ? `Last together: ${esc(G(together.game).title)} ${esc(together.note.slice(together.note.indexOf('with ')))}, ${together.day} days ago` : 'Games for two or more';
    return `<section class="tn-thread">${threadHead('Two players', games.length, reason)}<div class="tn-rail">${games
      .slice(0, 8)
      .map((g) => coverCard(g, `${g.players} players${g.installed ? '' : ` · ${k.size(g.size)}`}`))
      .join('')}</div></section>`;
  }

  function neverRail() {
    const games = X.unplayed().sort((a, b) => b.rating - a.rating);
    const here = games.filter((g) => g.installed).length;
    return `<section class="tn-thread">${threadHead('Never played', games.length, `Best rated first · ${here ? `${here} on this iPhone` : 'none on this iPhone yet'}`)}<div class="tn-rail">${games
      .slice(0, 8)
      .map((g) => coverCard(g, `Rated ${g.rating} · ${esc(AK.plat(g).short)}`))
      .join('')}</div></section>`;
  }

  function shortRail() {
    const games = D()
      .games.filter((g) => (avgSession(g.id) ?? Infinity) <= 20 || g.platform === 'arcade' || (genreOf(g) || {}).id === 'puzzle')
      .sort((a, b) => (avgSession(a.id) ?? 99) - (avgSession(b.id) ?? 99));
    const timed = games.map((g) => avgSession(g.id)).filter((x) => x != null);
    const avg = timed.reduce((n, x) => n + x, 0) / timed.length;
    return `<section class="tn-thread">${threadHead('Short sessions', games.length, `Arcade and puzzle · your sessions here average ${Math.round(avg)} min`)}<div class="tn-rail">${games
      .map((g) => coverCard(g, avgSession(g.id) != null ? `${Math.round(avgSession(g.id))} min a run` : `${esc(AK.plat(g).short)} · ${esc(g.genre)}`))
      .join('')}</div></section>`;
  }

  // Games without RomM genre metadata still get a home, so none disappear from browsing.
  function unmatchedRail() {
    const games = D().games.filter((g) => !genreOf(g));
    if (!games.length) return '';
    return `<section class="tn-thread">${threadHead('Not matched in RomM', games.length, 'No genre or series yet · still in Consoles, years and players')}<div class="tn-rail">${games
      .slice(0, 8)
      .map((g) => coverCard(g, `${esc(AK.plat(g).short)}${g.year ? ` · ${g.year}` : ''}`))
      .join('')}</div></section>`;
  }

  function collectionRail() {
    const cols = D().collections.filter((x) => x.kind === 'manual');
    const tile = (x) => `<article class="tn-col" style="${AK.lit(x.games.slice(0, 3))}">
      <span class="tn-ser-covers">${x.games.slice(0, 4).map((id) => `<img class="${G(id).installed ? '' : 'unlit'}" src="${k.cover(id, 'box')}" alt="">`).join('')}</span>
      <b>${esc(x.name)}</b><small class="tab">${x.games.length} games<br>${x.games.filter((id) => G(id).installed).length} on this iPhone</small>
    </article>`;
    return `<section class="tn-thread">${threadHead('Your collections', cols.length, 'Threads you tied yourself')}<div class="tn-rail">${cols.map(tile).join('')}</div></section>`;
  }

  function consoles() {
    const plats = [...D().platforms].sort((a, b) => b.games - a.games);
    const n = (t) => k.tier(t).length;
    const browseOnly = `Browse only — ${k
      .tier('unsupported')
      .map((p) => `${p.name}: ${p.blocked.replace(/^Browse only — /, '').replace(/^./, (x) => x.toLowerCase())}`)
      .join(' · ')}`;
    const chipP = (p) =>
      `<button class="tn-plat${p.tier === 'touch' ? '' : ' t-' + p.tier}"><i style="background:${p.color}"></i><b>${esc(p.short)}</b><span class="tab">${k.num(p.games)}</span>${
        p.tier === 'touch' ? `<small class="tab">${p.installed ? `${p.installed} here` : 'none here'}</small>` : `<small>${esc(k.tierLabel(p.tier))}</small>`
      }</button>`;
    return `<section class="tn-thread" id="consoles">${threadHead('Consoles', plats.length, 'Still here when you want to browse by machine')}
      <div class="tn-plats">${plats.slice(0, 9).map(chipP).join('')}</div>
      <div class="tn-tiers">${AK.tier('touch', ` <b class="tab">${n('touch')}</b>`)}${AK.tier('controller', ` <b class="tab">${n('controller')}</b>`)}${AK.tier('unsupported', ` <b class="tab">${n('unsupported')}</b>`)}<span class="tn-tier-note">${esc(browseOnly)}</span></div>
    </section>`;
  }

  function libraryScreen(c) {
    const t = k.totals();
    const cols = D().collections.length;
    return AK.scene(
      [CHAIN[CHAIN.length - 1].id],
      `${AK.field('f-page tn-f-lib', k.cover(CHAIN[CHAIN.length - 1].id, 'tall'))}${c.sys.shell({
        tab: 'library',
        title: 'Library',
        subtitle: `${k.num(t.games)} games · ${t.platforms} platforms · ${t.installed} on this iPhone`,
        actions: [AK.ARRIVING, AK.ACCOUNT],
        body: `<div class="tn-lib">
          <nav class="tn-jump" aria-label="Browse by">
            <button class="tn-jt">${i('gamepad', 17)}<b class="tab">${t.platforms}</b><span>Consoles</span></button>
            <button class="tn-jt">${i('smartphone', 17)}<b class="tab">${t.installed}</b><span>On this iPhone</span></button>
            <button class="tn-jt">${i('collections', 17)}<b class="tab">${cols}</b><span>Collections</span></button>
          </nav>
          ${pullHero()}
          ${seriesRail()}
          ${genreGrid()}
          ${makerRail()}
          ${playersRail()}
          ${neverRail()}
          ${shortRail()}
          ${collectionRail()}
          ${unmatchedRail()}
          ${consoles()}
        </div>`,
      })}`,
      'a-s-library tn-s-lib',
    );
  }

  // ================================================================ THREAD (drill-in)
  function threadRow(g) {
    const m = AK.lastMoment(g.id);
    const s = seriesOf(g.id);
    const tags = [s ? `${i('layers', 12)}${esc(s)} series` : '', `${i('tag', 12)}${esc(g.publisher)}`, `${i('calendar', 12)}${g.year}`].filter(Boolean);
    const verb = m ? AK.KIND[m.kind].verb : 'Play';
    return `<article class="tn-tg" style="${AK.lit(g.id)}">
      <button class="tn-tg-go" aria-label="${esc(verb)} ${esc(g.title)}">${m && X.hasPreview(g.id) ? still(g.id, 'lg') : titleCard(g.id, 'lg')}<span class="tn-tg-play">${i(m ? 'play' : 'download', 14)}</span></button>
      <div class="tn-tg-main">
        <b>${esc(g.title)}</b>
        <span class="tn-tg-m">${m ? esc(m.name) : 'Never played'}</span>
        ${m ? `<span class="tn-tg-s">${AK.sync(m.remote)}<span>${esc(k.ago(m.at))}</span></span>` : ''}
        <span class="tn-tg-h tab">${g.hours ? `${esc(k.hours(g.hours))} played` : 'Not started'}</span>
      </div>
      <span class="tn-tags" aria-label="Why it belongs">${tags.map((x) => `<span>${x}</span>`).join('')}</span>
    </article>`;
  }

  function threadScreen(c) {
    const T = GENRES.find((x) => x.id === 'metroidvania');
    const games = genreGames(T.id);
    const inProg = games.filter((g) => X.IN_PROGRESS.includes(g.id)).sort(byRecent);
    const playedG = games.filter((g) => isPlayed(g) && !X.IN_PROGRESS.includes(g.id)).sort(byRecent);
    const never = games.filter((g) => !isPlayed(g)).sort((a, b) => b.rating - a.rating);
    const fan = [...games].sort((a, b) => b.hours - a.hours);
    const h = hoursOf(games);

    // Neighbouring threads: series and makers of these games, then their era.
    const series = [...new Set(games.map((g) => seriesOf(g.id)).filter(Boolean))].map((n) => ({ icon: 'layers', label: `${n} series`, games: seriesGames(n) }));
    const makers = [...new Set(games.map((g) => g.publisher))].map((m) => ({ icon: 'tag', label: `Also by ${m}`, games: makerGames(m) }));
    const era = { icon: 'calendar', label: ERA16.name, games: eraGames(ERA16) };
    const outside = (o) => o.games.some((g) => (genreOf(g) || {}).id !== T.id);
    const links = [series.find((x) => x.label.startsWith('Castlevania')), makers.find((x) => x.label.endsWith('Konami')), era, ...series, ...makers]
      .filter((x, n, a) => x && outside(x) && a.indexOf(x) === n);

    // "One thread over": the best-rated unplayed game sharing a series or maker with this thread.
    const near = D()
      .games.filter((g) => !isPlayed(g) && (genreOf(g) || {}).id !== T.id)
      .map((g) => {
        const bySeries = games.find((x) => seriesOf(x.id) && seriesOf(x.id) === seriesOf(g.id));
        return { g, kin: bySeries || games.find((x) => x.publisher === g.publisher), series: !!bySeries };
      })
      .filter((x) => x.kin)
      .sort((a, b) => b.series - a.series || b.g.rating - a.g.rating)[0];

    const sec = (title, list, body) => `<section class="tn-tsec">${AK.kicker(title, `<span class="tab">${list.length}</span>`)}${body}</section>`;
    const nearCard = near
      ? `<article class="tn-near" style="${AK.lit(near.g.id)}">
          <img class="tn-near-img" src="${k.cover(near.g.id, 'box')}" alt="">
          <div><p class="tn-near-k">One thread over</p><b>${esc(near.g.title)}</b>
          <span class="tn-near-d">${near.series ? `${esc(seriesOf(near.g.id))} series, like ${esc(near.kin.title)}` : `By ${esc(near.g.publisher)}, like ${esc(near.kin.title)}`} · ${esc(AK.plat(near.g).short)} · ${near.g.year} · Never played</span>
          ${AK.btn(`Download · ${k.size(near.g.size)}`, { icon: 'download', kind: 'glass', cls: 'sm tn-tinted' })}</div>
        </article>`
      : '';

    const body = `<div class="tn-tp">
      <header class="tn-tp-head">
        <span class="tn-tp-fan" aria-hidden="true">${fan.map((g) => `<span style="${AK.lit(g.id)}"><img class="tn-tp-glow" src="${k.cover(g.id, 'box')}" alt=""><img src="${k.cover(g.id, 'box')}" alt=""></span>`).join('')}</span>
        <p class="tn-tp-src">${i('shapes', 13)}<span>Genre thread</span></p>
        <h1>${esc(T.name)}</h1>
        <p class="tn-tp-n tab">${games.length} games · ${hoursLabel(h)} played · ${inProg.length} in progress</p>
        <p class="tn-tp-d">One big map that opens up as you earn new abilities. Every locked door is a promise.</p>
        <p class="tn-tp-from">Games RomM lists as “Action · Exploration”</p>
      </header>
      ${inProg.length ? sec('In progress', inProg, `<div class="tn-tgs">${inProg.map(threadRow).join('')}</div>`) : ''}
      ${playedG.length ? sec('Played', playedG, `<div class="tn-tgs">${playedG.map(threadRow).join('')}</div>`) : ''}
      ${sec(
        'Never played',
        never,
        never.length
          ? `<div class="tn-tgs">${never.map(threadRow).join('')}</div>`
          : `<p class="tn-done">${i('check-circle', 16)}<span>You’ve started all ${games.length === 4 ? 'four' : games.length}.</span></p>${nearCard}`,
      )}
      <section class="tn-tsec tn-pulling">
        ${AK.kicker('Keep pulling', '<span>Neighbouring threads</span>')}
        <div class="tn-tp-links">${links
          .slice(0, 5)
          .map((o) => `<button class="tn-opt" style="${AK.lit(o.games[0].id)}">${i(o.icon, 14)}<span>${esc(o.label)}</span><em class="tab">${o.games.length}</em></button>`)
          .join('')}</div>
      </section>
    </div>`;

    return AK.scene(
      fan.slice(0, 3).map((g) => g.id),
      `${AK.field('f-page tn-f-thread', k.cover(fan[0].id, 'tall'))}${c.sys.shell({
        tab: 'library',
        title: T.name,
        titleMode: 'none',
        back: 'Library',
        actions: [{ icon: 'sort', label: 'Order' }, { icon: 'more', label: 'More' }],
        body,
      })}`,
      'a-s-library tn-s-thread',
    );
  }

  // ================================================================ register
  Studio.defineVersion({
    id: 'tonight',
    round: 3,
    extends: 'aurora',
    name: 'Aurora · Tonight',
    tagline: 'Start from the evening you have: a concierge Home and a Library you explore by pulling threads.',
    thesis:
      'Start from the evening you have, not the file you want. Home asks how long you’ve got and deals a hand of three picks — keep going, something new, a quick hit — each with the honest reason it was picked, above every game in progress. The Library is explored by association: pull a thread from a game you love and follow genres, makers, series and couch games through the collection. Platforms stay one tap away, as one thread among many.',
    swatch: ['#0A0812', '#FF7A3D', '#FF9FC8'],
    tabs: X.TABS,
    fonts: [],
    notes: {
      home: 'Tonight. A kicker names the evening and flags the resume point that needs a choice; the display question "How long have you got?" sits over four glass chips (About an hour selected). The hand follows: Keep going (Super Metroid’s resume frame, the moment, "Your sessions run about 45 min · 11 h 24 m in", Resume), then Something new (Secret of Mana, picked because of this month’s SNES role-playing, rated 90, up to 3 players, Download · 2 MB) and Quick hit (Tetris, "High score 3,161 — beat it"). Deal again reshuffles. Every game in progress follows as a compact row: EarthBound as two resume points in conflict, Ocarina of Time 3D as a title card made from its cover.',
      library: 'Threads. Three jump tiles keep Consoles, On this iPhone and Collections one tap away. "Pull a thread" starts from Super Metroid: covers joined by a glowing thread whose links are labelled and checked (Metroidvania → Konami → 2 players), ending on Streets of Rage 2 with Next options (Also by Sega · 1992 · Action). Rails follow, each with a count and a reason: Series you’ve started (a dot per game, filled when played), Genres as big glass tiles with fanned covers, Makers, Two players (last together with Sam), Never played, Short sessions, Your collections, then Consoles with the three support tiers. Every rail is capped with See all, so it scales from 72 games to 2,164; games without RomM genre or series metadata still appear in platform, year, player and play-history threads.',
      collections: 'A thread page: Metroidvanias. The header is lit by its games (a fan of their covers), counts games and hours and names where the thread comes from (games RomM lists as "Action · Exploration"). Games are ordered by your progress — in progress, played, never played — each with why it belongs (series, maker, year) and one action on its still. With nothing left unplayed, the page says so and offers the nearest game one thread over (Super Castlevania IV: Castlevania series, like Aria of Sorrow). Keep pulling ends the page with neighbouring threads that lead somewhere new (Castlevania series, Also by Konami, The 16-bit years, Also by Nintendo).',
    },
    screens: X.wrap({ home: homeScreen, library: libraryScreen, collections: threadScreen }),
  });
})();
