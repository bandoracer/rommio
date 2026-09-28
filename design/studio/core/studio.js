/*
 * Rommio design studio — viewer and registry.
 *
 * Versions register with Studio.defineVersion({...}) (see AUTHORING.md).
 * Views:
 *   Canvas   one version, every screen grouped by flow        #void
 *   Compare  one screen across every version of the round     #compare-home
 *   Matrix   one flow (columns) across every version (rows)    #matrix-player
 *   Focus    one screen of one version, large, with its brief #void-home
 *   Shot     bare device for headless screenshots             #shot-void-home[-duo-open][-dark]
 *
 * Rounds: v2 is the reference; the first round is archived as round 1 (ids void1…).
 * Round 3 (v3) explores Aurora's Home and Library: each version sets `extends: 'aurora'`,
 * so its screens carry both scopes (`.v-aurora .v-<id>`) and inherit Aurora's tokens
 * and components (window.AuroraKit), and it designs only the screens it explores.
 * Devices: iPhone · Android · iPhone Duo closed · iPhone Duo open (with fold).
 * Appearance: Light · Dark · Both (pairs every frame).
 *
 * Local-only hash extras (not needed in the published page):
 *   @0.4 zoom · ~Player one flow in Canvas · +duo-open device · +dark / +both appearance · +v1 / +v3 round
 *   ^300 scrolls every screen's content area by 300pt (review below the fold)
 * Keyboard: 1–9 versions · ←/→ screens · C V X F views · T tweaks · D device · A appearance · M motion
 */
(function () {
  const versions = [];
  const byId = {};

  // ---------- devices ----------
  // Geometry per screen kind; `vbar` = iOS 27.1 vertical bars for app screens.
  const DEVICES = {
    iphone: {
      id: 'iphone', label: 'iPhone', os: 'ios', radius: 56,
      app: { w: 402, h: 874, safe: { top: 62, bottom: 34, left: 0, right: 0 } },
      landscape: { w: 874, h: 402, safe: { top: 0, bottom: 21, left: 62, right: 62 } },
      portrait: { w: 402, h: 874, safe: { top: 62, bottom: 34, left: 0, right: 0 } },
    },
    android: {
      id: 'android', label: 'Android', os: 'android', radius: 44,
      app: { w: 402, h: 874, safe: { top: 44, bottom: 24, left: 0, right: 0 } },
      landscape: { w: 874, h: 402, safe: { top: 0, bottom: 20, left: 44, right: 44 } },
      portrait: { w: 402, h: 874, safe: { top: 44, bottom: 24, left: 0, right: 0 } },
    },
    'duo-closed': {
      id: 'duo-closed', label: 'Duo · closed', os: 'ios', radius: 42, duo: true, vbar: true,
      app: { w: 466, h: 678, safe: { top: 16, bottom: 20, left: 0, right: 0 } },
      landscape: { w: 678, h: 466, safe: { top: 0, bottom: 16, left: 20, right: 20 } },
      portrait: { w: 466, h: 678, safe: { top: 16, bottom: 20, left: 0, right: 0 } },
    },
    'duo-open': {
      id: 'duo-open', label: 'Duo · open', os: 'ios', radius: 30, duo: true, vbar: true,
      app: { w: 951, h: 669, safe: { top: 16, bottom: 20, left: 0, right: 0 }, fold: { x: 475 } },
      landscape: { w: 951, h: 669, safe: { top: 16, bottom: 20, left: 0, right: 0 }, fold: { x: 475 } },
      portrait: { w: 669, h: 951, safe: { top: 16, bottom: 20, left: 0, right: 0 }, fold: { y: 475 } },
    },
  };
  const DEVICE_IDS = Object.keys(DEVICES);
  // Kept for archived v1 screens that read Studio.SAFE[os][orient].
  const SAFE = {
    ios: { portrait: DEVICES.iphone.app.safe, landscape: DEVICES.iphone.landscape.safe },
    android: { portrait: DEVICES.android.app.safe, landscape: DEVICES.android.landscape.safe },
  };
  const MODES = ['canvas', 'compare', 'matrix', 'focus'];
  const SCHEMES = ['light', 'dark', 'both'];

  const store = {
    get(k, fallback) {
      try {
        const v = localStorage.getItem('rommio-studio2:' + k);
        return v == null ? fallback : JSON.parse(v);
      } catch (e) {
        return fallback;
      }
    },
    set(k, v) {
      try {
        localStorage.setItem('rommio-studio2:' + k, JSON.stringify(v));
      } catch (e) {
        /* storage unavailable: state lives for this page view only */
      }
    },
  };

  const state = {
    round: store.get('round', 2),
    mode: store.get('mode', 'compare'),
    version: store.get('version', null),
    screen: store.get('screen', 'home'),
    matrixFlow: store.get('matrixFlow', 'Library'),
    device: store.get('device', 'iphone'),
    scheme: store.get('scheme', 'light'),
    zoom: store.get('zoom', 0.45),
    tweaksOpen: store.get('tweaksOpen', false),
    motion: store.get('motion', true),
    tweaks: store.get('tweaks', {}),
    shot: false,
    flow: null,
    scrollY: 0,
  };

  // ---------- registry ----------
  function defineVersion(def) {
    if (!def.id || !def.screens) throw new Error('A version needs an id and screens');
    def.round = def.round ?? 2;
    def.tweaks = def.tweaks || [];
    def.fonts = def.fonts || [];
    if (byId[def.id]) versions.splice(versions.indexOf(byId[def.id]), 1, def);
    else versions.push(def);
    byId[def.id] = def;
  }
  const roundVersions = () => versions.filter((v) => v.round === state.round);

  // ---------- device chrome ----------
  const K = () => window.StudioKit;
  const esc = (s) => K().esc(s);

  const ICON = {
    battery: (c) => `<svg width="27" height="13" viewBox="0 0 27 13"><rect x="0.5" y="0.5" width="23" height="12" rx="3.8" fill="none" stroke="${c}" stroke-opacity=".38"/><rect x="2" y="2" width="${Math.round(20 * window.StudioData.now.battery)}" height="9" rx="2.5" fill="${c}"/><path d="M25 4.5v4c.8-.3 1.4-1.1 1.4-2s-.6-1.7-1.4-2z" fill="${c}" fill-opacity=".4"/></svg>`,
    wifi: (c) => `<svg width="17" height="12" viewBox="0 0 17 12"><path fill="${c}" d="M8.5 2.4c2.3 0 4.4.9 6 2.4l1.2-1.2A10.2 10.2 0 0 0 8.5.7 10.2 10.2 0 0 0 1.3 3.6l1.2 1.2a8.5 8.5 0 0 1 6-2.4zm0 3.3c1.4 0 2.6.5 3.6 1.4l1.2-1.2a6.8 6.8 0 0 0-9.6 0l1.2 1.2c1-.9 2.2-1.4 3.6-1.4zm0 3.3c.5 0 .9.2 1.2.5L8.5 11 7.3 9.5c.3-.3.7-.5 1.2-.5z"/></svg>`,
    signal: (c) => `<svg width="18" height="12" viewBox="0 0 18 12"><g fill="${c}"><rect x="0" y="8" width="3" height="4" rx="1"/><rect x="5" y="5.5" width="3" height="6.5" rx="1"/><rect x="10" y="3" width="3" height="9" rx="1"/><rect x="15" y="0" width="3" height="12" rx="1"/></g></svg>`,
  };

  function statusBar(dev, geo, kind, tone, vbarOn) {
    const c = tone === 'dark' ? '#000' : '#fff';
    const time = window.StudioData.now.time;
    if (vbarOn) {
      return `<div class="st-sb st-sb-v" style="color:${c}"><span class="st-sb-time">${time}</span>${ICON.signal(c)}${ICON.wifi(c)}${ICON.battery(c)}</div>`;
    }
    if (geo.w > geo.h) return ''; // landscape: games hide the status bar
    if (dev.duo && kind !== 'app') return ''; // Duo players run full screen
    if (dev.os === 'ios') {
      return `<div class="st-sb st-sb-ios" style="color:${c}"><span class="st-sb-time">${time}</span><span class="st-sb-right">${ICON.signal(c)}${ICON.wifi(c)}${ICON.battery(c)}</span></div>`;
    }
    return `<div class="st-sb st-sb-android" style="color:${c}"><span class="st-sb-time">${time}</span><span class="st-sb-right">${ICON.wifi(c)}${ICON.signal(c)}${ICON.battery(c)}</span></div>`;
  }
  function cutout(dev, geo) {
    const land = geo.w > geo.h;
    if (dev.id === 'iphone') return `<div class="st-cut st-cut-ios st-cut-${land ? 'landscape' : 'portrait'}"></div>`;
    if (dev.id === 'android') return `<div class="st-cut st-cut-android st-cut-${land ? 'landscape' : 'portrait'}"></div>`;
    // Duo: outer camera sits top-trailing; the inner camera occludes the top of the trailing half.
    if (dev.id === 'duo-closed') return land ? `<div class="st-cut st-hole" style="left:12px;top:${geo.h / 2 - 6}px"></div>` : `<div class="st-cut st-hole" style="left:${geo.w / 2 - 6}px;top:12px"></div>`;
    return land ? `<div class="st-cut st-hole" style="left:${Math.round(geo.w * 0.76)}px;top:9px"></div>` : `<div class="st-cut st-hole" style="right:26px;top:9px"></div>`;
  }
  function foldLine(geo) {
    if (!geo.fold) return '';
    return geo.fold.x ? `<div class="st-fold v" style="left:${geo.fold.x}px"></div>` : `<div class="st-fold h" style="top:${geo.fold.y}px"></div>`;
  }

  function geometry(version, screen, deviceId) {
    // Archived v1 screens only know phones.
    let dev = DEVICES[deviceId] || DEVICES.iphone;
    if (version.round === 1 && dev.duo) dev = DEVICES.iphone;
    const kind = version.round === 1 ? (screen.orient === 'landscape' ? 'landscape' : 'portrait') : screen.kind || 'app';
    const geo = dev[kind];
    return { dev, kind, geo };
  }

  function ctxFor(version, screen, deviceId, scheme) {
    const { dev, kind, geo } = geometry(version, screen, deviceId);
    const c = {
      D: window.StudioData,
      A: window.StudioArt,
      k: K(),
      i: K().icon,
      esc: K().esc,
      os: dev.os,
      device: { id: dev.id, label: dev.label, os: dev.os, duo: !!dev.duo, vbar: !!dev.vbar, fold: geo.fold || null },
      kind,
      orient: geo.w > geo.h ? 'landscape' : 'portrait',
      size: geo.w >= 600 ? 'regular' : 'compact',
      scheme,
      w: geo.w,
      h: geo.h,
      safe: geo.safe,
      version,
      screen,
    };
    if (window.StudioSys && version.round !== 1) c.sys = window.StudioSys.create(c);
    return c;
  }

  function renderScreen(version, screen, deviceId, scheme) {
    const def = version.screens[screen.id];
    const ctx = ctxFor(version, screen, deviceId, scheme);
    let html;
    let tone = (def && def.tone) || (version.tones && version.tones[screen.id]) || (version.round === 1 ? version.tone || 'light' : scheme === 'dark' ? 'light' : 'dark');
    if (!def) {
      html = `<div class="st-missing"><b>${esc(screen.title)}</b><span>Not designed yet in ${esc(version.name)}</span></div>`;
    } else {
      try {
        const out = typeof def === 'function' ? def(ctx) : def.render(ctx);
        if (out && typeof out === 'object') {
          html = out.html;
          tone = out.tone || tone;
        } else html = out;
      } catch (e) {
        console.error(version.id, screen.id, e);
        html = `<div class="st-missing st-error"><b>${esc(screen.title)}</b><span>${esc(e.message)}</span></div>`;
      }
    }
    // Data names the local device "This iPhone"; on Android it's "This phone".
    if (ctx.os === 'android' && html) html = html.replace(/\b([Tt])his iPhone\b/g, '$1his phone').replace(/\bTHIS IPHONE\b/g, 'THIS PHONE');
    return { html, tone, ctx };
  }

  function frame(version, screen, deviceId, scheme, opts = {}) {
    const { html, tone, ctx } = renderScreen(version, screen, deviceId, scheme);
    const { dev, kind, geo } = geometry(version, screen, deviceId);
    const s = geo.safe;
    const vbarOn = dev.vbar && kind === 'app' && version.round !== 1;
    const vars = `--safe-top:${s.top}px;--safe-bottom:${s.bottom}px;--safe-left:${s.left}px;--safe-right:${s.right}px;--scr-w:${geo.w}px;--scr-h:${geo.h}px${geo.fold ? `;--fold-x:${geo.fold.x || 0}px;--fold-y:${geo.fold.y || 0}px` : ''}`;
    const cls = `scr ${version.extends ? `v-${version.extends} ` : ''}v-${version.id} os-${dev.os} dev-${dev.id} kind-${kind} size-${ctx.size} scheme-${scheme}`;
    const hb = `rgba(${tone === 'dark' ? '0,0,0' : '255,255,255'},.8)`;
    return `<div class="st-dev st-dev-${dev.os} st-dev-${dev.id} st-dev-${geo.w > geo.h ? 'landscape' : 'portrait'}" data-v="${version.id}" data-s="${screen.id}" style="--dw:${geo.w}px;--dh:${geo.h}px;--dr:${dev.radius}px"${
      opts.click ? ` tabindex="0" role="button" aria-label="Open ${esc(version.name + ' ' + screen.title)}"` : ''
    }>
      <div class="${cls}" data-screen="${screen.id}" data-scheme="${scheme}" style="${vars}">${html}</div>
      ${foldLine(geo)}${statusBar(dev, geo, kind, tone, vbarOn)}${cutout(dev, geo)}
      <div class="st-hb st-hb-${dev.os}" style="background:${hb}"></div>
    </div>`;
  }

  // Light, dark or a pair.
  function frames(version, screen, opts = {}) {
    // Round 1 predates appearances and Duo: always a single phone frame.
    if (state.scheme === 'both' && version.round !== 1) return `<div class="st-pair">${frame(version, screen, state.device, 'light', opts)}${frame(version, screen, state.device, 'dark', opts)}</div>`;
    return frame(version, screen, state.device, state.scheme, opts);
  }

  // ---------- fonts & tweaks ----------
  function loadFonts() {
    const fams = [...new Set(['Roboto+Flex:opsz,wght@8..144,300..700', ...versions.flatMap((v) => v.fonts)])];
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://fonts.googleapis.com/css2?' + fams.map((f) => 'family=' + f).join('&') + '&display=swap';
    document.head.appendChild(link);
  }
  function tweakCSS() {
    let css = '';
    for (const v of versions) {
      const o = state.tweaks[v.id];
      if (!o) continue;
      const decls = Object.entries(o)
        .map(([k, val]) => {
          const t = v.tweaks.find((x) => x.var === k);
          return `${k}:${val}${t && t.unit ? t.unit : ''}`;
        })
        .join(';');
      if (decls) css += `.studio .scr.v-${v.id}{${decls}}\n`;
    }
    return css;
  }
  function applyTweaks() {
    let el = document.getElementById('studio-tweaks');
    if (!el) {
      el = document.createElement('style');
      el.id = 'studio-tweaks';
      document.head.appendChild(el);
    }
    el.textContent = tweakCSS();
  }

  // ---------- routing ----------
  const screenIds = () => window.StudioScreens.map((s) => s.id);
  function parseHash() {
    let h = decodeURIComponent(location.hash.replace(/^#/, ''));
    if (!h) return;
    const z = h.match(/@([\d.]+)/);
    if (z) state.zoom = parseFloat(z[1]);
    const f = h.match(/~([A-Za-z]+)/);
    state.flow = f ? f[1] : null;
    const sy = h.match(/\^(\d+)/);
    state.scrollY = sy ? parseInt(sy[1], 10) : 0;
    for (const flag of h.match(/\+[a-z0-9-]+/g) || []) {
      const x = flag.slice(1);
      if (DEVICES[x]) state.device = x;
      else if (SCHEMES.includes(x)) state.scheme = x;
      else if (x === 'v1') state.round = 1;
      else if (x === 'v2') state.round = 2;
      else if (x === 'v3') state.round = 3;
    }
    h = h.replace(/[@~+^].*$/, '');
    let parts = h.split('-');
    const takeTail = () => {
      // trailing -dark/-light, then a device id (which may contain a dash)
      const last = parts[parts.length - 1];
      if (last === 'dark' || last === 'light' || last === 'both') state.scheme = parts.pop();
      const two = parts.slice(-2).join('-');
      if (DEVICES[two] && parts.length > 2) {
        state.device = two;
        parts = parts.slice(0, -2);
      } else if (DEVICES[parts[parts.length - 1]] && parts.length > 1) state.device = parts.pop();
      else if (parts[parts.length - 1] === 'ios') {
        parts.pop();
        state.device = 'iphone';
      }
    };
    if (parts[0] === 'shot') {
      state.shot = true;
      state.version = parts[1];
      parts = parts.slice(2);
      takeTail();
      const s = parts.join('-');
      if (screenIds().includes(s)) state.screen = s;
      if (byId[state.version]) state.round = byId[state.version].round;
      return;
    }
    if (parts[0] === 'matrix') {
      state.mode = 'matrix';
      const want = (parts[1] || '').toLowerCase();
      const hit = window.StudioScreens.find((x) => x.flow.toLowerCase() === want);
      if (hit) state.matrixFlow = hit.flow;
      return;
    }
    if (parts[0] === 'compare') {
      state.mode = 'compare';
      parts = parts.slice(1);
      takeTail();
      const s = parts.join('-');
      if (screenIds().includes(s)) state.screen = s;
      return;
    }
    if (byId[parts[0]]) {
      state.version = parts[0];
      state.round = byId[parts[0]].round;
      parts = parts.slice(1);
      takeTail();
      const s = parts.join('-');
      if (screenIds().includes(s)) {
        state.mode = 'focus';
        state.screen = s;
      } else state.mode = 'canvas';
    }
  }
  function writeHash() {
    const token =
      state.mode === 'compare'
        ? `compare-${state.screen}`
        : state.mode === 'matrix'
          ? `matrix-${state.matrixFlow.toLowerCase()}`
          : state.mode === 'focus'
            ? `${state.version}-${state.screen}`
            : state.version;
    if (location.hash.slice(1) !== token) history.replaceState(null, '', '#' + token);
  }

  // ---------- views ----------
  const $ = (sel, root = document) => root.querySelector(sel);
  function flows() {
    const out = [];
    for (const s of window.StudioScreens) {
      let f = out.find((x) => x.name === s.flow);
      if (!f) out.push((f = { name: s.flow, screens: [] }));
      f.screens.push(s);
    }
    return out;
  }
  const screenById = (id) => window.StudioScreens.find((s) => s.id === id) || window.StudioScreens[0];
  const current = () => {
    const list = roundVersions();
    return list.find((v) => v.id === state.version) || list[0] || versions[0];
  };

  function cell(v, s, caption) {
    return `<figure class="st-cell">${frames(v, s, { click: true })}<figcaption>${caption}</figcaption></figure>`;
  }

  function viewCanvas() {
    const v = current();
    return `<div class="st-canvas" style="zoom:${state.zoom}">
      ${flows()
        .filter((f) => !state.flow || f.name === state.flow)
        .map(
          (f) => `<section class="st-flow">
        <h2 class="st-flow-name">${esc(f.name)}<span>${f.screens.length} screens</span></h2>
        <div class="st-flow-row">${f.screens.map((s) => cell(v, s, `<b>${esc(s.title)}</b><span>${esc(s.id)}</span>`)).join('')}</div>
      </section>`,
        )
        .join('')}
    </div>`;
  }
  function viewCompare() {
    const s = screenById(state.screen);
    return `<div class="st-canvas st-compare" style="zoom:${state.zoom}">
      <div class="st-flow-row">${roundVersions()
        .map((v) => cell(v, s, `<b>${esc(v.name)}</b><span>${esc(v.tagline || '')}</span>`))
        .join('')}</div>
    </div>`;
  }
  function viewMatrix() {
    const f = flows().find((x) => x.name === state.matrixFlow) || flows()[0];
    return `<div class="st-canvas st-matrix" style="zoom:${state.zoom}">
      ${roundVersions()
        .map(
          (v) => `<section class="st-flow">
        <h2 class="st-flow-name">${esc(v.name)}<span>${esc(v.tagline || '')}</span></h2>
        <div class="st-flow-row">${f.screens.map((s) => cell(v, s, `<b>${esc(s.title)}</b>`)).join('')}</div>
      </section>`,
        )
        .join('')}
    </div>`;
  }
  function specimen(v) {
    const pal = (v.palette || []).map((p) => `<li><i style="background:${p.hex}"></i><b>${esc(p.name)}</b><code>${esc(p.hex)}</code></li>`).join('');
    const type = (v.type || [])
      .map((t) => `<li><span style="font-family:${t.css || t.family}">${esc(t.sample || t.family)}</span><small>${esc(t.role)} · ${esc(t.family)}</small></li>`)
      .join('');
    return `${pal ? `<h4>Palette</h4><ul class="st-pal">${pal}</ul>` : ''}${type ? `<h4>Type</h4><ul class="st-type">${type}</ul>` : ''}`;
  }
  function viewFocus() {
    const v = current();
    const s = screenById(state.screen);
    const all = window.StudioScreens;
    const i = all.indexOf(s);
    const prev = all[(i - 1 + all.length) % all.length];
    const next = all[(i + 1) % all.length];
    return `<div class="st-focus">
      <div class="st-focus-stage">
        <button class="st-nav st-prev" data-screen="${prev.id}" aria-label="Previous screen: ${esc(prev.title)}">${K().icon('chevron-left', 22)}</button>
        <div class="st-focus-frame" style="zoom:${Math.max(state.zoom, 0.7)}">${frames(v, s)}</div>
        <button class="st-nav st-next" data-screen="${next.id}" aria-label="Next screen: ${esc(next.title)}">${K().icon('chevron-right', 22)}</button>
      </div>
      <aside class="st-focus-notes">
        <p class="st-eyebrow">${esc(s.flow)} · ${i + 1} of ${all.length}</p>
        <h3>${esc(s.title)}</h3>
        <p class="st-brief">${esc(s.brief)}</p>
        ${v.notes && v.notes[s.id] ? `<h4>${esc(v.name)} notes</h4><p class="st-brief">${esc(v.notes[s.id])}</p>` : ''}
        <h4>${esc(v.name)}${v.thesis ? ' · thesis' : ''}</h4>
        <p class="st-brief">${esc(v.thesis || v.concept || v.tagline || '')}</p>
        ${v.tabs ? `<h4>Tabs</h4><p class="st-brief">${v.tabs.map((t) => esc(t.label)).join(' · ')}</p>` : ''}
        ${specimen(v)}
      </aside>
    </div>`;
  }
  function tweakPanel() {
    const v = current();
    const o = state.tweaks[v.id] || {};
    const rows = v.tweaks
      .map((t, n) => {
        const id = `tw-${v.id}-${n}`;
        const val = o[t.var] ?? t.value;
        if (t.type === 'color')
          return `<label class="st-tw" for="${id}"><span>${esc(t.label)}</span><input id="${id}" type="color" data-var="${t.var}" value="${esc(val)}"><code>${esc(val)}</code></label>`;
        if (t.type === 'select')
          return `<label class="st-tw" for="${id}"><span>${esc(t.label)}</span><select id="${id}" data-var="${t.var}">${t.options
            .map((op) => `<option value="${esc(op.value ?? op)}"${String(op.value ?? op) === String(val) ? ' selected' : ''}>${esc(op.label ?? op)}</option>`)
            .join('')}</select></label>`;
        return `<label class="st-tw" for="${id}"><span>${esc(t.label)}</span><input id="${id}" type="range" data-var="${t.var}" min="${t.min}" max="${t.max}" step="${t.step || 1}" value="${esc(val)}"><code>${esc(val)}${esc(t.unit || '')}</code></label>`;
      })
      .join('');
    return `<aside class="st-tweaks" ${state.tweaksOpen ? '' : 'hidden'}>
      <header><b>Tweak ${esc(v.name)}</b><button class="st-ghost" data-act="close-tweaks" aria-label="Close tweaks">${K().icon('x', 16)}</button></header>
      ${rows || '<p class="st-muted">This version declares no tweaks.</p>'}
      <footer><button class="st-ghost" data-act="reset-tweaks">Reset</button><button data-act="copy-tweaks">Copy CSS</button></footer>
    </aside>`;
  }
  function toolbar() {
    const v = current();
    const seg = (label, items, key) =>
      `<div class="st-seg" role="group" aria-label="${label}">${items
        .map(([val, text]) => `<button class="${String(state[key]) === String(val) ? 'st-on' : ''}" data-set="${key}" data-val="${val}">${text}</button>`)
        .join('')}</div>`;
    return `<header class="st-bar">
      <div class="st-brand"><span class="st-logo">${K().icon('gamepad', 18)}</span><b>Rommio</b><span>UI Studio</span></div>
      ${seg('Round', [[3, 'v3'], [2, 'v2'], [1, 'v1']], 'round')}
      <nav class="st-versions" aria-label="Versions">${roundVersions()
        .map(
          (x, n) => `<button class="st-vchip ${x.id === v.id && (state.mode === 'canvas' || state.mode === 'focus') ? 'st-on' : ''}" data-version="${x.id}" title="${esc(x.tagline || '')} (${n + 1})">
            <span class="st-sw">${(x.swatch || []).map((c) => `<i style="background:${c}"></i>`).join('')}</span>${esc(x.name)}</button>`,
        )
        .join('')}</nav>
      <div class="st-controls">
        ${seg('View', MODES.map((m) => [m, m[0].toUpperCase() + m.slice(1)]), 'mode')}
        <label class="st-pick" for="flow-pick" ${state.mode === 'matrix' ? '' : 'hidden'}><span class="st-sr">Flow</span>
          <select id="flow-pick">${flows().map((f) => `<option value="${esc(f.name)}" ${f.name === state.matrixFlow ? 'selected' : ''}>${esc(f.name)}</option>`).join('')}</select></label>
        <label class="st-pick" for="screen-pick" ${state.mode === 'compare' || state.mode === 'focus' ? '' : 'hidden'}><span class="st-sr">Screen</span>
          <select id="screen-pick">${flows()
            .map((f) => `<optgroup label="${esc(f.name)}">${f.screens.map((s) => `<option value="${s.id}" ${s.id === state.screen ? 'selected' : ''}>${esc(s.title)}</option>`).join('')}</optgroup>`)
            .join('')}</select></label>
        <label class="st-pick" for="device-pick"><span class="st-sr">Device</span>
          <select id="device-pick">${DEVICE_IDS.map((d) => `<option value="${d}" ${d === state.device ? 'selected' : ''}>${esc(DEVICES[d].label)}</option>`).join('')}</select></label>
        ${seg('Appearance', [['light', K().icon('sun', 14)], ['dark', K().icon('moon', 14)], ['both', K().icon('contrast', 14)]], 'scheme')}
        <label class="st-zoom" for="zoom"><span class="st-sr">Zoom</span>${K().icon('search', 14)}<input id="zoom" type="range" min="0.15" max="1.2" step="0.05" value="${state.zoom}"><code>${Math.round(state.zoom * 100)}%</code></label>
        <button class="st-ghost ${state.motion ? '' : 'st-on'}" data-act="motion" title="Pause motion (M)" aria-label="Pause motion">${K().icon(state.motion ? 'pause' : 'play', 16)}</button>
        <button class="st-ghost ${state.tweaksOpen ? 'st-on' : ''}" data-act="tweaks" title="Tweaks (T)" aria-label="Tweaks">${K().icon('sliders', 16)}</button>
      </div>
    </header>
    <div class="st-subbar">${
      state.mode === 'compare'
        ? `<b>${esc(screenById(state.screen).title)}</b><span>${esc(screenById(state.screen).brief)}</span>`
        : state.mode === 'matrix'
          ? `<b>${esc(state.matrixFlow)}</b><span>Every ${state.round === 1 ? 'v1 direction, side by side · round 1 predates light/dark and iPhone Duo' : state.round === 3 ? 'v3 Aurora exploration, side by side · each designs Home and Library (and a drill-in)' : 'v2 direction, side by side'}</span>`
          : `<b>${esc(v.name)}</b><span>${esc(v.thesis || v.tagline || '')}</span>`
    }</div>`;
  }

  function render() {
    const root = document.getElementById('studio');
    if (state.shot) {
      document.documentElement.classList.add('st-is-shot');
      const v = byId[state.version] || versions[0];
      const s = screenById(state.screen);
      root.innerHTML = `<div class="st-shot">${state.scheme === 'both' ? `<div class="st-pair">${frame(v, s, state.device, 'light')}${frame(v, s, state.device, 'dark')}</div>` : frame(v, s, state.device, state.scheme)}</div>`;
      applyScroll();
      return;
    }
    const stage = $('.st-stage');
    const scroll = { x: stage ? stage.scrollLeft : 0, y: stage ? stage.scrollTop : 0 };
    root.className = 'studio' + (state.motion ? '' : ' st-no-motion');
    const body = { compare: viewCompare, matrix: viewMatrix, focus: viewFocus }[state.mode]?.() ?? viewCanvas();
    root.innerHTML = `${toolbar()}<main class="st-stage">${body}</main>${tweakPanel()}`;
    const st = $('.st-stage');
    st.scrollLeft = scroll.x;
    st.scrollTop = scroll.y;
    applyScroll();
    writeHash();
    ['round', 'mode', 'version', 'screen', 'matrixFlow', 'device', 'scheme', 'zoom', 'tweaksOpen', 'motion'].forEach((k) => store.set(k, state[k]));
  }

  // Local review aid: scroll every screen's content by state.scrollY.
  function applyScroll() {
    if (!state.scrollY) return;
    document.querySelectorAll('.scr .sys-content, .scr .sys-col').forEach((el) => (el.scrollTop = state.scrollY));
  }

  // ---------- events ----------
  function bind() {
    const root = document.getElementById('studio');
    root.addEventListener('click', (e) => {
      const t = e.target.closest('button, .st-dev[role=button]');
      if (!t) return;
      if (t.dataset.version) {
        state.version = t.dataset.version;
        if (state.mode === 'compare' || state.mode === 'matrix') state.mode = 'canvas';
      } else if (t.dataset.set) {
        const key = t.dataset.set;
        state[key] = key === 'round' ? Number(t.dataset.val) : t.dataset.val;
        if (key === 'round') state.version = (roundVersions()[0] || {}).id;
      } else if (t.dataset.screen) state.screen = t.dataset.screen;
      else if (t.classList.contains('st-dev')) {
        state.version = t.dataset.v;
        state.screen = t.dataset.s;
        state.mode = 'focus';
      } else if (t.dataset.act === 'tweaks' || t.dataset.act === 'close-tweaks') state.tweaksOpen = !state.tweaksOpen;
      else if (t.dataset.act === 'motion') state.motion = !state.motion;
      else if (t.dataset.act === 'reset-tweaks') {
        delete state.tweaks[current().id];
        store.set('tweaks', state.tweaks);
        applyTweaks();
      } else if (t.dataset.act === 'copy-tweaks') {
        const css = tweakCSS() || '/* no overrides */';
        const fallback = () => {
          const panel = t.closest('.st-tweaks');
          let ta = panel.querySelector('textarea');
          if (!ta) {
            ta = document.createElement('textarea');
            ta.id = 'tweak-css';
            ta.rows = 5;
            ta.setAttribute('aria-label', 'Tweak CSS');
            panel.appendChild(ta);
          }
          ta.value = css;
          ta.select();
        };
        try {
          navigator.clipboard.writeText(css).then(() => (t.textContent = 'Copied'), fallback);
        } catch (err) {
          fallback();
        }
        return;
      } else return;
      render();
    });
    root.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && e.target.classList.contains('st-dev')) e.target.click();
    });
    root.addEventListener('input', (e) => {
      const t = e.target;
      if (t.id === 'zoom') {
        state.zoom = parseFloat(t.value);
        document.querySelectorAll('.st-canvas').forEach((c) => (c.style.zoom = state.zoom));
        const f = $('.st-focus-frame');
        if (f) f.style.zoom = Math.max(state.zoom, 0.7);
        t.nextElementSibling.textContent = Math.round(state.zoom * 100) + '%';
        store.set('zoom', state.zoom);
        return;
      }
      if (t.dataset.var) {
        const v = current();
        state.tweaks[v.id] = state.tweaks[v.id] || {};
        state.tweaks[v.id][t.dataset.var] = t.value;
        const code = t.parentElement.querySelector('code');
        const def = v.tweaks.find((x) => x.var === t.dataset.var);
        if (code) code.textContent = t.value + (def && def.unit && t.type !== 'color' ? def.unit : '');
        store.set('tweaks', state.tweaks);
        applyTweaks();
      }
    });
    root.addEventListener('change', (e) => {
      const id = e.target.id;
      if (id === 'screen-pick') state.screen = e.target.value;
      else if (id === 'flow-pick') state.matrixFlow = e.target.value;
      else if (id === 'device-pick') state.device = e.target.value;
      else return;
      render();
    });
    document.addEventListener('keydown', (e) => {
      if (state.shot || e.metaKey || e.ctrlKey || e.altKey) return;
      if (['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName) && document.activeElement.type !== 'range') return;
      const all = window.StudioScreens;
      const i = all.findIndex((s) => s.id === state.screen);
      const list = roundVersions();
      if (/^[1-9]$/.test(e.key) && list[+e.key - 1]) {
        state.version = list[+e.key - 1].id;
        if (state.mode === 'compare' || state.mode === 'matrix') state.mode = 'focus';
      } else if (e.key === 'ArrowRight' && state.mode !== 'canvas') state.screen = all[(i + 1) % all.length].id;
      else if (e.key === 'ArrowLeft' && state.mode !== 'canvas') state.screen = all[(i - 1 + all.length) % all.length].id;
      else if (e.key === 'c') state.mode = 'canvas';
      else if (e.key === 'v') state.mode = 'compare';
      else if (e.key === 'x') state.mode = 'matrix';
      else if (e.key === 'f') state.mode = 'focus';
      else if (e.key === 't') state.tweaksOpen = !state.tweaksOpen;
      else if (e.key === 'd') state.device = DEVICE_IDS[(DEVICE_IDS.indexOf(state.device) + 1) % DEVICE_IDS.length];
      else if (e.key === 'a') state.scheme = SCHEMES[(SCHEMES.indexOf(state.scheme) + 1) % SCHEMES.length];
      else if (e.key === 'm') state.motion = !state.motion;
      else return;
      e.preventDefault();
      render();
    });
    window.addEventListener('hashchange', () => {
      parseHash();
      render();
    });
  }

  function boot() {
    if (!versions.length) {
      document.getElementById('studio').textContent = 'No versions registered.';
      return;
    }
    parseHash();
    if (!roundVersions().length) state.round = versions[0].round;
    if (!byId[state.version] || byId[state.version].round !== state.round) state.version = (roundVersions()[0] || versions[0]).id;
    if (!MODES.includes(state.mode)) state.mode = 'compare';
    if (!DEVICES[state.device]) state.device = 'iphone';
    if (!SCHEMES.includes(state.scheme)) state.scheme = 'light';
    loadFonts();
    applyTweaks();
    render();
    bind();
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => render());
  }

  window.Studio = { defineVersion, versions, byId, DEVICES, SAFE, render, boot, state, frame, screenById };
  document.addEventListener('DOMContentLoaded', boot);
})();
