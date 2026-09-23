/*
 * INSTRUMENT — precision hardware. Every control a key.
 *
 * DESIGN PLAN
 * -----------
 * Palette   Chassis  #E6E4DF  app ground (warm light grey)
 *           Panel    #F3F2EE  raised modules, keycaps
 *           Well     #DAD8D1  recessed fields and selector beds
 *           Ink      #111111  text, lit keys          Ink 2 #55534E  secondary text (6:1 on chassis)
 *           Rule     ink @ 13% / 22%  hairlines        Shade #BCB9B1  2px key bottom shade
 *           Graphite #2A2A2A  player body              Bezel #101010  recessed screens
 *           Signal   #FF5A1F  the one accent: primary key, lit selection, transfer meter
 *           LEDs     green #1FCF6B Synced · amber #FFB020 Waiting to sync · blue #3D7BFF Syncing (pulses)
 *                    red #FF3B30 Conflict · grey #A8A59D Local only / idle
 * Type      Archivo (variable, wdth 62–125)
 *             Display  wdth 125, 700–800, tight tracking — screen titles, wordmark, game titles
 *             UI       wdth 100–108, 500–600 — rows, key legends (sentence case)
 *             Dense    wdth 75–85, 600 — titles under covers and in pads
 *           JetBrains Mono
 *             Labels   10–11px caps, +0.08em — everything printed on the faceplate
 *             Readouts 16–52px tabular numerals with small caps units
 *           Rule of thumb: words you press are sentence case; words you read off the instrument are mono caps.
 * Grid      4px base; spacing 4 · 8 · 12 · 16 · 24 · 32. 16px side margin, 12px module gutter.
 *           Radii: key 10 (tweakable) · panel = key + 4 · chip 4 · LEDs round. Hairlines 1px, key shade 2px.
 *           A faint 16px faceplate grid sits on onboarding chassis only (tweakable).
 * Moves     Faceplate modules with printed mono labels · readouts (big mono numeral + tiny unit) ·
 *           segmented bar meters · LED sync indicators with spec wording · flat keycaps with a 2px shade ·
 *           a recessed screen with a labelled bezel wherever gameplay appears · the library as a rack of
 *           platform pads · onboarding as a three-position mode switch.
 * Nav       A bottom key strip — Home, Library, Collections, Downloads, Settings. The current key is lit ink;
 *           a key carries an LED when its section is busy (Downloads transferring). Pushed pages use a back
 *           key and a mono path label; the game page docks its Resume key in a bottom tray.
 * Player    Graphite device body. The game sits in a recessed screen whose bezel strip is printed with the
 *           platform and core (SNES · SNES9X). Dark flat keycaps on the side rails at tuned opacity: a cross
 *           d-pad, round face keys (A is the single orange key), L/R shoulder keys above the rails, pill keys
 *           for Select/Start and a round Menu key near the lower edge. Menus on the body use light keys —
 *           white keys on a black instrument — so pause and progress read as a control panel beside the game.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const D = () => window.StudioData;

  // ------------------------------------------------------------------ helpers
  const LED = { synced: 'ok', pending: 'wait', waiting: 'wait', syncing: 'live', conflict: 'bad', local: 'off', signin: 'wait', none: 'off' };
  const led = (state, cls = '') => `<span class="led led-${LED[state] || state} ${cls}" aria-hidden="true"></span>`;
  const sync = (state, label) => `<span class="sync">${led(state)}<span>${esc(label || k.syncLabel(state))}</span></span>`;
  const lbl = (t, cls = '') => `<span class="lbl ${cls}">${t}</span>`;
  // Storage in decimal GB, the way phones report it (18.4 GB, 64 GB).
  const gb = (mb) => (mb >= 1000 ? `${(mb / 1000).toFixed(1).replace(/\.0$/, '')} GB` : k.size(mb));
  const upper = (s) => esc(String(s).toUpperCase());
  const T = () => k.totals();
  const back = (c) => (c.os === 'android' ? 'arrow-left' : 'chevron-left');
  const phone = (c) => (c.os === 'android' ? 'phone' : 'iPhone');

  // Section rule: printed label, hairline, optional right readout.
  const rule = (title, right = '') =>
    `<div class="rule">${lbl(esc(title))}<span class="rule-line"></span>${right ? `<span class="lbl rule-r">${right}</span>` : ''}</div>`;

  function key(label, o = {}) {
    const cls = ['key', `key-${o.kind || 'plain'}`, o.size || '', o.block ? 'block' : '', o.iconRight && o.block ? 'split' : '', o.cls || ''].join(' ');
    return `<button class="${cls}"${o.aria ? ` aria-label="${esc(o.aria)}"` : ''}>${o.icon ? i(o.icon, { size: o.iconSize || 18, cls: o.icon === 'play' ? 'fill' : '' }) : ''}${
      label ? `<span class="key-l">${esc(label)}</span>` : ''
    }${o.sub ? `<span class="key-sub">${o.sub}</span>` : ''}${o.iconRight ? i(o.iconRight, 18) : ''}</button>`;
  }
  const ikey = (icon, aria, cls = '') => `<button class="ikey ${cls}" aria-label="${esc(aria)}">${i(icon, 20)}</button>`;

  const sw = (on, label) =>
    `<button class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}"><i></i></button>`;
  const toggleRow = (title, sub, on) =>
    `<div class="trow"><div class="trow-m"><b>${esc(title)}</b>${sub ? `<span>${sub}</span>` : ''}</div>${sw(on, title)}</div>`;

  function seg(opts, active, aria, cls = '') {
    return `<div class="seg ${cls}" role="radiogroup" aria-label="${esc(aria)}">${opts
      .map((o) => {
        const [v, extra] = Array.isArray(o) ? o : [o];
        const on = v === active;
        return `<button role="radio" aria-checked="${on}" class="${on ? 'on' : ''}">${esc(v)}${extra != null ? `<em>${esc(extra)}</em>` : ''}</button>`;
      })
      .join('')}</div>`;
  }

  // Segmented bar meter.
  function meter(frac, o = {}) {
    const n = o.n || 32;
    const on = Math.round(frac * n);
    return `<div class="meter ${o.cls || ''}" role="meter" aria-label="${esc(o.aria || 'Progress')}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(
      frac * 100,
    )}" style="--n:${n}">${k.range(n).map((j) => `<i${j < on ? ' class="on"' : ''}></i>`).join('')}</div>`;
  }

  const readout = (value, unit, label, cls = '') =>
    `<div class="ro ${cls}"><div class="ro-v">${value}${unit ? `<small>${unit}</small>` : ''}</div>${label ? lbl(esc(label)) : ''}</div>`;

  const TIER_ICON = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tier = (t) => `<span class="tier tier-${t}">${i(TIER_ICON[t], 12)}${upper(k.tierLabel(t))}</span>`;

  const cap = (color) => `<i class="cap" style="--c:${color}"></i>`;
  const cover = (id, kind = 'box', cls = '') => `<img class="cv ${cls}" src="${k.cover(id, kind)}" alt="">`;

  // Recessed screen with a printed bezel strip.
  function bezel(src, o = {}) {
    return `<div class="bz ${o.cls || ''}" style="${o.style || ''}">
      ${o.top ? `<div class="bz-strip">${o.top}</div>` : ''}
      <div class="bz-glass" style="${o.ar ? `aspect-ratio:${o.ar}` : ''}"><img class="px" src="${src}" alt="${esc(o.alt || '')}"></div>
      ${o.bottom ? `<div class="bz-strip bz-b">${o.bottom}</div>` : ''}
    </div>`;
  }

  const LOGO = `<svg class="logo" viewBox="0 0 32 32" aria-hidden="true"><rect x="1" y="1" width="30" height="30" rx="8" class="logo-body"/><path d="M8.5 16h8M12.5 12v8" class="logo-cross"/><circle cx="22.5" cy="16" r="3.2" class="logo-dot"/></svg>`;
  const wordmark = (cls = '') => `<span class="wm ${cls}">${LOGO}<span>rommio</span></span>`;

  const NAV = [
    ['home', 'home', 'Home'],
    ['library', 'library', 'Library'],
    ['collections', 'collections', 'Collections'],
    ['downloads', 'download', 'Downloads'],
    ['settings', 'settings', 'Settings'],
  ];
  function nav(active) {
    return `<nav class="nav" aria-label="Primary"><div class="nav-keys">${NAV.map(
      ([id, ic, label]) =>
        `<button class="nk ${id === active ? 'on' : ''}"${id === active ? ' aria-current="page"' : ''}>${i(ic, 20)}<span>${label}</span>${
          id === 'downloads' ? led('syncing', 'nk-led') : ''
        }</button>`,
    ).join('')}</div></nav>`;
  }

  function topbar(c, o = {}) {
    return `<header class="tb">
      <button class="ikey tb-back" aria-label="Back${o.backTo ? ' to ' + esc(o.backTo) : ''}">${i(o.close ? 'x' : back(c), 20)}</button>
      <span class="tb-path">${o.path || ''}</span>
      <span class="tb-r">${o.right || ''}</span>
    </header>`;
  }

  // Onboarding position switch: 01 Server · 02 Sign in · 03 Library.
  function steps(n) {
    const names = ['Server', 'Sign in', 'Library'];
    return `<ol class="steps" aria-label="Setup step ${n} of 3">${names
      .map((s, j) => `<li class="${j + 1 === n ? 'on' : j + 1 < n ? 'done' : ''}"><em>0${j + 1}</em>${upper(s)}</li>`)
      .join('')}</ol>`;
  }

  // ------------------------------------------------------------ player keys
  const DPAD_PATH =
    'M40 6a6 6 0 0 1 6-6h28a6 6 0 0 1 6 6v34h34a6 6 0 0 1 6 6v28a6 6 0 0 1-6 6H80v34a6 6 0 0 1-6 6H46a6 6 0 0 1-6-6V80H6a6 6 0 0 1-6-6V46a6 6 0 0 1 6-6h34z';
  function dpad(cls = '') {
    return `<div class="dpad ${cls}" role="group" aria-label="Directional pad">
      <svg viewBox="-1 -1 122 125" aria-hidden="true">
        <path d="${DPAD_PATH}" class="dp-shade" transform="translate(0 2.5)"/>
        <path d="${DPAD_PATH}" class="dp-face"/>
        <path d="M60 11l-7 9h14zM60 109l-7-9h14zM11 60l9-7v14zM109 60l-9-7v14z" class="dp-arrow"/>
        <circle cx="60" cy="60" r="9" class="dp-dimple"/>
      </svg>
      <button class="dp-hit u" aria-label="Up"></button><button class="dp-hit d" aria-label="Down"></button>
      <button class="dp-hit l" aria-label="Left"></button><button class="dp-hit r" aria-label="Right"></button>
    </div>`;
  }
  // SNES / DS diamond: X top, Y left, A right, B bottom.
  const diamond = (cls = '') => `<div class="diamond ${cls}" role="group" aria-label="Face buttons">
      <button class="fk x" aria-label="X">X</button><button class="fk y" aria-label="Y">Y</button>
      <button class="fk a" aria-label="A">A</button><button class="fk b" aria-label="B">B</button>
    </div>`;
  const pill = (t) => `<button class="pk" aria-label="${t}"><span>${upper(t)}</span></button>`;
  const menuKey = () => `<button class="mk" aria-label="Menu">${i('menu', 16)}</button>`;

  // The landscape player: body, bezel screen, rails. Reused by the controls preview.
  function landscape(c, o = {}) {
    const g = k.game(o.game || 'smetroid');
    const p = k.platformOf(g);
    return `<div class="pl ${o.cls || ''}" style="--ar:${k.aspect(g.id)}">
      <div class="pl-screen">
        <div class="bz-strip"><span>${upper(p.short)} · ${upper(p.core)}</span>${o.strip ?? `<span>${upper(g.title)}</span>`}</div>
        <div class="pl-glass"><img class="px" src="${k.frame(g.id, o.variant || 'b')}" alt="${esc(g.title)} gameplay"></div>
        ${o.over || ''}
      </div>
      <div class="pl-ctl">
        <button class="sk sk-l" aria-label="L">L</button>
        <button class="sk sk-r" aria-label="R">R</button>
        <div class="rail rail-l">${dpad()}</div>
        <div class="rail rail-r">${diamond()}</div>
        <div class="tert tert-l">${menuKey()}${pill('Select')}</div>
        <div class="tert tert-r">${pill('Start')}</div>
      </div>
    </div>`;
  }

  // ------------------------------------------------------------------ screens
  const screens = {};

  // ---------- Onboarding ----------
  screens.welcome = (c) => {
    const carts = [
      ['pkcrystal', 'panel', -2],
      ['chrono', 'graphite', -1],
      ['smetroid', 'signal', 0],
      ['linksawak', 'panel', 1],
      ['sotn', 'graphite', 2],
    ];
    return `<div class="page grid-bg welcome">
      <header class="w-top">${wordmark('wm-lg')}<span class="lbl">Native client for RomM</span></header>
      <div class="rack" aria-hidden="true">
        ${carts
          .map(
            ([id, body, n]) => `<div class="cart cart-${body}" style="--n:${n}">
            <span class="cart-grip"></span>
            <span class="cart-label">${cover(id, 'square')}</span>
            <span class="cart-t">${upper(k.platformOf(id).short)} · ${k.game(id).year}</span>
          </div>`,
          )
          .join('')}
        <div class="rack-lip"></div>
      </div>
      <div class="welcome-body">
        <h1 class="w-h">Your RomM library, native.<br>Playable offline.<br>Saves that follow you.</h1>
        <div class="w-keys">
          ${key('Connect to RomM', { kind: 'primary', size: 'lg', block: true, iconRight: 'arrow-right' })}
          ${key('Scan setup code', { iconRight: 'qr', size: 'lg', block: true })}
        </div>
        <p class="fine">Rommio connects to a RomM server you run. It does not provide games.</p>
      </div>
    </div>`;
  };

  screens.connect = (c) => {
    const s = D().server.discovered;
    return `<div class="page grid-bg onb">
      ${topbar(c, { path: steps(1), backTo: 'Welcome' })}
      <div class="onb-body">
        <h1 class="h1">Connect to<br>your server</h1>
        <p class="lede">Enter the address of your RomM server.</p>
        <div class="field-grp">
          <label class="lbl" for="in-srv">Server address</label>
          <div class="field ok">
            ${i('lock', 16)}<input id="in-srv" class="mono-in" value="romm.homelab.dev" spellcheck="false">
            <button class="field-x" aria-label="Clear address">${i('x', 16)}</button>
          </div>
          <div class="check">${led('synced')}<span><b>RomM 5.2.0</b> · protected by Cloudflare Access</span></div>
        </div>
        <div class="pn access">
          <div class="pn-h">${lbl('Access options')}<span class="lbl">Tries each in turn</span></div>
          <div class="sel4" role="radiogroup" aria-label="Access options">
            ${['Automatic', 'Direct', 'Cloudflare Access', 'Service token']
              .map((o, j) => `<button role="radio" aria-checked="${j === 0}" class="${j === 0 ? 'on' : ''}"><i class="sel-led"></i>${esc(o)}</button>`)
              .join('')}
          </div>
        </div>
        ${rule('Saved and nearby', String(s.length))}
        <ul class="list">
          ${s
            .map(
              (x) => `<li><button class="li">
              <span class="li-ic">${i(x.secure ? 'lock' : 'wifi', 18)}</span>
              <span class="li-m"><b class="mono">${esc(x.name)}</b><span>${esc(x.note)}</span></span>
              ${x.secure ? '' : '<span class="chip">HTTP</span>'}${i('chevron-right', 18)}
            </button></li>`,
            )
            .join('')}
        </ul>
      </div>
      <div class="dock">${key('Connect', { kind: 'primary', size: 'lg', block: true, iconRight: 'arrow-right' })}</div>
    </div>`;
  };

  screens.signin = (c) => {
    const pr = D().profiles[0];
    return `<div class="page grid-bg onb">
      ${topbar(c, { path: steps(2), backTo: 'Server' })}
      <div class="onb-body">
        <h1 class="h1">Sign in</h1>
        <div class="pn ident">
          <div class="ident-top">${led('synced')}<span class="lbl">Server verified</span><button class="linkkey">Change server</button></div>
          <div class="ident-host mono">${esc(pr.url)}</div>
          <dl class="spec spec-2">
            <div><dt>Version</dt><dd>${esc(pr.version)}</dd></div>
            <div><dt>Access</dt><dd>${esc(pr.access)} · verified</dd></div>
          </dl>
        </div>
        <div class="field-grp">
          <label class="lbl" for="in-user">Username</label>
          <div class="field">${i('user', 16)}<input id="in-user" value="ryan" spellcheck="false"></div>
        </div>
        <div class="field-grp">
          <label class="lbl" for="in-pass">Password</label>
          <div class="field">${i('key', 16)}<input id="in-pass" type="password" value="correcthorse">
            <button class="field-x" aria-label="Show password">${i('eye', 16)}</button></div>
        </div>
        ${key('Sign in', { kind: 'primary', size: 'lg', block: true, iconRight: 'arrow-right' })}
      </div>
      <div class="dock">
        <div class="or"><span></span>${lbl('or')}<span></span></div>
        ${key('Continue with single sign-on', { iconRight: 'shield', size: 'lg', block: true })}
      </div>
    </div>`;
  };

  screens.hydrate = (c) => {
    return `<div class="page grid-bg onb">
      ${topbar(c, { path: steps(3), backTo: 'Sign in' })}
      <div class="onb-body hyd">
        <h1 class="h1">Getting your<br>library ready</h1>
        <p class="lede">Rommio keeps a copy of your library on this ${phone(c)}, so you can browse offline.</p>
        <div class="pn hyd-pn">
          <div class="hyd-grid">
            ${readout(T().platforms, '', 'Platforms')}
            ${readout(k.num(T().games), '', 'Games')}
            ${readout(D().collections.length, '', 'Collections')}
          </div>
          <div class="hyd-done">${led('synced')}<span class="lbl">Catalogue ready</span></div>
          <div class="hyd-art">
            <div class="hyd-art-h">${lbl('Artwork')}<span class="sync">${led('syncing')}<span>Caching</span></span></div>
            <div class="hyd-art-v"><span class="big mono">61</span><small class="mono">%</small></div>
            ${meter(0.61, { n: 40, cls: 'm-live', aria: 'Artwork cached' })}
          </div>
        </div>
        <div class="pn hyd-sync">
          <div class="hyd-sync-t"><b>Saves that follow you</b><span>Game saves and resume states sync between your devices through your server.</span></div>
          <div class="chain" aria-hidden="true">
            <span class="node">${i('smartphone', 14)}THIS ${upper(phone(c))}</span><i></i>
            <span class="node node-srv">${i('server', 14)}ROMM</span><i></i>
            <span class="node">${i('tablet', 14)}IPAD · PIXEL 9</span>
          </div>
        </div>
        <div class="pn tlist">
          ${toggleRow('Keep artwork offline', 'Covers and screenshots for every game', true)}
          ${toggleRow('Sync on cellular', 'Saves and states only, never downloads', false)}
        </div>
      </div>
      <div class="dock">
        ${key('Start playing', { kind: 'primary', size: 'lg', block: true, iconRight: 'arrow-right' })}
        <p class="dock-note">Artwork keeps caching in the background.</p>
      </div>
    </div>`;
  };

  // ---------- Library ----------
  screens.home = (c) => {
    const hero = k.game('smetroid');
    const hp = k.platformOf(hero);
    const more = ['linksawak', 'chrono'].map(k.game);
    const mv = k.collection('metroidvania');
    const rc = k.collection('recent');
    return `<div class="page home">
      <header class="hd-home">
        ${wordmark()}
        <button class="srv" aria-label="Home server, online">${led('synced')}<span>${upper(D().profiles[0].name)}</span></button>
      </header>
      <div class="strip pn" role="group" aria-label="Library status">
        <button class="strip-c">${readout(T().installed, '', 'Installed')}</button>
        <button class="strip-c">${readout('3', '', 'In queue')}</button>
        <button class="strip-c attn" aria-label="1 conflict needs you">${readout('1', '', 'Conflict')}${led('conflict')}</button>
      </div>
      ${rule('Continue playing')}
      <article class="hero pn">
        ${bezel(k.frame(hero.id, 'b'), {
          alt: `${hero.title} resume point`,
          cls: 'hero-bz',
          bottom: `<span>${upper(hp.short)} · ${upper(hp.core)}</span><span>RESUME · ${upper(k.ago(hero.played))}</span>`,
        })}
        <div class="hero-row">
          <div class="hero-t"><h2>${esc(hero.title)}</h2>${sync(hero.sync)}</div>
          ${key('Resume', { kind: 'primary', icon: 'play', size: 'lg', cls: 'hero-key' })}
        </div>
      </article>
      <div class="cont pn">
        ${more
          .map((g) => {
            const p = k.platformOf(g);
            return `<button class="cc" aria-label="Resume ${esc(g.title)}">
            ${bezel(k.frame(g.id, 'a'), { cls: 'cc-bz', ar: k.aspect(g.id) })}
            <span class="cc-m"><b>${esc(g.title)}</b><span class="lbl">${upper(p.short)} · ${upper(k.ago(g.played))}</span></span>
            ${sync(g.sync)}
          </button>`;
          })
          .join('')}
      </div>
      <div class="duo">
        <button class="mod pn">
          <span class="mod-h">${lbl('Collection')}${lbl(String(mv.games.length))}</span>
          <span class="fan">${mv.games.map((id, n) => `<img class="cv" src="${k.cover(id)}" alt="" style="left:${n * 29}px">`).join('')}</span>
          <span class="mod-m"><b>${esc(mv.name)}</b><span>${esc(mv.note)}</span></span>
        </button>
        <button class="mod pn">
          <span class="mod-h">${lbl('Recently added')}${lbl(String(rc.games.length))}</span>
          <span class="fan">${rc.games.map((id, n) => `<img class="cv" src="${k.cover(id)}" alt="" style="left:${n * 18}px">`).join('')}</span>
          <span class="mod-m"><b>${esc(k.game(rc.games[0]).title)}</b><span>and ${rc.games.length - 1} more · last 30 days</span></span>
        </button>
      </div>
      ${nav('home')}
    </div>`;
  };

  screens.library = (c) => {
    const pad = (p) =>
      `<button class="pad" aria-label="${esc(p.name)}, ${p.games} games, ${p.installed} installed">
        <span class="pad-top">${cap(p.color)}<b>${esc(p.short)}</b></span>
        <span class="pad-bot"><em>${p.games}</em><span class="pad-in ${p.installed ? '' : 'zero'}">${i('download', 11)}${p.installed}</span></span>
      </button>`;
    const touch = k.tier('touch');
    const ctl = k.tier('controller');
    const off = k.tier('unsupported');
    return `<div class="page lib">
      <header class="hd">
        <h1 class="h1">Library</h1>
        <span class="hd-ro">${lbl(`${T().platforms} platforms`)}${lbl(`${k.num(T().games)} games`)}</span>
      </header>
      <div class="search">${i('search', 18)}<input aria-label="Search library" placeholder="Search ${k.num(T().games)} games"><button class="ikey sm" aria-label="Scan a cartridge code">${i('scan', 18)}</button></div>
      <section class="tierset">
        <div class="tier-h">${tier('touch')}<span class="lbl">${touch.length} platforms</span><span class="rule-line"></span><span class="lbl legend">Games · ${i('download', 10)} On device</span></div>
        <div class="pads">${touch.map(pad).join('')}</div>
      </section>
      <section class="tierset">
        <div class="tier-h">${tier('controller')}<span class="lbl">Needs a controller</span><span class="rule-line"></span></div>
        <div class="pads">${ctl.map(pad).join('')}</div>
      </section>
      <section class="tierset">
        <div class="tier-h">${tier('unsupported')}<span class="lbl">No player yet</span><span class="rule-line"></span></div>
        <div class="pads pads-2">${off
          .map(
            (p) => `<button class="pad pad-off" aria-label="${esc(p.name)}, ${p.games} games, browse only">
            <span class="pad-top">${cap(p.color)}<b>${esc(p.short)}</b><em>${p.games}</em></span>
            <span class="pad-why">${esc(p.blocked)}</span>
          </button>`,
          )
          .join('')}</div>
      </section>
      ${nav('library')}
    </div>`;
  };

  screens.platform = (c) => {
    const p = k.platform('snes');
    const games = k.gamesOn('snes').sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));
    return `<div class="page plat">
      ${topbar(c, { path: lbl('Library / Touch'), backTo: 'Library', right: ikey('search', 'Search Super Nintendo') })}
      <section class="plate">
        <div class="plate-id">${cap(p.color)}<span class="lbl">${upper(p.maker)} · ${p.year}</span></div>
        <h1 class="plate-t">${esc(p.name)}</h1>
        <div class="plate-ro pn">
          ${readout(p.games, '', 'Games')}
          ${readout(p.installed, '', 'Installed')}
          <div class="ro"><div class="ro-v ro-txt">${esc(p.core)}</div>${lbl('Core · ready')}</div>
          <div class="ro"><div class="ro-v ro-txt">${tier(p.tier)}</div>${lbl('Controls')}</div>
        </div>
      </section>
      <div class="filters">
        ${seg([['All', p.games], ['Installed', p.installed], ['Favourites']], 'All', 'Filter games')}
      </div>
      <div class="sortrow">${lbl('Sort')}<button class="sortkey">${i('sort', 15)}<span>Recently played</span></button></div>
      <div class="ggrid">
        ${games
          .map(
            (g) => `<button class="gcell ${g.installed ? '' : 'remote'}" aria-label="${esc(g.title)}">
            <span class="gcell-art">${cover(g.id)}${g.favorite ? `<span class="fav">${i('star', 12)}</span>` : ''}${
              g.installed ? '' : `<span class="dltag">${i('download', 11)}${upper(k.size(g.size))}</span>`
            }</span>
            <b>${esc(g.title)}</b>
            <span class="gcell-s">${
              g.installed ? `${i('check', 12)}<span>INSTALLED</span>` : `${i('download', 12)}<span>${upper(k.size(g.size))}</span>`
            }</span>
          </button>`,
          )
          .join('')}
      </div>
      ${nav('library')}
    </div>`;
  };

  screens.collections = (c) => {
    const all = D().collections;
    const mine = all.filter((x) => x.kind === 'manual');
    const smart = all.filter((x) => x.kind === 'smart');
    const auto = all.filter((x) => x.kind === 'auto');
    const stack = (x) => `<span class="stack">${x.games.slice(0, 3).map((id) => cover(id)).join('')}</span>`;
    const ruled = (x) => `<button class="li crow">
        ${stack(x)}
        <span class="li-m"><b>${esc(x.name)}</b><span class="rulechip">${i(x.kind === 'smart' ? 'filter' : 'sparkles', 11)}${esc(x.rule)}</span></span>
        <span class="count mono">${x.games.length}</span>
      </button>`;
    return `<div class="page cols">
      <header class="hd">
        <h1 class="h1">Collections</h1>
        ${ikey('plus', 'New collection')}
      </header>
      ${rule('My collections', String(mine.length))}
      <div class="ctiles">
        ${mine
          .map(
            (x) => `<button class="ctile pn">
            <span class="ctile-art">${x.games.slice(0, 4).map((id) => cover(id)).join('')}</span>
            <span class="ctile-m"><b>${esc(x.name)}</b><span class="count mono">${x.games.length}</span></span>
            <span class="ctile-n">${esc(x.note)}</span>
          </button>`,
          )
          .join('')}
      </div>
      ${rule('Smart', 'Rule-based')}
      <div class="list">${smart.map(ruled).join('')}</div>
      ${rule('Autogenerated', 'From metadata')}
      <div class="list">${auto.map(ruled).join('')}</div>
      ${nav('collections')}
    </div>`;
  };

  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = k.platformOf(g);
    const pr = D().progress;
    return `<div class="page gm">
      ${topbar(c, {
        path: lbl('Super Nintendo'),
        backTo: 'Super Nintendo',
        right: `${ikey('star', 'Favourite', 'on')}${ikey('more', 'More actions')}`,
      })}
      <div class="gm-scroll">
        <h1 class="gm-t">${esc(g.title)}</h1>
        <p class="gm-meta">${[p.short, g.year, g.publisher, g.genre.split(' · ')[0]].map(upper).join(' <i>·</i> ')} <i>·</i> <b>${g.rating}</b></p>
        <section class="label pn">
          <div class="label-art">${cover(g.id)}</div>
          <dl class="spec">
            <div><dt>Platform</dt><dd>${esc(p.name)}</dd></div>
            <div><dt>Released</dt><dd>${g.year}</dd></div>
            <div><dt>Publisher</dt><dd>${esc(g.publisher)}</dd></div>
            <div><dt>Genre</dt><dd>${esc(g.genre)}</dd></div>
            <div><dt>Rating</dt><dd class="mono">${g.rating}<small>/100</small></dd></div>
            <div><dt>Played</dt><dd class="mono">${esc(k.hours(g.hours))}</dd></div>
          </dl>
        </section>
        ${rule('Progress')}
        <div class="pn prog">
          <div class="prow"><span class="prow-l">${i('cartridge', 18)}<b>Game-save data</b></span>${sync(pr.gameSave.remote)}</div>
          <div class="prow"><span class="prow-l">${i('history', 18)}<b>Automatic resume</b></span>${sync(pr.resume.remote)}</div>
          <button class="prow"><span class="prow-l">${i('layers', 18)}<b>Save states</b></span><span class="prow-r"><span class="mono">3</span>${i('chevron-right', 16)}</span></button>
        </div>
        ${rule('File')}
        <div class="pn file">
          <div class="file-h"><span class="file-name mono">Super Metroid (USA).sfc</span><span class="file-size mono">3<small>MB</small></span></div>
          <div class="file-ro">
            <div class="ro"><div class="ro-v ro-txt">${i('check', 16)}Installed</div>${lbl(`On this ${phone(c)}`)}</div>
            <div class="ro"><div class="ro-v ro-txt">${esc(p.core)}</div>${lbl('Core · ready')}</div>
            <div class="ro"><div class="ro-v ro-txt">${tier(p.tier)}</div>${lbl('Controls')}</div>
          </div>
        </div>
        <p class="blurb">${esc(g.blurb)}</p>
        <div class="gm-2">
          ${key('Sync now', { icon: 'sync', block: true })}
          ${key('Remove download', { icon: 'trash', block: true, sub: 'Progress kept' })}
        </div>
      </div>
      <div class="tray">
        <button class="resume" aria-label="Resume Super Metroid">
          ${bezel(k.frame(g.id, pr.resume.frame), { cls: 'resume-bz' })}
          <span class="resume-m"><b>Resume</b><span class="lbl">${upper(k.ago(pr.resume.at))} · ${esc(pr.resume.device)}</span></span>
          ${i('play', 26)}
        </button>
        ${key('Start with game save', { icon: 'cartridge', block: true, cls: 'tray-2' })}
      </div>
    </div>`;
  };

  screens.downloads = (c) => {
    const dl = D().downloads;
    const run = dl.find((d) => d.state === 'running');
    const rg = k.game(run.game);
    const queued = dl.filter((d) => d.state === 'queued');
    const waiting = dl.filter((d) => d.state === 'waiting');
    const failed = dl.find((d) => d.state === 'failed');
    const done = dl.filter((d) => d.state === 'done');
    const st = D().storage;
    const row = (d, lead, right, sub) => {
      const g = k.game(d.game);
      return `<div class="li dl">
        ${lead}${cover(g.id, 'box', 'dl-cv')}
        <span class="li-m"><b>${esc(g.title)}</b><span>${sub || `${esc(k.platformOf(g).short)} · ${esc(gb(d.total))}`}</span></span>
        ${right}
      </div>`;
    };
    return `<div class="page dls">
      <header class="hd">
        <h1 class="h1">Downloads</h1>
        ${ikey('pause', 'Pause all downloads')}
      </header>
      <div class="pn dsum">
        <div class="dsum-ro">
          ${readout('1', '', 'Active')}
          ${readout(queued.length, '', 'Queued')}
          ${readout(waiting.length, '', 'Waiting')}
          ${readout('1', '', 'Failed', 'ro-bad')}
        </div>
        <div class="dsum-st">
          <div class="dsum-st-h">${lbl('ROM storage')}<span class="mono">${gb(st.roms)} <span class="of">of ${gb(st.total)}</span></span></div>
          ${meter(st.roms / st.total, { n: 48, cls: 'm-ink', aria: 'ROM storage used' })}
        </div>
      </div>
      <div class="pn xfer">
        <div class="xfer-h">
          ${cover(rg.id, 'box', 'xfer-cv')}
          <span class="xfer-t"><span class="xfer-st">${led('syncing')}${lbl('Transferring')}</span><b>${esc(rg.title)}</b><span class="lbl">${upper(k.platformOf(rg).name)} · ${gb(run.total)}</span></span>
          <span class="xfer-k">${ikey('pause', 'Pause download', 'sm')}${ikey('x', 'Cancel download', 'sm')}</span>
        </div>
        <div class="xfer-ro">
          <div class="xfer-pct"><span class="big mono">62</span><small class="mono">%</small></div>
          <dl class="xfer-dl">
            <div><dt>Received</dt><dd>${gb(run.done)} / ${gb(run.total)}</dd></div>
            <div><dt>Rate</dt><dd>${run.rate} MB/s</dd></div>
            <div><dt>Left</dt><dd>${esc(run.eta)}</dd></div>
          </dl>
        </div>
        ${meter(run.progress, { n: 48, cls: 'm-sig', aria: 'Download progress' })}
      </div>
      ${rule('Needs you', '1 FAILED')}
      <div class="pn fail">
        <div class="li dl">
          <span class="qn">${led('conflict')}</span>${cover(failed.game, 'box', 'dl-cv')}
          <span class="li-m"><b>${esc(k.game(failed.game).title)}</b><span>${esc(failed.reason)}</span></span>
          ${key('Retry', { icon: 'restart', cls: 'sm' })}
        </div>
        <div class="fail-m">${meter(failed.progress, { n: 48, cls: 'm-dim', aria: 'Stopped at' })}<span class="lbl">${gb(failed.done)} of ${gb(failed.total)} · stopped</span></div>
      </div>
      ${rule('Queue', String(queued.length))}
      <div class="list">
        ${queued.map((d, n) => row(d, `<span class="qn mono">0${n + 1}</span>`, `<span class="grip">${i('grip', 18)}</span>`)).join('')}
        ${waiting
          .map((d) => row(d, `<span class="qn">${i('wifi-off', 16)}</span>`, sync('waiting', 'Waiting for Wi-Fi'), `${esc(k.platformOf(k.game(d.game)).short)} · ${esc(gb(d.total))}`))
          .join('')}
      </div>
      ${rule('Completed')}
      <div class="list">
        ${done.map((d) => row(d, `<span class="qn">${i('check', 16)}</span>`, `<span class="lbl">${upper(k.agoShort(d.at))} ago</span>`)).join('')}
      </div>
      ${nav('downloads')}
    </div>`;
  };

  screens.settings = (c) => {
    const [home, sam] = D().profiles;
    const st = D().storage;
    const used = st.roms + st.saves + st.states + st.cache + st.cores + st.bios;
    const parts = [
      ['ROMs', st.roms, 'roms'],
      ['Saves', st.saves, 'saves'],
      ['States', st.states, 'states'],
      ['Cache', st.cache, 'cache'],
      ['Cores', st.cores, 'cores'],
      ['BIOS', st.bios, 'bios'],
    ];
    const cores = D().cores;
    const attention = cores.filter((x) => x.state === 'bios' || x.state === 'setup');
    return `<div class="page set">
      <header class="hd"><h1 class="h1">Settings</h1></header>
      <section class="pn acct">
        <div class="acct-id">
          <span class="avatar">${esc(D().user.initials)}</span>
          <span class="acct-n"><b>${esc(home.name)}</b><span class="mono">${esc(home.user)} @ ${esc(home.url)}</span></span>
          ${ikey('sync', 'Sync now')}
        </div>
        <div class="acct-h">${led('synced')}<span class="lbl">Online · synced ${esc(k.ago(home.lastSync))}</span><span class="lbl acct-v">${esc(home.version)}</span></div>
      </section>
      ${rule('Other profiles', '1')}
      <div class="pn plist">
        <button class="li prof"><span class="li-ic">${led('local')}</span><span class="li-m"><b>${esc(sam.name)}</b><span>Offline-ready · ${esc(sam.url)} · ${esc(k.ago(sam.lastSync))}</span></span><span class="linkkey">Switch</span></button>
      </div>
      ${rule('Sync')}
      <div class="pn tlist">
        ${toggleRow('Game saves', 'In-game battery saves, after every session', true)}
        ${toggleRow('Resume states', 'Automatic resume follows you between devices', true)}
        ${toggleRow('Sync on cellular', 'Saves and states only', false)}
      </div>
      <button class="pn entry">${i('gamepad', 20)}<span class="li-m"><b>Player &amp; controls</b><span>Touch layout, controllers, haptics</span></span>${i('chevron-right', 18)}</button>
      ${rule('Storage', `${gb(used)} OF ${gb(st.total)}`)}
      <div class="pn stor">
        <div class="stack-bar" role="img" aria-label="Storage used by Rommio">${parts
          .map(([n, v, id]) => `<i class="sb-${id}" style="flex:${v}"></i>`)
          .join('')}<i class="sb-free" style="flex:${st.total - used}"></i></div>
        <dl class="stor-l">${parts.map(([n, v, id]) => `<div><dt><i class="sw-${id}"></i>${esc(n)}</dt><dd>${gb(v)}</dd></div>`).join('')}</dl>
      </div>
      ${rule('Cores & BIOS', `${cores.length} CORES`)}
      <div class="pn plist">
        ${attention
          .map(
            (x) => `<button class="li"><span class="li-ic">${led('waiting')}</span><span class="li-m"><b>${esc(x.name)}</b><span>${esc(x.family)} · ${esc(x.note)}</span></span><span class="chip">${
              x.state === 'bios' ? 'NEEDS BIOS' : 'SETUP'
            }</span></button>`,
          )
          .join('')}
      </div>
      <button class="pn entry">${i('info', 20)}<span class="li-m"><b>About Rommio</b><span>Licences, support, diagnostics</span></span>${i('chevron-right', 18)}</button>
      ${nav('settings')}
    </div>`;
  };

  function knob(label, value, frac, unit = '%') {
    const n = 25;
    const a0 = -135;
    const sweep = 270;
    const ticks = k
      .range(n)
      .map((j) => {
        const a = ((a0 + (sweep * j) / (n - 1)) * Math.PI) / 180;
        const r1 = 40;
        const r2 = j % 6 === 0 ? 46 : 44;
        const on = j / (n - 1) <= frac + 0.001;
        return `<line x1="${(50 + r1 * Math.sin(a)).toFixed(2)}" y1="${(50 - r1 * Math.cos(a)).toFixed(2)}" x2="${(50 + r2 * Math.sin(a)).toFixed(2)}" y2="${(
          50 -
          r2 * Math.cos(a)
        ).toFixed(2)}" class="${on ? 'on' : ''}"/>`;
      })
      .join('');
    const va = ((a0 + sweep * frac) * Math.PI) / 180;
    return `<div class="knob" role="slider" tabindex="0" aria-label="${esc(label)}" aria-valuenow="${value}" aria-valuetext="${value}${unit}">
      <svg viewBox="0 0 100 100" aria-hidden="true">${ticks}
        <circle cx="50" cy="52" r="31" class="kn-shade"/><circle cx="50" cy="50" r="31" class="kn-face"/>
        <line x1="50" y1="50" x2="${(50 + 24 * Math.sin(va)).toFixed(2)}" y2="${(50 - 24 * Math.cos(va)).toFixed(2)}" class="kn-ind"/>
      </svg>
      <div class="kn-ro"><span class="mono">${value}</span><small class="mono">${unit}</small></div>
      ${lbl(esc(label))}
      <div class="kn-step"><button aria-label="Decrease ${esc(label)}">${i('minus', 16)}</button><button aria-label="Increase ${esc(label)}">${i('plus', 16)}</button></div>
    </div>`;
  }

  screens.controls = (c) => {
    const ct = D().controls;
    const L = c.os === 'android' ? { l: 44, r: 44, b: 20 } : { l: 62, r: 62, b: 21 };
    return `<div class="page ctl">
      ${topbar(c, { path: lbl('Settings / Player'), backTo: 'Settings' })}
      <h1 class="h1 ctl-t">Controls</h1>
      <div class="preview" style="--safe-left:${L.l}px;--safe-right:${L.r}px;--safe-bottom:${L.b}px;--ctl-opacity:${ct.opacity}">
        <div class="preview-in">${landscape(c, { variant: 'c' })}</div>
      </div>
      <p class="preview-cap">${lbl('Live preview')}<span class="lbl">Super Metroid · SNES layout</span></p>
      <div class="pn tlist">
        ${toggleRow('Show touch controls', '', ct.showTouch)}
        ${toggleRow('Hide when a controller connects', `${led('synced')}${esc(ct.connectedController.replace(' Wireless Controller', ''))} connected`, ct.autoHideOnController)}
        ${toggleRow('Left-handed layout', 'Swap the d-pad and face keys', ct.leftHanded)}
      </div>
      ${rule('Tuning')}
      <div class="pn knobs">
        ${knob('Opacity', Math.round(ct.opacity * 100), ct.opacity)}
        ${knob('Size', Math.round(ct.size * 100), (ct.size - 0.5) / 1)}
        ${knob('Deadzone', Math.round(ct.deadzone * 100), ct.deadzone / 0.5)}
      </div>
      ${rule('Feedback & display')}
      <div class="pn tlist">
        ${toggleRow('Haptics', 'A short tick on every key press', ct.haptics)}
        ${toggleRow('OLED black', 'True black around the game', ct.oled)}
        ${toggleRow('Console colours', 'Tint keys like the original pad', ct.consoleColors)}
      </div>
      <button class="pn entry">${i('controller', 20)}<span class="li-m"><b>Controller type</b><span>Button glyphs and layout</span></span><span class="val">${esc(ct.controllerType)}</span>${i('chevron-right', 18)}</button>
    </div>`;
  };

  // ---------- Player ----------
  screens.player = {
    tone: 'light',
    render: (c) =>
      `<div class="page dark">${landscape(c, {
        over: `<div class="toast" role="status">${i('check', 14)}<span>Automatic resume updated</span></div>`,
      })}</div>`,
  };

  screens['player-portrait'] = {
    tone: 'light',
    render: (c) => {
      const g = k.game('linksawak');
      const p = k.platformOf(g);
      return `<div class="page dark pp" style="--ar:${k.aspect(g.id)}">
        <div class="pp-screen">
          <div class="bz-strip"><span>${upper(p.short)} · ${upper(p.core)}</span><span class="bz-led">${led('syncing')}RESUME SYNCING</span></div>
          <div class="pp-glass"><img class="px" src="${k.frame(g.id, 'b')}" alt="${esc(g.title)} gameplay"></div>
        </div>
        <div class="pp-band">
          <span class="pp-name">${wordmark('wm-dark')}</span>
          ${menuKey()}
        </div>
        <div class="pp-ctl">
          <div class="pp-dpad">${dpad()}</div>
          <div class="pp-ab"><button class="fk b" aria-label="B">B</button><button class="fk a" aria-label="A">A</button></div>
          <div class="pp-ss">${pill('Select')}${pill('Start')}</div>
        </div>
      </div>`;
    },
  };

  screens.pause = {
    tone: 'light',
    render: (c) => {
      const g = k.game('smetroid');
      const p = k.platformOf(g);
      return `<div class="page dark pz" style="--ar:${k.aspect(g.id)}">
        <div class="pz-screen">
          <div class="bz-strip"><span>${i('pause', 10)} PAUSED</span><span>${upper(p.short)} · ${upper(p.core)}</span></div>
          <div class="pz-glass"><img class="px" src="${k.frame(g.id, 'b')}" alt="${esc(g.title)}, paused"></div>
        </div>
        <section class="pz-panel" aria-label="Pause menu">
          <div class="pz-head">
            <h1>${esc(g.title)}</h1>
            <dl class="pz-ro"><div><dt>Platform</dt><dd>${esc(p.short)} · ${esc(p.core)}</dd></div><div><dt>Session</dt><dd>${D().progress.session} min</dd></div><div><dt>Played</dt><dd>${esc(k.hours(g.hours))}</dd></div></dl>
          </div>
          <div class="pz-sync">${sync('synced', 'Game-save data synced')}${sync('waiting', 'Resume waiting to sync')}</div>
          ${key('Resume', { kind: 'primary', icon: 'play', block: true, size: 'lg' })}
          <div class="pz-grid">
            ${key('Save state', { kind: 'light', icon: 'save', block: true })}
            ${key('Load state', { kind: 'light', icon: 'layers', block: true })}
            ${key('Controls', { kind: 'light', icon: 'gamepad', block: true })}
            <button class="key key-light block latch" role="switch" aria-checked="false">${i('fast-forward', 18)}<span class="key-l">Fast-forward</span><i class="latch-led"></i></button>
            ${key('Reset', { kind: 'light', icon: 'restart', block: true })}
            ${key('Leave game', { kind: 'dim', icon: 'exit', block: true })}
          </div>
        </section>
      </div>`;
    },
  };

  screens.states = {
    tone: 'light',
    render: (c) => {
      const g = k.game('smetroid');
      const pr = D().progress;
      const slot = (s) =>
        s.empty
          ? `<button class="slot slot-empty" aria-label="Save state to slot 4"><span class="slot-hole">${i('plus', 18)}<span class="lbl">Save state here</span></span><span class="slot-n mono">04</span><span class="slot-name">Empty</span><span class="slot-meta">&nbsp;</span></button>`
          : `<button class="slot" aria-label="Slot ${s.n}, ${esc(s.name)}">
              <span class="slot-img"><img class="px" src="${k.frame(g.id, s.frame)}" alt=""></span>
              <span class="slot-n mono">0${s.n}</span>
              <span class="slot-name">${esc(s.name)}</span>
              <span class="slot-meta">${upper(k.agoShort(s.at))} · ${esc(s.device)}</span>
              ${sync(s.remote)}
            </button>`;
      return `<div class="page dark st">
        <header class="st-h">
          <button class="dkey" aria-label="Back to pause menu">${i('chevron-left', 20)}</button>
          <div class="st-t"><h1>Progress</h1><span class="lbl">${upper(g.title)} · SNES</span></div>
          <div class="st-hr">${key('Save state', { kind: 'light', icon: 'save', cls: 'sm' })}</div>
        </header>
        <div class="st-left">
          <button class="st-card st-resume" aria-label="Automatic resume, ${esc(k.ago(pr.resume.at))}">
            ${lbl('Automatic resume')}
            ${bezel(k.frame(g.id, pr.resume.frame), { cls: 'st-bz' })}
            <span class="st-card-f"><span class="mono caps">${upper(k.ago(pr.resume.at))} · ${esc(pr.resume.device)}</span>${sync(pr.resume.remote)}</span>
          </button>
          <button class="st-card st-gs" aria-label="Game-save data">
            ${lbl('Game-save data')}
            <span class="st-gs-m"><span class="mono">${esc(pr.gameSave.detail.toUpperCase())}</span><span class="mono caps">${upper(k.ago(pr.gameSave.at))} · ${esc(pr.gameSave.device)}</span></span>
            ${sync(pr.gameSave.remote)}
          </button>
        </div>
        <div class="st-right">
          <div class="st-rule">${lbl('Save states')}<span class="rule-line"></span>${lbl('3 of 4 slots')}</div>
          <div class="slots">${pr.slots.map(slot).join('')}</div>
          <div class="st-rule">${lbl('Recovery history')}<span class="rule-line"></span><button class="linkkey dk">All history${i('chevron-right', 14)}</button></div>
          <ol class="hist">${pr.history
            .map(
              (h) => `<li><span class="hist-t mono">${upper(k.agoShort(h.at))}</span><span class="hist-m"><b>${esc(h.kind)}</b><span>${esc(h.device)} · ${esc(
                h.reason,
              )}</span></span><button class="hist-k" aria-label="Restore ${esc(h.kind)}">${i('history', 16)}</button></li>`,
            )
            .join('')}</ol>
        </div>
      </div>`;
    },
  };

  screens.conflict = (c) => {
    const cf = D().conflict;
    const g = k.game(cf.game);
    const branch = (b, on) => `<button class="br ${on ? 'on' : ''}" role="radio" aria-checked="${on}">
        <span class="br-h"><span class="br-dev">${i('smartphone', 16)}<b>${esc(b.device)}</b></span><i class="sel-led"></i></span>
        ${bezel(k.frame(g.id, b.frame), { cls: 'br-bz', ar: k.aspect(g.id) })}
        <dl class="spec br-spec">
          <div><dt>Saved</dt><dd>${esc(k.ago(b.at))}</dd></div>
          <div><dt>Location</dt><dd>${esc(b.where)}</dd></div>
          <div><dt>Playtime</dt><dd class="mono">${esc(b.playtime)}</dd></div>
          <div><dt>Reason</dt><dd>${esc(b.reason)}</dd></div>
        </dl>
      </button>`;
    return `<div class="page cf">
      ${topbar(c, { close: true, path: `<span class="sync">${led('conflict')}<span>Conflict · ${esc(cf.kind)}</span></span>` })}
      <div class="cf-body">
        <h1 class="h1 cf-t">${esc(g.title)} has two resume points</h1>
        <p class="lede">Both devices played on from the same point. Choose the one to continue — the other stays as a recovery copy.</p>
        <div class="fork" aria-hidden="true"><span class="fork-root">${lbl('Last shared resume')}</span><i class="fork-l"></i><i class="fork-r"></i></div>
        <div class="brs" role="radiogroup" aria-label="Resume point">${branch(cf.branches[0], true)}${branch(cf.branches[1], false)}</div>
        <div class="pn tlist">${toggleRow('Keep the other as a recovery copy', 'Find it later in Recovery history', true)}</div>
      </div>
      <div class="dock">
        ${key('Continue from This iPhone', { kind: 'primary', size: 'lg', block: true, icon: 'play' })}
        ${key('Cancel', { kind: 'ghost', block: true })}
      </div>
    </div>`;
  };

  screens.dual = {
    tone: 'light',
    render: (c) => {
      const g = k.game('pkblack');
      const p = k.platformOf(g);
      const d = k.dual(g.id, 'a');
      return `<div class="page dark du">
        <div class="du-screen du-top">
          <div class="bz-strip"><span>${upper(p.short)} · ${upper(p.core)}</span><span>${upper(g.title)}</span></div>
          <div class="du-glass"><img class="px" src="${d.top}" alt="${esc(g.title)} top screen"></div>
        </div>
        <div class="hinge" aria-hidden="true"><i></i><i></i></div>
        <div class="du-screen du-bot">
          <div class="du-glass touch"><img class="px" src="${d.bottom}" alt="${esc(g.title)} touch screen"><span class="tap" aria-hidden="true"></span></div>
          <div class="bz-strip bz-b"><span>${i('pointer', 11)} TOUCH SCREEN</span><span>STYLUS · FINGER</span></div>
        </div>
        <div class="du-ctl">
          <button class="sk du-l" aria-label="L">L</button>
          ${menuKey()}
          <button class="sk du-r" aria-label="R">R</button>
          <div class="du-dpad">${dpad()}</div>
          <div class="du-ss">${pill('Select')}${pill('Start')}</div>
          <div class="du-face">${diamond()}</div>
        </div>
      </div>`;
    },
  };

  Studio.defineVersion({
    id: 'instrument1',
    round: 1,
    name: 'Instrument',
    tagline: 'Precision hardware. Every control a key.',
    concept:
      'Rommio as a beautifully engineered instrument, drawn flat: a warm-grey chassis, black ink and one signal orange. Labels are printed in tiny mono caps, numbers are readouts, sync state is a row of LEDs, and every action is a keycap with a 2px shade. When you play, the phone becomes the device — the game set into a recessed, labelled screen on a graphite body.',
    swatch: ['#E6E4DF', '#111111', '#FF5A1F'],
    fonts: ['Archivo:wdth,wght@62..125,100..900', 'JetBrains+Mono:wght@400..700'],
    tone: 'dark',
    tones: { player: 'light', 'player-portrait': 'light', pause: 'light', states: 'light', dual: 'light' },
    palette: [
      { name: 'Chassis', hex: '#E6E4DF' },
      { name: 'Panel', hex: '#F3F2EE' },
      { name: 'Ink', hex: '#111111' },
      { name: 'Ink 2', hex: '#55534E' },
      { name: 'Graphite', hex: '#2A2A2A' },
      { name: 'Signal', hex: '#FF5A1F' },
      { name: 'LED · Synced', hex: '#1FCF6B' },
      { name: 'LED · Waiting', hex: '#FFB020' },
      { name: 'LED · Syncing', hex: '#3D7BFF' },
      { name: 'LED · Conflict', hex: '#FF3B30' },
    ],
    type: [
      { role: 'Display · Archivo Expanded 700', family: 'Archivo', css: "'Archivo'; font-stretch: 125%; font-weight: 700", sample: 'Super Metroid' },
      { role: 'UI · Archivo 500', family: 'Archivo', css: "'Archivo'", sample: 'Start with game save' },
      { role: 'Labels & readouts', family: 'JetBrains Mono', css: "'JetBrains Mono'", sample: 'SNES · SNES9X · 62%' },
    ],
    tweaks: [
      { var: '--signal', label: 'Signal colour', type: 'color', value: '#ff5a1f' },
      { var: '--chassis', label: 'Chassis tone', type: 'color', value: '#e6e4df' },
      { var: '--graphite', label: 'Player body', type: 'color', value: '#2a2a2a' },
      { var: '--r-key', label: 'Key radius', type: 'range', min: 2, max: 22, value: 10, unit: 'px' },
      { var: '--grid-a', label: 'Faceplate grid', type: 'range', min: 0, max: 14, value: 5, unit: '%' },
      { var: '--ctl-opacity', label: 'Touch key opacity', type: 'range', min: 0.3, max: 1, step: 0.02, value: 0.72 },
    ],
    notes: {
      welcome: 'The library as a rack of cartridges, one pulled forward in signal orange. The promise is one line; everything else is a key.',
      home: 'Continue playing leads, framed as the same recessed, labelled screen the player uses, so a resume point looks like the game waiting in the device. Status is a three-cell readout; the conflict cell carries the red LED.',
      library: 'A rack of platform pads grouped by support tier. Pads carry the platform colour as a small cap, the game count as a readout and the on-device count; the tier header prints the legend once.',
      game: 'A spec sheet with the box art as its label. Resume is docked as the one orange key, with the resume frame set into it like a screen.',
      downloads: 'Transfers read like meters: one big percentage, three small readouts, a segmented bar. Queue numbers are real positions.',
      controls: 'The preview is the actual player rendered at 42% — tuning happens on flat knobs with steppers, never by dragging keys around.',
      player: 'The phone becomes the device: graphite body, recessed screen, bezel printed with platform and core. A is the single orange key.',
      pause: 'The game stays in its screen; the menu is a panel of light keys on the body. Fast-forward is a latching key with its own LED.',
      states: 'Resume and game-save data are their own items on the left; slots 01–04 read like a sample bank; recovery history below.',
      conflict: 'A small schematic shows the fork; the two branches are compared as matching spec columns. The recovery copy is on by default.',
      connect: 'The address is the instrument’s input; the live check reads back beside it with an LED. Access options are a four-position selector with a lit orange LED, not a hidden menu.',
      signin: 'The verified server is shown as a spec plate before any credentials are asked for. Single sign-on waits at the bottom as the alternative path.',
      hydrate: 'Catalogue counts as readouts, artwork caching as a live segmented meter. A three-node chain explains how saves travel. Start playing is never blocked by caching.',
      platform: 'A platform spec plate — count, installed, core, controls tier — above a cover grid. Games not on the device carry a printed download tag with their size.',
      collections: 'My collections get a four-cover rack; smart and autogenerated collections print their rule in mono so you can see why a game is there.',
      settings: 'A control panel: account plate with a sync key, flat switches for sync, a stacked storage meter with a legend of readouts, and cores that need attention called out with amber LEDs.',
      'player-portrait': 'Game Boy proportions: screen on top with a printed bezel, the wordmark and Menu key in the upper band, d-pad and diagonal B/A below, Select/Start pills centred.',
      dual: 'Two recessed screens on a hinge; the lower bezel is printed TOUCH SCREEN and a ring shows where the finger lands. L/R and Menu sit in a row above the pad.',
    },
    screens,
  });
})();
