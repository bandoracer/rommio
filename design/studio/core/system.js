/*
 * Native system chrome for v2 mockups.
 *
 * Every v2 screen gets `c.sys`, bound to its device, platform and appearance.
 * Navigation is always the platform's own:
 *
 *   iPhone          iOS 26 Liquid Glass: glass toolbar buttons, large titles,
 *                   floating tab bar with a separate Search tab, sheets.
 *   iPhone Duo      iOS 27.1 vertical bars: toolbar items and tabs share a
 *                   vertical bar on the trailing edge (Back/Close at the top,
 *                   then the prominent action, then everything else; tabs near
 *                   the bottom). The status bar moves into it. Open, the inner
 *                   display is ONE continuous canvas: the fold is a crease, not
 *                   a partition. List/detail may use `split` (a narrow list beside
 *                   a wide detail, not two halves); sheets float over the canvas.
 *   Android         Material 3: top app bar, navigation bar with the pill
 *                   indicator, extended FAB for the primary action, bottom sheets.
 *
 * Versions decide what goes *inside* the chrome (content, IA, components) and
 * tint it with CSS variables on their scope:
 *   --sys-tint        accent for selected tabs, prominent buttons, switches
 *   --sys-bg          page background (used for scroll-edge effects)
 *   --sys-title-font  font for large titles (defaults to the system font)
 *
 * API (all return HTML strings):
 *   c.sys.shell(opts)         whole screen: bars + content (see below)
 *   c.sys.sheet(opts)         modal sheet / side pane over a backdrop
 *   c.sys.list(sections)      inset-grouped list (iOS) / list (Android)
 *   c.sys.toggle(on, label)   switch
 *   c.sys.segmented(items, i) segmented control
 *   c.sys.search(opts)        search field
 *   c.sys.button(label, o)    system button: { style: 'prominent'|'glass'|'plain', icon, size: 'sm'|'md'|'lg' }
 *   c.sys.menu(items)         context menu popover
 *   c.sys.layout()            { top, bottom, left, right, width, height } of the content area
 *
 * shell(opts):
 *   tab          active tab id (tabs are declared on the version: `tabs: [...]`)
 *   tabBar       false to hide tabs (full-screen flows such as onboarding)
 *   title        navigation title
 *   titleMode    'large' (default) | 'inline' | 'none'
 *   subtitle     small line under a large title
 *   back         true, or a label for the back button's accessible name
 *   close        true for a Close (xmark) button instead of Back
 *   actions      [{ icon, label, badge, avatar }] trailing toolbar items
 *   primary      { icon, label } the prominent action (Play, Download, Done…)
 *   search       { placeholder, value, focused } a search field in the bar
 *   bottomBar    [{ icon, label }] bottom toolbar (iPhone) / vertical bar (Duo)
 *   body         content HTML (scrolls under the bars)
 *   split        { primary, secondary, primaryTitle, secondaryTitle, primaryWidth, divider }
 *                list/detail when regular width (Duo open); ignored when compact.
 *                The list is narrow (default 340pt, never snapped to the fold) and
 *                shares the page background; divider: true adds a hairline.
 *   edge         false to drop the top scroll-edge effect (full-bleed art)
 *   heroUnderBar true when body starts with art that should sit under the top bar
 */
(function () {
  const K = () => window.StudioKit;
  const esc = (s) => K().esc(s);
  const ic = (n, s = 20, cls) => K().icon(n, s, cls);

  const TAB_ICON = 26;

  function create(ctx) {
    const d = ctx.device;
    const ios = d.os === 'ios';
    const vbar = !!d.vbar && ctx.kind === 'app';
    const regular = ctx.size === 'regular';
    const tabs = (ctx.version && ctx.version.tabs) || [];

    // ---------- geometry ----------
    const VBAR = 72;
    function layout(opts = {}) {
      const s = ctx.safe;
      const tabBar = opts.tabBar !== false && tabs.length > 0;
      if (!ios) {
        const large = opts.titleMode === 'large' || opts.titleMode === undefined;
        const top = s.top + (opts.titleMode === 'none' && !opts.back && !opts.actions ? 0 : large ? 112 + (opts.subtitle ? 22 : 0) : 64);
        const bottom = s.bottom + (tabBar ? 80 : 0);
        return { top, bottom, left: s.left, right: s.right, width: ctx.w - s.left - s.right, height: ctx.h - top - bottom, vbar: false };
      }
      if (vbar) {
        const right = VBAR;
        return { top: s.top, bottom: s.bottom, left: s.left, right, width: ctx.w - s.left - right, height: ctx.h - s.top - s.bottom, vbar: true };
      }
      const barRow = opts.titleMode === 'none' && !opts.back && !opts.close && !(opts.actions || []).length && !opts.primary ? 0 : 52;
      const top = s.top + barRow;
      const bottom = s.bottom + (tabBar ? 74 : opts.bottomBar ? 64 : 0);
      return { top, bottom, left: s.left, right: s.right, width: ctx.w - s.left - s.right, height: ctx.h - top - bottom, vbar: false };
    }

    // ---------- small parts ----------
    const glassBtn = (item, extra = '') => {
      if (item.avatar)
        return `<button class="sys-avatar" aria-label="${esc(item.label || 'Account')}"${extra}>${esc(item.avatar)}${item.badge ? '<i class="sys-dot"></i>' : ''}</button>`;
      return `<button class="sys-gbtn" aria-label="${esc(item.label)}"${extra}>${ic(item.icon, 20)}${item.badge ? `<i class="sys-badge">${esc(item.badge === true ? '' : item.badge)}</i>` : ''}</button>`;
    };
    const backItem = (opts) =>
      opts.close
        ? { icon: 'x', label: 'Close' }
        : opts.back
          ? { icon: ios ? 'chevron-left' : 'arrow-left', label: typeof opts.back === 'string' ? `Back to ${opts.back}` : 'Back' }
          : null;

    function iosTabBar(active) {
      const main = tabs.filter((t) => t.role !== 'search');
      const search = tabs.find((t) => t.role === 'search');
      return `<nav class="sys-tabbar" aria-label="Tabs">
        <div class="sys-glass sys-tabs">${main
          .map(
            (t) => `<button class="sys-tab${t.id === active ? ' on' : ''}" aria-label="${esc(t.label)}"${t.id === active ? ' aria-current="page"' : ''}>${ic(t.icon, TAB_ICON)}<span>${esc(t.label)}</span>${t.badge ? `<i class="sys-badge">${esc(t.badge)}</i>` : ''}</button>`,
          )
          .join('')}</div>
        ${search ? `<button class="sys-glass sys-searchtab${search.id === active ? ' on' : ''}" aria-label="${esc(search.label)}">${ic(search.icon || 'search', 24)}</button>` : ''}
      </nav>`;
    }

    function vbarHTML(opts) {
      const back = backItem(opts);
      const top = [back ? glassBtn(back) : '', opts.primary ? `<button class="sys-gbtn sys-prominent" aria-label="${esc(opts.primary.label)}">${ic(opts.primary.icon, 20)}</button>` : ''].join('');
      const rest = [...(opts.actions || []), ...(opts.bottomBar || [])];
      const tabBar = opts.tabBar !== false && tabs.length > 0;
      const main = tabs.filter((t) => t.role !== 'search');
      const search = tabs.find((t) => t.role === 'search');
      return `<aside class="sys-vbar" aria-label="Navigation">
        <div class="sys-vgroup">
          ${top ? `<div class="sys-glass sys-vcap">${top}</div>` : ''}
          ${rest.length ? `<div class="sys-glass sys-vcap">${rest.map((x) => glassBtn(x)).join('')}</div>` : ''}
        </div>
        ${
          tabBar
            ? `<div class="sys-vgroup sys-vtabs">
          <div class="sys-glass sys-vcap tabs">${main
            .map(
              (t) => `<button class="sys-vtab${t.id === opts.tab ? ' on' : ''}" aria-label="${esc(t.label)}"${t.id === opts.tab ? ' aria-current="page"' : ''}>${ic(t.icon, 22)}${t.badge ? `<i class="sys-badge">${esc(t.badge)}</i>` : ''}</button>`,
            )
            .join('')}</div>
          ${search ? `<button class="sys-glass sys-vsearch${search.id === opts.tab ? ' on' : ''}" aria-label="${esc(search.label)}">${ic('search', 21)}</button>` : ''}
        </div>`
            : ''
        }
      </aside>`;
    }

    function iosNavBar(opts) {
      const back = backItem(opts);
      const acts = opts.actions || [];
      const inline = opts.titleMode === 'inline' && opts.title;
      const trailing = acts.length ? `<div class="sys-glass sys-cap">${acts.map((x) => glassBtn(x)).join('')}</div>` : '';
      const primary = opts.primary ? `<button class="sys-gbtn sys-prominent wide" aria-label="${esc(opts.primary.label)}">${ic(opts.primary.icon, 18)}<span>${esc(opts.primary.label)}</span></button>` : '';
      if (!back && !acts.length && !opts.primary && !inline) return '';
      return `<header class="sys-navbar">
        <div class="sys-nav-lead">${back ? `<div class="sys-glass sys-cap">${glassBtn(back)}</div>` : ''}</div>
        <div class="sys-nav-title">${inline ? `<b>${esc(opts.title)}</b>${opts.subtitle ? `<span>${esc(opts.subtitle)}</span>` : ''}` : ''}</div>
        <div class="sys-nav-trail">${trailing}${primary}</div>
      </header>`;
    }

    function iosBottomBar(items) {
      return `<div class="sys-bottombar">${items.map((x) => `<div class="sys-glass sys-cap">${glassBtn(x)}</div>`).join('')}</div>`;
    }

    // ---------- Material 3 ----------
    function mdTopBar(opts) {
      const back = backItem(opts);
      const large = (opts.titleMode === 'large' || opts.titleMode === undefined) && opts.title;
      const acts = (opts.actions || []).map((x) =>
        x.avatar ? `<button class="md-avatar" aria-label="${esc(x.label || 'Account')}">${esc(x.avatar)}</button>` : `<button class="md-icon" aria-label="${esc(x.label)}">${ic(x.icon, 24)}</button>`,
      );
      if (opts.titleMode === 'none' && !back && !acts.length) return '';
      return `<header class="md-topbar${large ? ' large' : ''}${large && opts.subtitle ? ' has-sub' : ''}">
        <div class="md-row">${back ? `<button class="md-icon" aria-label="${esc(back.label)}">${ic(opts.close ? 'x' : 'arrow-left', 24)}</button>` : ''}
          ${!large && opts.title ? `<b class="md-title">${esc(opts.title)}</b>` : '<span class="md-fill"></span>'}
          <div class="md-actions">${acts.join('')}</div></div>
        ${large ? `<b class="md-title lg">${esc(opts.title)}</b>${opts.subtitle ? `<span class="md-sub">${esc(opts.subtitle)}</span>` : ''}` : ''}
      </header>`;
    }
    function mdNavBar(active) {
      return `<nav class="md-navbar" aria-label="Navigation">${tabs
        .map(
          (t) => `<button class="md-nav${t.id === active ? ' on' : ''}" aria-label="${esc(t.label)}"${t.id === active ? ' aria-current="page"' : ''}><span class="md-ind">${ic(t.icon, 24)}${t.badge ? `<i class="md-badge">${esc(t.badge)}</i>` : ''}</span><span class="md-lbl">${esc(t.label)}</span></button>`,
        )
        .join('')}</nav>`;
    }

    // ---------- shell ----------
    function shell(opts = {}) {
      const L = layout(opts);
      const titleMode = opts.titleMode || 'large';
      const bigTitle = titleMode === 'large' && opts.title && (vbar || ios || !ios);
      let content = opts.body || '';
      if (opts.split && regular) {
        const sp = opts.split;
        const pw = sp.primaryWidth || Math.min(340, Math.round(L.width * 0.38));
        content = `<div class="sys-split${sp.divider ? ' divider' : ''}" style="--pw:${pw}px">
          <section class="sys-col primary">${sp.primaryTitle ? `<h1 class="sys-largetitle col">${esc(sp.primaryTitle)}</h1>` : ''}${sp.primary}</section>
          <section class="sys-col secondary">${sp.secondaryTitle ? `<h1 class="sys-largetitle col">${esc(sp.secondaryTitle)}</h1>` : ''}${sp.secondary}</section>
        </div>`;
      } else if (bigTitle && ios) {
        content = `<h1 class="sys-largetitle">${esc(opts.title)}</h1>${opts.subtitle ? `<p class="sys-subtitle">${esc(opts.subtitle)}</p>` : ''}${content}`;
      } else if (vbar && titleMode === 'inline' && opts.title && !opts.heroUnderBar) {
        content = `<h1 class="sys-inlinetitle">${esc(opts.title)}</h1>${content}`;
      }
      const vars = `--sys-top:${L.top}px;--sys-bottom:${L.bottom}px;--sys-left:${L.left}px;--sys-right:${L.right}px;--sys-cw:${L.width}px;--sys-ch:${L.height}px`;
      const split = opts.split && regular ? ' has-split' : '';
      const cls = `sys ${ios ? 'sys-ios' : 'sys-md'} ${vbar ? 'sys-vbar-on' : 'sys-hbar'}${split}${opts.heroUnderBar ? ' hero-under' : ''}`;
      if (!ios) {
        return `<div class="${cls}" style="${vars}">
          <main class="sys-content ${opts.contentClass || ''}">${opts.search ? search(opts.search) : ''}${content}</main>
          ${mdTopBar({ ...opts, titleMode })}
          ${opts.primary ? `<button class="md-fab" aria-label="${esc(opts.primary.label)}">${ic(opts.primary.icon, 22)}<span>${esc(opts.primary.label)}</span></button>` : ''}
          ${opts.tabBar !== false && tabs.length ? mdNavBar(opts.tab) : ''}
          ${opts.overlay || ''}
        </div>`;
      }
      return `<div class="${cls}" style="${vars}">
        <main class="sys-content ${opts.contentClass || ''}">${opts.search && !vbar ? search(opts.search) : ''}${opts.search && vbar ? search(opts.search) : ''}${content}</main>
        ${opts.edge !== false && !vbar ? '<div class="sys-edge top"></div>' : ''}
        ${opts.tabBar !== false && tabs.length && !vbar ? '<div class="sys-edge bottom"></div>' : ''}
        ${vbar ? vbarHTML(opts) : iosNavBar({ ...opts, titleMode })}
        ${!vbar && opts.tabBar !== false && tabs.length ? iosTabBar(opts.tab) : ''}
        ${!vbar && opts.tabBar === false && opts.bottomBar ? iosBottomBar(opts.bottomBar) : ''}
        ${opts.overlay || ''}
      </div>`;
    }

    // ---------- sheet ----------
    // opts: { detent: 'medium'|'tall'|'large'|'pane', title, close, done: {label,icon}, body, width,
    //         backdrop: (box) => html, scheme: 'dark'|'light' to force an appearance (e.g. player sheets) }
    // On Duo open a sheet becomes a floating inspector over the full-screen backdrop (not a half):
    //   box = the whole display; box.clear = the region the inspector leaves uncovered.
    function sheet(opts = {}) {
      const W = ctx.w;
      const H = ctx.h;
      const pane = (opts.detent === 'pane' || (regular && opts.detent !== 'large')) && d.id === 'duo-open';
      let box;
      let sheetStyle;
      if (pane) {
        // App screens keep the vertical bar (and the status bar inside it) visible beside the inspector.
        const edge = vbar ? VBAR + 4 : 12;
        const pw = Math.min(opts.width || 380, Math.round(W * 0.45));
        box = { x: 0, y: 0, w: W, h: H, clear: { x: 0, y: 0, w: W - pw - edge - 12, h: H } };
        // Start below the inner camera, which occludes the top of the trailing half.
        sheetStyle = `right:${edge}px;width:${pw}px;top:30px;bottom:12px`;
      } else if (ctx.orient === 'landscape') {
        const sw = Math.min(opts.width || 480, W - 120);
        box = { x: 0, y: 0, w: W, h: H };
        sheetStyle = `left:${(W - sw) / 2}px;width:${sw}px;top:${opts.detent === 'medium' ? Math.round(H * 0.18) : opts.detent === 'tall' ? Math.round(H * 0.08) : 10}px;bottom:0`;
      } else {
        box = { x: 0, y: 0, w: W, h: H };
        const top = opts.detent === 'medium' ? Math.round(H * 0.44) : opts.detent === 'tall' ? Math.round(H * 0.24) : ctx.safe.top + 8;
        sheetStyle = (opts.detent === 'medium' || opts.detent === 'tall') && ios ? `left:8px;right:8px;top:${top}px;bottom:8px` : `left:0;right:0;top:${top}px;bottom:0`;
      }
      if (!box.clear) box.clear = { x: 0, y: 0, w: box.w, h: box.h };
      const back = typeof opts.backdrop === 'function' ? opts.backdrop(box) : opts.backdrop || '';
      const head = ios
        ? `<header class="sys-sheet-head">${opts.close !== false ? `<div class="sys-glass sys-cap">${glassBtn({ icon: 'x', label: 'Close' })}</div>` : '<span></span>'}
            <b>${esc(opts.title || '')}</b>
            ${opts.done ? `<button class="sys-gbtn sys-prominent" aria-label="${esc(opts.done.label)}">${ic(opts.done.icon || 'check', 20)}</button>` : '<span></span>'}</header>`
        : `<header class="md-sheet-head"><i class="md-handle"></i>${opts.title ? `<b>${esc(opts.title)}</b>` : ''}</header>`;
      const kind = pane ? 'pane' : opts.detent === 'medium' || opts.detent === 'tall' ? 'medium' : 'large';
      const force = opts.scheme === 'dark' ? ' sys-force-dark' : opts.scheme === 'light' ? ' sys-force-light' : '';
      return `<div class="sys ${ios ? 'sys-ios' : 'sys-md'} sys-sheet-wrap ${kind}${force}">
        <div class="sys-backdrop" style="left:${box.x}px;top:${box.y}px;width:${box.w}px;height:${box.h}px">${back}</div>
        ${pane ? '' : '<div class="sys-dim"></div>'}
        <section class="sys-sheet ${kind}" style="${sheetStyle}" aria-label="${esc(opts.title || 'Sheet')}">
          ${ios && kind !== 'pane' ? '<i class="sys-grabber"></i>' : ''}${head}
          <div class="sys-sheet-body">${opts.body || ''}</div>
        </section>
      </div>`;
    }

    // ---------- controls ----------
    function toggle(on, label = '', inert = false) {
      const tag = inert ? 'span' : 'button';
      const attrs = inert ? 'aria-hidden="true"' : `role="switch" aria-checked="${!!on}" aria-label="${esc(label)}"`;
      return `<${tag} class="${ios ? 'sys-switch' : 'md-switch'}${on ? ' on' : ''}" ${attrs}><i></i></${tag}>`;
    }
    function segmented(items, active = 0) {
      if (!ios)
        return `<div class="md-seg" role="group">${items
          .map((x, n) => `<button class="${n === active ? 'on' : ''}">${n === active ? ic('check', 16) : ''}${esc(x)}</button>`)
          .join('')}</div>`;
      return `<div class="sys-seg" role="group">${items.map((x, n) => `<button class="${n === active ? 'on' : ''}">${esc(x)}</button>`).join('')}</div>`;
    }
    function search(o = {}) {
      if (!ios)
        return `<div class="md-search${o.focused ? ' focused' : ''}">${ic('search', 22)}<span class="${o.value ? 'val' : ''}">${esc(o.value || o.placeholder || 'Search')}</span>${o.avatar ? `<b class="md-avatar sm">${esc(o.avatar)}</b>` : ic('mic', 22)}</div>`;
      return `<div class="sys-search${o.focused ? ' focused' : ''}"><div class="sys-glass sys-searchfield">${ic('search', 18)}<span class="${o.value ? 'val' : ''}">${esc(o.value || o.placeholder || 'Search')}</span>${o.focused ? '<i class="sys-caret"></i>' : ''}${o.value ? `<b class="sys-clear">${ic('x', 12)}</b>` : ''}</div>${o.focused ? `<div class="sys-glass sys-cap">${glassBtn({ icon: 'x', label: 'Cancel search' })}</div>` : ''}</div>`;
    }
    function button(label, o = {}) {
      const style = o.style || 'glass';
      const size = o.size || 'md';
      if (!ios) {
        const m = { prominent: 'filled', glass: 'tonal', plain: 'text', bordered: 'outlined' }[style] || 'tonal';
        return `<button class="md-btn ${m} ${size}">${o.icon ? ic(o.icon, 18) : ''}<span>${esc(label)}</span></button>`;
      }
      return `<button class="sys-btn ${style} ${size}">${o.icon ? ic(o.icon, size === 'lg' ? 20 : 17) : ''}<span>${esc(label)}</span></button>`;
    }
    function list(sections) {
      const row = (r) => {
        const acc =
          r.accessory === 'chevron' || r.accessory === undefined
            ? ic('chevron-right', 16, 'sys-chev')
            : r.accessory === 'switch-on'
              ? toggle(true, r.title)
              : r.accessory === 'switch-off'
                ? toggle(false, r.title)
                : r.accessory === 'check'
                  ? ic('check', 18, 'sys-check')
                  : r.accessory || '';
        const icon = r.icon ? `<span class="sys-row-icon" style="${r.iconBg ? `background:${r.iconBg}` : ''}">${ic(r.icon, 17)}</span>` : '';
        return `<li class="sys-row">${icon}<span class="sys-row-main"><b>${esc(r.title)}</b>${r.subtitle ? `<small>${r.subtitle}</small>` : ''}</span>${r.value ? `<span class="sys-row-val">${r.value}</span>` : ''}${acc}</li>`;
      };
      return sections
        .map(
          (s) => `<section class="${ios ? 'sys-group' : 'md-group'}">${s.header ? `<h3>${esc(s.header)}</h3>` : ''}<ul>${s.rows.map(row).join('')}</ul>${s.footer ? `<p>${esc(s.footer)}</p>` : ''}</section>`,
        )
        .join('');
    }
    function menu(items) {
      return `<div class="${ios ? 'sys-glass sys-menu' : 'md-menu'}" role="menu">${items
        .map((x) => (x === '-' ? '<hr>' : `<button role="menuitem" class="${x.danger ? 'danger' : ''}"><span>${esc(x.label)}</span>${x.icon ? ic(x.icon, 18) : ''}</button>`))
        .join('')}</div>`;
    }

    return { shell, sheet, toggle, segmented, search, button, list, menu, layout, ios, vbar, regular, device: d };
  }

  window.StudioSys = { create };
})();
