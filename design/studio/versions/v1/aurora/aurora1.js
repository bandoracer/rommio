/*
 * AURORA — cinematic, colour-adaptive liquid glass.
 *
 * DESIGN PLAN
 * -----------
 * Concept    Every screen is lit by the game in focus. A light field built from
 *            the game's own palette (StudioArt.palette) and a heavily blurred
 *            copy of its art glows behind layered liquid glass. Super Metroid
 *            burns ember/violet, Link's Awakening glows sea/sun, EarthBound goes
 *            sky/pink. Change the game, the room changes colour.
 *
 * Palette    Night      #07070C  base (never pure black)
 *            Night 2    #0E0E16  raised base behind sheets
 *            Ink        #F7F7FB  primary text     · Ink 64% secondary · Ink 40% tertiary/decoration
 *            Glass      rgba(255,255,255,.07–.14) fills, blur 28px + saturate 180%
 *            Synced     #7CF2C4  mint     Waiting  #FFC66B amber
 *            Syncing    #6FE3FF  cyan     Conflict #FF7A6B coral
 *            Light      --l1/--l2/--l3, computed per game: the accent plus the two
 *                       most chromatic palette colours, lifted to a luminous band
 *                       (L 48–68%) so every game glows with equal energy.
 *
 * Type       Display  Bricolage Grotesque (opsz auto, wdth 82–92%, 700–800) — titles,
 *                     big numerals, platform monograms.
 *            UI       Figtree (500–700) — everything else. Tabular numbers for
 *                     times, sizes, counts and percentages.
 *
 * Shape      Spacing 4 · 8 · 12 · 16 · 20 · 24 · 32 · 40; 20px side gutter.
 *            Radius  32 hero / sheet · 24 card · 16 tile · 12 thumb · 999 pills and
 *                    discs; game screens sit concentric inside their card
 *                    (card radius minus padding). --r drives all of them.
 *            Depth   layering, not borders: blur + specular top edge + a thin
 *                    refraction rim (gradient mask) + soft inner shadow.
 *
 * Signatures 1. Ambient light field from the current game (.aura).
 *            2. Player "bias lighting": an enlarged, blurred copy of the live
 *               frame spills colour into the rails; controls sit in that light.
 *            3. Resume cards show the resume frame as a glowing screen (.gs).
 *            4. Onboarding with dramatic light fields and glass fields; first
 *               sync shows artwork resolving out of frosted tiles.
 *
 * Navigation Floating glass tab bar: Home · Library · Collections · Downloads,
 *            with a detached Search disc. Settings lives behind the account
 *            avatar (with a sync ring) on every root screen. Detail screens
 *            (game, settings, controls) push full-screen with a glass back disc.
 *
 * Player     Frosted glass discs and pills tinted by the ambient light, specular
 *            top edge, legends at 70% white. D-pad is a disc with an embossed
 *            cross; SNES diamond Y-left X-top B-bottom A-right. Shoulders are
 *            wide pills above the rails; Select/Start/Menu are small tertiary
 *            pills near the lower edge. Opacity tweakable (--ctl-o, 72%).
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;
  const Art = () => window.StudioArt;

  // ---------------------------------------------------------------- colour
  const hex2rgb = (h) => {
    h = String(h).replace('#', '');
    if (h.length === 3) h = h.split('').map((c) => c + c).join('');
    const n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };
  const toHsl = (hex) => {
    let [r, g, b] = hex2rgb(hex).map((v) => v / 255);
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
  };
  const chroma = (hex) => {
    const [, s, l] = toHsl(hex);
    return s * (1 - Math.abs(2 * l - 1));
  };
  // Lift any palette colour into a luminous band so every game glows evenly.
  const glow = (hex, lo = 0.5, hi = 0.68, smin = 0.52) => {
    const [h, s, l] = toHsl(hex);
    const ss = s < 0.1 ? s : Math.max(s, smin);
    const ll = Math.min(Math.max(l, lo), hi);
    return `hsl(${h.toFixed(0)} ${(ss * 100).toFixed(0)}% ${(ll * 100).toFixed(0)}%)`;
  };
  const lights = (id) => {
    const p = Art().palette(id);
    const rest = p.slice(0, 4).sort((a, b) => chroma(b) - chroma(a));
    return { l1: glow(p[4]), l2: glow(rest[0]), l3: glow(rest[1]) };
  };
  const lightVars = (L) => `--l1:${L.l1};--l2:${L.l2};--l3:${L.l3}`;
  const lit = (id) => lightVars(lights(id));
  const litHex = (hex) => glow(hex, 0.5, 0.66, 0.45);

  // ---------------------------------------------------------------- pieces
  const plat = (g) => k.platformOf(g);

  // Every non-player screen sits in a scene that carries its light variables,
  // so glass, buttons and toggles further down can pick up the game's colour.
  const scene = (vars, html, cls = '') => `<div class="au ${cls}" style="${vars}">${html}</div>`;

  // 1. Ambient light field. `img` is a game id whose art is blurred into the field.
  const aura = (id, { cls = '', kind = 'tall', style = '', img = id } = {}) =>
    `<div class="aura ${cls}" style="${style}" aria-hidden="true">
      ${img ? `<img class="aura-art" src="${k.cover(img, kind)}" alt="">` : ''}
      <i class="aura-light"></i><i class="aura-veil"></i>
    </div>`;

  // 3. A game frame presented as a glowing screen.
  const gs = (src, { ratio = 4 / 3, cls = '', label = '' } = {}) =>
    `<div class="gs ${cls}" style="--ar:${ratio}">
      <img class="gs-glow" src="${src}" alt="">
      <div class="gs-face"><img class="px" src="${src}" alt="${esc(label)}"></div>
    </div>`;

  const cover = (id, kind = 'box', cls = '') => `<img class="cov ${cls}" src="${k.cover(id, kind)}" alt="">`;

  const SYNC = {
    synced: ['ok', 'cloud-check'],
    pending: ['wait', 'clock'],
    waiting: ['wait', 'clock'],
    syncing: ['live', 'sync'],
    conflict: ['bad', 'alert'],
    local: ['mute', 'smartphone'],
    signin: ['wait', 'lock'],
    none: ['mute', 'cloud'],
  };
  // Sync-state pill: glowing dot + spec wording.
  const sync = (state, { cls = '' } = {}) => {
    const [tone] = SYNC[state] || SYNC.local;
    const mark = tone === 'live' ? `<span class="spin">${i('sync', 13)}</span>` : '<i class="dot"></i>';
    return `<span class="sp sp-${tone} ${cls}">${mark}${esc(k.syncLabel(state))}</span>`;
  };
  const dot = (state) => `<i class="dot dot-${(SYNC[state] || SYNC.local)[0]}" aria-label="${esc(k.syncLabel(state))}"></i>`;

  const TIER = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tier = (t) => `<span class="tier tier-${t}">${i(TIER[t], 13)}${esc(k.tierLabel(t))}</span>`;

  const sh = (title, meta = '', action = '') =>
    `<div class="sh"><h2>${esc(title)}</h2>${meta ? `<span class="sh-meta">${meta}</span>` : ''}${action ? `<button class="sh-act">${esc(action)}</button>` : ''}</div>`;

  const back = (c, label = 'Back') =>
    `<button class="disc glass" aria-label="${esc(label)}">${i(c.os === 'android' ? 'arrow-left' : 'chevron-left', 22)}</button>`;
  const disc = (icon, label, cls = '') => `<button class="disc glass ${cls}" aria-label="${esc(label)}">${i(icon, 20)}</button>`;

  const avatar = () =>
    `<button class="avatar" aria-label="Account and settings · Synced 2 min ago"><span>${esc(D().user.initials)}</span></button>`;

  const sw = (on, label) =>
    `<button class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${on ? 'true' : 'false'}" aria-label="${esc(label)}"><i></i></button>`;

  const swVis = (on) => `<span class="sw ${on ? 'on' : ''}" aria-hidden="true"><i></i></span>`;

  const btn = (label, { icon, kind = 'glass', cls = '', size = 18 } = {}) =>
    `<button class="btn btn-${kind} ${kind === 'glass' ? 'glass' : ''} ${cls}">${icon ? i(icon, size) : ''}<span>${esc(label)}</span></button>`;

  const mark = (size = 30) => `<span class="mark" style="--m:${size}px" aria-hidden="true"><i></i></span>`;

  // Floating glass tab bar with detached search disc.
  const tabbar = (active) => {
    const items = [
      ['home', 'home', 'Home'],
      ['library', 'library', 'Library'],
      ['collections', 'layers', 'Collections'],
      ['downloads', 'download', 'Downloads'],
    ];
    return `<nav class="tabbar" aria-label="Primary">
      <div class="tb glass">${items
        .map(
          ([id, ic, label]) =>
            `<button class="tb-i ${id === active ? 'on' : ''}" ${id === active ? 'aria-current="page"' : ''}>${
              id === 'downloads' ? `<span class="tb-ring" style="--p:62">${i(ic, 21)}</span>` : i(ic, 22)
            }<span>${label}</span></button>`,
        )
        .join('')}</div>
      <button class="tb-s glass" aria-label="Search">${i('search', 22)}</button>
    </nav>`;
  };

  // A glass list row.
  const row = ({ icon, iconCls = '', title, sub = '', right = '', chev = false, cls = '' }) =>
    `<div class="rw ${cls}">
      ${icon ? `<span class="rw-ic ${iconCls}">${i(icon, 18)}</span>` : ''}
      <div class="rw-t"><b>${title}</b>${sub ? `<span>${sub}</span>` : ''}</div>
      ${right}${chev ? `<span class="rw-chev">${i('chevron-right', 18)}</span>` : ''}
    </div>`;

  // ---------------------------------------------------------------- player controls
  const dpad = () => `<div class="ctl dpad" role="group" aria-label="D-pad">
      <span class="dp-x" aria-hidden="true"></span>
      <button class="dp dp-u" aria-label="Up">${i('chevron-up', 16)}</button>
      <button class="dp dp-d" aria-label="Down">${i('chevron-down', 16)}</button>
      <button class="dp dp-l" aria-label="Left">${i('chevron-left', 16)}</button>
      <button class="dp dp-r" aria-label="Right">${i('chevron-right', 16)}</button>
    </div>`;
  const diamond = () => `<div class="face" role="group" aria-label="Face buttons">
      <button class="ctl fb fb-x" aria-label="X">X</button>
      <button class="ctl fb fb-y" aria-label="Y">Y</button>
      <button class="ctl fb fb-a" aria-label="A">A</button>
      <button class="ctl fb fb-b" aria-label="B">B</button>
    </div>`;
  const duo = () => `<div class="face duo" role="group" aria-label="Face buttons">
      <button class="ctl fb fb-b" aria-label="B">B</button>
      <button class="ctl fb fb-a" aria-label="A">A</button>
    </div>`;
  const pill = (label, cls = '') => `<button class="ctl pl-pill ${cls}">${esc(label)}</button>`;
  const menuBtn = (cls = '') => `<button class="ctl pl-menu ${cls}" aria-label="Menu">${i('menu', 18)}</button>`;
  const shoulder = (s) => `<button class="ctl pl-sh pl-sh-${s.toLowerCase()}" aria-label="${s} shoulder">${s}</button>`;

  // 2. Bias lighting: the live frame, enlarged and blurred, behind everything.
  const bias = (src, cls = '') => `<div class="bias ${cls}" aria-hidden="true"><img src="${src}" alt=""><i></i></div>`;

  const landscapePlayer = (id, variant, { toast = false } = {}) => {
    const src = k.frame(id, variant);
    const g = k.game(id);
    return `<div class="pl pl-land" style="${lit(id)}">
      ${bias(src)}
      <div class="vp" style="--ar:${k.aspect(id)}"><img class="px" src="${src}" alt="${esc(g.title)} gameplay"></div>
      <div class="rail rail-l">
        ${shoulder('L')}
        ${dpad()}
        <div class="rail-low">${menuBtn()}${pill('Select')}</div>
      </div>
      <div class="rail rail-r">
        ${shoulder('R')}
        ${diamond()}
        <div class="rail-low">${pill('Start')}</div>
      </div>
      ${toast ? `<div class="toast glass" role="status">${i('check', 15)}<span>Automatic resume updated</span></div>` : ''}
    </div>`;
  };

  // ---------------------------------------------------------------- screens
  const screens = {};

  // ---- Onboarding --------------------------------------------------------
  screens.welcome = (c) => {
    const fan = [
      ['sotn', 'f0'],
      ['chrono', 'f1'],
      ['smetroid', 'f2'],
      ['linksawak', 'f3'],
      ['earthbound', 'f4'],
    ];
    return `
    <div class="aura a-welcome" aria-hidden="true">
      <img class="aura-art" src="${k.cover('smetroid', 'tall')}" alt="">
      <i class="aura-light"></i><i class="aura-veil"></i>
    </div>
    <div class="wl-stage" aria-hidden="true">
      ${fan
        .map(
          ([id, cls]) => `<figure class="wl-card ${cls}" style="${lit(id)}"><img class="wl-glow" src="${k.cover(id, 'box')}" alt=""><img class="wl-img" src="${k.cover(id, 'box')}" alt=""></figure>`,
        )
        .join('')}
    </div>
    <div class="pg wl-body">
      <div class="brand">${mark(34)}<span class="word">rommio</span></div>
      <h1 class="wl-h">Your RomM library, native and ready to play.</h1>
      <p class="lede">Browse natively, play offline, and pick up on any device — your saves follow you.</p>
      <div class="stack">
        ${btn('Connect to RomM', { kind: 'primary', cls: 'lg' })}
        ${btn('Scan setup code', { icon: 'qr', cls: 'lg' })}
      </div>
      <p class="fine">Rommio needs your own RomM server. It doesn’t provide games.</p>
    </div>`;
  };

  screens.connect = (c) => {
    const S = D().server.discovered;
    return `
    ${aura('metfusion', { cls: 'a-onb' })}
    <div class="pg onb">
      <div class="nav">${back(c, 'Back to welcome')}<span class="step">Step 1 of 2</span><span class="nav-sp"></span></div>
      <div class="onb-h">
        <h1 class="lt">Connect your server</h1>
        <p class="sub">Use the address you open RomM with in a browser.</p>
      </div>
      <div class="fg glass">
        <label class="fld" for="au-server">
          <span class="fld-ic">${i('globe', 20)}</span>
          <span class="fld-main"><span class="fld-l">Server address</span><input id="au-server" type="url" value="romm.homelab.dev" autocomplete="url" spellcheck="false"></span>
          <button class="fld-x" aria-label="Clear address">${i('x', 16)}</button>
        </label>
        <div class="fld-check ok" role="status">${i('check-circle', 16)}<span>RomM 5.2.0 · protected by Cloudflare Access</span></div>
      </div>
      ${btn('Connect', { kind: 'primary', cls: 'lg wide' })}
      ${sh('Saved and nearby')}
      <div class="grp glass">
        ${row({ icon: 'server', iconCls: 'lit', title: esc(S[0].name), sub: esc(S[0].note), right: `<span class="chip-s ok">${i('lock', 12)}HTTPS</span>` })}
        ${row({ icon: 'wifi', title: esc(S[1].name), sub: esc(S[1].note), right: `<span class="chip-s wait">${i('alert', 12)}Not encrypted</span>` })}
      </div>
      <div class="grp glass access">
        ${row({ icon: 'shield', title: 'Access options', sub: 'How Rommio reaches your server' })}
        <div class="acc" role="radiogroup" aria-label="Access method">
          ${['Automatic', 'Direct', 'Cloudflare Access', 'Service token']
            .map((o, n) => `<button role="radio" aria-checked="${n === 0}" class="${n === 0 ? 'on' : ''}">${n === 0 ? i('check', 14) : ''}${o}</button>`)
            .join('')}
        </div>
      </div>
      <p class="fine onb-fine">Have a setup code from RomM? <button class="txt-btn">${i('qr', 14)} Scan it</button></p>
    </div>`;
  };

  screens.signin = (c) => `
    ${aura('chrono', { cls: 'a-onb a-sign' })}
    <i class="horizon-glow" aria-hidden="true"></i><i class="horizon" aria-hidden="true"></i>
    <div class="pg onb">
      <div class="nav">${back(c, 'Change server')}<span class="step">Step 2 of 2</span><span class="nav-sp"></span></div>
      <div class="srv glass">
        <span class="srv-ic">${i('server', 22)}<i></i></span>
        <div class="srv-t"><b>romm.homelab.dev</b><span>${i('shield', 13)}RomM 5.2.0 · access verified</span></div>
        <button class="txt-btn">Change</button>
      </div>
      <div class="onb-h low">
        <h1 class="lt">Sign in</h1>
        <p class="sub">Use your RomM account on this server.</p>
      </div>
      <div class="fg glass">
        <label class="fld" for="au-user">
          <span class="fld-ic">${i('user', 20)}</span>
          <span class="fld-main"><span class="fld-l">Username</span><input id="au-user" type="text" value="ryan" autocomplete="username"></span>
        </label>
        <label class="fld focus" for="au-pass">
          <span class="fld-ic">${i('key', 20)}</span>
          <span class="fld-main"><span class="fld-l">Password</span><input id="au-pass" type="password" value="correcthorse" autocomplete="current-password"></span>
          <button class="fld-x" aria-label="Show password">${i('eye', 18)}</button>
        </label>
      </div>
      ${btn('Sign in', { kind: 'primary', cls: 'lg wide' })}
      <div class="or"><span>or</span></div>
      ${btn('Continue with single sign-on', { icon: 'key', cls: 'lg wide' })}
    </div>`;

  screens.hydrate = (c) => {
    const wall = ['smetroid', 'linksawak', 'chrono', 'sotn', 'earthbound', 'sonic2', 'metfusion', 'pkcrystal', 'sor2', 'mm2', 'ff6', 'kirbyadv', 'oot3d', 'jetset', 'aria'];
    const cached = new Set([0, 1, 2, 3, 4, 5, 7, 9, 12]); // 9 of 15 ≈ 61%
    return `
    <div class="aura a-hyd" aria-hidden="true">
      <img class="aura-art" src="${k.cover('chrono', 'tall')}" alt="">
      <i class="aura-light"></i><i class="aura-veil"></i>
    </div>
    <div class="hy-wall" aria-hidden="true">${wall
      .map((id, n) =>
        cached.has(n)
          ? `<figure class="hy-t on" style="${lit(id)}">${cover(id)}</figure>`
          : `<figure class="hy-t"><span class="hy-sh"></span></figure>`,
      )
      .join('')}</div>
    <i class="scrim-top" aria-hidden="true"></i>
    <div class="pg hy">
      <div class="hy-head">
        <p class="eyebrow">${i('server', 13)}Home server · first sync</p>
        <h1 class="lt">Getting your library ready</h1>
      </div>
      <div class="hy-card glass">
        <div class="hy-big">
          <div><span class="hy-num">61<small>%</small></span><span class="hy-cap">Artwork cached for offline</span></div>
          <span class="hy-live">${i('sync', 14)}</span>
        </div>
        <div class="bar glowbar" role="progressbar" aria-valuenow="61" aria-valuemin="0" aria-valuemax="100" aria-label="Artwork cached"><i style="width:61%"></i></div>
        <ul class="hy-list">
          <li><span>${i('check', 15)}</span><b>Platforms</b><em>${k.totals().platforms}</em></li>
          <li><span>${i('check', 15)}</span><b>Games</b><em>${k.num(k.totals().games)}</em></li>
          <li><span>${i('check', 15)}</span><b>Collections</b><em>Done</em></li>
        </ul>
      </div>
      <div class="hy-sync glass">
        <div class="hy-dev" aria-hidden="true">${i('smartphone', 18)}<i></i>${i('cloud', 18)}<i></i>${i('tablet', 18)}</div>
        <p><b>Your progress travels with you.</b> Game saves and resume states sync between This iPhone, Pixel 9 and iPad.</p>
      </div>
      <div class="grp glass hy-opts">
        ${row({ title: 'Keep artwork offline', sub: 'Covers and screenshots stay on this device', right: sw(true, 'Keep artwork offline') })}
        ${row({ title: 'Sync on cellular', sub: 'Game saves and resume states only', right: sw(false, 'Sync on cellular') })}
      </div>
      <div class="hy-cta">
        ${btn('Start playing', { kind: 'primary', cls: 'lg wide', icon: 'play' })}
        <p class="fine">Artwork keeps caching in the background.</p>
      </div>
    </div>`;
  };

  // ---- Library ------------------------------------------------------------
  const resumeCard = (id, variant, pos) => {
    const g = k.game(id);
    const p = plat(g);
    return `<article class="cc glass cc-${pos}" style="${lit(id)}" ${pos !== 'on' ? 'aria-hidden="true"' : ''}>
      ${gs(k.frame(id, variant), { ratio: 4 / 3, label: `${g.title} resume frame` })}
      <div class="cc-b">
        <p class="cc-m"><span>${esc(p.short)}</span>${esc(k.ago(g.played))}</p>
        <h3 class="cc-t">${esc(g.title)}</h3>
        <div class="cc-r">${sync(g.sync)}${btn('Resume', { kind: 'primary', icon: 'play', cls: 'sm', size: 16 })}</div>
      </div>
    </article>`;
  };

  screens.home = (c) => {
    const mv = k.collectionGames('metroidvania');
    const recent = k.collectionGames('recent');
    return `
    ${aura('smetroid', { cls: 'a-home' })}
    <div class="pg home">
      <header class="hd">
        <div class="hd-t"><p class="eyebrow"><i class="dot dot-ok"></i>Home server · synced 2 min ago</p><h1 class="lt">Continue playing</h1></div>
        ${avatar()}
      </header>
      <div class="car" aria-roledescription="carousel">
        ${resumeCard('chrono', 'b', 'prev')}
        ${resumeCard('smetroid', 'b', 'on')}
        ${resumeCard('linksawak', 'b', 'next')}
      </div>
      <div class="pager" aria-hidden="true"><i class="on"></i><i></i><i></i></div>
      <div class="status">
        <button class="st glass"><b class="tab">${k.totals().installed}</b><span>installed</span></button>
        <button class="st glass"><b class="tab">3</b><span>in queue</span></button>
        <button class="st glass bad"><b class="tab">1</b><span>conflict needs you</span>${i('chevron-right', 16)}</button>
      </div>
      <article class="hl glass" style="${lit('sotn')}">
        <div class="hl-t">
          <p class="eyebrow">Collection · ${mv.length} games</p>
          <h3>Metroidvanias</h3>
          <p class="hl-n">${esc(k.collection('metroidvania').note)}</p>
        </div>
        <div class="hl-fan" aria-hidden="true">${mv
          .slice(0, 3)
          .reverse()
          .map((g, n) => `<img class="hl-c h${n}" src="${k.cover(g.id, 'box')}" alt="">`)
          .join('')}</div>
      </article>
      ${sh('Recently added', `${recent.length} games`, 'See all')}
      <div class="shelf">${recent
        .map(
          (g) => `<figure class="sf" style="${lit(g.id)}">${cover(g.id)}<figcaption><b>${esc(g.title)}</b><span>${esc(plat(g).short)}</span></figcaption></figure>`,
        )
        .join('')}</div>
    </div>
    ${tabbar('home')}`;
  };

  const platTile = (p, cls = '') =>
    `<button class="pt glass ${cls}" style="--pc:${litHex(p.color)}" aria-label="${esc(p.name)}, ${p.games} games, ${p.installed} installed">
      <span class="pt-glow" aria-hidden="true"></span>
      <span class="pt-top"><span class="pt-s">${esc(p.short)}</span>${
        p.installed ? `<span class="pt-in">${i('check', 11)}${p.installed}</span>` : ''
      }</span>
      <span class="pt-n">${cls === 'ctrl' ? i('gamepad', 12) : ''}${esc(p.name)}</span>
      <span class="pt-c"><b>${k.num(p.games)}</b> games</span>
    </button>`;

  screens.library = (c) => {
    const touch = k.tier('touch').slice().sort((a, b) => b.installed - a.installed || b.games - a.games);
    const featured = touch.slice(0, 6);
    const more = touch.slice(6);
    return `
    <div class="aura a-lib" aria-hidden="true"><i class="aura-light"></i><i class="aura-veil"></i></div>
    <div class="pg lib">
      <header class="hd">
        <div class="hd-t"><p class="eyebrow">${k.totals().platforms} platforms · ${k.num(k.totals().games)} games</p><h1 class="lt">Library</h1></div>
        ${avatar()}
      </header>
      <label class="search glass" for="au-lib-search">${i('search', 18)}<input id="au-lib-search" type="search" placeholder="Search games, platforms, collections"></label>
      <section class="tsec">
        ${sh('Touch', '17 platforms · on-screen controls')}
        <div class="pgrid">${featured.map((p) => platTile(p)).join('')}</div>
        <button class="more glass">
          <span class="more-ms">${more
            .slice(0, 6)
            .map((p) => `<i style="--pc:${litHex(p.color)}">${esc(p.short)}</i>`)
            .join('')}</span>
          <span class="more-t">${more.length} more</span>${i('chevron-right', 16)}
        </button>
      </section>
      <section class="tsec">
        ${sh('Controller first', 'Pair a controller to play')}
        <div class="pgrid">${k
          .tier('controller')
          .map((p) => platTile(p, 'ctrl'))
          .join('')}</div>
      </section>
      <section class="tsec">
        ${sh('Browse only', 'No embedded player yet')}
        <div class="grp glass">${k
          .tier('unsupported')
          .map((p) =>
            row({
              icon: 'eye',
              title: `${esc(p.name)}`,
              sub: esc(p.blocked.replace('Browse only — ', '')),
              right: `<span class="rw-v tab">${k.num(p.games)}</span>`,
              chev: true,
            }),
          )
          .join('')}</div>
      </section>
    </div>
    ${tabbar('library')}`;
  };

  screens.platform = (c) => {
    const p = k.platform('snes');
    const games = k
      .gamesOn('snes')
      .slice()
      .sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));
    return `
    <div class="aura a-plat" aria-hidden="true"><i class="aura-light"></i><i class="aura-veil"></i></div>
    <div class="pg plat">
      <div class="nav">${back(c, 'Library')}<span class="nav-sp"></span>${disc('search', 'Search Super Nintendo')}${disc('more', 'More')}</div>
      <header class="ph">
        <div class="ph-fan" aria-hidden="true">
          <img src="${k.cover('earthbound', 'box')}" alt="" class="pf0"><img src="${k.cover('chrono', 'box')}" alt="" class="pf1"><img src="${k.cover('smetroid', 'box')}" alt="" class="pf2">
        </div>
        <h1 class="ph-t">Super<br>Nintendo</h1>
        <div class="ph-m">
          <span class="tab"><b>${p.games}</b> games</span><span class="tab"><b>${p.installed}</b> installed</span>
        </div>
        <div class="ph-chips"><span class="chip-core">${i('cpu', 13)}${esc(p.core)} ready</span>${tier(p.tier)}</div>
      </header>
      <div class="filters">
        <div class="seg glass" role="tablist" aria-label="Filter">
          <button role="tab" aria-selected="true" class="on">All</button><button role="tab" aria-selected="false">Installed</button><button role="tab" aria-selected="false">Favourites</button>
        </div>
        <button class="sort glass">${i('sort', 16)}<span>Recently played</span></button>
      </div>
      <div class="ggrid">${games
        .slice(0, 9)
        .map(
          (g) => `<figure class="gt ${g.installed ? 'in' : 'out'}" style="${lit(g.id)}">
            <div class="gt-art">${cover(g.id)}${g.installed ? '' : `<span class="gt-dl glass" aria-label="Not downloaded">${i('download', 15)}</span>`}</div>
            <figcaption><b>${esc(g.title)}</b><span>${g.installed ? `${i('check', 12)}Installed` : `${k.size(g.size)} · Not downloaded`}</span></figcaption>
          </figure>`,
        )
        .join('')}</div>
    </div>
    ${tabbar('library')}`;
  };

  const collArt = (ids, cls = '') =>
    `<div class="ca ${cls}" aria-hidden="true">${ids
      .slice(0, 3)
      .map((id, n) => `<img class="ca${n}" src="${k.cover(id, 'box')}" alt="">`)
      .join('')}</div>`;

  screens.collections = (c) => {
    const C = D().collections;
    const mine = C.filter((x) => x.kind === 'manual');
    const smart = C.filter((x) => x.kind === 'smart');
    const auto = C.filter((x) => x.kind === 'auto');
    return `
    ${aura('sor2', { cls: 'a-coll' })}
    <div class="pg coll">
      <header class="hd">
        <div class="hd-t"><p class="eyebrow">${C.length} collections</p><h1 class="lt">Collections</h1></div>
        ${disc('plus', 'New collection')}
        ${avatar()}
      </header>
      ${sh('My collections')}
      <div class="cgrid">${mine
        .map(
          (x) => `<article class="cc2 glass" style="${lit(x.games[0])}">
            <span class="cc2-glow" aria-hidden="true"></span>
            ${collArt(x.games)}
            <div class="cc2-t"><h3>${esc(x.name)}</h3><span>${x.games.length} games</span></div>
          </article>`,
        )
        .join('')}</div>
      ${sh('Smart', 'Update themselves')}
      <div class="grp glass">${smart
        .map(
          (x) => `<div class="rw sm-row">
            ${collArt(x.games, 'mini')}
            <div class="rw-t"><b>${esc(x.name)}</b><span>${i('sparkles', 12)}${esc(x.rule)}</span></div>
            <span class="rw-v tab">${x.games.length}</span><span class="rw-chev">${i('chevron-right', 18)}</span>
          </div>`,
        )
        .join('')}</div>
      ${sh('Autogenerated', 'From your library')}
      <div class="ashelf">${auto
        .map(
          (x) => `<article class="ac glass" style="${lit(x.games[0])}">
            ${collArt(x.games, 'mini')}
            <div class="ac-t"><b>${esc(x.name)}</b><span>${esc(x.rule)}</span><em class="tab">${x.games.length} games</em></div>
          </article>`,
        )
        .join('')}</div>
    </div>
    ${tabbar('collections')}`;
  };

  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = plat(g);
    const P = D().progress;
    const prow = (icon, title, sub, right) =>
      `<div class="rw"><span class="rw-ic">${i(icon, 18)}</span><div class="rw-t"><b>${title}</b><span>${sub}</span></div>${right}</div>`;
    return `
    ${aura('smetroid', { cls: 'a-game' })}
    <div class="gm-hero" aria-hidden="true"><img class="px" src="${k.cover('smetroid', 'tall')}" alt=""></div>
    <i class="scrim-top" aria-hidden="true"></i>
    <div class="pg game">
      <div class="nav">${back(c, 'Home')}<span class="nav-sp"></span>${disc('heart', 'Favourite', 'on')}${disc('more', 'More')}</div>
      <header class="gm-h">
        <p class="eyebrow">${esc(p.name)} · ${g.year}</p>
        <h1 class="gm-t">Super Metroid</h1>
        <p class="gm-m"><span>${esc(g.publisher)} · ${esc(g.genre)}</span><span class="score" aria-label="Rating ${g.rating}">${g.rating}</span></p>
        <div class="gm-chips"><span class="chip-core">${i('cpu', 13)}${esc(p.core)} ready</span>${tier(p.tier)}<span class="chip-s ok">${i('check', 12)}Installed</span></div>
      </header>
      <section class="rs glass">
        <div class="rs-top">
          ${gs(k.frame('smetroid', P.resume.frame), { label: 'Resume frame' })}
          <div class="rs-t">
            <p class="eyebrow">Resume</p>
            <b>${esc(k.ago(P.resume.at))}</b>
            <span>${i('smartphone', 13)}${esc(P.resume.device)}</span>
            <span>${i('clock', 13)}${esc(k.hours(g.hours))} played</span>
          </div>
        </div>
        <div class="rs-b">
          ${btn('Resume', { kind: 'primary', icon: 'play', cls: 'lg' })}
          ${btn('Start with game save', { cls: 'lg' })}
        </div>
      </section>
      ${sh('Progress', '', 'History')}
      <div class="grp glass">
        ${prow('cartridge', 'Game-save data', `${esc(k.ago(P.gameSave.at))} · ${esc(P.gameSave.detail)}`, sync(P.gameSave.remote))}
        ${prow('history', 'Automatic resume', `${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}`, sync(P.resume.remote))}
        ${prow('layers', 'Save states', 'Slots 1–3 · 1 free', `<span class="rw-v tab">3</span><span class="rw-chev">${i('chevron-right', 18)}</span>`)}
      </div>
      <div class="grp glass">
        ${row({ icon: 'file', title: 'Super Metroid (USA).sfc', sub: `${k.size(g.size)} · Installed on this iPhone` })}
      </div>
      <p class="blurb">${esc(g.blurb)}</p>
      <div class="gm-act">
        ${btn('Sync now', { icon: 'sync' })}
        ${btn('Remove download', { icon: 'trash', cls: 'quiet' })}
      </div>
      <p class="fine left">Removing the download keeps your game saves and states.</p>
    </div>`;
  };

  screens.downloads = (c) => {
    const DL = D().downloads;
    const run = DL.find((d) => d.state === 'running');
    const q = DL.filter((d) => d.state === 'queued');
    const wait = DL.filter((d) => d.state === 'waiting');
    const fail = DL.filter((d) => d.state === 'failed');
    const done = DL.filter((d) => d.state === 'done');
    const S = D().storage;
    const rg = k.game(run.game);
    const thumb = (id) => `<img class="th" src="${k.cover(id, 'box')}" alt="">`;
    const trow = (id, sub, right, extra = '') => {
      const g = k.game(id);
      return `<div class="rw thumb">${thumb(id)}<div class="rw-t"><b>${esc(g.title)}</b><span>${sub}</span>${extra}</div>${right}</div>`;
    };
    return `
    ${aura('ff7', { cls: 'a-dl' })}
    <div class="pg dl">
      <header class="hd">
        <div class="hd-t"><p class="eyebrow">${i('hard-drive-download', 13)}ROMs · ${k.size(S.roms)} of ${k.size(S.total)}</p><h1 class="lt">Downloads</h1></div>
        ${avatar()}
      </header>
      <div class="sum glass" role="list" aria-label="Summary">
        <span role="listitem"><b class="tab">1</b>Active</span>
        <span role="listitem"><b class="tab">${q.length}</b>Queued</span>
        <span role="listitem" class="wait"><b class="tab">${wait.length}</b>Waiting</span>
        <span role="listitem" class="bad"><b class="tab">${fail.length}</b>Failed</span>
      </div>
      <article class="act glass" style="${lit(run.game)}">
        <span class="act-glow" aria-hidden="true"></span>
        <div class="act-top">
          <img class="th" src="${k.cover(run.game, 'box')}" alt="">
          <div class="act-t"><p class="eyebrow">Downloading · ${esc(plat(rg).short)}</p><b>${esc(rg.title)}</b><span class="tab">${k.size(run.done)} of ${k.size(run.total)}</span></div>
          <span class="act-pct tab">62<small>%</small></span>
        </div>
        <div class="bar glowbar lg" role="progressbar" aria-valuenow="62" aria-valuemin="0" aria-valuemax="100" aria-label="${esc(rg.title)} download"><i style="width:62%"></i></div>
        <div class="act-b">
          <span class="tab">${i('zap', 13)}${run.rate} MB/s</span><span class="tab">${i('timer', 13)}${esc(run.eta)} left</span>
          <span class="act-sp"></span>
          ${disc('pause', `Pause ${rg.title}`)}${disc('x', `Cancel ${rg.title}`)}
        </div>
      </article>
      ${sh('Up next', `${q.length + wait.length} waiting their turn`, 'Edit')}
      <div class="grp glass">
        ${q.map((d) => trow(d.game, `${esc(plat(k.game(d.game)).short)} · ${k.size(d.total)}`, `<span class="grip" aria-label="Reorder">${i('grip', 18)}</span>`)).join('')}
        ${wait.map((d) => trow(d.game, `${esc(plat(k.game(d.game)).short)} · ${k.size(d.total)}`, `<span class="chip-s wait">${i('wifi-off', 12)}${esc(d.reason)}</span>`)).join('')}
      </div>
      ${sh('Needs attention')}
      <div class="grp glass">
        ${fail
          .map((d) =>
            trow(d.game, `<span class="bad-t">${esc(d.reason)}</span>`, btn('Retry', { icon: 'restart', cls: 'sm tint-bad', size: 14 }), `<span class="tab">Stopped at ${k.size(d.done)} of ${k.size(d.total)}</span>`),
          )
          .join('')}
      </div>
      ${sh('Completed recently')}
      <div class="grp glass">
        ${done.map((d) => trow(d.game, `${esc(plat(k.game(d.game)).short)} · ${k.size(d.total)} · ${esc(k.ago(d.at))}`, `<span class="chip-s ok">${i('check', 12)}Ready</span>`)).join('')}
      </div>
    </div>
    ${tabbar('downloads')}`;
  };

  // ---- Settings -----------------------------------------------------------
  screens.settings = (c) => {
    const [home, sam] = D().profiles;
    const S = D().storage;
    const parts = [
      ['ROMs', S.roms, 'var(--l1)'],
      ['Save states', S.states, 'var(--live)'],
      ['Game saves', S.saves, 'var(--ok)'],
      ['Cache', S.cache, 'var(--wait)'],
      ['Cores', S.cores, 'rgb(247 247 251 / .6)'],
      ['BIOS', S.bios, 'rgb(247 247 251 / .32)'],
    ];
    const used = parts.reduce((n, x) => n + x[1], 0);
    return `
    <div class="aura a-set" aria-hidden="true"><i class="aura-light"></i><i class="aura-veil"></i></div>
    <div class="pg set">
      <div class="nav">${back(c, 'Home')}<h1 class="nav-t">Settings</h1><span class="nav-sp"></span></div>
      <section class="acct glass">
        <div class="acct-top">
          <span class="avatar xl" aria-hidden="true"><span>${esc(D().user.initials)}</span></span>
          <div class="acct-t"><b>${esc(home.name)}</b><span>${esc(home.user)} · ${esc(home.url)}</span></div>
          <span class="rw-chev">${i('chevron-right', 18)}</span>
        </div>
        <div class="acct-s">
          <span class="sp sp-ok xs"><i class="dot"></i>Online</span>
          <span class="acct-m">${i('cloud-check', 14)}Synced ${esc(k.ago(home.lastSync))}</span>
          <span class="acct-m">${esc(home.version)}</span>
        </div>
        <div class="acct-p">
          <span class="acct-pi">${i('server', 16)}</span>
          <div class="rw-t"><b>${esc(sam.name)}</b><span>${esc(sam.user)} · ${esc(sam.url)}</span></div>
          <span class="chip-s mute">${i('cloud-off', 12)}Offline-ready</span>
          <span class="rw-chev">${i('chevron-right', 18)}</span>
        </div>
      </section>
      ${sh('Sync')}
      <div class="grp glass">
        ${row({ icon: 'save', title: 'Game saves', sub: 'In-game save data follows you', right: sw(true, 'Sync game saves') })}
        ${row({ icon: 'history', title: 'Resume states', sub: 'Pick up mid-level on any device', right: sw(true, 'Sync resume states') })}
        ${row({ icon: 'signal', title: 'Sync on cellular', right: sw(false, 'Sync on cellular') })}
      </div>
      ${sh('Storage', `<span class="tab">${k.size(used)} on this iPhone</span>`)}
      <div class="stor glass">
        <div class="stack-bar" aria-hidden="true">${parts.map(([, v, col]) => `<i style="flex:${Math.max(v / used, 0.02)};background:${col}"></i>`).join('')}</div>
        <ul class="legend">${parts
          .map(([n, v, col]) => `<li><i style="background:${col}"></i><span>${n}</span><b class="tab">${k.size(v)}</b></li>`)
          .join('')}</ul>
      </div>
      <div class="grp glass">
        ${row({ icon: 'gamepad', iconCls: 'lit', title: 'Player & controls', sub: 'Touch layout, controllers, display', chev: true })}
        ${row({ icon: 'cpu', title: 'Cores & BIOS', sub: `11 cores · <span class="wait-t">1 needs BIOS · 1 needs setup</span>`, right: '<i class="dot dot-wait"></i>', chev: true })}
        ${row({ icon: 'info', title: 'About Rommio', chev: true })}
      </div>
    </div>`;
  };

  // Mini live preview reuses the real landscape player, scaled down.
  screens.controls = (c) => {
    const C = D().controls;
    const slider = (label, val, min, max, fmt) =>
      `<div class="sl"><div class="sl-t"><span>${label}</span><b class="tab">${fmt}</b></div><input type="range" aria-label="${label}" min="${min}" max="${max}" value="${val}" style="--v:${((val - min) / (max - min)) * 100}%"></div>`;
    const safe = c.os === 'android' ? 44 : 62;
    return `
    ${aura('mmx', { cls: 'a-ctl' })}
    <div class="pg ctls">
      <div class="nav">${back(c, 'Settings')}<span class="nav-sp"></span><button class="txt-btn">Reset</button></div>
      <div class="ctl-h"><h1 class="lt">Controls</h1><p class="sub">Global preferences for every game, previewed live.</p></div>
      <div class="prev" role="img" aria-label="Live preview of the touch layout over Mega Man X">
        <div class="prev-in" style="--safe-left:${safe}px;--safe-right:${safe}px">${landscapePlayer('mmx', 'a')}</div>
      </div>
      <div class="grp glass">
        ${row({ icon: 'hand', title: 'Show touch controls', right: sw(C.showTouch, 'Show touch controls') })}
        ${row({ icon: 'gamepad', title: 'Hide when a controller connects', sub: `<span class="ok-t"><i class="dot dot-ok"></i>DualSense connected</span>`, right: sw(C.autoHideOnController, 'Hide when a controller connects') })}
        ${row({ icon: 'flip', title: 'Left-handed layout', right: sw(C.leftHanded, 'Left-handed layout') })}
      </div>
      <div class="grp glass sls">
        ${slider('Opacity', 72, 20, 100, '72%')}
        ${slider('Size', 100, 70, 130, '100%')}
        ${slider('Stick deadzone', 12, 0, 40, '12%')}
      </div>
      <div class="grp glass">
        ${row({ icon: 'vibrate', title: 'Haptics', right: sw(C.haptics, 'Haptics') })}
        ${row({ icon: 'contrast', title: 'OLED black', sub: 'Pure black around the game', right: sw(C.oled, 'OLED black') })}
        ${row({ icon: 'palette', title: 'Console colours', sub: 'Tint buttons like the original pad', right: sw(C.consoleColors, 'Console colours') })}
        ${row({ icon: 'controller', title: 'Controller type', right: `<span class="rw-v">${esc(C.controllerType)}</span>`, chev: true })}
      </div>
    </div>`;
  };

  // ---- Player -------------------------------------------------------------
  screens.player = {
    tone: 'light',
    render: () => landscapePlayer('smetroid', 'b', { toast: true }),
  };

  screens['player-portrait'] = (c) => {
    const id = 'linksawak';
    const g = k.game(id);
    const src = k.frame(id, 'a');
    return `<div class="pl pl-port" style="${lit(id)}">
      ${bias(src, 'port')}
      <div class="pp-top">
        <span class="pp-title glass"><b>${esc(g.title)}</b>${sync(g.sync, { cls: 'xs' })}</span>
        ${menuBtn('lg')}
      </div>
      <div class="vp pp-vp" style="--ar:${k.aspect(id)}"><img class="px" src="${src}" alt="${esc(g.title)} gameplay"></div>
      <div class="pp-ss">${pill('Select')}${pill('Start')}</div>
      <div class="pp-band">
        <div class="pp-d">${dpad()}</div>
        <div class="pp-f">${duo()}</div>
      </div>
    </div>`;
  };

  screens.pause = (c) => {
    const id = 'smetroid';
    const g = k.game(id);
    const src = k.frame(id, 'b');
    const tile = (icon, label, sub = '', cls = '', attrs = '', extra = '') =>
      `<button class="pz-t glass ${cls}" ${attrs}>${i(icon, 20)}<span class="pz-tt"><b>${label}</b>${sub ? `<em>${sub}</em>` : ''}</span>${extra}</button>`;
    return `<div class="pl pz" style="${lit(id)}">
      ${bias(src, 'dim')}
      <div class="pz-l">
        ${gs(src, { label: `${g.title}, paused` })}
        <div class="pz-meta">
          <p class="eyebrow">${i('pause', 12)}Paused · ${esc(plat(g).name)}</p>
          <h1>${esc(g.title)}</h1>
          <p class="pz-s tab">Playing for ${D().progress.session ?? 42} min · ${esc(k.hours(g.hours))} total</p>
          <p class="pz-sync"><span class="pz-si"><i class="dot dot-ok"></i>Game-save data synced</span><span class="pz-si"><i class="dot dot-wait"></i>Resume waiting to sync</span></p>
        </div>
      </div>
      <div class="pz-r">
        ${btn('Resume', { kind: 'primary', icon: 'play', cls: 'lg wide' })}
        <div class="pz-grid">
          ${tile('save', 'Save state', 'Slot 4 is free')}
          ${tile('history', 'Load state', '3 states · Resume')}
          ${tile('gamepad', 'Controls', 'Touch · 72% opacity')}
          ${tile('fast-forward', 'Fast-forward', 'Off', 'ff', 'role="switch" aria-checked="false"', swVis(false))}
          ${tile('restart', 'Reset', 'Restart the game')}
          ${tile('exit', 'Leave game', 'Updates automatic resume', 'leave')}
        </div>
      </div>
    </div>`;
  };

  screens.states = (c) => {
    const id = 'smetroid';
    const P = D().progress;
    const src = k.frame(id, 'b');
    return `<div class="pl stt" style="${lit(id)}">
      ${bias(src, 'dim deep')}
      <header class="stt-h">
        ${back(c, 'Back to pause menu')}
        <div class="stt-ht"><p class="eyebrow">Super Metroid · Progress</p><h1>Save states</h1></div>
        <div class="seg glass" role="tablist" aria-label="View"><button role="tab" aria-selected="true" class="on">Save states</button><button role="tab" aria-selected="false">Game-save history</button></div>
      </header>
      <div class="stt-main">
        <div class="stt-top">
          <article class="sa glass">
            ${gs(k.frame(id, P.resume.frame), { label: 'Automatic resume frame' })}
            <div class="sa-t"><b>Automatic resume</b><span class="tab">${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}</span>${sync(P.resume.remote)}</div>
          </article>
          <article class="sa glass sv">
            <span class="sv-ic">${i('cartridge', 24)}</span>
            <div class="sa-t"><b>Game-save data</b><span class="tab">${esc(P.gameSave.detail)}</span><span class="tab">${esc(k.ago(P.gameSave.at))} · ${esc(P.gameSave.device)}</span>${sync(P.gameSave.remote)}</div>
          </article>
        </div>
        <div class="slots">${P.slots
          .map((s) =>
            s.empty
              ? `<button class="slot empty glass"><span class="slot-plus">${i('plus', 22)}</span><b>Slot ${s.n} · Empty</b><span>Save state here</span></button>`
              : `<article class="slot glass">
                <div class="slot-f">${gs(k.frame(id, s.frame), { label: s.name })}<span class="slot-n tab">${s.n}</span></div>
                <div class="slot-t"><b>${esc(s.name)}</b><span class="tab">${esc(s.device)} · ${esc(k.agoShort(s.at))}</span>${sync(s.remote, { cls: 'xs' })}</div>
              </article>`,
          )
          .join('')}</div>
      </div>
      <aside class="hist glass" aria-label="Recovery history">
        <div class="hist-h"><b>Recovery history</b></div>
        <div class="hist-f" role="tablist" aria-label="History type"><button class="on" role="tab" aria-selected="true">All</button><button role="tab" aria-selected="false">States</button><button role="tab" aria-selected="false">Game save</button></div>
        <ol class="tl">${P.history
          .map(
            (h) => `<li><i></i><div><b>${esc(h.kind)}</b><span class="tab">${esc(h.device)} · ${esc(k.agoShort(h.at))} · ${esc(h.reason)}</span></div></li>`,
          )
          .join('')}</ol>
      </aside>
    </div>`;
  };

  screens.conflict = (c) => {
    const C = D().conflict;
    const g = k.game(C.game);
    const [a, b] = C.branches;
    return `
    ${aura(C.game, { cls: 'a-conf' })}
    <div class="pg conf">
      <div class="nav"><button class="disc glass" aria-label="Cancel">${i('x', 20)}</button><span class="nav-sp"></span><span class="chip-s bad">${i('branch', 12)}Conflict</span></div>
      <header class="cf-h">
        <p class="eyebrow">${esc(g.title)} · ${esc(C.kind)}</p>
        <h1 class="lt">Two places to pick up from</h1>
        <p class="sub">${esc(a.device)} and ${esc(b.device)} each moved on from the same resume. Choose which one to continue.</p>
      </header>
      <div class="br-line" aria-hidden="true"><i></i></div>
      <div class="brs" role="radiogroup" aria-label="Resume to continue">${C.branches
        .map(
          (x, n) => `<button class="br glass ${n === 0 ? 'on' : ''}" role="radio" aria-checked="${n === 0}">
            ${gs(k.frame(C.game, x.frame), { label: `${x.where} on ${x.device}` })}
            <span class="br-t">
              <span class="br-d">${i('smartphone', 14)}<b>${esc(x.device)}</b><span class="radio"></span></span>
              <span class="br-w">${esc(x.where)}</span>
              <span class="br-m tab">${esc(k.ago(x.at))} · ${esc(x.reason)}</span>
              <span class="br-m tab">${esc(x.playtime)} played</span>
            </span>
          </button>`,
        )
        .join('')}</div>
      <div class="grp glass">
        ${row({ icon: 'archive', title: 'Keep a recovery copy', sub: `${esc(b.device)} · ${esc(b.where)} stays in history`, right: sw(true, 'Keep the other branch as a recovery copy') })}
      </div>
      <div class="cf-cta">
        ${btn(`Continue from ${a.device}`, { kind: 'primary', cls: 'lg wide' })}
        ${btn('Cancel', { kind: 'text', cls: 'lg wide' })}
      </div>
    </div>`;
  };

  screens.dual = (c) => {
    const id = 'pkblack';
    const g = k.game(id);
    const d = k.dual(id, 'a');
    return `<div class="pl pl-dual" style="${lit(id)}">
      ${bias(d.top, 'port')}
      <div class="ds">
        <div class="ds-s top"><img class="px" src="${d.top}" alt="${esc(g.title)} top screen"></div>
        <div class="ds-s bot"><img class="px" src="${d.bottom}" alt="${esc(g.title)} touch screen"></div>
        <span class="ds-touch glass">${i('pointer', 12)}Touch screen</span>
      </div>
      <div class="dd-band">
        ${shoulder('L')}${shoulder('R')}
        <div class="dd-d">${dpad()}</div>
        <div class="dd-c">${menuBtn('lg')}<div class="dd-ss">${pill('Select')}${pill('Start')}</div></div>
        <div class="dd-f">${diamond()}</div>
      </div>
    </div>`;
  };

  // ---------------------------------------------------------------- scene lights
  // The light each non-player screen is lit by. Wrapping the screen in a scene
  // lets every descendant (glass, primary glow, toggles, tab bar) inherit it.
  const LIGHT = {
    welcome: () => lightVars({ l1: lights('smetroid').l1, l2: lights('linksawak').l2, l3: lights('chrono').l2 }),
    connect: () => lit('metfusion'),
    signin: () => lit('chrono'),
    hydrate: () => lightVars({ l1: lights('chrono').l1, l2: lights('linksawak').l2, l3: lights('smetroid').l1 }),
    home: () => lit('smetroid'),
    library: () => lightVars({ l1: litHex('#7b6fd6'), l2: litHex('#d6312f'), l3: litHex('#2f6fd1') }),
    platform: () => lightVars({ l1: litHex('#7b6fd6'), l2: lights('chrono').l1, l3: lights('smetroid').l1 }),
    collections: () => lit('sor2'),
    game: () => lit('smetroid'),
    downloads: () => lit('ff7'),
    // Settings has no game of its own: it keeps the last game's embers, dimmed.
    settings: () => lit('smetroid'),
    controls: () => lit('mmx'),
    conflict: () => lit('earthbound'),
  };
  for (const [id, vars] of Object.entries(LIGHT)) {
    const render = screens[id];
    screens[id] = (c) => scene(vars(), render(c), `s-${id}`);
  }

  // ---------------------------------------------------------------- register
  Studio.defineVersion({
    id: 'aurora1',
    round: 1,
    name: 'Aurora',
    tagline: 'Cinematic liquid glass, lit by the game in your hands.',
    concept:
      'Every screen is lit by the game you are looking at: a light field built from its own palette and blurred art glows behind layered liquid glass, so Super Metroid burns ember and violet while Link’s Awakening glows sea and sun. In the player, the live frame spills its colour into the side rails like bias lighting, and the frosted controls sit in that light.',
    swatch: ['#07070C', '#FF7A3D', '#6FE3FF'],
    fonts: ['Bricolage+Grotesque:opsz,wdth,wght@12..96,75..100,300..800', 'Figtree:wght@400..800'],
    tone: 'light',
    palette: [
      { name: 'Night', hex: '#07070C' },
      { name: 'Night 2', hex: '#0E0E16' },
      { name: 'Ink', hex: '#F7F7FB' },
      { name: 'Glass (12%)', hex: '#FFFFFF1F' },
      { name: 'Synced · mint', hex: '#7CF2C4' },
      { name: 'Waiting · amber', hex: '#FFC66B' },
      { name: 'Syncing · cyan', hex: '#6FE3FF' },
      { name: 'Conflict · coral', hex: '#FF7A6B' },
      { name: 'Light · Super Metroid', hex: '#FF7A3D' },
      { name: 'Light · Link’s Awakening', hex: '#2F8FB8' },
    ],
    type: [
      { role: 'Display', family: 'Bricolage Grotesque', css: "'Bricolage Grotesque', sans-serif; font-weight: 800; font-stretch: 85%", sample: 'Super Metroid' },
      { role: 'UI', family: 'Figtree', css: "'Figtree', sans-serif", sample: 'Resume · 1 min ago · This iPhone' },
      { role: 'Numbers', family: 'Figtree tabular', css: "'Figtree', sans-serif; font-variant-numeric: tabular-nums", sample: '1.2 of 1.9 GB · 18.4 MB/s · 62%' },
    ],
    tweaks: [
      { var: '--ambient', label: 'Ambient light', type: 'range', min: 0, max: 100, step: 5, value: 90, unit: '%' },
      { var: '--blur', label: 'Glass blur', type: 'range', min: 0, max: 60, step: 2, value: 28, unit: 'px' },
      { var: '--glass-a', label: 'Glass fill', type: 'range', min: 3, max: 22, step: 1, value: 9, unit: '%' },
      { var: '--r', label: 'Corner radius', type: 'range', min: 12, max: 36, step: 2, value: 24, unit: 'px' },
      { var: '--ctl-o', label: 'Control opacity', type: 'range', min: 30, max: 100, step: 2, value: 72, unit: '%' },
      { var: '--drift', label: 'Light drift', type: 'select', options: ['running', 'paused'], value: 'running' },
    ],
    notes: {
      welcome: 'Five covers float in a shallow arc, each casting its own light. The centre card is the brand’s hero game; the field behind is built from three palettes so the first frame already feels lit.',
      home: 'The carousel is looped so both neighbours peek. Each card carries its own light variables, so its resume screen glows in its own colour while the page field follows the focused game.',
      library: 'Platform tiles glow in each console’s identity colour. Tiers stay visually distinct: Touch tiles, Controller tiles with a gamepad mark, Browse only as quiet rows with the reason.',
      game: 'The cover art is shown crisp at the top and then dissolves into its own light field. The resume screen is the actual resume frame.',
      player: 'Bias lighting: an enlarged, blurred copy of the live frame fills the rails. The glass controls sample that light through backdrop blur, so they are tinted by the game without extra colour.',
      pause: 'The frozen frame stays on screen as a glowing display; status wording is exact about which item has synced.',
      states: 'Automatic resume and game-save data are their own items above manual slots; recovery history sits beside them.',
      conflict: 'The two branches are side by side with their own frames. The default choice is highlighted, and keeping a recovery copy is on by default.',
    },
    screens,
  });
})();
