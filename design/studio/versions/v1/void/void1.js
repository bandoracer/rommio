/*
 * VOID — OLED-black minimalism.
 *
 * Plan
 *   Palette   Void #000 · Ink #F4F4F5 (56% / 32% steps) · Hairline white 8.5% ·
 *             Tint #B9C6FF (controls, focus, "live") · Synced #86E0A8 ·
 *             Waiting #E9C46A · Conflict #FF8A7A
 *   Type      Geist for everything you read; Geist Mono for anything you
 *             measure (times, sizes, counts, sync state).
 *   Shape     20px side margin, 8pt rhythm, 16px radius on art, pills for
 *             actions. No cards: hairlines separate, art carries colour.
 *   Signature The game frame is shown at its true aspect like a screen in a
 *             dark room; everything else recedes. One tint, used only for
 *             things you can touch in the player and for live state.
 *   Nav       Floating pill tab bar that names only the current tab.
 *   Player    Pure black rails. Controls are hairline shapes with a 9% tint
 *             fill and tint legends — present, never loud. Tertiary controls
 *             (Menu, Select, Start) sit lower and dimmer.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = window.StudioData;

  // ---------- shared pieces ----------
  const dotClass = (state) => ({ synced: 'ok', syncing: 'live', pending: 'wait', waiting: 'wait', conflict: 'bad' })[state] || 'mute';
  const dot = (state) => `<span class="dot ${dotClass(state)}"></span>`;
  const sync = (state, label) => `<span class="sync">${dot(state)}${esc(label || k.syncLabel(state))}</span>`;

  const topbar = (c, { title = '', back = true, right = '' } = {}) => `
    <header class="top">
      ${back ? `<button class="icon-btn" aria-label="Back">${i(c.os === 'android' ? 'arrow-left' : 'chevron-left', 22)}</button>` : '<span></span>'}
      <span class="top-title">${esc(title)}</span>
      <span class="top-right">${right}</span>
    </header>`;

  const tabs = (active) => {
    const items = [
      ['home', 'home', 'Home'],
      ['library', 'library', 'Library'],
      ['collections', 'collections', 'Collections'],
      ['downloads', 'downloads', 'Downloads'],
      ['settings', 'settings', 'Settings'],
    ];
    return `<nav class="tabs" aria-label="Primary">${items
      .map(([id, ic, label]) => `<button class="tab ${id === active ? 'on' : ''}" aria-label="${label}"${id === active ? ' aria-current="page"' : ''}>${i(ic, 21)}${id === active ? `<span>${label}</span>` : ''}</button>`)
      .join('')}</nav>`;
  };

  const cover = (id, kind = 'box', cls = '') => `<img class="cover ${cls}" src="${k.cover(id, kind)}" alt="">`;
  const frameImg = (id, v = 'a', cls = '') => `<img class="frame ${cls}" src="${k.frame(id, v)}" alt="" style="aspect-ratio:${k.aspect(id)}">`;

  const toggle = (on, label, inert) =>
    inert
      ? `<span class="toggle ${on ? 'on' : ''}" aria-hidden="true"><i></i></span>`
      : `<button class="toggle ${on ? 'on' : ''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}"><i></i></button>`;
  const slider = (value, min, max, label, unit = '%') => {
    const p = ((value - min) / (max - min)) * 100;
    return `<div class="slider" style="--p:${p}%"><span class="slider-track"><i></i><b></b></span><output>${esc(value)}${unit}</output><span class="sr">${esc(label)}</span></div>`;
  };
  const steps = (n) => `<div class="steps" aria-label="Step ${n} of 2"><i class="${n >= 1 ? 'on' : ''}"></i><i class="${n >= 2 ? 'on' : ''}"></i></div>`;
  const wordmark = () => `<span class="wordmark"><span class="mark"></span>rommio</span>`;

  // Rounded plus for the d-pad: outer radius r, inner (concave) radius ri.
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
  const dpad = (size = 128, style = '') => {
    const a = size * 0.36;
    const c = size / 2;
    const t = size * 0.075;
    const tri = (x, y, rot) => `<path d="M${x - t},${y + t * 0.6} L${x},${y - t * 0.6} L${x + t},${y + t * 0.6}" transform="rotate(${rot} ${x} ${y})"/>`;
    return `<div class="dpad" style="width:${size}px;height:${size}px;${style}" role="group" aria-label="Directional pad">
      <svg viewBox="-1 -1 ${size + 2} ${size + 2}" width="${size}" height="${size}"><path class="dpad-body" d="${plusPath(size, a, size * 0.09, size * 0.05)}"/>
      <g class="dpad-arrows">${tri(c, size * 0.16, 0)}${tri(size * 0.84, c, 90)}${tri(c, size * 0.84, 180)}${tri(size * 0.16, c, 270)}</g>
      <circle class="dpad-hub" cx="${c}" cy="${c}" r="${size * 0.055}"/></svg></div>`;
  };
  // Nintendo diamond: Y left, X top, B bottom, A right.
  const diamond = (size = 132, btn = 50, labels = ['Y', 'X', 'B', 'A'], style = '') => {
    const c = size / 2;
    const o = (size - btn) / 2;
    const pos = [
      [0, o],
      [o, 0],
      [o, size - btn],
      [size - btn, o],
    ];
    return `<div class="diamond" style="width:${size}px;height:${size}px;${style}" role="group" aria-label="Face buttons">${labels
      .map((l, n) => `<button class="fb" style="left:${pos[n][0]}px;top:${pos[n][1]}px;width:${btn}px;height:${btn}px" aria-label="${l} button">${l}</button>`)
      .join('')}</div>`;
  };
  const pill = (label, style = '', cls = '') => `<button class="pill-ctl ${cls}" style="${style}" aria-label="${esc(label)}">${label === 'Menu' ? i('more', 18) : esc(label)}</button>`;

  // ---------- screens ----------
  const screens = {};

  screens.welcome = () => `
    <div class="welcome">
      <div class="welcome-art" aria-hidden="true">
        ${['smetroid', 'linksawak', 'chrono', 'sotn', 'sonic2', 'metfusion', 'pkcrystal', 'earthbound', 'mm2', 'oot3d', 'sor2', 'ff6']
          .map((id) => `<img src="${k.cover(id, 'box')}" alt="">`)
          .join('')}
      </div>
      <div class="welcome-body">
        ${wordmark()}
        <h1>Your RomM library, in your pocket.</h1>
        <p class="lede">Browse natively, play offline, and pick up on any device right where you left off.</p>
        <div class="stack">
          <button class="btn primary">Connect to RomM</button>
          <button class="btn quiet">${i('qr', 18)}Scan setup code</button>
        </div>
        <p class="fine">Rommio connects to a RomM server you run. It doesn’t provide games.</p>
      </div>
    </div>`;

  screens.connect = (c) => `
    <div class="page flow">
      ${topbar(c, { right: steps(1) })}
      <div class="flow-body">
        <h1 class="h-xl">Connect to your server</h1>
        <p class="sub">The address you use for RomM in a browser.</p>
        <label class="field focus" for="void-server">
          <span class="field-label">Server address</span>
          <span class="field-box"><span class="field-pre">https://</span><input id="void-server" value="romm.homelab.dev" aria-label="Server address"><span class="caret"></span></span>
        </label>
        <div class="check">${dot('synced')}<span><b>RomM 5.2.0</b> found · protected by Cloudflare Access</span></div>
        <button class="row-link"><span>${i('shield', 18)}Access</span><span class="row-val">Automatic ${i('chevron-right', 16)}</span></button>
        <p class="label">Saved and nearby</p>
        <ul class="list">
          ${D.server.discovered
            .map(
              (s) => `<li class="li"><span class="li-icon">${i(s.secure ? 'lock' : 'wifi', 18)}</span><div class="li-main"><b class="mono-b">${esc(s.name)}</b><span>${esc(s.note)}${s.secure ? '' : ' · not encrypted'}</span></div>${i('chevron-right', 16, 'dim')}</li>`,
            )
            .join('')}
        </ul>
      </div>
      <div class="flow-foot"><button class="btn primary wide">Connect</button></div>
    </div>`;

  screens.signin = (c) => `
    <div class="page flow">
      ${topbar(c, { right: steps(2) })}
      <div class="flow-body">
        <div class="server-id">
          <span class="server-mark">${i('server', 18)}</span>
          <div><b class="mono-b">romm.homelab.dev</b><span>${i('check', 13)} RomM 5.2.0 · access verified</span></div>
          <button class="link">Change</button>
        </div>
        <h1 class="h-xl">Sign in</h1>
        <p class="sub">Use your RomM account. Your password goes only to your server.</p>
        <label class="field" for="void-user"><span class="field-label">Username</span><span class="field-box"><input id="void-user" value="ryan" aria-label="Username"></span></label>
        <label class="field focus" for="void-pass"><span class="field-label">Password</span><span class="field-box"><input id="void-pass" type="password" value="correct-horse" aria-label="Password"><span class="caret"></span><span class="field-end">${i('eye', 18)}</span></span></label>
        <button class="btn primary wide">Sign in</button>
        <div class="or"><span>or</span></div>
        <button class="btn quiet wide">${i('key', 18)}Continue with single sign-on</button>
      </div>
    </div>`;

  screens.hydrate = (c) => {
    const ids = D.games.map((g) => g.id);
    const tiles = k.range(40).map((n) => ids[(n * 7) % ids.length]);
    const done = Math.round(tiles.length * 0.61);
    return `
    <div class="page flow">
      <div class="mosaic" aria-hidden="true">${tiles
        .map((id, n) => (n < done ? `<img src="${k.cover(id, 'box')}" alt="">` : `<span></span>`))
        .join('')}</div>
      <div class="hydrate-body">
        <p class="eyebrow">Signed in as ryan</p>
        <h1 class="h-xl">Getting your library ready</h1>
        <p class="sub">Start playing now. Rommio finishes caching in the background so your library works offline.</p>
        <ul class="list tight">
          <li class="li"><span class="li-main"><b>Platforms</b></span><span class="num">22</span>${i('check', 16, 'ok-i')}</li>
          <li class="li"><span class="li-main"><b>Games</b></span><span class="num">2,164</span>${i('check', 16, 'ok-i')}</li>
          <li class="li"><span class="li-main"><b>Collections</b></span><span class="num">9</span>${i('check', 16, 'ok-i')}</li>
          <li class="li"><span class="li-main"><b>Artwork</b><span class="bar" style="--p:61%"><i></i></span></span><span class="num">61%</span><span class="spin"></span></li>
        </ul>
        <div class="note">${i('cloud-check', 20)}<p>Game-save data and resume states sync between your devices, so you can stop on one and continue on another.</p></div>
        <ul class="list tight">
          <li class="li"><span class="li-main"><b>Keep artwork offline</b></span>${toggle(true, 'Keep artwork offline')}</li>
          <li class="li"><span class="li-main"><b>Sync on cellular</b></span>${toggle(false, 'Sync on cellular')}</li>
        </ul>
      </div>
      <div class="flow-foot"><button class="btn primary wide">Start playing</button></div>
    </div>`;
  };

  screens.home = () => {
    const hero = k.game('smetroid');
    const others = ['linksawak', 'chrono'].map(k.game);
    return `
    <div class="page">
      <header class="home-head">${wordmark()}<button class="avatar" aria-label="Account: ryan, online">R<i></i></button></header>
      <button class="attention">${dot('conflict')}<span class="att-t">EarthBound has two versions of your progress</span>${i('chevron-right', 16)}</button>
      <div class="resume">
        <div class="screen-well" style="--glow:${window.StudioArt.palette(hero.id)[4]}">${frameImg(hero.id, 'b')}</div>
        <div class="resume-copy">
          <p class="eyebrow">Continue · ${esc(k.platformOf(hero).short)} · ${esc(k.ago(1))}</p>
          <h1>${esc(hero.title)}</h1>
          <div class="row gap12"><button class="btn primary sm">${i('play', 16)}Resume</button>${sync('pending', 'Resume waiting to sync')}</div>
        </div>
      </div>
      <section class="sect">
        <ul class="list">
          ${others
            .map(
              (g) => `<li class="li"><img class="li-thumb" src="${k.frame(g.id, 'a')}" alt="" style="aspect-ratio:${k.aspect(g.id)}"><div class="li-main"><b>${esc(g.title)}</b><span>${esc(k.platformOf(g).short)} · ${esc(k.ago(g.played))}</span></div>${sync(g.sync, g.sync === 'syncing' ? 'Syncing' : k.syncLabel(g.sync))}</li>`,
            )
            .join('')}
        </ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Metroidvanias</h2><span class="more">4</span></div>
        <div class="shelf">${k
          .collectionGames('metroidvania')
          .map((g) => `<figure class="tile">${cover(g.id)}<figcaption>${esc(g.title)}</figcaption></figure>`)
          .join('')}</div>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Recently added</h2><span class="more">6</span></div>
        <div class="shelf">${k
          .collectionGames('recent')
          .map((g) => `<figure class="tile">${cover(g.id)}<figcaption>${esc(g.title)}</figcaption></figure>`)
          .join('')}</div>
      </section>
    </div>
    ${tabs('home')}`;
  };

  screens.library = () => {
    const touch = k.tier('touch');
    const shown = touch.slice(0, 8);
    const fan = (p) => `<span class="fan" aria-hidden="true">${k
      .gamesOn(p.id)
      .slice(0, 3)
      .map((g) => `<img src="${k.cover(g.id, 'box')}" alt="">`)
      .join('')}</span>`;
    const plat = (p) => `<button class="plat">${fan(p)}<span class="plat-top"><i style="background:${p.color}"></i><b>${esc(p.short)}</b></span><span class="plat-name">${esc(p.name)}</span><span class="plat-meta">${p.games} · ${p.installed} installed</span></button>`;
    const row = (p, note) => `<li class="li"><span class="plat-dot" style="background:${p.color}"></span><div class="li-main"><b>${esc(p.name)}</b><span>${note}</span></div>${i('chevron-right', 16, 'dim')}</li>`;
    return `
    <div class="page">
      <header class="big-head"><h1 class="h-xl">Library</h1><span class="head-meta">${k.num(k.totals().games)} games</span></header>
      <div class="search">${i('search', 18)}<span>Search your library</span></div>
      <section class="sect">
        <div class="sect-h"><h2>Touch</h2><span class="more">${touch.length} platforms</span></div>
        <div class="plat-grid">${shown.map(plat).join('')}</div>
        <button class="row-link"><span>All ${touch.length} touch platforms</span>${i('chevron-right', 16)}</button>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Controller</h2><span class="more">Pair a controller to play</span></div>
        <ul class="list">${k.tier('controller').map((p) => row(p, `${p.games} games · ${p.installed} installed · ${p.core}`)).join('')}</ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Browse only</h2></div>
        <ul class="list">${k.tier('unsupported').map((p) => row(p, `${p.games} games · ${esc(p.blocked)}`)).join('')}</ul>
      </section>
    </div>
    ${tabs('library')}`;
  };

  screens.platform = (c) => {
    const p = k.platform('snes');
    const list = k.gamesOn('snes').sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));
    return `
    <div class="page">
      ${topbar(c, { title: '', right: `<button class="icon-btn" aria-label="Search">${i('search', 20)}</button>` })}
      <header class="plat-head">
        <p class="eyebrow">${esc(p.maker)} · ${p.year}</p>
        <h1 class="h-xl">${esc(p.name)}</h1>
        <p class="meta-line">${p.games} games · ${p.installed} installed · ${esc(p.core)} · Touch</p>
      </header>
      <div class="chips">
        <button class="chip on">All <span>${p.games}</span></button>
        <button class="chip">Installed <span>${p.installed}</span></button>
        <button class="chip">Favourites <span>2</span></button>
        <button class="chip sort">${i('sort', 14)}Recently played</button>
      </div>
      <div class="grid3">${list
        .map(
          (g) => `<figure class="gcell ${g.installed ? '' : 'remote'}">${cover(g.id)}<figcaption><b>${esc(g.title)}</b><span>${g.installed ? `${dot(g.sync)}${g.played != null ? esc(k.agoShort(g.played)) : 'Installed'}` : `${i('download', 12)}${esc(k.size(g.size))}`}</span></figcaption></figure>`,
        )
        .join('')}</div>
    </div>
    ${tabs('library')}`;
  };

  screens.collections = () => {
    const mine = D.collections.filter((x) => x.kind === 'manual');
    const smart = D.collections.filter((x) => x.kind === 'smart');
    const auto = D.collections.filter((x) => x.kind === 'auto');
    const mosaic = (col) => `<span class="mosaic4">${col.games
      .slice(0, 4)
      .map((id) => `<img src="${k.cover(id, 'square')}" alt="">`)
      .join('')}</span>`;
    const row = (col) => `<li class="li"><span class="stack3">${col.games
      .slice(0, 3)
      .map((id) => `<img src="${k.cover(id, 'box')}" alt="">`)
      .join('')}</span><div class="li-main"><b>${esc(col.name)}</b><span>${esc(col.rule)}</span></div><span class="num">${col.games.length}</span></li>`;
    return `
    <div class="page">
      <header class="big-head"><h1 class="h-xl">Collections</h1><button class="icon-btn" aria-label="New collection">${i('plus', 22)}</button></header>
      <section class="sect">
        <div class="sect-h"><h2>Yours</h2><span class="more">${mine.length}</span></div>
        <div class="col-grid">${mine
          .map((col) => `<figure class="col">${mosaic(col)}<figcaption><b>${esc(col.name)}</b><span>${col.games.length} games</span></figcaption></figure>`)
          .join('')}</div>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Smart</h2></div>
        <ul class="list">${smart.map(row).join('')}</ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Automatic</h2></div>
        <ul class="list">${auto.map(row).join('')}</ul>
      </section>
    </div>
    ${tabs('collections')}`;
  };

  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D.progress;
    return `
    <div class="page">
      <div class="game-hero">${cover(g.id, 'tall', 'hero-img')}<div class="hero-fade"></div></div>
      <header class="top floating">
        <button class="icon-btn glass" aria-label="Back">${i(c.os === 'android' ? 'arrow-left' : 'chevron-left', 22)}</button><span></span>
        <button class="icon-btn glass" aria-label="More">${i('more', 20)}</button>
      </header>
      <div class="game-body">
        <p class="eyebrow">${esc(p.short)} · ${g.year} · ${esc(g.publisher)}</p>
        <h1 class="h-xxl">${esc(g.title)}</h1>
        <p class="meta-line">${esc(g.genre)} · ${g.rating} / 100 · ${esc(k.hours(g.hours))} played</p>
        <div class="resume-row">
          <img class="rr-frame" src="${k.frame(g.id, 'b')}" alt="" style="aspect-ratio:${k.aspect(g.id)}">
          <div class="rr-copy"><b>Resume</b><span>${esc(k.ago(pr.resume.at))} · ${esc(pr.resume.device)}</span></div>
          <button class="play" aria-label="Resume Super Metroid">${i('play', 22)}</button>
        </div>
        <button class="btn quiet wide">Start with game save</button>
        <ul class="list">
          <li class="li"><span class="li-icon">${i('save', 18)}</span><div class="li-main"><b>Game-save data</b><span>${esc(k.ago(pr.gameSave.at))} · ${esc(pr.gameSave.detail)}</span></div>${sync('synced')}</li>
          <li class="li"><span class="li-icon">${i('history', 18)}</span><div class="li-main"><b>Automatic resume</b><span>${esc(k.ago(pr.resume.at))} · This iPhone</span></div>${sync('waiting')}</li>
          <li class="li"><span class="li-icon">${i('layers', 18)}</span><div class="li-main"><b>Save states</b><span>3 slots · latest ${esc(k.ago(42))}</span></div>${i('chevron-right', 16, 'dim')}</li>
        </ul>
        <p class="blurb">${esc(g.blurb)}</p>
        <div class="file">
          <div><b class="mono-b">Super Metroid (USA).sfc</b><span>${esc(k.size(g.size))} · Installed · ${esc(p.core)} ready · Touch</span></div>
        </div>
        <div class="row gap12 actions"><button class="btn quiet sm">${i('sync', 16)}Sync now</button><button class="btn quiet sm">${i('trash', 16)}Remove download</button></div>
      </div>
    </div>`;
  };

  screens.downloads = () => {
    const dl = D.downloads;
    const run = dl.find((d) => d.state === 'running');
    const g = (d) => k.game(d.game);
    return `
    <div class="page">
      <header class="big-head"><h1 class="h-xl">Downloads</h1><button class="icon-btn" aria-label="Pause all">${i('pause', 20)}</button></header>
      <p class="summary">1 active · 2 queued · 1 waiting · 1 failed</p>
      <section class="sect">
        <div class="active">
          <img class="active-cover" src="${k.cover(run.game, 'box')}" alt="">
          <div class="active-main">
            <b>${esc(g(run).title)}</b>
            <span>${esc(k.platformOf(g(run)).short)} · ${esc(k.size(run.done))} of ${esc(k.size(run.total))}</span>
            <span class="bar lg" style="--p:${run.progress * 100}%"><i></i></span>
            <span class="mono-row"><span>${run.rate} MB/s</span><span>${esc(run.eta)} left</span></span>
          </div>
          <button class="icon-btn ring" aria-label="Pause download">${i('pause', 18)}</button>
        </div>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Up next</h2></div>
        <ul class="list">
          ${dl
            .filter((d) => d.state === 'queued' || d.state === 'waiting')
            .map(
              (d, n) => `<li class="li"><img class="li-cover" src="${k.cover(d.game, 'box')}" alt=""><div class="li-main"><b>${esc(g(d).title)}</b><span>${d.state === 'waiting' ? `${i('wifi-off', 12)} ${esc(d.reason)}` : `${esc(k.platformOf(g(d)).short)} · ${esc(k.size(d.total))}`}</span></div><span class="num">${d.state === 'waiting' ? '' : n + 1}</span></li>`,
            )
            .join('')}
          ${dl
            .filter((d) => d.state === 'failed')
            .map(
              (d) => `<li class="li"><img class="li-cover" src="${k.cover(d.game, 'box')}" alt=""><div class="li-main"><b>${esc(g(d).title)}</b><span class="bad-t">Connection closed at ${k.pct(d.progress)}</span></div><button class="btn quiet xs">Retry</button></li>`,
            )
            .join('')}
        </ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Finished</h2><button class="more">Clear</button></div>
        <ul class="list">${dl
          .filter((d) => d.state === 'done')
          .map((d) => `<li class="li"><img class="li-cover" src="${k.cover(d.game, 'box')}" alt=""><div class="li-main"><b>${esc(g(d).title)}</b><span>${esc(k.size(d.total))} · ${esc(k.ago(d.at))}</span></div>${i('check', 16, 'ok-i')}</li>`)
          .join('')}</ul>
      </section>
    </div>
    ${tabs('downloads')}`;
  };

  screens.settings = () => {
    const s = D.storage;
    const prof = D.profiles;
    const parts = [
      ['ROMs', s.roms, 'var(--fg)'],
      ['Save states', s.states, 'var(--tint)'],
      ['Game saves', s.saves, 'var(--ok)'],
      ['Cache', s.cache, 'var(--fg3)'],
      ['Cores & BIOS', s.cores + s.bios, 'var(--wait)'],
    ];
    const used = parts.reduce((n, p) => n + p[1], 0);
    return `
    <div class="page">
      <header class="big-head"><h1 class="h-xl">Settings</h1></header>
      <section class="sect account">
        <span class="avatar lg" aria-hidden="true">R<i></i></span>
        <div class="li-main"><b>ryan</b><span class="mono">${esc(prof[0].url)}</span></div>
        <button class="btn quiet xs">Switch</button>
      </section>
      <p class="acct-status">${dot('synced')}Online · synced ${esc(k.ago(prof[0].lastSync))} · ${esc(prof[0].version)}</p>
      <section class="sect profiles">
        <ul class="list">
          <li class="li"><span class="li-icon">${i('server', 18)}</span><div class="li-main"><b>${esc(prof[1].name)}</b><span>${esc(prof[1].url)} · offline ready</span></div>${i('chevron-right', 16, 'dim')}</li>
          <li class="li"><span class="li-icon">${i('plus', 18)}</span><div class="li-main"><b>Add a server</b></div></li>
        </ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Sync</h2></div>
        <ul class="list">
          <li class="li"><div class="li-main"><b>Game-save data</b><span>After every session</span></div>${toggle(true, 'Sync game-save data')}</li>
          <li class="li"><div class="li-main"><b>Resume states</b><span>Pick up on any device</span></div>${toggle(true, 'Sync resume states')}</li>
          <li class="li"><div class="li-main"><b>Use cellular data</b></div>${toggle(false, 'Use cellular data')}</li>
        </ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Play</h2></div>
        <ul class="list">
          <li class="li"><span class="li-icon">${i('gamepad', 18)}</span><div class="li-main"><b>Player & controls</b></div><span class="row-val">OLED black ${i('chevron-right', 16)}</span></li>
          <li class="li"><span class="li-icon">${i('cpu', 18)}</span><div class="li-main"><b>Cores & BIOS</b><span>11 cores · <em class="wait-t">1 needs BIOS</em> · 1 needs setup</span></div>${i('chevron-right', 16, 'dim')}</li>
        </ul>
      </section>
      <section class="sect">
        <div class="sect-h"><h2>Storage</h2><span class="more">${esc(k.size(used))} on this iPhone</span></div>
        <div class="stack-bar">${parts.map((p) => `<i style="flex:${Math.max(p[1], 260)};background:${p[2]}"></i>`).join('')}</div>
        <ul class="legend">${parts.map((p) => `<li><i style="background:${p[2]}"></i>${esc(p[0])}<span>${esc(k.size(p[1]))}</span></li>`).join('')}</ul>
      </section>
    </div>
    ${tabs('settings')}`;
  };

  screens.controls = (c) => {
    const s = D.controls;
    return `
    <div class="page">
      ${topbar(c, { title: 'Player & controls' })}
      <div class="preview" aria-label="Preview of the Super Nintendo layout">
        <div class="mini">${screens.player({ ...c, w: 874, h: 402, orient: 'landscape', safe: Studio.SAFE[c.os].landscape })}</div>
      </div>
      <p class="preview-cap">Live preview · Super Nintendo layout</p>
      <section class="sect">
        <ul class="list">
          <li class="li"><div class="li-main"><b>Touch controls</b></div>${toggle(s.showTouch, 'Touch controls')}</li>
          <li class="li"><div class="li-main"><b>Hide with a controller</b><span>${dot('synced')}${esc(s.connectedController)}</span></div>${toggle(s.autoHideOnController, 'Hide with a controller')}</li>
          <li class="li"><div class="li-main"><b>Left-handed</b><span>Swap the d-pad and buttons</span></div>${toggle(s.leftHanded, 'Left-handed')}</li>
        </ul>
      </section>
      <section class="sect">
        <ul class="list sliders">
          <li class="li"><div class="li-main"><b>Opacity</b></div>${slider(Math.round(s.opacity * 100), 0, 100, 'Opacity')}</li>
          <li class="li"><div class="li-main"><b>Size</b></div>${slider(Math.round(s.size * 100), 60, 140, 'Size')}</li>
          <li class="li"><div class="li-main"><b>Stick deadzone</b></div>${slider(Math.round(s.deadzone * 100), 0, 40, 'Stick deadzone')}</li>
        </ul>
      </section>
      <section class="sect">
        <ul class="list">
          <li class="li"><div class="li-main"><b>Haptics</b></div>${toggle(s.haptics, 'Haptics')}</li>
          <li class="li"><div class="li-main"><b>OLED black</b><span>Pure black around the game</span></div>${toggle(s.oled, 'OLED black')}</li>
          <li class="li"><div class="li-main"><b>Console colours</b></div>${toggle(s.consoleColors, 'Console colours')}</li>
          <li class="li"><div class="li-main"><b>Controller type</b></div><span class="row-val">${esc(s.controllerType)} ${i('chevron-right', 16)}</span></li>
        </ul>
      </section>
    </div>`;
  };

  // Landscape player: 874×402, 4:3 viewport at full height.
  screens.player = (c) => {
    const vh = c.h - 32;
    const vw = Math.round(vh * (4 / 3));
    const vx = Math.round((c.w - vw) / 2);
    const L = c.safe.left;
    const railMid = (L + vx) / 2; // centre of the usable part of each black rail
    return `
    <div class="player">
      <img class="viewport" src="${k.frame('smetroid', 'a')}" alt="Super Metroid" style="left:${vx}px;top:16px;width:${vw}px;height:${vh}px">
      <div class="toast" style="left:${c.w / 2}px">${i('check', 14)}Automatic resume updated</div>
      <div class="ctl" style="--ctl-opacity:${D.controls.opacity}">
        ${pill('L', `left:${railMid - 44}px;top:22px;width:88px`, 'shoulder')}
        ${pill('R', `left:${c.w - railMid - 44}px;top:22px;width:88px`, 'shoulder')}
        ${dpad(124, `position:absolute;left:${railMid - 62}px;top:${c.h / 2 - 54}px`)}
        ${diamond(128, 46, ['Y', 'X', 'B', 'A'], `position:absolute;left:${c.w - railMid - 64}px;top:${c.h / 2 - 56}px`)}
        ${pill('Menu', `left:${railMid - 26}px;bottom:${c.safe.bottom + 8}px;width:52px`, 'tert')}
        ${pill('Select', `left:${c.w - railMid - 58}px;bottom:${c.safe.bottom + 10}px;width:56px`, 'tert small')}
        ${pill('Start', `left:${c.w - railMid + 4}px;bottom:${c.safe.bottom + 10}px;width:52px`, 'tert small')}
      </div>
    </div>`;
  };

  screens['player-portrait'] = (c) => {
    const g = k.game('linksawak');
    const vw = c.w - 32;
    const vh = Math.round(vw / k.aspect(g.id));
    const top = c.safe.top + 50;
    return `
    <div class="player portrait">
      <div class="p-band" style="top:${c.safe.top}px">
        <span class="p-title">${esc(g.title)}</span>
        ${pill('Menu', 'position:static;width:52px', 'tert')}
      </div>
      <img class="viewport" src="${k.frame(g.id, 'a')}" alt="${esc(g.title)}" style="left:16px;top:${top}px;width:${vw}px;height:${vh}px">
      <div class="ctl" style="--ctl-opacity:${D.controls.opacity}">
        ${pill('Select', `left:${c.w / 2 - 70}px;top:${top + vh + 26}px`, 'tert small')}
        ${pill('Start', `left:${c.w / 2 + 6}px;top:${top + vh + 26}px`, 'tert small')}
        ${dpad(150, `position:absolute;left:28px;top:${top + vh + 128}px`)}
        <button class="fb solo" style="left:${c.w - 112}px;top:${top + vh + 134}px" aria-label="A button">A</button>
        <button class="fb solo" style="left:${c.w - 192}px;top:${top + vh + 184}px" aria-label="B button">B</button>
      </div>
    </div>`;
  };

  screens.pause = (c) => {
    const g = k.game('smetroid');
    const L = c.safe.left;
    const fw = 372;
    return `
    <div class="pause">
      <div class="pause-left" style="left:${L + 6}px;width:${fw}px">
        <img class="pause-frame" src="${k.frame(g.id, 'a')}" alt="" style="width:${fw}px">
        <div class="pause-id">
          <b>${esc(g.title)}</b>
          <span>SNES · Snes9x · session 42 min</span>
        </div>
        <div class="pause-sync">${sync('synced', 'Game-save data synced')}${sync('waiting', 'Resume waiting to sync')}</div>
      </div>
      <div class="pause-menu" style="right:${c.safe.right + 6}px">
        <p class="eyebrow">Paused</p>
        <button class="btn primary wide">${i('play', 16)}Resume</button>
        <ul class="menu">
          <li><button>${i('save', 18)}<span>Save state</span><em>Slot 4 free</em></button></li>
          <li><button>${i('layers', 18)}<span>Load state</span><em>3 slots</em></button></li>
          <li><button>${i('gamepad', 18)}<span>Controls</span></button></li>
          <li><button>${i('fast-forward', 18)}<span>Fast-forward</span>${toggle(false, 'Fast-forward', true)}</button></li>
          <li><button>${i('restart', 18)}<span>Reset</span></button></li>
          <li><button class="danger">${i('exit', 18)}<span>Leave game</span></button></li>
        </ul>
      </div>
    </div>`;
  };

  screens.states = (c) => {
    const pr = D.progress;
    const g = k.game(pr.game);
    const L = c.safe.left;
    const R = c.safe.right;
    return `
    <div class="states" style="padding-left:${L}px;padding-right:${R}px">
      <header class="states-head">
        <button class="icon-btn" aria-label="Back">${i('chevron-left', 22)}</button>
        <div><b>Progress</b><span>${esc(g.title)}</span></div>
        <button class="btn quiet xs">${i('sync', 14)}Sync now</button>
      </header>
      <div class="states-grid">
        <div class="states-main">
          <ul class="list">
            <li class="li"><img class="st-thumb" src="${k.frame(g.id, pr.resume.frame)}" alt=""><div class="li-main"><b>Automatic resume</b><span>${esc(k.ago(pr.resume.at))} · ${esc(pr.resume.device)}</span></div>${sync('waiting')}</li>
            <li class="li"><span class="st-thumb icon">${i('save', 20)}</span><div class="li-main"><b>Game-save data</b><span>${esc(k.ago(pr.gameSave.at))} · ${esc(pr.gameSave.detail)}</span></div>${sync('synced')}</li>
          </ul>
          <p class="label">Save states</p>
          <div class="slots">${pr.slots
            .map((s) =>
              s.empty
                ? `<button class="slot empty"><span class="slot-img">${i('plus', 20)}</span><b>Slot ${s.n}</b><span>Save state here</span></button>`
                : `<button class="slot"><img class="slot-img" src="${k.frame(g.id, s.frame)}" alt=""><b>${s.n} · ${esc(s.name)}</b><span>${dot(s.remote)}${esc(k.agoShort(s.at))} · ${esc(s.device)}</span></button>`,
            )
            .join('')}</div>
        </div>
        <aside class="states-side">
          <p class="label">History</p>
          <ul class="hist">${pr.history
            .map((h) => `<li><b>${esc(h.kind)}</b><span>${esc(k.ago(h.at))} · ${esc(h.device)} · ${esc(h.reason)}</span></li>`)
            .join('')}</ul>
          <button class="row-link small"><span>All recovery copies</span>${i('chevron-right', 14)}</button>
        </aside>
      </div>
    </div>`;
  };

  screens.conflict = (c) => {
    const cf = D.conflict;
    const g = k.game(cf.game);
    return `
    <div class="page flow">
      ${topbar(c, { title: '', back: false, right: `<button class="icon-btn" aria-label="Close">${i('x', 20)}</button>` })}
      <div class="flow-body">
        <p class="eyebrow">${esc(g.title)} · ${esc(cf.kind)}</p>
        <h1 class="h-xl">Two versions of your progress</h1>
        <p class="sub">This iPhone and Pixel 9 both played since the last sync. Choose where to continue.</p>
        <div class="branches">${cf.branches
          .map(
            (b, n) => `<button class="branch ${n === 0 ? 'on' : ''}" role="radio" aria-checked="${n === 0}">
              <img src="${k.frame(g.id, b.frame)}" alt="" style="aspect-ratio:${k.aspect(g.id)}">
              <span class="branch-copy"><b>${esc(b.device)}</b><span>${esc(k.ago(b.at))}</span><span>${esc(b.where)} · ${esc(b.playtime)}</span></span>
              <span class="radio"></span>
            </button>`,
          )
          .join('')}</div>
        <div class="li keep"><div class="li-main"><b>Keep the other as a recovery copy</b><span>You can restore it later from Progress</span></div>${toggle(true, 'Keep the other as a recovery copy')}</div>
      </div>
      <div class="flow-foot">
        <button class="btn primary wide">Continue from This iPhone</button>
        <button class="btn text wide">Decide later</button>
      </div>
    </div>`;
  };

  screens.dual = (c) => {
    const g = k.game('pkblack');
    const d = k.dual(g.id, 'a');
    const sw = c.w - 48;
    const sh = Math.round(sw * 0.75);
    const top = c.safe.top + 50;
    const bandTop = top + sh * 2 + 10;
    return `
    <div class="player portrait">
      <div class="p-band" style="top:${c.safe.top}px">
        ${pill('L', 'position:static;width:76px', 'shoulder')}
        ${pill('Menu', 'position:static;width:52px', 'tert')}
        ${pill('R', 'position:static;width:76px', 'shoulder')}
      </div>
      <img class="viewport" src="${d.top}" alt="${esc(g.title)} top screen" style="left:24px;top:${top}px;width:${sw}px;height:${sh}px">
      <img class="viewport touch" src="${d.bottom}" alt="${esc(g.title)} touch screen" style="left:24px;top:${top + sh + 10}px;width:${sw}px;height:${sh}px">
      <div class="ctl" style="--ctl-opacity:${D.controls.opacity}">
        ${dpad(120, `position:absolute;left:22px;top:${bandTop + 22}px`)}
        ${diamond(124, 44, ['Y', 'X', 'B', 'A'], `position:absolute;right:22px;top:${bandTop + 20}px`)}
        ${pill('Select', `left:${c.w / 2 - 62}px;top:${bandTop + 118}px;width:58px`, 'tert small')}
        ${pill('Start', `left:${c.w / 2 + 4}px;top:${bandTop + 118}px;width:58px`, 'tert small')}
      </div>
    </div>`;
  };

  Studio.defineVersion({
    id: 'void1',
    round: 1,
    name: 'Void',
    tagline: 'OLED-black minimalism. The art is the only colour.',
    concept:
      'Pure #000 everywhere, hairlines instead of cards, one cool tint reserved for controls and live state. Games sit on black the way a screen glows in a dark room — the interface recedes until you touch it.',
    swatch: ['#000000', '#b9c6ff', '#f4f4f5'],
    fonts: ['Geist:wght@300..700', 'Geist+Mono:wght@400..600'],
    tone: 'light',
    palette: [
      { name: 'Void', hex: '#000000' },
      { name: 'Ink', hex: '#F4F4F5' },
      { name: 'Tint', hex: '#B9C6FF' },
      { name: 'Hairline', hex: '#161617' },
      { name: 'Synced', hex: '#86E0A8' },
      { name: 'Waiting', hex: '#E9C46A' },
      { name: 'Conflict', hex: '#FF8A7A' },
    ],
    type: [
      { role: 'Display & UI', family: 'Geist', css: "'Geist', sans-serif", sample: 'Super Metroid' },
      { role: 'Measure', family: 'Geist Mono', css: "'Geist Mono', monospace", sample: '11 h 24 m · 3 MB' },
    ],
    tweaks: [
      { var: '--tint', label: 'Control tint', type: 'color', value: '#b9c6ff' },
      { var: '--ctl-alpha', label: 'Control fill', type: 'range', min: 0, max: 30, step: 1, value: 9, unit: '%' },
      { var: '--ctl-edge', label: 'Control edge', type: 'range', min: 0, max: 60, step: 1, value: 30, unit: '%' },
      { var: '--radius', label: 'Art radius', type: 'range', min: 0, max: 28, value: 14, unit: 'px' },
      { var: '--pad', label: 'Side margin', type: 'range', min: 12, max: 32, value: 20, unit: 'px' },
    ],
    notes: {
      player: 'Pure black rails and hairline controls filled with 9% tint. The d-pad and diamond overlap the viewport edge by ~15px at full-height 4:3 — acceptable per the player spec, and the low fill keeps the game readable underneath.',
      home: 'The resume frame is shown at its real aspect, never cropped: it is literally where you stopped.',
      hydrate: 'The cover mosaic fills in as artwork caches — the progress bar you can feel.',
    },
    screens,
  });
})();
