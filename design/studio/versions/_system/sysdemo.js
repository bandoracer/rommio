/*
 * SYSTEM DEMO — round 0, hidden from the toolbar. A plain, stock rendering of
 * a few screens that exercises every piece of c.sys so the native chrome can
 * be checked on each device and appearance, and so version authors have a
 * working reference:
 *   #shot-sysdemo-home · -library-duo-open · -game-duo-closed-dark · -pause · -settings-android
 */
(function () {
  const k = window.StudioKit;
  const esc = k.esc;

  const row = (g) => `<li class="sd-row"><img src="${k.cover(g.id, 'box')}" alt=""><span><b>${esc(g.title)}</b><small>${esc(k.platformOf(g).short)} · ${esc(k.ago(g.played))}</small></span></li>`;

  // Stage wireframe: shows k.stage() geometry (screens, rails, clusters) for any device.
  const box = (r, cls, label = '') => `<div class="sd-box ${cls}" style="left:${r.x}px;top:${r.y}px;width:${r.w}px;height:${r.h}px">${label}</div>`;
  const disc = (p, label) => `<div class="sd-disc" style="left:${p.cx - p.size / 2}px;top:${p.cy - p.size / 2}px;width:${p.size}px;height:${p.size}px">${label}</div>`;
  const stageDemo = (c, st, screensHtml) => `<div class="sd-stage">
      ${st.rails.map((r) => box(r, 'rail')).join('')}
      ${screensHtml}
      ${disc(st.dpad, 'D-pad')}${disc(st.face, 'Y X B A')}
      ${box(st.l, 'sh', 'L')}${box(st.r, 'sh', 'R')}
      ${box({ x: st.tertiary.cx - 120, y: st.tertiary.cy - st.tertiary.h / 2, w: 240, h: st.tertiary.h }, 'tert', 'Select · Menu · Start')}
      <div class="sd-toast" style="left:${st.toast.cx}px;top:${st.toast.cy}px">Automatic resume updated</div>
    </div>`;

  const screens = {
    player: (c) => {
      const st = k.stage(c, { aspect: k.aspect('smetroid') });
      return stageDemo(c, st, `<img class="sd-screen" src="${k.frame('smetroid', 'a')}" alt="" style="left:${st.game.x}px;top:${st.game.y}px;width:${st.game.w}px;height:${st.game.h}px">`);
    },
    'player-portrait': (c) => {
      const st = k.stage(c, { aspect: k.aspect('linksawak') });
      return stageDemo(c, st, `<img class="sd-screen" src="${k.frame('linksawak', 'a')}" alt="" style="left:${st.game.x}px;top:${st.game.y}px;width:${st.game.w}px;height:${st.game.h}px">`);
    },
    dual: (c) => {
      const st = k.stage(c, { dual: true });
      const d = k.dual('pkblack', 'a');
      return stageDemo(
        c,
        st,
        `<img class="sd-screen" src="${d.top}" alt="" style="left:${st.top.x}px;top:${st.top.y}px;width:${st.top.w}px;height:${st.top.h}px"><img class="sd-screen" src="${d.bottom}" alt="" style="left:${st.touch.x}px;top:${st.touch.y}px;width:${st.touch.w}px;height:${st.touch.h}px">`,
      );
    },
    home: (c) =>
      c.sys.shell({
        tab: 'home',
        title: 'Home',
        actions: [{ avatar: 'R', label: 'Account' }],
        body: `<div class="sd-pad"><div class="sd-hero"><img src="${k.frame('smetroid', 'b')}" alt=""></div>
          <div class="sd-actions">${c.sys.button('Resume', { style: 'prominent', icon: 'play', size: 'lg' })}${c.sys.button('Details', { style: 'glass', size: 'lg' })}</div></div>
          <ul class="sd-list">${k.recentlyPlayed(6).map(row).join('')}</ul>`,
      }),
    library: (c) => {
      const plats = `<ul class="sd-list">${k
        .tier('touch')
        .slice(0, 10)
        .map((p) => `<li class="sd-row${p.id === 'snes' ? ' sel' : ''}"><i class="sd-sw" style="background:${p.color}"></i><span><b>${esc(p.name)}</b><small>${p.games} games</small></span></li>`)
        .join('')}</ul>`;
      const games = `<div class="sd-grid">${k
        .gamesOn('snes')
        .map((g) => `<figure><img src="${k.cover(g.id)}" alt=""><figcaption>${esc(g.title)}</figcaption></figure>`)
        .join('')}</div>`;
      return c.sys.shell({
        tab: 'library',
        title: 'Library',
        actions: [{ icon: 'sort', label: 'Sort' }, { icon: 'more', label: 'More' }],
        body: plats,
        split: { primary: plats, primaryTitle: 'Library', secondary: games, secondaryTitle: 'Super Nintendo' },
      });
    },
    game: (c) =>
      c.sys.shell({
        tab: 'library',
        title: 'Super Metroid',
        titleMode: 'inline',
        back: 'Super Nintendo',
        primary: { icon: 'play', label: 'Resume' },
        actions: [{ icon: 'heart', label: 'Favourite' }, { icon: 'more', label: 'More' }],
        heroUnderBar: true,
        edge: false,
        body: `<img class="sd-bleed" src="${k.cover('smetroid', 'wide')}" alt=""><div class="sd-pad"><h2>Super Metroid</h2><p>${esc(k.game('smetroid').blurb)}</p></div>`,
      }),
    search: (c) =>
      c.sys.shell({
        tab: 'search',
        title: 'Search',
        search: { value: 'metroid', focused: true },
        body: `<ul class="sd-list">${['smetroid', 'metfusion'].map((id) => row(k.game(id))).join('')}</ul>`,
      }),
    settings: (c) =>
      c.sys.shell({
        tab: 'home',
        title: 'Settings',
        back: true,
        body: c.sys.list([
          { rows: [{ icon: 'user', title: 'ryan', subtitle: 'romm.homelab.dev · RomM 5.2.0', iconBg: '#5e5ce6' }] },
          {
            header: 'Sync',
            rows: [
              { icon: 'save', title: 'Game-save data', accessory: 'switch-on', iconBg: '#34c759' },
              { icon: 'history', title: 'Resume states', accessory: 'switch-on', iconBg: '#0a84ff' },
              { icon: 'signal', title: 'Use cellular data', accessory: 'switch-off', iconBg: '#ff9f0a' },
            ],
            footer: 'Game-save data syncs after every session.',
          },
          { header: 'Storage', rows: [{ icon: 'storage', title: 'On this iPhone', value: '19.6 GB', iconBg: '#8e8e93' }] },
        ]),
      }),
    controls: (c) =>
      c.sys.shell({
        title: 'Controls',
        back: true,
        tab: 'home',
        body: `<div class="sd-pad">${c.sys.segmented(['Automatic', 'DualSense', 'Touch'], 0)}</div>${c.sys.list([{ rows: [{ title: 'Left-handed', accessory: 'switch-off' }, { title: 'Haptics', accessory: 'switch-on' }] }])}`,
      }),
    pause: (c) =>
      c.sys.sheet({
        detent: 'medium',
        title: 'Paused',
        done: { label: 'Resume', icon: 'play' },
        backdrop: (b) => `<img class="sd-game" src="${k.frame('smetroid', 'a')}" alt="" style="width:${b.w}px;height:${b.h}px">`,
        body: c.sys.list([{ rows: [{ icon: 'save', title: 'Save state' }, { icon: 'layers', title: 'Load state' }, { icon: 'exit', title: 'Leave game', accessory: '' }] }]),
      }),
    conflict: (c) =>
      c.sys.sheet({
        detent: 'large',
        title: 'EarthBound',
        backdrop: () => screens.home(c),
        body: `<div class="sd-pad"><p>This iPhone and Pixel 9 both played since the last sync.</p>${c.sys.button('Continue from This iPhone', { style: 'prominent', size: 'lg' })}</div>`,
      }),
  };

  Studio.defineVersion({
    id: 'sysdemo',
    round: 0,
    name: 'System',
    tagline: 'Stock chrome reference',
    tabs: [
      { id: 'home', label: 'Home', icon: 'home' },
      { id: 'library', label: 'Library', icon: 'library' },
      { id: 'collections', label: 'Collections', icon: 'collections' },
      { id: 'downloads', label: 'Downloads', icon: 'downloads', badge: '3' },
      { id: 'search', label: 'Search', icon: 'search', role: 'search' },
    ],
    screens,
  });
})();
