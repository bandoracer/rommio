/*
 * Shared helpers for version renderers. Only data access and formatting live
 * here — anything visual belongs to a version.
 */
(function () {
  const D = () => window.StudioData;

  const EXTRA_ICONS = {
    'cloud-check': '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/><path d="m9 14 2 2 4-4"/>',
    dpad: '<path d="M9 3h6v6h6v6h-6v6H9v-6H3V9h6z"/>',
    rommio: '<rect x="3" y="5" width="18" height="14" rx="4"/><path d="M8 12h4M10 10v4"/><circle cx="15.5" cy="11" r="1"/><circle cx="17.5" cy="13" r="1"/>',
  };

  const Kit = {
    esc(s) {
      return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
    },

    // --- data ---
    game(id) {
      return D().games.find((g) => g.id === id);
    },
    platform(id) {
      return D().platforms.find((p) => p.id === id);
    },
    platformOf(gameOrId) {
      const g = typeof gameOrId === 'string' ? Kit.game(gameOrId) : gameOrId;
      return Kit.platform(g.platform);
    },
    gamesOn(pid) {
      return D().games.filter((g) => g.platform === pid);
    },
    installed() {
      return D().games.filter((g) => g.installed);
    },
    recentlyPlayed(n = 6) {
      return D().games.filter((g) => g.played != null).sort((a, b) => a.played - b.played).slice(0, n);
    },
    collection(id) {
      return D().collections.find((c) => c.id === id);
    },
    collectionGames(id) {
      return Kit.collection(id).games.map(Kit.game);
    },
    tier(t) {
      return D().platforms.filter((p) => p.tier === t);
    },
    totals() {
      const p = D().platforms;
      return {
        platforms: p.length,
        games: p.reduce((n, x) => n + x.games, 0),
        installed: p.reduce((n, x) => n + x.installed, 0),
      };
    },

    // --- formatting ---
    ago(min) {
      if (min == null) return 'Never';
      if (min < 1) return 'Just now';
      if (min < 60) return `${Math.round(min)} min ago`;
      if (min < 60 * 24) return `${Math.round(min / 60)} h ago`;
      if (min < 60 * 48) return 'Yesterday';
      return `${Math.round(min / 1440)} days ago`;
    },
    agoShort(min) {
      if (min == null) return '—';
      if (min < 1) return 'now';
      if (min < 60) return `${Math.round(min)}m`;
      if (min < 60 * 24) return `${Math.round(min / 60)}h`;
      return `${Math.round(min / 1440)}d`;
    },
    size(mb) {
      if (mb == null) return '—';
      if (mb < 1) return `${Math.round(mb * 1024)} KB`;
      if (mb < 1000) return `${mb % 1 ? mb.toFixed(1) : mb} MB`;
      return `${(mb / 1000).toFixed(1)} GB`;
    },
    hours(h) {
      if (!h) return 'Not played';
      const hh = Math.floor(h);
      const mm = Math.round((h - hh) * 60);
      return hh ? `${hh} h ${mm} m` : `${mm} m`;
    },
    pct(x) {
      return `${Math.round(x * 100)}%`;
    },
    num(n) {
      return n.toLocaleString('en-US');
    },
    // User-facing sync wording (docs/save-sync-spec.md §11).
    syncLabel(state) {
      return (
        {
          synced: 'Synced',
          pending: 'Waiting to sync',
          waiting: 'Waiting to sync',
          syncing: 'Syncing',
          conflict: 'Conflict',
          local: 'Local only',
          signin: 'Sign-in needed',
          none: 'Not downloaded',
        }[state] || state
      );
    },
    tierLabel(t) {
      return { touch: 'Touch', controller: 'Controller', unsupported: 'Browse only' }[t] || t;
    },
    // Colour helpers for versions that tint UI by platform or game.
    rgb(hex) {
      const h = hex.replace('#', '');
      const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
      return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    },
    luminance(hex) {
      const lin = (v) => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
      const [r, g, b] = Kit.rgb(hex).map(lin);
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    },
    // Readable text colour on a background.
    onColor(hex, dark = '#111111', light = '#ffffff') {
      return Kit.luminance(hex) > 0.36 ? dark : light;
    },
    // Mix toward white (amount > 0) or black (amount < 0), e.g. shade('#2b2b2b', 0.4).
    shade(hex, amount) {
      const t = amount < 0 ? 0 : 255;
      const a = Math.abs(amount);
      return '#' + Kit.rgb(hex).map((v) => Math.round(v + (t - v) * a).toString(16).padStart(2, '0')).join('');
    },
    // Classic face-button colours per console (the "Console colours" setting).
    consoleColors(pid) {
      const sfc = { Y: '#2fa84f', X: '#2e6fd8', B: '#f2c230', A: '#d8342f' };
      const ps = { square: '#e889c9', triangle: '#39c9a6', cross: '#7fa8e6', circle: '#ef5d5d' };
      return (
        {
          snes: sfc,
          nds: sfc,
          '3ds': sfc,
          psx: ps,
          psp: ps,
          n64: { A: '#2f6fe0', B: '#2fa84f', C: '#f2c230' },
          genesis: { A: '#2b2b2b', B: '#2b2b2b', C: '#2b2b2b' },
          nes: { B: '#c9302c', A: '#c9302c' },
          gb: { B: '#9b2257', A: '#9b2257' },
          gbc: { B: '#9b2257', A: '#9b2257' },
          gba: { B: '#5a55c7', A: '#5a55c7' },
        }[pid] || sfc
      );
    },
    // Player stage geometry. On iPhone Duo the controls never overlap the game:
    // the game shrinks to leave dedicated room for them, and the control
    // clusters sit low, where thumbs rest. Every rect is in screen points.
    //
    //   const s = k.stage(c, { aspect: k.aspect('smetroid') });   // player (landscape)
    //   const s = k.stage(c, { aspect: 10 / 9 });                  // player-portrait ({ shoulders: true } if it has L/R)
    //   const s = k.stage(c, { dual: true });                      // DS / 3DS
    //
    // Returns { game | top + touch, dpad, face, l, r, tertiary, toast, rails, size }:
    //   game/top/touch  {x, y, w, h}   screens (never covered)
    //   dpad, face      {cx, cy, size} cluster centres and diameter
    //   l, r            {x, y, w, h}   shoulder buttons
    //   tertiary        {cx, cy, w, h} row for Select · Menu · Start (clear of the game)
    //   toast           {cx, cy}       transient notices, in free space above the game
    //   rails           [{x, y, w, h}, {x, y, w, h}] the areas reserved for controls
    stage(c, o = {}) {
      const W = c.w;
      const H = c.h;
      const s = c.safe;
      const dev = (c.device && c.device.id) || 'iphone';
      const pick = (open, closed, phone) => (dev === 'duo-open' ? open : dev === 'duo-closed' ? closed : phone);
      const gap = pick(14, 10, 10);
      // Portrait on the closed Duo: the outer camera sits top-centre, so screens start lower.
      const camPad = dev === 'duo-closed' && H > W ? 10 : 0;
      const rect = (x, y, w, h) => ({ x: Math.round(x), y: Math.round(y), w: Math.round(w), h: Math.round(h) });

      if (o.dual) {
        // DS / 3DS: screens stacked (top above the fold when open), controls in a band at the
        // bottom. The touch screen stops above the clusters (and above the shoulders when they
        // would share its columns), so nothing is ever drawn over either screen.
        const S = pick(148, 124, 112); // closed: big enough for 44pt face buttons
        const tertH = 44;
        const sh = { w: S * 0.8, h: 40 };
        const clusterTop = H - s.bottom - gap - S;
        const cy = clusterTop + S / 2;
        const lcx = s.left + gap + S / 2;
        const rcx = W - s.right - gap - S / 2;
        const shoulderTop = clusterTop - gap - sh.h;
        const foldY = c.device && c.device.fold && c.device.fold.y;
        const fit = (limitBottom) => {
          if (foldY) {
            const topH = foldY - s.top - 2 * gap;
            const topW = Math.min(W - 2 * gap, topH * (4 / 3));
            const top = rect((W - topW) / 2, foldY - gap - topW * 0.75, topW, topW * 0.75);
            const th = limitBottom - (foldY + gap);
            const tw = Math.min(W - 2 * gap, th * (4 / 3));
            return { top, touch: rect((W - tw) / 2, foldY + gap, tw, tw * 0.75) };
          }
          const each = (limitBottom - s.top - camPad - 2 * gap) / 2;
          const w = Math.min(W - 2 * gap, each * (4 / 3));
          const top = rect((W - w) / 2, s.top + camPad + gap, w, w * 0.75);
          return { top, touch: rect((W - w) / 2, top.y + top.h + gap, w, w * 0.75) };
        };
        let { top, touch } = fit(clusterTop - gap);
        if (lcx + sh.w / 2 > touch.x) ({ top, touch } = fit(shoulderTop - gap));
        return {
          size: S,
          top,
          touch,
          dpad: { cx: lcx, cy, size: S },
          face: { cx: rcx, cy, size: S },
          l: rect(lcx - sh.w / 2, shoulderTop, sh.w, sh.h),
          r: rect(rcx - sh.w / 2, shoulderTop, sh.w, sh.h),
          tertiary: { cx: W / 2, cy: H - s.bottom - gap - tertH / 2, w: W - 2 * (S + 2 * gap), h: tertH },
          // Notices sit in the free space under the touch screen, between the clusters.
          toast: { cx: W / 2, cy: Math.round((touch.y + touch.h + (H - s.bottom - gap - tertH)) / 2) },
          rails: [
            rect(0, shoulderTop - gap, touch.x, H - shoulderTop + gap),
            rect(touch.x + touch.w, shoulderTop - gap, W - touch.x - touch.w, H - shoulderTop + gap),
            rect(touch.x, touch.y + touch.h, touch.w, H - touch.y - touch.h),
          ],
        };
      }

      const aspect = o.aspect || 4 / 3;
      if (W > H) {
        // Landscape: side rails hold the controls; the game is centred between them.
        const S = pick(176, 124, 120);
        const railL = s.left + S + 2 * gap;
        const railR = s.right + S + 2 * gap;
        const tertH = 44;
        const availW = W - railL - railR;
        const availH = H - s.top - s.bottom - 2 * gap;
        let gw = availW;
        let gh = gw / aspect;
        if (gh > availH) {
          gh = availH;
          gw = gh * aspect;
        }
        const slack = H - s.top - s.bottom - gh;
        const game = rect(railL + (availW - gw) / 2, s.top + Math.max(gap, slack * 0.3), gw, gh);
        const cy = Math.min(H * pick(0.7, 0.68, 0.64), H - s.bottom - gap - S / 2);
        const sh = { w: S * 0.9, h: pick(46, 38, 38) };
        const lcx = s.left + gap + S / 2;
        const rcx = W - s.right - gap - S / 2;
        const tertY = game.y + game.h + gap + tertH / 2 <= H - s.bottom - tertH / 2 ? H - s.bottom - gap - tertH / 2 : null;
        return {
          size: S,
          game,
          dpad: { cx: lcx, cy, size: S },
          face: { cx: rcx, cy, size: S },
          l: rect(lcx - sh.w / 2, cy - S / 2 - gap - sh.h, sh.w, sh.h),
          r: rect(rcx - sh.w / 2, cy - S / 2 - gap - sh.h, sh.w, sh.h),
          // Under the game when there is room, otherwise at the foot of the rails.
          tertiary: tertY ? { cx: W / 2, cy: tertY, w: game.w, h: tertH } : { cx: W / 2, cy: H - s.bottom - tertH / 2, w: W, h: tertH },
          toast: { cx: game.x + game.w / 2, cy: Math.max(s.top + 20, game.y / 2) },
          rails: [rect(0, 0, railL, H), rect(W - railR, 0, railR, H)],
        };
      }

      // Portrait: game at the top, controls in the band below it, biased to the bottom.
      const S = pick(176, 128, 128);
      const tertH = 44;
      const gw = W - 2 * gap - s.left - s.right;
      let gh = gw / aspect;
      // Pass { shoulders: true } for portrait games with L/R so the game leaves room for them.
      const shoulderRoom = o.shoulders ? 40 + gap : 0;
      const maxGh = H - s.top - camPad - s.bottom - S - tertH - shoulderRoom - 5 * gap;
      let gW = gw;
      if (gh > maxGh) {
        gh = maxGh;
        gW = gh * aspect;
      }
      const game = rect((W - gW) / 2, s.top + camPad + gap, gW, gh);
      const tertCy = H - s.bottom - gap - tertH / 2;
      const cy = tertCy - tertH / 2 - gap - S / 2;
      const sh = { w: S * 0.8, h: 40 };
      return {
        size: S,
        game,
        dpad: { cx: W * 0.26, cy, size: S },
        face: { cx: W * 0.74, cy, size: S },
        // Shoulders sit just above the clusters, never on them.
        l: rect(W * 0.26 - sh.w / 2, cy - S / 2 - gap - sh.h, sh.w, sh.h),
        r: rect(W * 0.74 - sh.w / 2, cy - S / 2 - gap - sh.h, sh.w, sh.h),
        tertiary: { cx: W / 2, cy: tertCy, w: W * 0.6, h: tertH },
        toast: { cx: W / 2, cy: game.y + game.h + gap + 20 }, // between the shoulder buttons
        rails: [rect(0, game.y + game.h, W, H - game.y - game.h)],
      };
    },
    range(n) {
      return Array.from({ length: n }, (_, i) => i);
    },

    // --- art ---
    cover(id, kind) {
      return window.StudioArt.cover(id, kind);
    },
    frame(id, variant) {
      return window.StudioArt.frame(id, variant);
    },
    dual(id, variant) {
      return window.StudioArt.dual(id, variant);
    },
    aspect(id) {
      return window.StudioArt.aspect(id);
    },

    // --- icons ---
    // icon('play') · icon('play', 18) · icon('play', 18, 'cls') · icon('play', { size: 18, cls: 'x', stroke: 1.5 })
    icon(name, opts, cls) {
      const o = typeof opts === 'number' ? { size: opts, cls } : opts || {};
      const size = o.size || 20;
      const body = EXTRA_ICONS[name] || (window.StudioIcons || {})[name];
      if (!body) return `<span class="i-missing" title="missing icon ${Kit.esc(name)}">?</span>`;
      const sw = o.stroke ? ` stroke-width="${o.stroke}"` : '';
      return `<svg class="i ${o.cls || ''}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor"${sw} stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
    },
    iconNames() {
      return [...Object.keys(window.StudioIcons || {}), ...Object.keys(EXTRA_ICONS)];
    },
  };

  window.StudioKit = Kit;
})();
