/*
 * RISO — "A printed zine for your collection."
 *
 * DESIGN PLAN
 * ---------------------------------------------------------------------------
 * Concept   Rommio is a risograph-printed fanzine and game instruction manual.
 *           Bright paper, flat fluorescent inks, overprint where inks overlap,
 *           a little deliberate misregistration, halftone only where it means
 *           something (progress remaining, storage, the cover). Page furniture
 *           always encodes truth: the "edition" is the server profile, folios
 *           and datelines are real counts from data, captions carry metadata.
 *
 * Palette   Paper        #FBFAF6  bright, neutral — never cream
 *           Black        #1B1B1B  text, rules, primary buttons
 *           Fluo Pink    #FF48B0  primary ink: onboarding, home, conflict (fill only)
 *           Blue         #0078BF  secondary ink: library, platform, game (fill + large type)
 *           Yellow       #FFE800  player ink: controls, downloads (fill only)
 *           Green        #00A95C  collections, "Synced" (fill only)
 *           Overprints   pink×blue #002284 · pink×yellow #FF4200 · blue×yellow #006D00
 *           Muted text   #5E5D5A  (6.3:1 on paper). Fluorescents never carry small text.
 *
 * Type      Display  Big Shoulders Display 800–900, uppercase, huge, may crop.
 *           Text     Newsreader (text optical size) for blurbs, body, captions.
 *           Data     IBM Plex Mono for labels, folios, sizes, times, states.
 *
 * Grid      402pt portrait page, 16pt outer margin, 12-col feel via 2/3-col grids.
 *           One spacing scale: 4 · 8 · 12 · 16 · 24 · 32 · 48.
 *           Rules: 3pt masthead rule, 1.5pt section rules, 1pt hairline dividers.
 *
 * Signature moves
 *   1. rz(): the riso cover. Two plates of the same cover art — the pink plate
 *      from the green channel, the blue plate from the red channel — screened
 *      into their inks and multiplied onto paper, blue plate offset by --mis.
 *      Not-downloaded games print as a single-plate "proof".
 *   2. Your moments are photographs: resume frames and save states are never
 *      inked — they are clean snapshots in a paper border with a keyline.
 *   3. Misregistered headlines: a section-ink copy of every display title
 *      multiplied 2px off-register.
 *   4. Section inks give rhythm: Onboarding pink · Home pink · Library blue ·
 *      Collections green · Downloads yellow · Settings black (the colophon) ·
 *      Player yellow on black.
 *   5. Stamps: sync states and support tiers are rubber-stamp labels with an
 *      ink chip — the word always carries the meaning, colour only reinforces.
 *
 * Navigation  Tab screens open with a nameplate masthead (wordmark, edition =
 *           server profile, dateline of real counts) and a bottom "index tab"
 *           bar whose active tab is printed in the section ink. Pushed screens
 *           use a slim top bar (iOS chevron + back title, Android arrow).
 *
 * Player    OLED-black stage (data: OLED black on). The game is never tinted.
 *           Controls are flat fluorescent shapes: pink d-pad with an overprinted
 *           yellow pivot, yellow face buttons with black letters, blue shoulder
 *           pills; each shape carries a 2px off-register plate. Select/Start and
 *           Menu are small paper-outline pills near the lower edge. Pause and
 *           Save states arrive as printed manual pages beside the frozen game.
 */
(function () {
  const k = window.StudioKit;
  const i = k.icon;
  const esc = k.esc;
  const A = window.StudioArt;
  const D = () => window.StudioData;

  // Library totals (22 platforms · 2,164 games · 91 installed).
  const LIB = k.totals();

  // ---------------------------------------------------------------------------
  // formatting
  // ---------------------------------------------------------------------------
  const gb = (mb) => k.size(mb);
  const n = (x) => k.num(x);
  const plat = (id) => k.platformOf(id);

  // ---------------------------------------------------------------------------
  // components
  // ---------------------------------------------------------------------------

  // The riso cover: two plates of the same art, screened into inks, overprinted.
  // o.proof → single blue plate (not downloaded). o.inks → [a, b] css colours.
  const rz = (id, kind = 'box', o = {}) => {
    const u = k.cover(id, kind);
    const cls = ['rz', `rz-${kind}`, o.proof ? 'rz-proof' : '', o.cls || ''].join(' ').trim();
    const vars = [];
    if (o.inks) vars.push(`--pa:${o.inks[0]}`, `--pb:${o.inks[1]}`);
    if (o.style) vars.push(o.style);
    const st = vars.length ? ` style="${vars.join(';')}"` : '';
    const label = o.alt ? ` role="img" aria-label="${esc(o.alt)}"` : ' aria-hidden="true"';
    return `<span class="${cls}"${st}${label}><span class="rz-p rz-a"><span class="rz-s"><img src="${u}" alt=""></span></span><span class="rz-p rz-b"><span class="rz-s"><img src="${u}" alt=""></span></span></span>`;
  };

  // A clean snapshot of a real moment: never inked.
  const snap = (url, o = {}) =>
    `<span class="snap ${o.cls || ''}" style="--ar:${o.ar || '4 / 3'}${o.style ? ';' + o.style : ''}"><img src="${url}" alt="${esc(o.alt || '')}"${o.alt ? '' : ' aria-hidden="true"'}></span>`;

  // Sync-state stamp. Wording from k.syncLabel (save-sync-spec §11).
  const SYNC = { synced: 'ok', pending: 'wait', waiting: 'wait', syncing: 'live', conflict: 'bad', local: 'local', signin: 'bad', none: 'none' };
  const stamp = (state, o = {}) => {
    const t = SYNC[state] || 'none';
    const glyph = t === 'live' ? i('sync', 11) : t === 'bad' ? i('alert', 11) : '<i class="chip"></i>';
    return `<span class="stamp st-${t} ${o.plain ? 'plain' : ''} ${o.cls || ''}">${glyph}<span>${esc(o.label || k.syncLabel(state))}</span></span>`;
  };

  // Support tiers stay distinct: Touch · Controller · Browse only.
  const TIER_ICON = { touch: 'hand', controller: 'gamepad', unsupported: 'eye' };
  const tierTag = (t) => `<span class="tier tier-${t}">${i(TIER_ICON[t], 12)}<span>${esc(k.tierLabel(t))}</span></span>`;

  const btn = (label, o = {}) =>
    `<button class="btn ${o.kind || 'primary'} ${o.cls || ''}"${o.aria ? ` aria-label="${esc(o.aria)}"` : ''}>${o.icon ? i(o.icon, o.isz || 20) : ''}${label ? `<span>${esc(label)}</span>` : ''}${o.trail ? i(o.trail, 18) : ''}</button>`;

  const iconBtn = (icon, label, cls = '') => `<button class="ib ${cls}" aria-label="${esc(label)}">${i(icon, 22)}</button>`;

  // Headline with a misregistered section-ink copy behind it.
  const mr = (tag, text, cls = '', attrs = '') => `<${tag} class="mr ${cls}" data-t="${esc(text)}"${attrs}>${esc(text)}</${tag}>`;

  const sw = (on, label) => `<button class="sw ${on ? 'on' : ''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}"><i></i></button>`;

  const check = (on, label) =>
    `<span class="ck ${on ? 'on' : ''}" role="checkbox" aria-checked="${on}" aria-label="${esc(label)}">${on ? i('check', { size: 16, stroke: 3 }) : ''}</span>`;

  const bar = (pct, o = {}) =>
    `<span class="bar ${o.cls || ''}" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(pct * 100)}"${o.label ? ` aria-label="${esc(o.label)}"` : ''}><i style="width:${(pct * 100).toFixed(1)}%"></i></span>`;

  const slider = (label, value, text, o = {}) => {
    const id = 'rs-' + label.toLowerCase().replace(/[^a-z]+/g, '-');
    return `<div class="sl"><label for="${id}"><b>${esc(label)}</b><output for="${id}">${esc(text)}</output></label><input id="${id}" type="range" min="${o.min ?? 0}" max="${o.max ?? 100}" value="${value}" style="--v:${(((value - (o.min ?? 0)) / ((o.max ?? 100) - (o.min ?? 0))) * 100).toFixed(1)}%">${o.note ? `<small>${esc(o.note)}</small>` : ''}</div>`;
  };

  // Section header: display label, rule, mono count.
  const sh = (label, right = '', o = {}) =>
    `<div class="sh ${o.cls || ''}"><h3>${esc(label)}</h3><span class="sh-rule"></span>${right ? `<span class="sh-r">${right}</span>` : ''}</div>`;

  // Nameplate masthead for tab screens. The edition is the active profile.
  const masthead = (c, o = {}) => {
    const prof = D().profiles.find((p) => p.active);
    return `<header class="mh">
      <div class="mh-row">
        <span class="wm" aria-label="Rommio"><span class="wm-t mr" data-t="ROMMIO">ROMMIO</span></span>
        <span class="mh-ed"><b>${esc(prof.name)} edition</b><span>${esc(prof.url)}</span></span>
        <span class="mh-act">${o.search !== false ? iconBtn('search', 'Search') : ''}<button class="av" aria-label="Profile: ${esc(prof.user)}">${esc(D().user.initials)}</button></span>
      </div>
      <div class="mh-date">${o.date}</div>
    </header>`;
  };

  const TABS = [
    ['home', 'home', 'Home'],
    ['library', 'library', 'Library'],
    ['collections', 'layers', 'Collections'],
    ['downloads', 'download', 'Downloads'],
    ['settings', 'settings', 'Settings'],
  ];
  const tabs = (active) =>
    `<nav class="tabs" aria-label="Primary">${TABS.map(
      ([id, ic, label]) =>
        `<button class="tab ${id === active ? 'on' : ''}"${id === active ? ' aria-current="page"' : ''}>${i(ic, 21)}<span>${label}</span>${id === 'downloads' ? '<em class="badge" aria-label="3 in queue">3</em>' : ''}</button>`,
    ).join('')}</nav>`;

  // Slim top bar for pushed screens.
  const topbar = (c, o = {}) => {
    const android = c.os === 'android';
    const lead = o.cancel
      ? `<button class="tb-back tb-cancel">${android ? i('x', 22) : ''}<span>${android ? '' : 'Cancel'}</span></button>`
      : `<button class="tb-back" aria-label="Back${o.back ? ' to ' + esc(o.back) : ''}">${i(android ? 'arrow-left' : 'chevron-left', android ? 22 : 24)}${!android && o.back ? `<span>${esc(o.back)}</span>` : ''}</button>`;
    return `<header class="tb ${android ? 'tb-android' : ''} ${o.cls || ''}">
      ${lead}
      ${o.title ? `<span class="tb-title">${esc(o.title)}</span>` : '<span></span>'}
      <span class="tb-r">${o.right || ''}</span>
    </header>`;
  };

  const grain = () => `--grain:url(${A.grain(0.07, 180)})`;
  const page = (sec, body, o = {}) =>
    `<div class="rs sec-${sec} ${o.cls || ''}" style="${grain()}" data-os="${o.c ? o.c.os : ''}">${body}</div>`;

  // ---------------------------------------------------------------------------
  // player components (dark stage)
  // ---------------------------------------------------------------------------
  const DPAD_PATH = 'M38 4h24a4 4 0 0 1 4 4v30h30a4 4 0 0 1 4 4v24a4 4 0 0 1-4 4H66v30a4 4 0 0 1-4 4H38a4 4 0 0 1-4-4V70H4a4 4 0 0 1-4-4V42a4 4 0 0 1 4-4h30V8a4 4 0 0 1 4-4z';
  const dpad = (size, style = '') =>
    `<div class="dp" style="--sz:${size}px;${style}" role="group" aria-label="D-pad">
      <svg viewBox="-2 0 104 104" aria-hidden="true">
        <path class="dp-b" d="${DPAD_PATH}" transform="translate(2.5 2.5)"/>
        <path class="dp-k" d="${DPAD_PATH}"/>
        <path class="dp-a" d="${DPAD_PATH}"/>
        <circle class="dp-pivot" cx="50" cy="54" r="11"/>
        <path class="dp-ar" d="M50 12l-7 10h14zM50 96l-7-10h14zM8 54l10-7v14zM92 54l-10-7v14z"/>
      </svg>
      <button class="dp-hit up" aria-label="Up"></button><button class="dp-hit dn" aria-label="Down"></button><button class="dp-hit lt" aria-label="Left"></button><button class="dp-hit rt" aria-label="Right"></button>
    </div>`;

  // SNES diamond: Y left, X top, B bottom, A right.
  const diamond = (size, gap, style = '', labels = ['Y', 'X', 'B', 'A']) => {
    const [y, x, b, a] = labels;
    return `<div class="fb" style="--b:${size}px;--g:${gap}px;${style}" role="group" aria-label="Face buttons">
      <button class="fbtn f-top" aria-label="${x} button">${x}</button>
      <button class="fbtn f-left" aria-label="${y} button">${y}</button>
      <button class="fbtn f-right" aria-label="${a} button">${a}</button>
      <button class="fbtn f-bot" aria-label="${b} button">${b}</button>
    </div>`;
  };

  const pill = (label, cls = '', style = '') => `<button class="pill ${cls}" style="${style}">${label}</button>`;

  // ---------------------------------------------------------------------------
  // screens
  // ---------------------------------------------------------------------------
  const screens = {};

  // ---------- Welcome: the zine cover ----------
  screens.welcome = (c) =>
    page(
      'pink',
      `
    <div class="wel">
      <div class="wel-top"><span>A native client for RomM</span><span>${c.os === 'android' ? 'Android' : 'iOS'} edition</span></div>
      <h1 class="wel-mast mr" data-t="ROMMIO">ROMMIO</h1>
      <p class="wel-dek">Your RomM library, native on your phone. Playable offline, with saves that follow you.</p>
      <div class="wel-art" aria-hidden="true">
        <span class="wel-sun"></span>
        <span class="wel-ht"></span>
        <span class="wel-print wel-c1">${rz('smetroid', 'box')}</span>
        <span class="wel-print wel-c3">${rz('chrono', 'box')}</span>
        <span class="wel-print wel-c2">${rz('linksawak', 'box')}</span>
        <span class="wel-sticker"><b>Your server.<br>Your games.</b></span>
      </div>
      <div class="wel-cta">
        ${btn('Connect to RomM', { cls: 'full', trail: 'arrow-right' })}
        ${btn('Scan setup code', { kind: 'secondary', icon: 'qr', cls: 'full' })}
        <p class="fine">Rommio needs your own RomM server. It doesn’t provide games.</p>
      </div>
    </div>`,
      { c },
    );

  // ---------- Connect ----------
  const steps = (on) =>
    `<ol class="steps" aria-label="Setup steps">${['Server', 'Sign in', 'First sync']
      .map((s, n) => `<li class="${n === on ? 'on' : n < on ? 'done' : ''}"${n === on ? ' aria-current="step"' : ''}>${n < on ? i('check', { size: 12, stroke: 3 }) : ''}${s}</li>`)
      .join('')}</ol>`;

  screens.connect = (c) => {
    const [saved, found] = D().server.discovered;
    return page(
      'pink',
      `
    ${topbar(c, { back: 'Welcome', right: steps(0) })}
    <main class="pg ob">
      <p class="kick"><span>Your RomM server</span></p>
      ${mr('h1', 'Where does your library live?', 'ob-h')}
      <p class="lede">Enter the address of the RomM server you run. Rommio checks it as you type.</p>
      <div class="field ok">
        <label for="rs-server">Server address</label>
        <div class="field-box"><span class="pre">https://</span><input id="rs-server" value="romm.homelab.dev" autocomplete="url"><span class="field-ok" aria-hidden="true">${i('check', { size: 16, stroke: 3 })}</span></div>
        <p class="field-msg" role="status">${i('shield', 14)}<span><b>RomM 5.2.0</b> · protected by Cloudflare Access</span></p>
      </div>
      ${btn('Connect', { cls: 'full', trail: 'arrow-right' })}
      <button class="opt-row" aria-label="Access options: Automatic">
        <span class="opt-l"><b>Access options</b><small>Or choose Direct, Cloudflare Access, or a service token</small></span>
        <span class="opt-v">Automatic${i('chevron-right', 18)}</span>
      </button>
      ${sh('Saved & nearby', `${D().server.discovered.length} servers`)}
      <ul class="srv">
        <li><span class="srv-i">${i('lock', 18)}</span><span class="srv-m"><b>${esc(saved.name)}</b><small>${esc(saved.note)}</small></span><span class="stamp st-ok plain"><i class="chip"></i><span>HTTPS</span></span></li>
        <li><span class="srv-i">${i('wifi', 18)}</span><span class="srv-m"><b>${esc(found.name)}</b><small>${esc(found.note)}</small></span><span class="stamp st-local plain"><i class="chip"></i><span>No HTTPS</span></span></li>
      </ul>
    </main>`,
      { c },
    );
  };

  // ---------- Sign in ----------
  screens.signin = (c) => {
    const prof = D().profiles[0];
    return page(
      'pink',
      `
    ${topbar(c, { back: 'Change server', right: steps(1) })}
    <main class="pg ob">
      <div class="idcard">
        <div class="idcard-band"><span>Server verified</span>${i('badge-check', 18)}</div>
        <div class="idcard-body">
          <span class="idcard-i">${i('server', 22)}</span>
          <div><b>${esc(prof.url)}</b><small>${esc(prof.version)} · access verified via ${esc(prof.access)}</small></div>
        </div>
      </div>
      ${mr('h1', 'Sign in', 'ob-h xl')}
      <p class="lede">Use your RomM account. Rommio keeps you signed in on this device.</p>
      <div class="field">
        <label for="rs-user">Username</label>
        <div class="field-box"><input id="rs-user" value="${esc(prof.user)}" autocomplete="username"></div>
      </div>
      <div class="field">
        <label for="rs-pass">Password</label>
        <div class="field-box"><input id="rs-pass" type="password" value="correct-horse" autocomplete="current-password"><button class="field-eye" aria-label="Show password">${i('eye', 18)}</button></div>
      </div>
      ${btn('Sign in', { cls: 'full', trail: 'arrow-right' })}
      <div class="or"><span>or</span></div>
      ${btn('Continue with single sign-on', { kind: 'secondary', icon: 'key', cls: 'full' })}
    </main>`,
      { c },
    );
  };

  // ---------- First sync ----------
  screens.hydrate = (c) => {
    const devs = D().devices;
    return page(
      'pink',
      `
    ${topbar(c, { back: 'Sign in', right: steps(2) })}
    <main class="pg ob hy">
      <p class="kick"><span>First sync · romm.homelab.dev</span></p>
      ${mr('h1', 'Syncing your library', 'ob-h')}
      <ul class="press" aria-label="Sync progress">
        <li class="done">${check(true, 'Platforms')}<b>Platforms</b><span class="press-v">${LIB.platforms}</span></li>
        <li class="done">${check(true, 'Games')}<b>Games</b><span class="press-v">${n(LIB.games)}</span></li>
        <li class="run"><span class="ck run" aria-hidden="true"></span><b>Artwork</b><span class="press-v">61% cached</span>${bar(0.61, { label: 'Artwork cached', cls: 'press-bar' })}</li>
        <li class="done">${check(true, 'Collections')}<b>Collections</b><span class="press-v">Done</span></li>
      </ul>
      <section class="travel">
        <h3>Saves travel with you</h3>
        <p>Game saves and resume states sync between your devices through your server.</p>
        <div class="travel-map" aria-hidden="true">
          <span class="tm-dev on">${i('smartphone', 16)}${esc(devs[0].name)}</span>
          <span class="tm-line"></span>
          <span class="tm-srv">${i('server', 16)}RomM</span>
          <span class="tm-line"></span>
          <span class="tm-dev">${i('smartphone', 16)}${esc(devs[1].name)}</span>
        </div>
      </section>
      <ul class="opts">
        <li><span><b>Keep artwork offline</b><small>Covers stay on this device</small></span>${sw(true, 'Keep artwork offline')}</li>
        <li><span><b>Sync on cellular</b><small>Otherwise waits for Wi-Fi</small></span>${sw(false, 'Sync on cellular')}</li>
      </ul>
    </main>
    <footer class="dock">
      ${btn('Start playing', { cls: 'full', trail: 'arrow-right' })}
      <p class="fine">Artwork keeps caching in the background.</p>
    </footer>`,
      { c },
    );
  };

  // ---------- Home: the issue ----------
  screens.home = (c) => {
    const lead = k.game('smetroid');
    const others = ['linksawak', 'chrono'].map(k.game);
    const col = k.collection('metroidvania');
    const recent = k.collectionGames('recent');
    return page(
      'pink',
      `
    ${masthead(c, { date: `<span>${LIB.installed} installed</span><span>3 in queue</span><a class="hot">${i('alert', 12)}1 conflict needs you</a>` })}
    <main class="pg home">
      ${sh('Continue playing', '3 in progress')}
      <article class="lead">
        <figure class="lead-fig">
          ${snap(k.frame(lead.id, 'b'), { ar: '16 / 10', alt: 'Super Metroid resume frame' })}
          <span class="lead-tag">${i('history', 13)}Resume point</span>
        </figure>
        <div class="lead-body">
          ${mr('h2', lead.title, 'lead-t')}
          <p class="meta">${esc(plat(lead).short)} · ${esc(k.ago(lead.played))} · This iPhone</p>
          <div class="lead-row">
            ${btn('Resume', { icon: 'play', isz: 18, cls: 'lead-btn' })}
            ${stamp(lead.sync)}
          </div>
        </div>
      </article>
      <div class="pair">
        ${others
          .map(
            (g) => `<article class="pc">
          <span class="pc-fig">${snap(k.frame(g.id, 'a'), { alt: '' })}${stamp(g.sync, { cls: 'on-photo' })}</span>
          <h3>${esc(g.title)}</h3>
          <p class="meta">${esc(plat(g).short)} · ${esc(k.ago(g.played))}</p>
        </article>`,
          )
          .join('')}
      </div>
      <section class="feat">
        <div class="feat-art" aria-hidden="true">${col.games.map((id, n) => rz(id, 'box', { cls: 'fa' + n })).join('')}</div>
        <div class="feat-copy">
          <p class="kick"><span>Collection</span></p>
          <h3 class="feat-t">${esc(col.name)}</h3>
          <p class="feat-note">“${esc(col.note)}”</p>
          <p class="meta">${col.games.length} games · yours</p>
        </div>
      </section>
      ${sh('Recently added', `${recent.length} games`)}
      <div class="shelf">
        ${recent
          .map(
            (g) => `<figure class="sh-i">${rz(g.id, 'box', { proof: !g.installed })}<figcaption><b>${esc(g.title)}</b><span>${esc(plat(g).short)}</span></figcaption></figure>`,
          )
          .join('')}
      </div>
    </main>
    ${tabs('home')}`,
      { c },
    );
  };

  // ---------- Library: the catalogue ----------
  screens.library = (c) => {
    const touch = k.tier('touch');
    const ctrl = k.tier('controller');
    const none = k.tier('unsupported');
    const onDev = (x) => `<span class="on">${i('hard-drive-download', 11)}${x}</span>`;
    const cell = (p) => `<li class="pcell t-${p.tier}" aria-label="${esc(p.name)}: ${p.games} games, ${p.installed} on this device">
        <span class="pcell-h"><b>${esc(p.short)}</b><i class="swatch" style="--c:${p.color}"></i></span>
        <span class="pcell-n"><span>${n(p.games)}</span>${onDev(p.installed)}</span>
        ${p.blocked ? `<span class="pcell-why">${esc(p.blocked.replace(/^Browse only — (.)/, (m, c) => c.toUpperCase()))}</span>` : ''}
      </li>`;
    return page(
      'blue',
      `
    ${masthead(c, { search: false, date: `<span>${LIB.platforms} platforms</span><span>${n(LIB.games)} games</span><span>${LIB.installed} installed</span>` })}
    <main class="pg lib">
      <div class="lib-head">
        ${mr('h1', 'Library', 'page-t')}
        <p class="lib-sub">Every platform on your server, sorted by how it plays here.</p>
      </div>
      <label class="search"><span class="sr">Search</span>${i('search', 18)}<input placeholder="Search ${n(LIB.games)} games" aria-label="Search games"><kbd>${i('sliders', 16)}</kbd></label>
      <p class="lib-key" aria-label="Key"><b>Key</b><span><em>Games</em></span><span>${i('hard-drive-download', 11)}On this phone</span><span><i class="swatch" style="--c:var(--paper)"></i>Console colour</span></p>
      <section class="tierblock">
        ${sh('Touch', `${touch.length} platforms`)}
        <ul class="pgrid">${touch.map(cell).join('')}</ul>
      </section>
      <section class="tierblock">
        ${sh('Controller-first', `${ctrl.length} · best with a pad`)}
        <ul class="pgrid">${ctrl.map(cell).join('')}</ul>
      </section>
      <section class="tierblock">
        ${sh('Browse only', `${none.length} · no player`)}
        <ul class="pgrid two">${none.map(cell).join('')}</ul>
      </section>
    </main>
    ${tabs('library')}`,
      { c },
    );
  };

  // ---------- Platform ----------
  screens.platform = (c) => {
    const p = k.platform('snes');
    const games = k
      .gamesOn('snes')
      .slice()
      .sort((a, b) => (a.played ?? 1e9) - (b.played ?? 1e9));
    const favs = games.filter((g) => g.favorite).length;
    return page(
      'blue',
      `
    ${topbar(c, { back: 'Library', right: iconBtn('search', 'Search Super Nintendo') })}
    <main class="pg plat">
      <div class="plat-head">
        <h1 class="plat-t"><span class="plat-short mr" data-t="${esc(p.short)}" aria-hidden="true">${esc(p.short)}</span><span class="plat-name">${esc(p.name)}</span></h1>
        <p class="meta">${esc(p.maker)} · ${p.year} · ${esc(p.core)} core</p>
        <div class="plat-stats">
          <span><b>${p.games}</b>games</span>
          <span><b>${p.installed}</b>installed</span>
          <span><b>${favs}</b>favourites</span>
          ${tierTag(p.tier)}
        </div>
      </div>
      <div class="filters">
        <div class="seg" role="tablist" aria-label="Filter">
          <button role="tab" aria-selected="true" class="on">All</button>
          <button role="tab" aria-selected="false">Installed</button>
          <button role="tab" aria-selected="false">Favourites</button>
        </div>
        <button class="sort">${i('sort', 16)}Recently played</button>
      </div>
      <ul class="cgrid">
        ${games
          .map(
            (g) => `<li class="cg ${g.installed ? 'in' : 'out'}">
          <span class="cg-art">${rz(g.id, 'box', { proof: !g.installed })}${g.favorite ? `<span class="cg-fav" aria-label="Favourite">${i('star', 12)}</span>` : ''}${g.installed ? '' : `<button class="cg-dl" aria-label="Download ${esc(g.title)}">${i('download', 15)}</button>`}</span>
          <b>${esc(g.title)}</b>
          ${g.installed ? `<span class="cg-s in">${i('check', { size: 11, stroke: 2.8 })}Installed</span>` : `<span class="cg-s">${i('download', 11)}Not downloaded</span>`}
        </li>`,
          )
          .join('')}
      </ul>
    </main>`,
      { c },
    );
  };

  // ---------- Collections ----------
  screens.collections = (c) => {
    const all = D().collections;
    const mine = all.filter((x) => x.kind === 'manual');
    const smart = all.filter((x) => x.kind === 'smart');
    const auto = all.filter((x) => x.kind === 'auto');
    const stack = (ids, cls = '') => `<span class="stack ${cls}" aria-hidden="true">${ids.slice(0, 3).map((id, n) => rz(id, 'box', { cls: 's' + n })).join('')}</span>`;
    const rowItem = (x) => `<li class="crow">
        <span class="crow-m"><b>${esc(x.name)}</b><small>${i(x.kind === 'smart' ? 'filter' : 'sparkles', 12)}${esc(x.rule)}</small></span>
        ${stack(x.games, 'mini')}
        <span class="crow-n">${x.games.length}</span>
      </li>`;
    return page(
      'green',
      `
    ${masthead(c, { date: `<span>${mine.length} yours</span><span>${smart.length} smart</span><span>${auto.length} automatic</span>` })}
    <main class="pg cols">
      ${mr('h1', 'Collections', 'page-t crop')}
      ${sh('My collections', `${mine.length}`, { cls: 'first' })}
      <ul class="mine">
        ${mine
          .map(
            (x) => `<li class="mc">
          ${stack(x.games)}
          <b>${esc(x.name)}</b>
          <small>${x.games.length} games</small>
          <em>${esc(x.note)}</em>
        </li>`,
          )
          .join('')}
      </ul>
      ${sh('Smart', 'By rule')}
      <ul class="clist">${smart.map(rowItem).join('')}</ul>
      ${sh('Automatic', 'From metadata')}
      <ul class="clist">${auto.map(rowItem).join('')}</ul>
    </main>
    ${tabs('collections')}`,
      { c },
    );
  };

  // ---------- Game: the feature spread ----------
  screens.game = (c) => {
    const g = k.game('smetroid');
    const p = plat(g);
    const pr = D().progress;
    const [lede, pull] = g.blurb.split(/(?<=\.)\s+/);
    const states = pr.slots.filter((x) => !x.empty).length;
    return page(
      'blue',
      `
    ${topbar(c, { back: 'Super Nintendo', right: iconBtn('heart', 'Favourite', 'on') + iconBtn('more', 'More actions') })}
    <main class="pg game">
      <div class="hero">
        ${rz(g.id, 'wide', { cls: 'hero-art', alt: g.title + ' cover art' })}
        <span class="score" aria-label="Rating ${g.rating}"><small>Score</small><b>${g.rating}</b></span>
        <h1 class="hero-t" data-t="${esc(g.title)}"><span>${esc(g.title)}</span></h1>
      </div>
      <p class="meta hero-meta">${esc(p.short)} · ${g.year} · ${esc(g.publisher)} · ${esc(g.genre)}</p>
      <section class="resume">
        ${snap(k.frame(g.id, pr.resume.frame), { alt: 'Resume frame', cls: 'resume-shot' })}
        <div class="resume-m">
          ${btn('Resume', { icon: 'play', isz: 18, cls: 'full' })}
          <p class="meta">${esc(k.ago(pr.resume.at))} · ${esc(pr.resume.device)}</p>
          ${btn('Start with game save', { kind: 'secondary', cls: 'full sm' })}
        </div>
      </section>
      <dl class="prog" aria-label="Progress">
        <div><dt>Game-save data</dt><dd>${stamp(pr.gameSave.remote, { plain: true })}</dd></div>
        <div><dt>Automatic resume</dt><dd>${stamp(pr.resume.remote, { plain: true })}</dd></div>
        <div><dt>Save states</dt><dd><b>${states}</b> of ${pr.slots.length} slots</dd></div>
      </dl>
      <blockquote class="pull"><p>${esc(pull)}</p></blockquote>
      <p class="body">${esc(lede)}</p>
      <section class="spec" aria-label="Specifications">
        <h3 class="spec-h">Specifications</h3>
        <dl>
          <div><dt>File</dt><dd class="file">Super Metroid (USA).sfc<small>${gb(g.size)} · Installed</small></dd></div>
          <div><dt>Player</dt><dd>${esc(p.core)} ready ${tierTag(p.tier)}</dd></div>
          <div><dt>Played</dt><dd>${esc(k.hours(g.hours))} · last ${esc(k.ago(g.played))}</dd></div>
        </dl>
      </section>
      <div class="game-act">
        ${btn('Sync now', { kind: 'secondary', icon: 'sync', isz: 18, cls: 'sm' })}
        ${btn('Remove download', { kind: 'quiet', icon: 'trash', isz: 18, cls: 'sm' })}
      </div>
      <p class="fine left">Removing the download keeps your game saves, save states and resume point.</p>
    </main>`,
      { c },
    );
  };

  // ---------- Downloads: the order sheet ----------
  screens.downloads = (c) => {
    const dl = D().downloads;
    const run = dl.find((d) => d.state === 'running');
    const queued = dl.filter((d) => d.state === 'queued');
    const waiting = dl.filter((d) => d.state === 'waiting');
    const failed = dl.filter((d) => d.state === 'failed');
    const done = dl.filter((d) => d.state === 'done');
    const g = (id) => k.game(id);
    const left = run.total - run.done + queued.reduce((s, d) => s + d.total, 0) + waiting.reduce((s, d) => s + d.total, 0);
    const st = D().storage;
    const line = (d, lead, right, extra = '') => {
      const gm = g(d.game);
      return `<li class="ol ${d.state}">
        <span class="ol-n">${lead}</span>
        <span class="ol-m"><b>${esc(gm.title)}</b><small>${esc(plat(gm).short)}${extra}</small></span>
        <span class="ol-r">${right}</span>
      </li>`;
    };
    const rg = g(run.game);
    return page(
      'yellow',
      `
    ${masthead(c, { date: `<span>1 downloading</span><span>${queued.length} queued</span><span>${waiting.length} waiting</span><span>${failed.length} failed</span>` })}
    <main class="pg dls">
      ${mr('h1', 'Downloads', 'page-t')}
      <div class="sheet-sum">
        <span><b>${gb(left)}</b>left to fetch</span>
        <span><b>${gb(st.roms)}</b>ROMs on device</span>
        <span><b>${gb(st.total - st.roms - st.saves - st.states - st.cache - st.cores - st.bios)}</b>free</span>
      </div>
      ${sh('Now downloading', `${k.pct(run.progress)}`)}
      <article class="now">
        ${rz(rg.id, 'box', { cls: 'now-art' })}
        <div class="now-m">
          <b>${esc(rg.title)}</b>
          <p class="meta">${esc(plat(rg).short)} · ${gb(run.done)} of ${gb(run.total)}</p>
          <p class="meta dim">${run.rate} MB/s · ${esc(run.eta)} left</p>
        </div>
        <span class="now-act">${iconBtn('pause', 'Pause download')}${iconBtn('x', 'Cancel download')}</span>
        ${bar(run.progress, { cls: 'now-bar', label: rg.title + ' progress' })}
      </article>
      ${sh('Up next', `${queued.length + waiting.length} in queue`)}
      <ol class="ledger">
        ${queued.map((d, n) => line(d, String(n + 1), gb(d.total))).join('')}
        ${waiting.map((d) => line(d, i('wifi-off', 15), gb(d.total), ` · ${esc(d.reason)}`)).join('')}
      </ol>
      ${sh('Failed', `${failed.length}`)}
      <ul class="ledger">
        ${failed
          .map((d) => {
            const gm = g(d.game);
            return `<li class="ol failed">
            <span class="ol-n bad">${i('alert', 15)}</span>
            <span class="ol-m"><b>${esc(gm.title)}</b><small>${esc(d.reason)} · ${gb(d.done)} of ${gb(d.total)}</small></span>
            <span class="ol-r">${btn('Retry', { kind: 'ink', icon: 'restart', isz: 15, cls: 'xs' })}</span>
          </li>`;
          })
          .join('')}
      </ul>
      ${sh('Completed recently', `${done.length}`)}
      <ul class="ledger">
        ${done.map((d) => line(d, i('check', { size: 15, stroke: 2.6 }), gb(d.total), ` · ${esc(k.ago(d.at))}`)).join('')}
      </ul>
    </main>
    ${tabs('downloads')}`,
      { c },
    );
  };

  // ---------- Settings: the colophon ----------
  screens.settings = (c) => {
    const [home, sam] = D().profiles;
    const st = D().storage;
    const used = st.roms + st.saves + st.states + st.cache + st.cores + st.bios;
    const parts = [
      ['ROMs', st.roms, 'blue'],
      ['Save states', st.states, 'pink'],
      ['Game saves', st.saves, 'green'],
      ['Cache', st.cache, 'yellow'],
      ['Cores', st.cores, 'black'],
      ['BIOS', st.bios, 'black'],
    ];
    const cores = D().cores;
    const bios = cores.filter((x) => x.state === 'bios');
    const setup = cores.filter((x) => x.state === 'setup');
    return page(
      'black',
      `
    ${masthead(c, { search: false, date: `<span>${D().profiles.length} profiles</span><span>${cores.length} cores</span><span>${gb(used)} on device</span>` })}
    <main class="pg set">
      ${mr('h1', 'Settings', 'page-t')}
      <section class="colo" aria-label="Account">
        <p class="colo-k">Colophon</p>
        <p class="colo-l">This edition is printed from</p>
        <p class="colo-srv">${esc(home.name)}</p>
        <p class="colo-l"><b>${esc(home.user)}</b> at ${esc(home.url)}<br>${esc(home.version)} · via ${esc(home.access)}</p>
        <p class="colo-state">${stamp('synced', { label: 'Online · synced ' + k.ago(home.lastSync) })}</p>
        <div class="colo-act">${btn('Account', { kind: 'secondary', cls: 'xs', icon: 'user', isz: 15 })}${btn('Sign out', { kind: 'quiet', cls: 'xs', icon: 'exit', isz: 15 })}</div>
      </section>
      ${sh('Profiles', '2')}
      <ul class="rows">
        <li><span class="r-i">${i('server', 18)}</span><span class="r-m"><b>${esc(home.name)}</b><small>${esc(home.url)} · active</small></span><span class="r-v">${i('check', { size: 18, stroke: 2.6 })}</span></li>
        <li><span class="r-i">${i('archive', 18)}</span><span class="r-m"><b>${esc(sam.name)}</b><small>${esc(sam.url)}<br>Offline-ready · synced ${esc(k.ago(sam.lastSync))}</small></span><span class="r-v">${i('chevron-right', 18)}</span></li>
      </ul>
      ${sh('Sync', 'Saves & resume')}
      <ul class="rows">
        <li><span class="r-m"><b>Game saves</b><small>In-game save data, across devices</small></span>${sw(true, 'Sync game saves')}</li>
        <li><span class="r-m"><b>Resume states</b><small>Automatic resume, across devices</small></span>${sw(true, 'Sync resume states')}</li>
        <li><span class="r-m"><b>Use cellular data</b><small>Otherwise waits for Wi-Fi</small></span>${sw(false, 'Sync on cellular')}</li>
      </ul>
      ${sh('Player', '')}
      <ul class="rows">
        <li><span class="r-i">${i('gamepad', 18)}</span><span class="r-m"><b>Player &amp; controls</b><small>Touch layout, controllers, display</small></span><span class="r-v">${i('chevron-right', 18)}</span></li>
        <li><span class="r-i">${i('cpu', 18)}</span><span class="r-m"><b>Cores &amp; BIOS</b><small>${cores.length} cores · ${bios.length} needs BIOS · ${setup.length} needs setup</small></span><span class="r-v"><span class="dotn">2</span>${i('chevron-right', 18)}</span></li>
      </ul>
      ${sh('Storage', `${gb(used)} of ${Math.round(st.total / 1000)} GB`)}
      <div class="stor">
        <div class="stor-bar" role="img" aria-label="Storage used by Rommio">${parts.map(([l, v, c2]) => `<i class="ink-${c2}" style="flex:${v}"></i>`).join('')}<i class="free" style="flex:${st.total - used}"></i></div>
        <ul class="stor-key">${parts.map(([l, v, c2]) => `<li><i class="ink-${c2}"></i><span>${l}</span><b>${gb(v)}</b></li>`).join('')}</ul>
      </div>
      ${sh('About', '')}
      <ul class="rows"><li><span class="r-i">${i('info', 18)}</span><span class="r-m"><b>About Rommio</b><small>Licences, diagnostics, support</small></span><span class="r-v">${i('chevron-right', 18)}</span></li></ul>
    </main>
    ${tabs('settings')}`,
      { c },
    );
  };

  // ---------- Controls ----------
  screens.controls = (c) => {
    const ct = D().controls;
    return page(
      'yellow',
      `
    ${topbar(c, { back: 'Settings' })}
    <main class="pg ctl">
      ${mr('h1', 'Player & controls', 'ob-h')}
      <figure class="prev" aria-label="Live preview of the touch layout">
        <div class="prev-stage">
          <span class="prev-vp">${snap(k.frame('smetroid', 'a'), { ar: '4 / 3', cls: 'px' })}</span>
          <div class="prev-l">${pill('L', 'sh-l')}${dpad(62)}</div>
          <div class="prev-r">${pill('R', 'sh-r')}${diamond(22, 20)}</div>
        </div>
        <figcaption><span>Live preview · Super Metroid</span><span>${k.pct(ct.opacity)} · ${k.pct(ct.size)}</span></figcaption>
      </figure>
      ${sh('Touch controls', '')}
      <ul class="rows">
        <li><span class="r-m"><b>Show touch controls</b></span>${sw(ct.showTouch, 'Show touch controls')}</li>
        <li class="tall"><span class="r-m"><b>Hide when a controller connects</b><small class="conn"><i class="chip ok"></i>${esc(ct.connectedController)} connected</small></span>${sw(ct.autoHideOnController, 'Hide when a controller connects')}</li>
        <li><span class="r-m"><b>Left-handed layout</b><small>Swaps the d-pad and buttons</small></span>${sw(ct.leftHanded, 'Left-handed layout')}</li>
      </ul>
      ${sh('Tuning', '')}
      <div class="sls">
        ${slider('Opacity', Math.round(ct.opacity * 100), k.pct(ct.opacity), { min: 20 })}
        ${slider('Size', Math.round(ct.size * 100), k.pct(ct.size), { min: 60, max: 140 })}
        ${slider('Stick deadzone', Math.round(ct.deadzone * 100), k.pct(ct.deadzone), { max: 40 })}
      </div>
      ${sh('Feel & display', '')}
      <ul class="rows">
        <li><span class="r-m"><b>Haptics</b></span>${sw(ct.haptics, 'Haptics')}</li>
        <li><span class="r-m"><b>OLED black</b><small>Pure black around the game</small></span>${sw(ct.oled, 'OLED black')}</li>
        <li><span class="r-m"><b>Console colours</b><small>Use each console’s button colours</small></span>${sw(ct.consoleColors, 'Console colours')}</li>
        <li><span class="r-m"><b>Controller type</b></span><button class="r-sel">${esc(ct.controllerType)}${i('chevron-down', 16)}</button></li>
      </ul>
    </main>`,
      { c },
    );
  };

  // ---------- Player (landscape) ----------
  screens.player = {
    tone: 'light',
    render: (c) => {
      const g = k.game('smetroid');
      return `<div class="rs stage land">
      <div class="vp vp-land" style="--ar:${k.aspect(g.id)}"><img src="${k.frame(g.id, 'a')}" alt="Super Metroid, in game"></div>
      <div class="rail rail-l ctl-a">
        ${pill('L', 'sh-l')}
        ${dpad(112, 'top:149px')}
        <div class="tert">${pill(i('menu', 18), 'menu', '')}${pill('Select', 'ss')}</div>
      </div>
      <div class="rail rail-r ctl-a">
        ${pill('R', 'sh-r')}
        ${diamond(44, 37, 'top:149px')}
        <div class="tert">${pill('Start', 'ss')}</div>
      </div>
      <div class="toast" role="status">${i('check', { size: 14, stroke: 3 })}Automatic resume updated</div>
    </div>`;
    },
  };

  // ---------- Player (portrait, GBC) ----------
  screens['player-portrait'] = {
    tone: 'light',
    render: (c) => {
      const g = k.game('linksawak');
      return `<div class="rs stage port">
      <div class="pband">
        <span class="pband-t"><b>${esc(g.title)}</b><small>${esc(plat(g).name)}</small></span>
        ${pill(i('menu', 18) + '<span>Menu</span>', 'menu wide')}
      </div>
      <div class="vp vp-port" style="--ar:${k.aspect(g.id)}"><img src="${k.frame(g.id, 'a')}" alt="${esc(g.title)}, in game"></div>
      <div class="pss">${pill('Select', 'ss')}${pill('Start', 'ss')}</div>
      <div class="pctl ctl-a">
        ${dpad(152, 'left:22px;top:0')}
        <div class="ba">
          <button class="fbtn big f-b" aria-label="B button">B</button>
          <button class="fbtn big f-a" aria-label="A button">A</button>
        </div>
      </div>
    </div>`;
    },
  };

  // ---------- Pause: an instruction-manual page ----------
  screens.pause = {
    tone: 'light',
    render: (c) => {
      const g = k.game('smetroid');
      const pr = D().progress;
      return `<div class="rs stage land pause">
      <figure class="pz-game">
        <span class="vp vp-still" style="--ar:${k.aspect(g.id)}"><img src="${k.frame(g.id, 'a')}" alt="Super Metroid, paused"></span>
        <span class="pz-stamp">Paused</span>
        <figcaption>${i('history', 13)}Automatic resume updated · ${esc(k.ago(pr.resume.at))}</figcaption>
      </figure>
      <section class="manual" aria-label="Pause menu" style="${grain()}">
        <p class="man-k"><span>Pause menu</span><span>${esc(plat(g).short)} · ${esc(plat(g).core)}</span></p>
        <h1 class="man-t mr" data-t="${esc(g.title)}">${esc(g.title)}</h1>
        <p class="man-meta"><span>Session ${esc(k.hours(pr.session / 60))}</span><span>${esc(k.hours(g.hours))} played</span></p>
        ${btn('Resume', { icon: 'play', isz: 18, cls: 'full man-resume' })}
        <div class="man-grid">
          <button class="mg">${i('save', 18)}<span>Save state</span></button>
          <button class="mg">${i('history', 18)}<span>Load state</span></button>
          <button class="mg">${i('gamepad', 18)}<span>Controls</span></button>
          <div class="mg tog"><span class="mg-l">${i('fast-forward', 18)}<span>Fast-forward</span></span>${sw(false, 'Fast-forward')}</div>
          <button class="mg">${i('restart', 18)}<span>Reset</span></button>
          <button class="mg warn">${i('exit', 18)}<span>Leave game</span></button>
        </div>
        <p class="man-status">${stamp(pr.gameSave.remote, { plain: true, label: 'Game-save data synced' })}${stamp(pr.resume.remote, { plain: true, label: 'Resume waiting to sync' })}</p>
      </section>
    </div>`;
    },
  };

  // ---------- Save states ----------
  screens.states = {
    tone: 'light',
    render: (c) => {
      const g = k.game('smetroid');
      const pr = D().progress;
      const cell = (s) =>
        s.empty
          ? `<li class="ss-c empty"><span class="ss-empty">${i('plus', 20)}<span>Save state here</span></span><span class="ss-k">Slot ${s.n}</span><b>Empty</b></li>`
          : `<li class="ss-c">
          ${snap(k.frame(g.id, s.frame), { alt: '' })}
          <span class="ss-k">Slot ${s.n}</span>
          <b>${esc(s.name)}</b>
          <small>${esc(s.device)}<br>${esc(k.ago(s.at))}</small>
          ${stamp(s.remote, { plain: true })}
        </li>`;
      return `<div class="rs stage land states">
      <div class="st-game"><img src="${k.frame(g.id, 'a')}" alt="Super Metroid, paused"></div>
      <section class="st-page" style="${grain()}">
        <header class="st-h">
          <button class="tb-back" aria-label="Back to pause menu">${i(c.os === 'android' ? 'arrow-left' : 'chevron-left', 22)}<span>Pause</span></button>
          <h1 class="mr" data-t="Progress">Progress</h1>
          <span class="st-g">${esc(g.title)} · ${esc(plat(g).short)}</span>
        </header>
        <ul class="sheet">
          <li class="ss-c key">
            ${snap(k.frame(g.id, pr.resume.frame), { alt: '' })}
            <span class="ss-k">${esc(pr.resume.label)}</span>
            <b>Resume point</b>
            <small>${esc(pr.resume.device)}<br>${esc(k.ago(pr.resume.at))}</small>
            ${stamp(pr.resume.remote, { plain: true })}
          </li>
          <li class="ss-c key">
            <span class="sram" aria-hidden="true"><b>SRAM</b><span>${esc(pr.gameSave.detail.replace(/^SRAM · /, ''))}</span></span>
            <span class="ss-k">${esc(pr.gameSave.label)}</span>
            <b>In-game save</b>
            <small>${esc(pr.gameSave.device)}<br>${esc(k.ago(pr.gameSave.at))}</small>
            ${stamp(pr.gameSave.remote, { plain: true })}
          </li>
          <li class="sheet-div" aria-hidden="true"></li>
          ${pr.slots.map(cell).join('')}
        </ul>
        <div class="hist">
          <div class="hist-h"><h2>Recovery history</h2><span class="hist-a">${btn('Game-save history', { kind: 'secondary', cls: 'xs' })}${btn('State history', { kind: 'secondary', cls: 'xs' })}</span></div>
          <ol>
            ${pr.history.map((h) => `<li><b>${esc(h.kind)}</b><span>${esc(h.device)}</span><span>${esc(h.reason)}</span><span>${esc(k.ago(h.at))}</span></li>`).join('')}
          </ol>
        </div>
      </section>
    </div>`;
    },
  };

  // ---------- Conflict: which moment continues? ----------
  screens.conflict = (c) => {
    const cf = D().conflict;
    const g = k.game(cf.game);
    const [a, b] = cf.branches;
    const card = (x, on, newer) => `<li class="moment ${on ? 'on' : ''}">
        <button class="moment-b" role="radio" aria-checked="${on}">
          <span class="polaroid">${snap(k.frame(g.id, x.frame), { alt: `${g.title} at ${x.where}` })}<span class="pol-cap">${esc(x.where)}</span></span>
          ${on ? '<span class="pick">Continues</span>' : ''}
          <span class="moment-dev">${esc(x.device)}</span>
          <span class="meta">${esc(k.ago(x.at))}${newer ? ' · newer' : ''}</span>
          <span class="moment-d"><span>${esc(x.reason)}</span><span>${esc(x.playtime)} played</span></span>
        </button>
      </li>`;
    return page(
      'pink',
      `
    ${topbar(c, { cancel: true, title: 'Resume conflict' })}
    <main class="pg cf">
      <p class="kick"><span>${esc(g.title)} · ${esc(cf.kind)}</span></p>
      ${mr('h1', 'Which moment continues?', 'cf-t')}
      <p class="lede">EarthBound was played on two devices before they could sync. Pick the moment to keep playing from.</p>
      <ul class="moments" role="radiogroup" aria-label="Choose a resume point">
        ${card(a, true, true)}
        ${card(b, false, false)}
      </ul>
      <label class="keep">${check(true, 'Keep the other as a recovery copy')}<span><b>Keep the other as a recovery copy</b><small>You can restore it later from Recovery history.</small></span></label>
    </main>
    <footer class="dock">
      ${btn(`Continue from ${a.device}`, { cls: 'full', trail: 'arrow-right' })}
    </footer>`,
      { c },
    );
  };

  // ---------- Dual screen (DS) ----------
  screens.dual = {
    tone: 'light',
    render: (c) => {
      const g = k.game('pkblack');
      const d = k.dual(g.id, 'a');
      return `<div class="rs stage dualst">
      <div class="ds ds-top"><img src="${d.top}" alt="${esc(g.title)}, top screen"></div>
      <div class="ds ds-bot"><img src="${d.bottom}" alt="${esc(g.title)}, touch screen"><span class="ds-tag">${i('pointer', 12)}Touch</span></div>
      <div class="dband ctl-a">
        ${pill('L', 'sh-l')}
        ${pill(i('menu', 18), 'menu mid')}
        ${pill('R', 'sh-r')}
        ${dpad(118, 'left:20px;top:52px')}
        ${diamond(40, 38, 'right:22px;top:50px', ['Y', 'X', 'B', 'A'])}
        <div class="dss">${pill('Select', 'ss')}${pill('Start', 'ss')}</div>
      </div>
    </div>`;
    },
  };

  // ---------------------------------------------------------------------------
  Studio.defineVersion({
    id: 'riso1',
    round: 1,
    name: 'Riso',
    tagline: 'A printed zine for your collection.',
    concept:
      'Rommio as a risograph-printed fanzine and instruction manual: bright paper, flat fluorescent inks that overprint where they meet, a hair of misregistration and huge condensed headlines. Box art is printed in two inks; your own moments — resume points and save states — stay clean photographs. Each section carries its own ink, and every folio, dateline and caption is a real number from your server.',
    swatch: ['#FF48B0', '#0078BF', '#FBFAF6'],
    fonts: ['Big+Shoulders+Display:wght@700..900', 'Newsreader:ital,opsz,wght@0,6..72,400..700;1,6..72,400..600', 'IBM+Plex+Mono:wght@400;500;600'],
    tone: 'dark',
    tones: { player: 'light', 'player-portrait': 'light', pause: 'light', states: 'light', dual: 'light' },
    palette: [
      { name: 'Paper', hex: '#FBFAF6' },
      { name: 'Black', hex: '#1B1B1B' },
      { name: 'Fluorescent Pink', hex: '#FF48B0' },
      { name: 'Blue', hex: '#0078BF' },
      { name: 'Yellow', hex: '#FFE800' },
      { name: 'Green', hex: '#00A95C' },
      { name: 'Overprint · pink × blue', hex: '#002284' },
      { name: 'Overprint · pink × yellow', hex: '#FF4200' },
    ],
    type: [
      { role: 'Display', family: 'Big Shoulders Display 900', css: "'Big Shoulders Display', sans-serif; font-weight: 900; text-transform: uppercase", sample: 'SUPER METROID' },
      { role: 'Text', family: 'Newsreader', css: "'Newsreader', serif", sample: 'Every sealed door is a promise.' },
      { role: 'Data', family: 'IBM Plex Mono', css: "'IBM Plex Mono', monospace", sample: '1.2 of 1.9 GB · 18.4 MB/s' },
    ],
    tweaks: [
      { var: '--ink-a', label: 'Primary ink', type: 'color', value: '#ff48b0' },
      { var: '--ink-b', label: 'Secondary ink', type: 'color', value: '#0078bf' },
      { var: '--mis', label: 'Misregistration', type: 'range', min: 0, max: 6, step: 0.5, value: 2, unit: 'px' },
      { var: '--halftone', label: 'Halftone strength', type: 'range', min: 0, max: 100, step: 5, value: 55, unit: '%' },
      { var: '--paper', label: 'Paper stock', type: 'color', value: '#fbfaf6' },
      { var: '--ctl-alpha', label: 'Touch control opacity', type: 'range', min: 30, max: 100, step: 1, value: 72, unit: '%' },
    ],
    notes: {
      welcome: 'A zine cover: the nameplate is the product, three covers printed in pink and blue overprint into indigo where they overlap, and the yellow sticker carries the one promise people ask about first.',
      home: 'The issue. The dateline under the nameplate is the status line — real counts, with the conflict printed as a pink call-out. Resume frames are photographs (never inked); box art is printed.',
      library: 'A catalogue index: every platform is a cell with its count, installed count and the platform’s own spot colour as a swatch chip. Tiers are separate sections, never merged.',
      platform: 'Installed games are printed in both inks; games that are not downloaded print as a single-plate proof so the state reads even before the label.',
      game: 'A feature spread: knockout title across the printed cover, a pull-quote from the blurb, and a manual-style specification box for progress, file and player.',
      downloads: 'An order sheet: queue positions are real order, the active job’s remaining bytes are halftone, and failures get the pink ink and a Retry.',
      settings: 'The colophon: who printed this edition (server, account, version, access) set like the back page of a zine, then plain rows.',
      player: 'OLED-black stage, the game untouched. Controls are fluorescent flat shapes with a 2px off-register plate; the yellow pivot overprints the pink d-pad into orange.',
      pause: 'An instruction-manual page slides in beside the frozen game. The footer is the truthful sync status in spec wording.',
      states: 'A photographer’s contact sheet: automatic resume and game-save data first, then manual slots; recovery history is a ledger with both histories one tap away.',
      conflict: 'Two printed snapshots on the table. Choosing one stamps it “Continues”; the other is kept as a recovery copy by default.',
    },
    screens,
  });
})();
