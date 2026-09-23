// Layout probe: tools/probe.sh '<selector>' shot-<v>-<screen>  (prints computed boxes)
addEventListener('load', () => setTimeout(() => {
  const q = new URLSearchParams(location.search).get('q') || '.scr > *';
  const out = [...document.querySelectorAll(q)].slice(0, 20).map((el) => {
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return `${el.tagName.toLowerCase()}.${String(el.className.baseVal ?? el.className).replace(/ /g, '.')} ` +
      `display=${cs.display} flex=${cs.flexDirection} cols=${cs.gridTemplateColumns} pos=${cs.position} ` +
      `box=${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.width)}x${Math.round(r.height)} font=${cs.fontFamily.split(',')[0]} ${cs.fontSize}`;
  });
  // With &rules=1, list every stylesheet rule that matches the first element.
  if (new URLSearchParams(location.search).get('rules')) {
    const el = document.querySelector(q);
    const walk = (rules, parent) => {
      for (const r of rules) {
        if (r.selectorText) {
          const sel = parent ? (r.selectorText.includes('&') ? r.selectorText.replace(/&/g, parent) : parent + ' ' + r.selectorText) : r.selectorText;
          try { if (el && el.matches(sel)) out.push(`RULE ${sel} { ${r.style.cssText.slice(0, 160)} }`); } catch (e) {}
          if (r.cssRules && r.cssRules.length) walk(r.cssRules, sel);
        } else if (r.cssRules) walk(r.cssRules, parent);
      }
    };
    for (const s of document.styleSheets) { try { walk(s.cssRules, ''); } catch (e) {} }
  }
  const pre = document.createElement('pre');
  pre.id = 'probe';
  pre.textContent = out.join('\n') || 'no match';
  document.body.appendChild(pre);
}, 500));
