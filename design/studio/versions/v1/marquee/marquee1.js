/*
 * MARQUEE — loud, joyful, arcade-bright.
 *
 * DESIGN PLAN
 * -----------
 * Palette      Night #120F24 (ground) · Slab #1E1A3A (cards) · Slab-2 #29244D (fields, raised)
 *              Lip #0A0817 (hard shadows) · Ink #FFF8EC (text) · Ink-2 #C9C2E4 · Ink-3 #9890BD
 *              Candy: Coral #FF5A4E · Sun #FFC83D · Mint #3DDC97 · Sky #43C6F7 · Grape #8C6CFF
 *              Platform identity comes from platform.color (lifted when too dark for the night ground).
 *              Each section owns one candy: Home sun · Library sky · Collections grape ·
 *              Downloads mint · Settings coral. Sync states own one each: Synced mint ·
 *              Waiting sun · Syncing sky · Conflict coral · Local only lavender.
 * Type         Display — Unbounded 700–900: titles, platform short names, keys.
 *              UI — Outfit 400–700: everything you read.
 *              Pixel — Press Start 2P: counters, playtime "scores", slot numbers, button legends. Never prose.
 * Shape        Spacing 4 · 8 · 12 · 16 · 20 · 24 · 32 (20px page gutter).
 *              Radius --r (24) for cards, --r-xl (+6) heroes, --r-sm (×0.6) chips/fields, pills for stickers.
 *              Outline --ow (2px) used deliberately: fields, selected states, stickers on imagery.
 *              Depth is a solid lip, never blur: box-shadow 0 var(--drop) 0 <darker colour>.
 * Signature    1. Keys — every primary action is a chunky arcade key with a darker lip (sun = go).
 *              2. Colour slabs — platforms are solid colour tiles with giant wide short names and
 *                 a console silhouette watermark; the lip under a card is the platform colour.
 *              3. Stickers — die-cut covers with a cream border, tilted a few degrees; sync and tier
 *                 badges are candy stickers. Tilt is reserved for hero moments (--tilt).
 *              4. Scoreboard numerals — playtime, counts and progress read like an arcade score.
 *              5. Power bars — segmented progress with a lit tip.
 * Navigation   Floating chunky tab bar (Home, Library, Collections, Downloads, Settings); the active tab
 *              becomes a key in its section colour with its label. Pushed pages drop the tab bar and use
 *              a round back key. Onboarding has a three-pip step meter.
 * Player       OLED black. Viewport centred at 92% height, rails either side. D-pad left, SNES diamond right
 *              in console colours (Y mint, X sky, B sun, A coral) as tinted glass with a solid colour ring
 *              at the tuned opacity, pixel legends (Game Boy gets berry A/B and slanted Select/Start); shoulders sit on top of the rails with a rounded outer corner; Menu, Select
 *              and Start are small tertiary pills near the lower edge. Pause freezes the frame as a tilted
 *              sticker beside a chunky list with a blinking arcade cursor; states are memory cards.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;

  // ---------- colour helpers ----------
  const rgb = (hex) => {
    const n = parseInt(hex.replace('#', ''), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };
  const hex = (c) => '#' + c.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
  const mix = (a, b, t) => {
    const A = rgb(a);
    const B = rgb(b);
    return hex(A.map((v, n) => v + (B[n] - v) * t));
  };
  const lum = (h) => {
    const c = rgb(h).map((v) => {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  };
  const NIGHT = '#120F24';
  const INK = '#FFF8EC';
  const onColor = (h) => (lum(h) > 0.186 ? NIGHT : INK);
  // Dark platform colours are lifted so a slab still reads on the night ground.
  const lift = (h) => (lum(h) < 0.035 ? mix(h, '#8a86b0', 0.22) : h);
  const pvars = (p) => {
    const c = lift(p.color);
    return `--pc:${c};--pi:${onColor(c)};--pl:${mix(c, '#07050f', 0.45)}`;
  };
  const gvars = (g) => {
    const pal = window.StudioArt.palette(g.id);
    return `--g0:${pal[0]};--g1:${pal[1]};--g2:${pal[2]};--g3:${pal[3]};--g4:${pal[4]}`;
  };

  // Approximate width fit for Unbounded 900 uppercase.
  const fit = (text, width, max, factor = 0.84) => Math.min(max, Math.floor(width / (text.length * factor)));
  const gb = (mb) => (mb >= 1000 ? `${(mb / 1000).toFixed(1).replace(/\.0$/, '')} GB` : `${Math.round(mb)} MB`);
  const score = (h) => k.hours(h).replace(/ h /, 'H ').replace(/ m$/, 'M').replace(/^(\d+) m$/, '$1M');

  // ---------- console silhouettes (48×48, evenodd holes) ----------
  const FORM = {
    nes: 'cart', snes: 'cart', n64: 'cart', genesis: 'cart', sms: 'cart', pce: 'cart', vb: 'cart',
    gb: 'hv', gbc: 'hv', ngp: 'hv', gba: 'hh', gg: 'hh', lynx: 'hh', psp: 'hh',
    nds: 'clam', '3ds': 'clam', psx: 'disc', saturn: 'disc', dc: 'disc', gc: 'disc', ps2: 'disc', arcade: 'cab',
  };
  const SIL = {
    cart:
      '<path d="M9 20h30a5 5 0 0 1 5 5v10a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5V25a5 5 0 0 1 5-5zM11 29.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM17.5 29.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM26 30h12v3H26z"/><path d="M15 7h18v14H15zM18 10v6h12v-6z"/>',
    disc:
      '<path d="M8 14h32a5 5 0 0 1 5 5v16a5 5 0 0 1-5 5H8a5 5 0 0 1-5-5V19a5 5 0 0 1 5-5zM18 19a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM33 21h6v3h-6zM33 28h6v3h-6z"/><circle cx="18" cy="27" r="2.6"/>',
    hv: '<path d="M13 3h22a3 3 0 0 1 3 3v32a8 8 0 0 1-8 8H13a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM15 7v15h18V7zM17 29h3v-3h3v3h3v3h-3v3h-3v-3h-3zM30 31a1.9 1.9 0 1 0 0 3.8a1.9 1.9 0 1 0 0-3.8zM33.5 27.5a1.9 1.9 0 1 0 0 3.8a1.9 1.9 0 1 0 0-3.8z"/>',
    hh: '<path d="M11 12h26a9 9 0 0 1 9 9v6a9 9 0 0 1-9 9H11a9 9 0 0 1-9-9v-6a9 9 0 0 1 9-9zM15 16v16h18V16zM6 23h2.5v-2.5h2.5V23h2.5v2.5H11V28H8.5v-2.5H6zM38.5 20.5a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4zM41.5 24.5a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4z"/>',
    clam:
      '<path d="M10 3h28a3 3 0 0 1 3 3v15H7V6a3 3 0 0 1 3-3zM12 7v11h24V7z"/><path d="M7 23h34v18a3 3 0 0 1-3 3H10a3 3 0 0 1-3-3zM16 26v12h16V26zM10 31h2v-2h2v2h2v2h-2v2h-2v-2h-2zM36.5 28.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3zM36.5 34a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z"/>',
    cab: '<path d="M14 2h20v8l-2 2v13l5 4v17H11V29l5-4V12l-2-2zM17 4.5v3h14v-3zM18 14v10h12V14zM18.5 32a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM26 33h2.5v2.5H26zM30.5 33H33v2.5h-2.5z"/>',
  };
  const sil = (pid, cls = '') =>
    `<svg class="sil ${cls}" viewBox="0 0 48 48" fill="currentColor" fill-rule="evenodd" aria-hidden="true">${SIL[FORM[pid] || 'cart']}</svg>`;

  // ---------- small components ----------
  const logo = (size = 30) => `<svg class="mark" width="${size}" height="${size}" viewBox="0 0 32 32" aria-hidden="true">
      <rect x="1" y="5" width="30" height="24" rx="9" fill="var(--coral)"/>
      <rect x="1" y="3" width="30" height="24" rx="9" fill="var(--sun)"/>
      <path d="M8.5 13.5h3v-3h3v3h3v3h-3v3h-3v-3h-3z" fill="#120F24"/>
      <circle cx="21.5" cy="12.8" r="2.3" fill="#120F24"/><circle cx="25" cy="17.6" r="2.3" fill="#120F24"/></svg>`;

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
  const sync = (state, { solid = false, icon = true, label } = {}) => {
    const [c, ic] = SYNC[state] || SYNC.local;
    return `<span class="stk ${c}${solid ? ' solid' : ''}">${icon ? i(ic, 14) : ''}<span>${esc(label || k.syncLabel(state))}</span></span>`;
  };
  const TIER = { touch: ['sun', 'hand'], controller: ['sky', 'gamepad'], unsupported: ['mute', 'eye'] };
  const tier = (t, solid = false) => {
    const [c, ic] = TIER[t];
    return `<span class="stk ${c}${solid ? ' solid' : ''}">${i(ic, 14)}<span>${esc(k.tierLabel(t))}</span></span>`;
  };
  const pchip = (p, cls = '') => `<span class="pchip ${cls}" style="${pvars(p)}">${esc(p.short)}</span>`;

  const key = (label, { tone = 'sun', icon, cls = '', aria, trail } = {}) =>
    `<button class="key ${tone} ${cls}"${aria ? ` aria-label="${esc(aria)}"` : ''}>${icon ? i(icon, cls.includes('sm') ? 16 : 20) : ''}${
      label ? `<span>${esc(label)}</span>` : ''
    }${trail ? i(trail, 18) : ''}</button>`;
  const ibtn = (icon, label, cls = '') => `<button class="ibtn ${cls}" aria-label="${esc(label)}">${i(icon, 22)}</button>`;
  const back = (c, label = 'Back', cls = '') => ibtn(c.os === 'android' ? 'arrow-left' : 'chevron-left', label, cls);
  const sw = (on, label) =>
    `<button class="sw${on ? ' on' : ''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}"><i></i></button>`;
  const chev = () => `<span class="chev">${i('chevron-right', 18)}</span>`;
  const cover = (id, cls = '', kind = 'box') => `<img class="cov ${cls}" src="${k.cover(id, kind)}" alt="">`;
  const frame = (id, v = 'a', cls = '') =>
    `<img class="px ${cls}" src="${k.frame(id, v)}" alt="" style="aspect-ratio:${k.aspect(id)}">`;

  const sect = (title, { count, more, cls = '' } = {}) =>
    `<div class="sh ${cls}"><h2>${esc(title)}</h2>${count != null ? `<span class="cnt pix">${esc(count)}</span>` : ''}${
      more ? `<button class="sh-more">${esc(more)}${i('chevron-right', 16)}</button>` : ''
    }</div>`;

  const row = ({ icon, tone = 'sun', title, sub = '', trail = '', cls = '', lead }) =>
    `<div class="row ${cls}">${lead || `<span class="ico ${tone}">${i(icon, 20)}</span>`}<span class="row-t"><b>${esc(title)}</b>${
      sub ? `<small>${sub}</small>` : ''
    }</span>${trail}</div>`;

  const pbar = (pct, { segs = 20, tone = 'mint', live = false, cls = '' } = {}) => {
    const on = Math.round(pct * segs);
    return `<div class="pbar ${tone} ${cls}" role="progressbar" aria-valuenow="${Math.round(pct * 100)}" aria-valuemin="0" aria-valuemax="100">${k
      .range(segs)
      .map((n) => `<i class="${n < on ? 'on' : ''}${live && n === on - 1 ? ' tip' : ''}"></i>`)
      .join('')}</div>`;
  };

  const TABS = [
    ['home', 'home', 'Home', 'sun'],
    ['library', 'library', 'Library', 'sky'],
    ['collections', 'collections', 'Collections', 'grape'],
    ['downloads', 'downloads', 'Downloads', 'mint'],
    ['settings', 'settings', 'Settings', 'coral'],
  ];
  const tabs = (active) => `<div class="tabfade"></div><nav class="tabs" aria-label="Primary">${TABS.map(
    ([id, ic, label, tone]) =>
      `<button class="tab ${tone}${id === active ? ' on' : ''}" aria-label="${label}"${id === active ? ' aria-current="page"' : ''}>${i(ic, 22)}${
        id === active ? `<span>${label}</span>` : ''
      }${id === 'downloads' && id !== active ? '<b class="badge pix">3</b>' : ''}</button>`,
  ).join('')}</nav>`;

  const steps = (c, n) => `<header class="obbar">
      ${n > 1 ? back(c, n === 2 ? 'Change server' : 'Back') : back(c, 'Back')}
      <div class="pips" role="img" aria-label="Step ${n} of 3">${[1, 2, 3].map((x) => `<i class="${x < n ? 'done' : x === n ? 'on' : ''}"></i>`).join('')}</div>
      <span class="pix step">${n}/3</span>
    </header>`;

  // Cartridge shell with a cover for a label (collections).
  const cart = (id, cls = '', style = '') =>
    `<span class="cart ${cls}" style="${style}"><span class="cart-ridge"></span><img src="${k.cover(id, 'box')}" alt=""></span>`;
  const pile = (ids) =>
    `<span class="pile">${ids
      .slice(0, 3)
      .map((id, n) => cart(id, `c${n}`))
      .join('')}</span>`;

  // ---------- player pieces ----------
  // Rounded polygon path (quadratic corners) for the d-pad.
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
  const PLUS = roundPoly(
    [[42, 2], [78, 2], [78, 42], [118, 42], [118, 78], [78, 78], [78, 118], [42, 118], [42, 78], [2, 78], [2, 42], [42, 42]],
    11,
  );
  const dpad = (cls = '') => `<div class="dpad ${cls}" role="group" aria-label="D-pad">
      <svg viewBox="0 0 120 120" aria-hidden="true"><path class="dp-body" d="${PLUS}"/>
        <path class="dp-arrow" d="M60 14l9 11H51z"/><path class="dp-arrow" d="M60 106l9-11H51z"/>
        <path class="dp-arrow" d="M14 60l11-9v18z"/><path class="dp-arrow" d="M106 60l-11-9v18z"/>
        <circle class="dp-hub" cx="60" cy="60" r="9"/></svg>
      <button class="dp up" aria-label="Up"></button><button class="dp down" aria-label="Down"></button>
      <button class="dp left" aria-label="Left"></button><button class="dp right" aria-label="Right"></button>
    </div>`;
  const face = (cls = '', set = 'snes') => {
    if (set === 'gb')
      return `<div class="face gb ${cls}" role="group" aria-label="Face buttons">
        <button class="fb berry b" aria-label="B"><span class="pix">B</span></button>
        <button class="fb berry a" aria-label="A"><span class="pix">A</span></button></div>`;
    return `<div class="face ${cls}" role="group" aria-label="Face buttons">
      <button class="fb mint y" aria-label="Y"><span class="pix">Y</span></button>
      <button class="fb sky x" aria-label="X"><span class="pix">X</span></button>
      <button class="fb sun b" aria-label="B"><span class="pix">B</span></button>
      <button class="fb coral a" aria-label="A"><span class="pix">A</span></button></div>`;
  };
  const shoulder = (s) => `<button class="shl ${s.toLowerCase()}" aria-label="${s} shoulder"><span class="pix">${s}</span></button>`;
  const pill = (label, cls = '') => `<button class="tpill ${cls}" aria-label="${label}"><span class="pix">${label}</span></button>`;
  const menuBtn = (cls = '') => `<button class="menu-btn ${cls}" aria-label="Menu">${logo(22)}</button>`;

  const playerLandscape = (c, { toast = false, gid = 'smetroid', v = 'b' } = {}) => `
    <div class="pl">
      <div class="pl-view" style="--ar:${k.aspect(gid)}">${frame(gid, v)}</div>
      <div class="rail l">
        ${shoulder('L')}
        ${dpad()}
        <div class="tert">${menuBtn()}${pill('Select')}</div>
      </div>
      <div class="rail r">
        ${shoulder('R')}
        ${face()}
        <div class="tert">${pill('Start')}</div>
      </div>
      ${
        toast
          ? `<div class="toast" role="status"><span class="toast-ic">${i('check', 14)}</span><span>Automatic resume updated</span><span class="toast-sub">${sync('waiting', { icon: false })}</span></div>`
          : ''
      }
    </div>`;

  // ---------- screens ----------
  const screens = {};

  /* ---------------- Onboarding ---------------- */
  screens.welcome = (c) => {
    const ticker = D()
      .platforms.map((p) => `<span>${esc(p.short.toUpperCase())}</span><i></i>`)
      .join('');
    const s = (id, cls, chip) => `<figure class="wsticker ${cls}">${cover(id)}${chip || ''}</figure>`;
    return `
    <div class="page welcome">
      <div class="collage" aria-hidden="true">
        ${s('smetroid', 'w1')}
        ${s('chrono', 'w2', pchip(k.platform('snes'), 'on-cover'))}
        ${s('sonic2', 'w3', pchip(k.platform('genesis'), 'on-cover'))}
        ${s('kirbyadv', 'w4')}
        ${s('linksawak', 'w5', pchip(k.platform('gbc'), 'on-cover'))}
        <span class="fstk mint pix">Plays offline</span>
        <span class="fstk sky pix">Saves follow you</span>
      </div>
      <div class="ticker" aria-hidden="true"><div class="ticker-run">${ticker}${ticker}</div></div>
      <div class="wbody">
        <div class="brand lg">${logo(34)}<span>rommio</span></div>
        <h1>Your RomM library, ready to play.</h1>
        <p class="lede">Native, playable offline, and your saves follow you to every device.</p>
        <div class="stack">
          ${key('Connect to RomM', { cls: 'wide', trail: 'arrow-right' })}
          ${key('Scan setup code', { tone: 'ghost', icon: 'qr', cls: 'wide' })}
        </div>
        <p class="fine">Needs your own RomM server. Rommio doesn’t provide games.</p>
      </div>
    </div>`;
  };

  screens.connect = (c) => {
    const disc = D().server.discovered;
    return `
    <div class="page ob">
      ${steps(c, 1)}
      <div class="obhead">
        <h1>Where’s your RomM?</h1>
        <p>Enter the address you use to open RomM in a browser.</p>
      </div>
      <div class="field ok">
        <label for="mq-server">Server address</label>
        <div class="input">${i('globe', 20)}<input id="mq-server" value="romm.homelab.dev" readonly><span class="okdot" aria-label="Reachable">${i('check', 15)}</span></div>
        <p class="note ok" role="status">${i('shield', 15)}<span><b>RomM 5.2.0</b> · protected by Cloudflare Access</span></p>
      </div>
      ${key('Connect', { cls: 'wide', trail: 'arrow-right' })}
      ${sect('Saved and nearby', { cls: 'sm' })}
      <div class="card list">
        ${disc
          .map((s, n) =>
            row({
              icon: n ? 'wifi' : 'server',
              tone: n ? 'sky' : 'sun',
              title: s.name,
              sub: `${esc(s.note)}${s.secure ? '' : ' · <span class="warn">Not encrypted</span>'}`,
              trail: `${s.secure ? `<span class="lockic" aria-label="Encrypted">${i('lock', 16)}</span>` : ''}${chev()}`,
            }),
          )
          .join('')}
      </div>
      <div class="card access">
        <div class="access-h"><span class="ico grape">${i('key', 20)}</span><span class="row-t"><b>Access options</b><small>How Rommio reaches your server</small></span></div>
        <div class="opts" role="radiogroup" aria-label="Access options">
          ${['Automatic', 'Direct', 'Cloudflare Access', 'Service token']
            .map((o, n) => `<button class="opt${n ? '' : ' on'}" role="radio" aria-checked="${!n}">${n ? '' : i('check', 14)}${esc(o)}</button>`)
            .join('')}
        </div>
      </div>
    </div>`;
  };

  screens.signin = (c) => `
    <div class="page ob">
      ${steps(c, 2)}
      <div class="ticket" aria-label="Server verified">
        <div class="tk-main">
          <span class="pix tk-lbl">Server</span>
          <b>romm.homelab.dev</b>
          <span class="tk-meta"><span class="pix">RomM 5.2.0</span><span>Cloudflare Access</span></span>
        </div>
        <div class="tk-stub">${i('shield', 26)}<span class="pix">Access verified</span></div>
      </div>
      <div class="obhead">
        <h1>Sign in</h1>
        <p>Use the account you sign in to RomM with.</p>
      </div>
      <div class="field">
        <label for="mq-user">Username</label>
        <div class="input">${i('user', 20)}<input id="mq-user" value="ryan" readonly></div>
      </div>
      <div class="field focus">
        <label for="mq-pass">Password</label>
        <div class="input">${i('lock', 20)}<input id="mq-pass" type="password" value="correcthorse" readonly>${ibtn('eye', 'Show password', 'in')}</div>
      </div>
      ${key('Sign in', { cls: 'wide', trail: 'arrow-right' })}
      <div class="or"><span>or</span></div>
      ${key('Continue with single sign-on', { tone: 'ghost', icon: 'key', cls: 'wide' })}
      <p class="fine">Your password goes straight to your server. Rommio keeps only a session token.</p>
    </div>`;

  screens.hydrate = (c) => {
    const t = { platforms: 22, games: 2164 };
    const item = (label, done, extra = '') =>
      `<li class="chk${done ? ' done' : ''}"><span class="chk-ic">${done ? i('check', 16) : i('image', 16)}</span><b>${esc(label)}</b>${extra}</li>`;
    return `
    <div class="page ob hyd">
      ${steps(c, 3)}
      <div class="obhead">
        <h1>Stocking your shelves</h1>
        <p>Getting Home server ready so it works offline.</p>
      </div>
      <div class="scoreboard">
        <div class="sc sky"><b class="pix">${t.platforms}</b><span>Platforms</span></div>
        <div class="sc sun"><b class="pix">${k.num(t.games)}</b><span>Games</span></div>
      </div>
      <ul class="card checks">
        ${item('Platforms', true, '<span class="pix ok">Done</span>')}
        ${item('Games', true, '<span class="pix ok">Done</span>')}
        ${item('Collections', true, '<span class="pix ok">Done</span>')}
        <li class="chk live"><span class="chk-ic">${i('image', 16)}</span><b>Artwork</b><span class="pix pct">61%</span>${pbar(0.61, { segs: 24, tone: 'sun', live: true })}</li>
      </ul>
      <div class="card follow">
        <div class="devices" aria-hidden="true">
          <span class="dv">${i('smartphone', 20)}</span><i class="dash"></i><span class="dv hub">${logo(22)}</span><i class="dash"></i><span class="dv">${i('tablet', 20)}</span>
        </div>
        <p><b>Your saves follow you.</b> Game saves and resume states sync between devices through RomM.</p>
      </div>
      <div class="card list tight">
        ${row({ icon: 'image', tone: 'grape', title: 'Keep artwork offline', trail: sw(true, 'Keep artwork offline') })}
        ${row({ icon: 'signal', tone: 'sky', title: 'Sync on cellular', trail: sw(false, 'Sync on cellular') })}
      </div>
      <div class="dock">
        ${key('Start playing', { icon: 'play', cls: 'wide' })}
        <p class="fine">Artwork keeps caching in the background.</p>
      </div>
    </div>`;
  };

  /* ---------------- Library ---------------- */
  screens.home = (c) => {
    const P = D().progress;
    const cont = ['smetroid', 'linksawak', 'chrono'].map(k.game);
    const card = (g, n) => {
      const p = k.platformOf(g);
      const st = g.id === P.game ? P.resume.remote : g.sync;
      return `
      <article class="cc" style="${pvars(p)}">
        <div class="cc-shot">${frame(g.id, g.id === P.game ? P.resume.frame : 'a')}
          ${pchip(p, 'tilt lg')}
        </div>
        <div class="cc-body">
          <h3>${esc(g.title)}</h3>
          <p class="cc-meta">${i('clock', 14)}<span>${esc(k.ago(g.played))}</span>${sync(st)}</p>
          <div class="cc-foot">
            <div class="score"><span class="pix lbl">Playtime</span><b class="pix">${score(g.hours)}</b></div>
            ${key('Resume', { icon: 'play' })}
          </div>
        </div>
      </article>`;
    };
    const mv = k.collection('metroidvania');
    return `
    <div class="page home">
      <header class="hdr">
        <div class="brand">${logo(30)}<span>rommio</span></div>
        <div class="hdr-r">${ibtn('search', 'Search', 'ghost')}<button class="avatar" aria-label="Account · ryan">R</button></div>
      </header>
      ${sect('Continue playing', { count: '3', cls: 'big' })}
      <div class="car">${cont.map(card).join('')}</div>
      <div class="dots" aria-hidden="true"><i class="on"></i><i></i><i></i></div>
      <div class="hstat">
        <span><b class="pix">91</b> installed</span><span class="sep"></span><span><b class="pix">3</b> in queue</span>
        <button class="stk coral solid btn">${i('alert', 14)}<span>1 conflict needs you</span>${i('chevron-right', 14)}</button>
      </div>
      <article class="hl">
        <div class="hl-t">
          <span class="pix eyebrow">Collection</span>
          <h3>${esc(mv.name)}</h3>
          <p>${esc(mv.note)}</p>
          <span class="pix hl-n">${mv.games.length} games</span>
        </div>
        ${pile(mv.games)}
      </article>
      ${sect('Recently added', { more: 'See all' })}
      <div class="shelf">${k
        .collectionGames('recent')
        .map(
          (g, n) =>
            `<figure class="tile">${cover(g.id)}${n === 0 ? '<span class="new pix">New</span>' : ''}<figcaption>${esc(g.title)}</figcaption></figure>`,
        )
        .join('')}</div>
    </div>
    ${tabs('home')}`;
  };

  const ptile = (p, w, cls = '') => {
    const short = p.short.toUpperCase();
    return `<button class="pt ${cls}" style="${pvars(p)}" aria-label="${esc(p.name)}, ${p.games} games, ${p.installed} installed">
      ${sil(p.id)}
      <span class="pt-short" style="font-size:${fit(short, w, 34)}px">${esc(short)}</span>
      <span class="pt-name">${esc(p.name)}</span>
      <span class="pt-foot"><span class="pix">${p.games}</span>${
        p.installed ? `<span class="pt-inst">${i('check', 12)}<span class="pix">${p.installed}</span></span>` : ''
      }</span>
    </button>`;
  };

  screens.library = (c) => {
    const touch = k.tier('touch');
    const ctrl = k.tier('controller');
    const un = k.tier('unsupported');
    return `
    <div class="page lib">
      <header class="hdr title">
        <div><h1>Library</h1><p class="pix sub">22 platforms · 2,164 games</p></div>
        ${ibtn('sort', 'Sort platforms', 'ghost')}
      </header>
      <label class="search" for="mq-search">${i('search', 20)}<input id="mq-search" placeholder="Search 2,164 games" readonly></label>
      <div class="tierh">${tier('touch', true)}<span>${touch.length} platforms · on-screen controls</span></div>
      <div class="ptgrid">${touch.map((p) => ptile(p, 132)).join('')}</div>
      <div class="tierh">${tier('controller', true)}<span>Pair a controller to play</span></div>
      <div class="ptrow">${ctrl.map((p) => ptile(p, 84, 'sm')).join('')}</div>
      <div class="tierh">${tier('unsupported', true)}<span>Visible, not playable here</span></div>
      <div class="card list browse">
        ${un
          .map((p) =>
            row({
              lead: `<span class="pmini" style="${pvars(p)}">${sil(p.id)}</span>`,
              title: p.name,
              sub: `${esc(p.blocked)}`,
              trail: `<span class="pix n">${p.games}</span>${chev()}`,
            }),
          )
          .join('')}
      </div>
    </div>
    ${tabs('library')}`;
  };

  screens.platform = (c) => {
    const p = k.platform('snes');
    const games = k.gamesOn('snes').sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));
    const fav = games.filter((g) => g.favorite).length;
    return `
    <div class="page plat" style="${pvars(p)}">
      <div class="plat-hero">
        ${sil('snes', 'plat-sil')}
        <div class="plat-top">${back(c, 'Library', 'onslab')}${ibtn('search', 'Search Super Nintendo', 'onslab')}</div>
        <div class="plat-copy">
          <h1><span class="plat-big">SNES</span><span class="plat-name">${esc(p.name)} · ${esc(p.maker)} · ${p.year}</span></h1>
          <div class="plat-stats">
            <span><b class="pix">${p.games}</b>games</span>
            <span><b class="pix">${p.installed}</b>installed</span>
            <span class="core">${i('cpu', 14)}${esc(p.core)}</span>
          </div>
        </div>
      </div>
      <div class="filters">
        <div class="chips" role="tablist" aria-label="Filter">
          <button class="chip on" role="tab" aria-selected="true">All <span class="pix">${p.games}</span></button>
          <button class="chip" role="tab" aria-selected="false">Installed <span class="pix">${p.installed}</span></button>
          <button class="chip" role="tab" aria-selected="false">${i('heart', 14)}<span class="sr">Favourites</span><span class="pix">${fav}</span></button>
        </div>
        <button class="sortbtn">${i('sort', 16)}Recently played</button>
      </div>
      <div class="ggrid">${games
        .map(
          (g) => `<figure class="gt${g.installed ? '' : ' remote'}">
            <span class="gt-art">${cover(g.id)}${
              g.installed
                ? `<span class="gt-b ok" aria-label="Installed">${i('check', 14)}</span>`
                : `<span class="gt-b dl" aria-label="Not downloaded">${i('download', 14)}</span>`
            }</span>
            <figcaption>${esc(g.title)}</figcaption>
          </figure>`,
        )
        .join('')}</div>
    </div>
    ${tabs('library')}`;
  };

  screens.collections = (c) => {
    const cols = D().collections;
    const mine = cols.filter((x) => x.kind === 'manual');
    const smart = cols.filter((x) => x.kind === 'smart');
    const auto = cols.filter((x) => x.kind === 'auto');
    const tones = ['sun', 'coral', 'mint', 'sky'];
    return `
    <div class="page cols">
      <header class="hdr title">
        <div><h1>Collections</h1></div>
        ${ibtn('plus', 'New collection', 'ghost')}
      </header>
      ${sect('My collections', { count: mine.length, cls: 'sm' })}
      <div class="cgrid">${mine
        .map(
          (x, n) => `<article class="cc2 ${tones[n]}">
          ${pile(x.games)}
          <h3>${esc(x.name)}</h3>
          <span class="pix">${x.games.length} games</span>
        </article>`,
        )
        .join('')}</div>
      ${sect('Smart', { count: smart.length, cls: 'sm' })}
      <div class="card list smart">${smart
        .map((x) =>
          row({
            lead: `<span class="strip">${x.games
              .slice(0, 3)
              .map((id) => `<img src="${k.cover(id, 'box')}" alt="">`)
              .join('')}</span>`,
            title: x.name,
            sub: `<span class="rule">${i('filter', 12)}${esc(x.rule)}</span>`,
            trail: `<span class="pix n">${x.games.length}</span>`,
          }),
        )
        .join('')}</div>
      ${sect('Autogenerated', { count: auto.length, cls: 'sm' })}
      <div class="autos">${auto
        .map(
          (x) => `<article class="auto">
          <span class="auto-art">${x.games
            .slice(0, 2)
            .map((id) => `<img src="${k.cover(id, 'box')}" alt="">`)
            .join('')}</span>
          <span class="row-t"><b>${esc(x.name)}</b><small>${esc(x.rule)}</small></span><span class="pix">${x.games.length}</span>
        </article>`,
        )
        .join('')}</div>
    </div>
    ${tabs('collections')}`;
  };

  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const P = D().progress;
    return `
    <div class="page game" style="${pvars(p)};${gvars(g)}">
      <div class="g-hero"><img class="px" src="${k.cover(g.id, 'wide')}" alt=""></div>
      <div class="g-top">${back(c, 'Super Nintendo', 'onimg')}<span class="g-top-r">${ibtn('heart', 'Favourite', 'onimg fav')}${ibtn('more', 'More', 'onimg')}</span></div>
      <div class="g-id">
        <figure class="sticker">${cover(g.id)}<span class="rating pix" aria-label="Rating ${g.rating}">${g.rating}</span></figure>
        <div class="g-copy">
          ${pchip(p)}
          <h1>${esc(g.title)}</h1>
          <p>${g.year} · ${esc(g.publisher)}<br>${esc(g.genre)}</p>
        </div>
      </div>
      <section class="card resume">
        <div class="res-top">
          <span class="res-shot">${frame(g.id, P.resume.frame)}</span>
          <div class="res-t">
            <b>Automatic resume</b>
            <small>${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}</small>
            <span class="score sm"><span class="pix lbl">Playtime</span><b class="pix">${score(g.hours)}</b></span>
          </div>
        </div>
        ${key('Resume', { icon: 'play', cls: 'wide xl' })}
        ${key('Start with game save', { tone: 'ghost', icon: 'save', cls: 'wide' })}
      </section>
      <div class="sh sm"><h2>Progress</h2><button class="sh-more">${i('sync', 15)}Sync now</button></div>
      <div class="card list">
        ${row({ icon: 'save', tone: 'mint', title: 'Game-save data', sub: `${esc(k.ago(P.gameSave.at))} · ${esc(P.gameSave.device)}`, trail: sync(P.gameSave.remote) })}
        ${row({ icon: 'history', tone: 'sun', title: 'Automatic resume', sub: `${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}`, trail: sync(P.resume.remote) })}
        ${row({ icon: 'layers', tone: 'sky', title: 'Save states', sub: 'Slots 1–3 · 1 free', trail: `<span class="pix big">3</span>${chev()}` })}
      </div>
      <div class="sh sm"><h2>On this iPhone</h2></div>
      <div class="card file">
        <div class="file-r">${i('cartridge', 20)}<span class="row-t"><b>Super Metroid (USA).sfc</b><small>${esc(k.size(g.size))} · Installed</small></span></div>
        <div class="file-tags">${tier('touch')}<span class="stk mute">${i('cpu', 14)}<span>${esc(p.core)} ready</span></span></div>
      </div>
      <p class="blurb">${esc(g.blurb)}</p>
      <div class="g-sec">
        ${key('Remove download', { tone: 'ghost', icon: 'trash', cls: 'wide sm' })}
        <p class="fine">Progress is kept on your server.</p>
      </div>
    </div>`;
  };

  screens.downloads = (c) => {
    const dl = D().downloads;
    const run = dl.find((d) => d.state === 'running');
    const rg = k.game(run.game);
    const queued = dl.filter((d) => d.state === 'queued' || d.state === 'waiting');
    const failed = dl.find((d) => d.state === 'failed');
    const done = dl.filter((d) => d.state === 'done');
    const st = D().storage;
    const thumb = (id) => `<img class="thumb" src="${k.cover(id, 'box')}" alt="">`;
    return `
    <div class="page dls">
      <header class="hdr title">
        <div><h1>Downloads</h1><p class="pix sub">1 active · 3 waiting · 1 failed</p></div>
        ${ibtn('pause', 'Pause all', 'ghost')}
      </header>
      <div class="store">
        <span class="store-t">ROMs use <b>${gb(st.roms)}</b> on this iPhone</span>
        <span class="store-n">${gb(st.total - st.roms)} free</span>
        ${pbar(st.roms / st.total, { segs: 32, tone: 'grape' })}
      </div>
      <article class="card active">
        <div class="act-top">
          ${thumb(rg.id)}
          <div class="act-t">
            <span class="pix live">Downloading</span>
            <b>${esc(rg.title)}</b>
            <small>${esc(k.platformOf(rg).short)} · ${esc(k.size(run.total))}</small>
          </div>
          <span class="act-pct pix">${k.pct(run.progress)}</span>
        </div>
        ${pbar(run.progress, { segs: 24, tone: 'mint', live: true, cls: 'xl' })}
        <div class="act-foot">
          <span class="row-t"><b>${esc(k.size(run.done))} of ${esc(k.size(run.total))}</b><small>${run.rate} MB/s · <span class="eta">${esc(run.eta)} left</span></small></span>
          <span class="act-btns">${ibtn('pause', 'Pause', 'sm')}${ibtn('x', 'Cancel', 'sm')}</span>
        </div>
      </article>
      ${sect('Needs you', { count: 1, cls: 'sm' })}
      <div class="card fail">
        ${row({
          lead: thumb(failed.game),
          title: k.game(failed.game).title,
          sub: `<span class="err">${i('alert-circle', 13)}${esc(failed.reason)}</span>`,
          trail: key('Retry', { tone: 'coral', icon: 'restart', cls: 'sm' }),
        })}
        ${pbar(failed.progress, { segs: 24, tone: 'coral', cls: 'thin' })}
      </div>
      ${sect('Up next', { count: queued.length, cls: 'sm' })}
      <div class="card list q">
        ${queued
          .map((d, n) => {
            const g = k.game(d.game);
            return row({
              lead: `<span class="qn pix">${n + 1}</span>${thumb(g.id)}`,
              title: g.title,
              sub: `${esc(k.platformOf(g).short)} · ${esc(k.size(d.total))}`,
              trail: d.state === 'waiting' ? `<span class="stk sun">${i('wifi-off', 14)}<span>Waiting for Wi-Fi</span></span>` : `<span class="grip">${i('grip', 18)}</span>`,
            });
          })
          .join('')}
      </div>
      ${sect('Completed recently', { cls: 'sm' })}
      <div class="card list done">
        ${done
          .map((d) => {
            const g = k.game(d.game);
            return row({ lead: thumb(g.id), title: g.title, sub: `${esc(k.platformOf(g).short)} · ${esc(k.size(d.total))} · ${esc(k.ago(d.at))}`, trail: `<span class="okc">${i('check', 16)}</span>` });
          })
          .join('')}
      </div>
    </div>
    ${tabs('downloads')}`;
  };

  /* ---------------- Settings ---------------- */
  screens.settings = (c) => {
    const [home, sam] = D().profiles;
    const st = D().storage;
    const used = st.roms + st.saves + st.states + st.cache + st.cores + st.bios;
    const parts = [
      ['ROMs', st.roms, 'coral'],
      ['Saves', st.saves, 'mint'],
      ['States', st.states, 'sky'],
      ['Cache', st.cache, 'grape'],
      ['Cores', st.cores + st.bios, 'sun'],
    ];
    return `
    <div class="page set">
      <header class="hdr title"><div><h1>Settings</h1></div></header>
      <section class="acct">
        <div class="acct-top">
          <span class="avatar xl" aria-hidden="true">R</span>
          <div class="row-t"><b>${esc(home.name)}</b><small>${esc(home.user)} · ${esc(home.url)}</small></div>
          ${ibtn('more', 'Account options', 'onslab')}
        </div>
        <div class="acct-tags">
          <span class="stk mint solid"><i class="live-dot"></i><span>Online</span></span>
          ${sync('synced', { label: `Synced ${k.ago(home.lastSync)}` })}
          <span class="stk mute"><span class="pix">${esc(home.version)}</span></span>
        </div>
      </section>
      <p class="glabel pix">Profiles</p>
      <div class="card list">
        ${row({ icon: 'users', tone: 'grape', title: sam.name, sub: `${esc(sam.user)} · ${esc(sam.url)}`, trail: `<span class="stk grape">${i('wifi-off', 14)}<span>Offline-ready</span></span>` })}
      </div>
      <p class="glabel pix">Sync</p>
      <div class="card list">
        ${row({ icon: 'save', tone: 'mint', title: 'Game saves', sub: 'Battery and memory-card data', trail: sw(true, 'Sync game saves') })}
        ${row({ icon: 'history', tone: 'sun', title: 'Resume states', sub: 'Pick up on any device', trail: sw(true, 'Sync resume states') })}
        ${row({ icon: 'signal', tone: 'sky', title: 'Use cellular data', trail: sw(false, 'Sync on cellular') })}
      </div>
      <p class="glabel pix">Play</p>
      <div class="card list">
        ${row({ icon: 'gamepad', tone: 'coral', title: 'Player & controls', sub: 'Touch layout, controllers, haptics', trail: chev() })}
        ${row({ icon: 'cpu', tone: 'sky', title: 'Cores & BIOS', sub: '<span class="stk sun mini">1 needs BIOS</span><span class="stk sky mini">1 needs setup</span>', trail: chev() })}
      </div>
      <p class="glabel pix">Storage</p>
      <div class="card storage">
        <div class="st-top"><b class="pix">${gb(used)}</b><span>used of ${gb(st.total)}</span></div>
        <div class="stbar">${parts.map(([n, v, t]) => `<i class="${t}" style="flex:${Math.max(v, 400)}"></i>`).join('')}<i class="free" style="flex:${st.total - used}"></i></div>
        <ul class="legend">${parts.map(([n, v, t]) => `<li><i class="${t}"></i>${n}<span class="pix">${gb(v)}</span></li>`).join('')}</ul>
      </div>
      <div class="card list">
        ${row({ icon: 'info', tone: 'grape', title: 'About Rommio', trail: chev() })}
      </div>
    </div>
    ${tabs('settings')}`;
  };

  screens.controls = (c) => {
    const C = D().controls;
    const slider = (label, val, pct, icon, tone) =>
      `<div class="row slide"><span class="ico ${tone}">${i(icon, 20)}</span><span class="row-t"><span class="sl-top"><b>${esc(label)}</b><span class="pix val">${esc(val)}</span></span>
        <span class="track" role="slider" aria-label="${esc(label)}" aria-valuetext="${esc(val)}" style="--v:${pct}"><i class="fill"></i><i class="knob"></i></span></span></div>`;
    return `
    <div class="page ctl">
      <header class="hdr sub">${back(c, 'Settings')}<h1>Controls</h1><span class="hdr-sp"></span></header>
      <div class="preview" aria-label="Live preview of the touch layout">
        <div class="preview-scale" style="--safe-left:62px;--safe-right:62px;--safe-bottom:21px;--scr-w:874px;--scr-h:402px">${playerLandscape(c)}</div>
        <span class="preview-tag pix">Live preview</span>
      </div>
      <div class="pad-banner">${i('gamepad', 20)}<span><b>${esc(C.connectedController)}</b> connected · touch controls hide while it’s in use</span></div>
      <p class="glabel pix">Touch controls</p>
      <div class="card list">
        ${row({ icon: 'hand', tone: 'sun', title: 'Show touch controls', trail: sw(C.showTouch, 'Show touch controls') })}
        ${row({ icon: 'gamepad', tone: 'sky', title: 'Hide when a controller connects', cls: 'wrap', trail: sw(C.autoHideOnController, 'Hide when a controller connects') })}
        ${row({ icon: 'flip', tone: 'grape', title: 'Left-handed layout', trail: sw(C.leftHanded, 'Left-handed layout') })}
      </div>
      <p class="glabel pix">Look</p>
      <div class="card list">
        ${slider('Opacity', k.pct(C.opacity), C.opacity, 'contrast', 'mint')}
        ${slider('Size', k.pct(C.size), 0.5, 'scaling', 'coral')}
        ${row({ icon: 'palette', tone: 'sun', title: 'Console colours', sub: 'Original face-button colours', trail: sw(true, 'Console colours') })}
        ${row({ icon: 'moon', tone: 'grape', title: 'OLED black', sub: 'Pure black around the game', trail: sw(C.oled, 'OLED black') })}
      </div>
      <p class="glabel pix">Feel</p>
      <div class="card list">
        ${slider('Stick deadzone', k.pct(C.deadzone), C.deadzone / 0.4, 'crosshair', 'sky')}
        ${row({ icon: 'vibrate', tone: 'coral', title: 'Haptics', trail: sw(C.haptics, 'Haptics') })}
        ${row({ icon: 'controller', tone: 'mint', title: 'Controller type', trail: `<span class="val-t">${esc(C.controllerType)}</span>${chev()}` })}
      </div>
    </div>`;
  };

  /* ---------------- Player ---------------- */
  screens.player = (c) => playerLandscape(c, { toast: true });

  screens['player-portrait'] = (c) => {
    const g = k.game('linksawak');
    return `
    <div class="pp" style="${pvars(k.platformOf(g))}">
      <div class="pp-view" style="--ar:${k.aspect(g.id)}">${frame(g.id, 'b')}</div>
      <div class="pp-band">
        <span class="pp-label"><span class="pix">${esc(g.title)}</span>${pchip(k.platformOf(g), 'sm')}</span>
        ${menuBtn()}
      </div>
      ${dpad('pp-dpad')}
      ${face('pp-face', 'gb')}
      <div class="pp-ss">${pill('Select', 'slant')}${pill('Start', 'slant')}</div>
    </div>`;
  };

  screens.pause = (c) => {
    const g = k.game('smetroid');
    const P = D().progress;
    const items = [
      ['play', 'Resume', 'on'],
      ['save', 'Save state', ''],
      ['history', 'Load state', ''],
      ['gamepad', 'Controls', ''],
      ['fast-forward', 'Fast-forward', 'tog'],
      ['restart', 'Reset', ''],
      ['exit', 'Leave game', 'danger'],
    ];
    return `
    <div class="pause">
      <div class="pz-left">
        <figure class="freeze">${frame(g.id, P.resume.frame)}<span class="pix paused">Paused</span></figure>
        <div class="pz-id">
          <h1>${esc(g.title)}</h1>
          <p>${pchip(k.platformOf(g), 'sm')}<span class="pix">Session ${g.played}M</span><span class="pix dim">Total ${score(g.hours)}</span></p>
          <p class="pz-status" role="status">${sync('synced', { label: 'Game-save data synced' })}${sync('waiting', { label: 'Resume waiting to sync' })}</p>
        </div>
      </div>
      <nav class="pz-menu" aria-label="Pause menu">
        <ul>${items
          .map(
            ([ic, label, cls]) => `<li><button class="pz-item ${cls}"${cls === 'on' ? ' aria-current="true"' : ''}${cls === 'tog' ? ' role="switch" aria-checked="false"' : ''}>${
              cls === 'on' ? '<span class="cursor" aria-hidden="true"></span>' : ''
            }${i(ic, 18)}<span>${label}</span>${cls === 'tog' ? `<span class="pix x2">2x</span><span class="sw" aria-hidden="true"><i></i></span>` : ''}</button></li>`,
          )
          .join('')}</ul>
      </nav>
    </div>`;
  };

  screens.states = (c) => {
    const g = k.game('smetroid');
    const P = D().progress;
    return `
    <div class="states">
      <img class="st-bg px" src="${k.frame(g.id, P.resume.frame)}" alt="">
      <header class="st-h">
        ${ibtn('x', 'Close', 'ghost')}
        <div class="row-t"><b>Progress</b><small>${esc(g.title)} · SNES</small></div>
        <span class="st-h-r">${key('Game-save history', { tone: 'ghost', icon: 'save', cls: 'sm' })}</span>
      </header>
      <div class="st-cols">
        <div class="st-special">
          <article class="mc special sun">
            <span class="mc-shot">${frame(g.id, P.resume.frame)}<span class="mc-n pix">Auto</span></span>
            <b>Automatic resume</b>
            <small>${esc(k.ago(P.resume.at))} · ${esc(P.resume.device)}</small>
            <span class="mc-foot">${sync(P.resume.remote)}${key('', { icon: 'play', cls: 'sm', aria: 'Resume' })}</span>
          </article>
          <article class="mc gs">
            <span class="gs-top"><span class="ico mint">${i('save', 18)}</span><span class="row-t"><b>Game-save data</b><small>${esc(P.gameSave.detail)}</small></span></span>
            <span class="gs-foot"><small>${esc(k.agoShort(P.gameSave.at))} ago · ${esc(P.gameSave.device)}</small>${sync(P.gameSave.remote)}</span>
          </article>
        </div>
        <div class="slots">${P.slots
          .map((s) =>
            s.empty
              ? `<button class="mc slot empty"><span class="mc-n pix">0${s.n}</span><span class="plus">${i('plus', 22)}</span><b>Save state here</b><small>Empty slot</small></button>`
              : `<article class="mc slot">
                <span class="mc-shot">${frame(g.id, s.frame)}<span class="mc-n pix">0${s.n}</span></span>
                <b>${esc(s.name)}</b>
                <small>${esc(s.device)} · ${esc(k.agoShort(s.at))} ago</small>
                ${sync(s.remote)}
              </article>`,
          )
          .join('')}</div>
        <aside class="hist">
          <p class="glabel pix">Recovery history</p>
          <ol>${P.history
            .map(
              (h) =>
                `<li><i class="tl"></i><span class="row-t"><b>${esc(h.kind)}</b><small>${esc(h.device)} · ${esc(k.agoShort(h.at))} · ${esc(h.reason)}</small></span></li>`,
            )
            .join('')}</ol>
          <button class="hist-all">${i('history', 16)}State history</button>
        </aside>
      </div>
    </div>`;
  };

  screens.conflict = (c) => {
    const C = D().conflict;
    const g = k.game(C.game);
    const card = (b, on) => `
      <button class="br${on ? ' on' : ''}" role="radio" aria-checked="${on}">
        <span class="br-shot">${frame(g.id, b.frame)}</span>
        <span class="br-t">
          <span class="br-dev">${i('smartphone', 16)}<b>${esc(b.device)}</b></span>
          <small>${esc(k.ago(b.at))} · ${esc(b.reason)}</small>
          <span class="br-where">${i('pin', 14)}${esc(b.where)}</span>
          <span class="br-score"><span class="pix lbl">Playtime</span><b class="pix">${esc(b.playtime.replace(/ h /, 'H ').replace(/ m$/, 'M'))}</b></span>
        </span>
        <span class="radio" aria-hidden="true">${on ? i('check', 16) : ''}</span>
      </button>`;
    return `
    <div class="page conf">
      <header class="hdr sub">${ibtn('x', 'Cancel', 'ghost')}<span class="stk coral solid">${i('alert', 14)}<span>Conflict</span></span><span class="hdr-sp"></span></header>
      <div class="conf-head">
        <p class="pix eyebrow">${esc(g.title)} · ${esc(C.kind)}</p>
        <h1>Played in two places. Which one continues?</h1>
        <p>Both devices moved EarthBound forward since the last sync. Pick one to continue; the other is kept as a recovery copy.</p>
      </div>
      <div class="brs" role="radiogroup" aria-label="Choose which resume to continue">
        ${card(C.branches[0], true)}
        <span class="vs" aria-hidden="true">VS</span>
        ${card(C.branches[1], false)}
      </div>
      <div class="card list tight">
        ${row({ icon: 'archive', tone: 'grape', title: 'Keep the other as a recovery copy', cls: 'wrap', trail: sw(true, 'Keep the other as a recovery copy') })}
      </div>
      <div class="dock">
        ${key('Continue from This iPhone', { icon: 'play', cls: 'wide' })}
        ${key('Cancel', { tone: 'ghost', cls: 'wide' })}
      </div>
    </div>`;
  };

  screens.dual = (c) => {
    const g = k.game('pkblack');
    const d = k.dual(g.id, 'a');
    return `
    <div class="dual">
      <div class="ds">
        <img class="px ds-top" src="${d.top}" alt="">
        <div class="ds-bot"><img class="px" src="${d.bottom}" alt=""><span class="touch-tag">${i('pointer', 13)}<span class="pix">Touch</span></span></div>
      </div>
      <div class="ds-band">
        ${shoulder('L')}
        ${menuBtn('ds-menu')}
        ${shoulder('R')}
        ${dpad('ds-dpad')}
        ${face('ds-face')}
        <div class="ds-ss">${pill('Select')}${pill('Start')}</div>
      </div>
    </div>`;
  };

  Studio.defineVersion({
    id: 'marquee1',
    round: 1,
    name: 'Marquee',
    tagline: 'Loud, joyful, arcade-bright.',
    concept:
      'Walking into an arcade, or unwrapping a console. Saturated colour slabs on a night ground, chunky wide type, keys with a solid lip you want to press, and stickers where they add delight. Every platform wears its own colour; playtime reads like a high score. Loud, never busy: one candy per section, one tilt per screen.',
    swatch: ['#120F24', '#FFC83D', '#FF5A4E'],
    fonts: ['Unbounded:wght@500..900', 'Outfit:wght@400..800', 'Press+Start+2P'],
    tone: 'light',
    palette: [
      { name: 'Night', hex: '#120F24' },
      { name: 'Slab', hex: '#1E1A3A' },
      { name: 'Ink', hex: '#FFF8EC' },
      { name: 'Sun', hex: '#FFC83D' },
      { name: 'Coral', hex: '#FF5A4E' },
      { name: 'Mint', hex: '#3DDC97' },
      { name: 'Sky', hex: '#43C6F7' },
      { name: 'Grape', hex: '#8C6CFF' },
    ],
    type: [
      { role: 'Display & keys', family: 'Unbounded', css: "'Unbounded', sans-serif", sample: 'Super Metroid' },
      { role: 'Interface', family: 'Outfit', css: "'Outfit', sans-serif", sample: 'Automatic resume · 1 min ago' },
      { role: 'Scores & counters', family: 'Press Start 2P', css: "'Press Start 2P', monospace", sample: '11H 24M  62%' },
    ],
    tweaks: [
      { var: '--accent', label: 'Go-key colour', type: 'color', value: '#ffc83d' },
      { var: '--ow', label: 'Outline weight', type: 'range', min: 0, max: 4, step: 0.5, value: 2, unit: 'px' },
      { var: '--drop', label: 'Hard shadow depth', type: 'range', min: 0, max: 8, step: 1, value: 4, unit: 'px' },
      { var: '--r', label: 'Card radius', type: 'range', min: 12, max: 32, step: 1, value: 24, unit: 'px' },
      { var: '--tilt', label: 'Sticker tilt', type: 'range', min: 0, max: 10, step: 1, value: 4, unit: 'deg' },
      { var: '--ctl-alpha', label: 'Touch control opacity', type: 'range', min: 30, max: 100, step: 1, value: 72, unit: '%' },
    ],
    notes: {
      home: 'Continue is a marquee of big cards: the resume frame is the hero, playtime reads as a score, and the lip under each card is the platform colour. The conflict gets a coral sticker in the status line so it can’t be missed.',
      library: 'Platforms are colour slabs with their short name set huge, plus a console silhouette. Touch scrolls sideways in two rows so all three tiers stay visible at once; Browse only explains why.',
      game: 'The cover is a die-cut sticker with a pixel rating stuck to it. Resume leads with the actual resume frame and its sync state; Start with game save is the honest alternative.',
      controls: 'The preview is the real in-game layout scaled down, so every tweak here is exactly what you get. Marquee ships console colours on — it is the direction’s signature.',
      player: 'SNES face buttons in their four classic colours: tinted glass with a solid ring, at the tuned opacity, so the legend stays readable. Menu, Select and Start sit low and small: tertiary by design.',
      pause: 'The frame freezes into a tilted sticker so the game stays present. A blinking arcade cursor marks the selection; the status line tells the truth about both kinds of progress.',
      states: 'Memory cards: Automatic resume and Game-save data stand apart from the numbered slots; recovery history sits alongside so nothing feels lost.',
      conflict: 'A versus screen. Both branches show where you were, when, and your playtime as a score; the other is kept as a recovery copy by default.',
    },
    screens,
  });
})();
