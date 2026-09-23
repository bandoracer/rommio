/*
 * Procedural pixel art for the mockups: cover scenes and gameplay frames.
 *
 *   StudioArt.cover(gameId, kind)   kind: box (3:4) | wide (16:9) | square | tall (9:16) | banner (3:1)
 *   StudioArt.frame(gameId, variant) native-resolution gameplay frame (variant a–d = different moments)
 *   StudioArt.dual(gameId, variant)  { top, bottom } for DS / 3DS
 *   StudioArt.grain()                tileable film-grain texture
 *   StudioArt.palette(gameId)        the game's 5-colour palette
 *
 * Everything returns PNG data URLs and is cached, so calling it from every
 * screen render is cheap. Art is drawn at low resolution into a pixel buffer
 * with ordered dithering, then scaled up with nearest-neighbour sampling.
 */
(function () {
  const cache = new Map();
  const D = () => window.StudioData;

  // ---------- small utilities ----------
  function hash(str) {
    let h = 2166136261;
    for (let i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h >>> 0;
  }
  function rng(seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function rgb(h) {
    if (Array.isArray(h)) return h;
    h = h.replace('#', '');
    if (h.length === 3) h = h.split('').map((c) => c + c).join('');
    const n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function mix(a, b, t) {
    const A = rgb(a);
    const B = rgb(b);
    return [A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t].map(Math.round);
  }
  const WHITE = [255, 255, 255];
  const BLACK = [0, 0, 0];
  const BAYER = [
    [0, 8, 2, 10],
    [12, 4, 14, 6],
    [3, 11, 1, 9],
    [15, 7, 13, 5],
  ];
  const bayer = (x, y) => (BAYER[y & 3][x & 3] + 0.5) / 16;

  // Smooth 1-D value noise in [0,1], control points every `step` px.
  function noise1(rand, step) {
    const pts = [];
    return function (x) {
      const i = Math.floor(x / step);
      const f = x / step - i;
      while (pts.length <= i + 1) pts.push(rand());
      const s = f * f * (3 - 2 * f);
      return pts[Math.max(0, i)] * (1 - s) + pts[Math.max(0, i + 1)] * s;
    };
  }

  // ---------- pixel buffer ----------
  class Buf {
    constructor(w, h) {
      this.w = w;
      this.h = h;
      this.d = new Uint8ClampedArray(w * h * 4);
    }
    set(x, y, c, a = 1) {
      x |= 0;
      y |= 0;
      if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
      const i = (y * this.w + x) * 4;
      const d = this.d;
      if (a >= 1) {
        d[i] = c[0];
        d[i + 1] = c[1];
        d[i + 2] = c[2];
      } else {
        d[i] += (c[0] - d[i]) * a;
        d[i + 1] += (c[1] - d[i + 1]) * a;
        d[i + 2] += (c[2] - d[i + 2]) * a;
      }
      d[i + 3] = 255;
    }
    get(x, y) {
      const i = ((y | 0) * this.w + (x | 0)) * 4;
      return [this.d[i], this.d[i + 1], this.d[i + 2]];
    }
    rect(x, y, w, h, c, a) {
      for (let j = Math.max(0, y | 0); j < Math.min(this.h, (y + h) | 0); j++)
        for (let i = Math.max(0, x | 0); i < Math.min(this.w, (x + w) | 0); i++) this.set(i, j, c, a);
    }
    // dithered fill: density 0..1
    dither(x, y, w, h, c, density) {
      for (let j = Math.max(0, y | 0); j < Math.min(this.h, (y + h) | 0); j++)
        for (let i = Math.max(0, x | 0); i < Math.min(this.w, (x + w) | 0); i++) if (bayer(i, j) < density) this.set(i, j, c);
    }
    // vertical dithered gradient through colour stops
    grad(y0, y1, stops, x0 = 0, x1 = this.w) {
      const n = stops.length - 1;
      for (let y = Math.max(0, y0 | 0); y < Math.min(this.h, y1 | 0); y++) {
        const t = (y - y0) / Math.max(1, y1 - y0 - 1);
        const p = Math.min(n - 1e-6, t * n);
        const k = Math.floor(p);
        const f = p - k;
        for (let x = x0; x < x1; x++) this.set(x, y, f > bayer(x, y) ? stops[k + 1] : stops[k]);
      }
    }
    circle(cx, cy, r, c, a) {
      for (let y = Math.floor(cy - r); y <= Math.ceil(cy + r); y++)
        for (let x = Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
          const dx = x + 0.5 - cx;
          const dy = y + 0.5 - cy;
          if (dx * dx + dy * dy <= r * r) this.set(x, y, c, a);
        }
    }
    ellipse(cx, cy, rx, ry, c) {
      for (let y = Math.floor(cy - ry); y <= Math.ceil(cy + ry); y++)
        for (let x = Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
          const dx = (x + 0.5 - cx) / rx;
          const dy = (y + 0.5 - cy) / ry;
          if (dx * dx + dy * dy <= 1) this.set(x, y, c);
        }
    }
    // column fill from height function: fills from top(x) down to bottom
    ridge(topFn, bottom, c, x0 = 0, x1 = this.w) {
      for (let x = x0; x < x1; x++) {
        const t = Math.round(topFn(x));
        for (let y = Math.max(0, t); y < Math.min(this.h, bottom); y++) this.set(x, y, c);
      }
    }
    tri(cx, top, halfBase, height, c) {
      for (let j = 0; j < height; j++) {
        const hw = Math.round((j / height) * halfBase);
        for (let i = -hw; i <= hw; i++) this.set(cx + i, top + j, c);
      }
    }
    line(x0, y0, x1, y1, c) {
      x0 |= 0; y0 |= 0; x1 |= 0; y1 |= 0;
      const dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
      const dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
      let err = dx + dy;
      for (;;) {
        this.set(x0, y0, c);
        if (x0 === x1 && y0 === y1) break;
        const e2 = 2 * err;
        if (e2 >= dy) { err += dy; x0 += sx; }
        if (e2 <= dx) { err += dx; y0 += sy; }
      }
    }
    sprite(x, y, rows, colors, flip = false, scale = 1) {
      rows.forEach((row, j) => {
        for (let i = 0; i < row.length; i++) {
          const ch = row[flip ? row.length - 1 - i : i];
          if (ch === '.' || !colors[ch]) continue;
          if (scale === 1) this.set(x + i, y + j, colors[ch]);
          else this.rect(x + i * scale, y + j * scale, scale, scale, colors[ch]);
        }
      });
    }
    toURL(scale) {
      const c = document.createElement('canvas');
      c.width = this.w;
      c.height = this.h;
      const ctx = c.getContext('2d');
      ctx.putImageData(new ImageData(this.d, this.w, this.h), 0, 0);
      if (scale === 1) return c.toDataURL('image/png');
      const o = document.createElement('canvas');
      o.width = this.w * scale;
      o.height = this.h * scale;
      const octx = o.getContext('2d');
      octx.imageSmoothingEnabled = false;
      octx.drawImage(c, 0, 0, o.width, o.height);
      return o.toDataURL('image/png');
    }
  }

  // ---------- 3x5 pixel font ----------
  const FONT = {
    A: '010101111101101', B: '110101110101110', C: '011100100100011', D: '110101101101110', E: '111100110100111',
    F: '111100110100100', G: '011100101101011', H: '101101111101101', I: '111010010010111', J: '001001001101010',
    K: '101101110101101', L: '100100100100111', M: '101111111101101', N: '110101101101101', O: '010101101101010',
    P: '110101110100100', Q: '010101101110011', R: '110101110101101', S: '011100010001110', T: '111010010010010',
    U: '101101101101111', V: '101101101101010', W: '101101111111101', X: '101101010101101', Y: '101101010010010',
    Z: '111001010100111', 0: '111101101101111', 1: '010110010010111', 2: '110001010100111', 3: '110001010001110',
    4: '101101111001001', 5: '111100110001110', 6: '011100111101111', 7: '111001010010010', 8: '111101111101111',
    9: '111101111001110', ' ': '000000000000000', '-': '000000111000000', '.': '000000000000010', ':': '000010000010000',
    '×': '000101010101000', '!': '010010010000010', '?': '110001010000010', "'": '010010000000000', ',': '000000000010100',
    '/': '001001010100100', '*': '101111111010000', '>': '100010001010100', '<': '001010100010001',
  };
  function text(buf, str, x, y, c, s = 1, shadow) {
    let cx = x;
    for (const ch of String(str).toUpperCase()) {
      const g = FONT[ch] || FONT['?'];
      for (let j = 0; j < 5; j++)
        for (let i = 0; i < 3; i++)
          if (g[j * 3 + i] === '1') {
            if (shadow) buf.rect(cx + i * s + s, y + j * s + s, s, s, shadow);
            buf.rect(cx + i * s, y + j * s, s, s, c);
          }
      cx += 4 * s;
    }
    return cx;
  }
  const textWidth = (str, s = 1) => String(str).length * 4 * s - s;

  // ---------- sprites ----------
  const HERO = [
    '....aaaaa...',
    '...abbbbba..',
    '..abbbbbbba.',
    '..aaaccccaa.',
    '..acacaccca.',
    '..accccccca.',
    '...acccccaa.',
    '..aabbbbbaa.',
    '.abbbdbbbbba',
    '.accbdbbbcca',
    '.accabbbbcca',
    '..aabbbbbaa.',
    '..addaaadda.',
    '..adda.adda.',
    '.aaaa..aaaa.',
    '............',
  ];
  const BLOB = [
    '...aaaaaa...',
    '..abbbbbba..',
    '.abbbbbbbba.',
    '.abwwbbwwba.',
    'abbwabbawbba',
    'abbbbbbbbbba',
    'abbbbbbbbbba',
    '.aacccccaa..',
    '.acca..acca.',
    '.aaa....aaa.',
  ];
  const SHIP = [
    '......a......',
    '.....aba.....',
    '.....aba.....',
    '....abcba....',
    '....abcba....',
    'a..abbbbba..a',
    'ba.abbbbba.ab',
    'bbabbdbdbbabb',
    'bbbbbbbbbbbbb',
    'abbba.b.abbba',
    '.aa..aaa..aa.',
    '......d......',
  ];
  const BUG = [
    '..a.....a..',
    '...a...a...',
    '..bbbbbbb..',
    '.bbwbbbwbb.',
    'bbbbbbbbbbb',
    'b.bcccccb.b',
    'b.b.....b.b',
    '...bb.bb...',
  ];
  const COIN = ['.aaaa.', 'abbbba', 'abcbba', 'abcbba', 'abcbba', 'abcbba', 'abbbba', '.aaaa.'];
  const KART = [
    '.....aaaaaa.....',
    '....abbbbbba....',
    '....abccccba....',
    '...aabbbbbbaa...',
    'aaaabbbbbbbbaaaa',
    'addabbbbbbbbadda',
    'addaabbbbbbaadda',
    'addaaaaaaaaaadda',
    '.aa..........aa.',
  ];

  // ---------- scenes ----------
  function sky(b, pal, y1, y0 = 0) {
    b.grad(y0, y1, [pal[0], mix(pal[0], pal[1], 0.34), mix(pal[0], pal[1], 0.68), pal[1]]);
  }
  function stars(b, r, n, c, maxY) {
    for (let i = 0; i < n; i++) {
      const x = Math.floor(r() * b.w);
      const y = Math.floor(r() * maxY);
      const bright = r() < 0.12;
      b.set(x, y, c, bright ? 1 : 0.35 + r() * 0.5);
      if (bright && b.w > 90) {
        b.set(x - 1, y, c, 0.5); b.set(x + 1, y, c, 0.5); b.set(x, y - 1, c, 0.5); b.set(x, y + 1, c, 0.5);
      }
    }
  }
  function cloud(b, x, y, s, c, shade) {
    const puffs = [[0, 0, 1], [s * 0.9, -s * 0.35, 1.2], [s * 1.9, 0, 0.95], [s * 0.9, s * 0.2, 1]];
    for (const [dx, dy, k] of puffs) b.circle(x + dx, y + dy, s * 0.62 * k, c);
    if (shade) for (let i = -s * 0.5; i < s * 2.5; i++) b.set(x + i, y + s * 0.55, shade);
  }
  function pine(b, x, base, h, c) {
    const w = Math.max(2, Math.round(h * 0.34));
    for (let j = 0; j < h; j++) {
      const t = j / h;
      const tier = (t * 3) % 1;
      const hw = Math.round(w * (0.25 + 0.75 * t) * (0.55 + 0.45 * tier));
      for (let i = -hw; i <= hw; i++) b.set(x + i, base - h + j, c);
    }
    b.rect(x, base - 1, 1, 3, c);
  }
  function palm(b, x, base, h, c) {
    for (let j = 0; j < h; j++) b.set(x + Math.round(Math.sin(j / h) * 2), base - j, c);
    const tx = x + 2;
    const ty = base - h;
    for (let k = 0; k < 5; k++) {
      const ang = -Math.PI + (k / 4) * Math.PI;
      for (let l = 0; l < h * 0.55; l++)
        b.set(tx + Math.cos(ang) * l, ty + Math.sin(ang) * l * 0.45 + (l * l) / (h * 1.6), c);
    }
  }

  const SCENES = {
    space(b, P, r) {
      sky(b, P, b.h);
      const nx = noise1(r, b.w / 5);
      const ny = noise1(r, b.h / 4);
      for (let y = 0; y < b.h; y++)
        for (let x = 0; x < b.w; x++) {
          const v = nx(x + y * 0.6) * ny(y + x * 0.3);
          if (v > 0.32 && bayer(x, y) < (v - 0.32) * 2.2) b.set(x, y, P[2]);
        }
      stars(b, r, (b.w * b.h) / 40, mix(P[4], WHITE, 0.7), b.h);
      const pr = Math.min(b.w, b.h) * (0.16 + r() * 0.05);
      const px = b.w * (0.68 + r() * 0.1);
      const py = b.h * (0.3 + r() * 0.08);
      b.circle(px, py, pr, mix(P[2], P[4], 0.35));
      for (let y = -pr; y < pr; y++)
        for (let x = -pr; x < pr; x++)
          if (x * x + y * y < pr * pr && (x + y) / pr > 0.1 && bayer(px + x, py + y) < ((x + y) / pr) * 0.9)
            b.set(px + x, py + y, P[3]);
      for (let a = 0; a < Math.PI * 2; a += 0.01) {
        const x = px + Math.cos(a) * pr * 1.7;
        const y = py + Math.sin(a) * pr * 0.42;
        if (!(Math.sin(a) < 0 && Math.hypot(x - px, y - py) < pr)) b.set(x, y, P[4]);
      }
      const n = noise1(r, b.w / 7);
      b.ridge((x) => b.h * 0.86 - n(x) * b.h * 0.12, b.h, P[3]);
    },
    cave(b, P, r) {
      b.grad(0, b.h, [P[0], P[1], mix(P[1], P[2], 0.5), P[0]]);
      const n = noise1(r, 3);
      for (let y = 0; y < b.h; y++)
        for (let x = 0; x < b.w; x++) if (n(x * 1.7 + y * 3.1) > 0.72 && bayer(x, y) < 0.5) b.set(x, y, P[2]);
      const count = Math.round(b.w / 7);
      for (let i = 0; i < count; i++) {
        const x = Math.round((i + r() * 0.8) * (b.w / count));
        const len = b.h * (0.12 + r() * 0.3);
        b.tri(x, 0, 0, 0, P[3]);
        for (let j = 0; j < len; j++) {
          const hw = Math.round((1 - j / len) * (2 + b.w / 60));
          for (let k = -hw; k <= hw; k++) b.set(x + k, j, P[3]);
        }
      }
      const gn = noise1(r, b.w / 6);
      b.ridge((x) => b.h * 0.78 - gn(x) * b.h * 0.14, b.h, P[3]);
      for (let i = 0; i < Math.max(3, b.w / 18); i++) {
        const x = r() * b.w;
        const y = b.h * (0.35 + r() * 0.4);
        b.circle(x, y, 2 + b.w / 50, P[4], 0.18);
        b.set(x, y, mix(P[4], WHITE, 0.5));
        b.set(x, y - 1, P[4]); b.set(x, y + 1, P[4]); b.set(x - 1, y, P[4]); b.set(x + 1, y, P[4]);
      }
    },
    sky(b, P, r) {
      sky(b, P, b.h);
      b.circle(b.w * 0.74, b.h * 0.22, Math.min(b.w, b.h) * 0.11, P[4]);
      b.circle(b.w * 0.74, b.h * 0.22, Math.min(b.w, b.h) * 0.16, P[4], 0.2);
      const cc = mix(P[0], WHITE, 0.78);
      const cs = mix(P[1], P[0], 0.4);
      for (let i = 0; i < 4; i++) cloud(b, r() * b.w * 0.9 - 4, b.h * (0.12 + r() * 0.45), 3 + r() * (b.w / 22), cc, cs);
      const ix = b.w * (0.18 + r() * 0.2);
      const iy = b.h * 0.58;
      const iw = b.w * 0.26;
      b.ellipse(ix, iy, iw * 0.5, b.h * 0.035, P[2]);
      for (let j = 0; j < b.h * 0.16; j++) {
        const hw = iw * 0.5 * (1 - j / (b.h * 0.16));
        for (let k = -hw; k <= hw; k++) b.set(ix + k, iy + 2 + j, P[3]);
      }
      pine(b, ix - iw * 0.2, iy - 1, b.h * 0.08, P[3]);
      pine(b, ix + iw * 0.12, iy - 1, b.h * 0.11, P[3]);
      for (let i = 0; i < 6; i++) cloud(b, (i / 5) * b.w - 6, b.h * 0.92, 4 + b.w / 18, cc, null);
    },
    forest(b, P, r) {
      const hy = b.h * 0.62;
      sky(b, P, hy + 4);
      b.circle(b.w * 0.3, b.h * 0.26, Math.min(b.w, b.h) * 0.1, P[4]);
      b.circle(b.w * 0.3, b.h * 0.26, Math.min(b.w, b.h) * 0.15, P[4], 0.18);
      const m = noise1(r, b.w / 4);
      b.ridge((x) => hy - m(x) * b.h * 0.26, b.h, mix(P[2], P[1], 0.45));
      const h2 = noise1(r, b.w / 3);
      b.ridge((x) => hy + 4 - h2(x) * b.h * 0.1, b.h, P[2]);
      const n = Math.round(b.w / 5);
      for (let i = 0; i < n; i++) pine(b, Math.round((i + r() * 0.6) * (b.w / n)), b.h * 0.86 + r() * 3, b.h * (0.12 + r() * 0.16), P[3]);
      b.rect(0, b.h * 0.86, b.w, b.h, P[3]);
    },
    ocean(b, P, r) {
      const hy = Math.round(b.h * 0.55);
      sky(b, [P[0], P[1], P[2], P[3], P[4]], hy);
      const sr = Math.min(b.w, b.h) * 0.14;
      b.circle(b.w * 0.5, hy, sr, P[4]);
      for (let y = hy - sr; y < hy; y += 3) b.rect(b.w * 0.5 - sr, y, sr * 2, 1, mix(P[4], P[1], 0.5), 0.5);
      for (let i = 0; i < 3; i++) cloud(b, r() * b.w, b.h * (0.1 + r() * 0.25), 3 + r() * (b.w / 26), mix(P[0], WHITE, 0.6), null);
      b.grad(hy, b.h, [P[2], mix(P[2], P[3], 0.5), P[3]]);
      for (let y = hy + 1; y < b.h; y += 2) {
        const depth = (y - hy) / (b.h - hy);
        for (let k = 0; k < 3 + depth * 5; k++) {
          const len = 2 + r() * 6 * (1 + depth * 2);
          b.rect(r() * b.w, y, len, 1, mix(P[1], P[2], 0.4), 0.8);
        }
        const rw = sr * (1 - depth * 0.3) * (0.6 + r() * 0.5);
        b.rect(b.w * 0.5 - rw, y, rw * 2, 1, P[4], 0.85 - depth * 0.5);
      }
      const ix = b.w * 0.16;
      b.ellipse(ix, hy + 1, b.w * 0.12, b.h * 0.03, P[3]);
      palm(b, ix, hy, b.h * 0.18, P[3]);
    },
    city(b, P, r) {
      const hy = b.h * 0.72;
      sky(b, P, b.h);
      b.circle(b.w * 0.78, b.h * 0.2, Math.min(b.w, b.h) * 0.08, P[4]);
      stars(b, r, b.w / 3, mix(P[4], WHITE, 0.5), b.h * 0.5);
      let x = -2;
      const far = mix(P[2], P[1], 0.45);
      while (x < b.w) {
        const w = 4 + r() * (b.w / 12);
        const h = b.h * (0.15 + r() * 0.3);
        b.rect(x, hy - h, w, b.h, far);
        x += w + 1;
      }
      x = -3;
      while (x < b.w) {
        const w = 6 + r() * (b.w / 9);
        const h = b.h * (0.1 + r() * 0.34);
        const top = hy + 6 - h;
        b.rect(x, top, w, b.h, P[3]);
        if (r() < 0.4) b.rect(x + w / 2 - 1, top - 3 - r() * 4, 1, 6, P[3]);
        for (let wy = top + 2; wy < b.h - 2; wy += 3)
          for (let wx = x + 1; wx < x + w - 1; wx += 2) if (r() < 0.28) b.set(wx, wy, P[4]);
        x += w + (r() < 0.3 ? 2 : 0);
      }
    },
    castle(b, P, r) {
      sky(b, P, b.h);
      const mr = Math.min(b.w, b.h) * 0.17;
      const mx = b.w * 0.66;
      const my = b.h * 0.28;
      b.circle(mx, my, mr * 1.5, P[4], 0.12);
      b.circle(mx, my, mr, P[4]);
      b.circle(mx - mr * 0.3, my - mr * 0.1, mr * 0.2, mix(P[4], P[2], 0.35));
      b.circle(mx + mr * 0.35, my + mr * 0.3, mr * 0.14, mix(P[4], P[2], 0.35));
      stars(b, r, b.w / 2, mix(P[4], WHITE, 0.4), b.h * 0.6);
      const m = noise1(r, b.w / 4);
      b.ridge((x) => b.h * 0.66 - m(x) * b.h * 0.14, b.h, P[2]);
      const base = b.h * 0.8;
      const cx = b.w * 0.42;
      const cw = b.w * 0.44;
      b.rect(cx - cw / 2, base - b.h * 0.2, cw, b.h, P[3]);
      const towers = [[-0.5, 0.36, 0.1], [-0.18, 0.5, 0.08], [0.12, 0.42, 0.09], [0.46, 0.3, 0.08]];
      for (const [ox, th, tw] of towers) {
        const tx = cx + ox * cw;
        const w = Math.max(3, b.w * tw);
        const top = base - b.h * th;
        b.rect(tx - w / 2, top, w, b.h, P[3]);
        b.tri(Math.round(tx), Math.round(top - w * 1.4), Math.round(w / 2 + 1), Math.round(w * 1.4), P[3]);
        if (r() < 0.8) b.set(tx, top + w * 0.8, P[4]);
      }
      for (let i = cx - cw / 2; i < cx + cw / 2; i += 3) b.rect(i, base - b.h * 0.2 - 2, 2, 2, P[3]);
      for (let i = 0; i < 4; i++) {
        const bx = r() * b.w;
        const by = b.h * (0.12 + r() * 0.3);
        b.set(bx, by, P[3]); b.set(bx - 1, by - 1, P[3]); b.set(bx + 1, by - 1, P[3]);
      }
      b.rect(0, base, b.w, b.h, P[3]);
    },
    desert(b, P, r) {
      sky(b, P, b.h * 0.7);
      const sr = Math.min(b.w, b.h) * 0.16;
      b.circle(b.w * 0.62, b.h * 0.34, sr * 1.4, P[4], 0.15);
      b.circle(b.w * 0.62, b.h * 0.34, sr, P[4]);
      const tx = b.w * 0.26;
      b.tri(Math.round(tx), Math.round(b.h * 0.42), Math.round(b.w * 0.14), Math.round(b.h * 0.2), mix(P[2], P[1], 0.4));
      for (let k = 0; k < 3; k++) {
        const col = [mix(P[2], P[1], 0.25), P[2], P[3]][k];
        const ph = r() * 10;
        const base = b.h * (0.62 + k * 0.1);
        b.ridge((x) => base - Math.sin(x / (b.w / (2 + k)) + ph) * b.h * 0.05, b.h, col);
      }
      const cxp = b.w * 0.78;
      const cb = b.h * 0.88;
      const ch = b.h * 0.14;
      b.rect(cxp, cb - ch, 2, ch, P[3]);
      b.rect(cxp - 3, cb - ch * 0.7, 1, ch * 0.3, P[3]);
      b.rect(cxp - 3, cb - ch * 0.42, 3, 1, P[3]);
      b.rect(cxp + 3, cb - ch * 0.85, 1, ch * 0.3, P[3]);
      b.rect(cxp + 2, cb - ch * 0.56, 2, 1, P[3]);
    },
    volcano(b, P, r) {
      sky(b, P, b.h);
      const vx = b.w * 0.55;
      const top = b.h * 0.3;
      const base = b.h * 0.82;
      for (let y = top; y < base; y++) {
        const hw = 3 + ((y - top) / (base - top)) * b.w * 0.42;
        b.rect(vx - hw, y, hw * 2, 1, P[2]);
      }
      for (let k = 0; k < 4; k++) {
        let x = vx + (r() - 0.5) * 4;
        for (let y = top; y < base; y++) {
          x += (r() - 0.5) * 1.6 + (k < 2 ? -0.35 : 0.35);
          b.set(x, y, P[4]);
          if (r() < 0.3) b.set(x + 1, y, mix(P[4], P[2], 0.5));
        }
      }
      b.circle(vx, top, 5 + b.w / 30, P[4], 0.35);
      for (let i = 0; i < 6; i++) b.circle(vx - 6 + i * 3 + r() * 4, top - 5 - i * (b.h / 18), 3 + i * 1.2, mix(P[0], P[1], 0.6), 0.6);
      const g = noise1(r, b.w / 5);
      b.ridge((x) => base - g(x) * b.h * 0.06, b.h, P[3]);
      for (let i = 0; i < b.w / 8; i++) b.rect(r() * b.w, base + 2 + r() * (b.h - base), 3 + r() * 5, 1, P[4], 0.8);
    },
    track(b, P, r) {
      const hy = Math.round(b.h * 0.44);
      sky(b, P, hy);
      cloud(b, b.w * 0.2, b.h * 0.14, 3 + b.w / 22, mix(P[0], WHITE, 0.7), null);
      cloud(b, b.w * 0.68, b.h * 0.22, 2 + b.w / 30, mix(P[0], WHITE, 0.7), null);
      const m = noise1(r, b.w / 4);
      b.ridge((x) => hy - m(x) * b.h * 0.12, hy + 1, P[2]);
      for (let y = hy; y < b.h; y++) {
        const t = (y - hy) / (b.h - hy);
        const z = 1 / (t + 0.08);
        const stripe = Math.floor(z * 1.4) % 2 === 0;
        const curve = Math.sin(t * 2.2) * b.w * 0.08 * (1 - t);
        const cx = b.w / 2 + curve;
        const rw = b.w * (0.06 + t * 0.5);
        b.rect(0, y, b.w, 1, stripe ? P[2] : mix(P[2], P[3], 0.35));
        b.rect(cx - rw, y, rw * 2, 1, stripe ? P[3] : mix(P[3], BLACK, 0.2));
        const edge = Math.max(1, rw * 0.08);
        b.rect(cx - rw - edge, y, edge, 1, stripe ? P[4] : WHITE);
        b.rect(cx + rw, y, edge, 1, stripe ? P[4] : WHITE);
        if (stripe && t > 0.1) b.rect(cx - 0.5, y, Math.max(1, rw * 0.03), 1, mix(WHITE, P[3], 0.2));
      }
    },
    arena(b, P, r) {
      const hy = b.h * 0.62;
      sky(b, P, hy);
      b.circle(b.w * 0.5, hy - b.h * 0.08, Math.min(b.w, b.h) * 0.2, P[4], 0.9);
      for (let y = hy - b.h * 0.3; y < hy; y += 3) b.rect(b.w * 0.25, y, b.w * 0.5, 1, P[1], 0.35);
      const px = b.w * 0.5;
      for (let k = 0; k < 3; k++) {
        const w = b.w * (0.3 - k * 0.07);
        const y = hy - b.h * (0.1 + k * 0.1);
        b.rect(px - w / 2, y, w, b.h * 0.1, P[3]);
        for (let i = -w / 2 - 3; i < w / 2 + 3; i++) b.set(px + i, y - 1 + Math.abs(i) / (w * 0.4), P[3]);
      }
      for (let y = hy; y < b.h; y++) {
        const t = (y - hy) / (b.h - hy);
        const tile = Math.floor(1 / (t + 0.15)) % 2;
        for (let x = 0; x < b.w; x++) {
          const u = Math.floor(((x - b.w / 2) / (0.3 + t)) / 8) % 2;
          b.set(x, y, (u ^ tile) & 1 ? P[2] : mix(P[2], P[3], 0.5));
        }
      }
      for (let x = 0; x < b.w; x += 2) b.rect(x, hy - 3 - r() * 4, 2, 8, P[3]);
    },
    puzzle(b, P, r) {
      b.rect(0, 0, b.w, b.h, P[3]);
      const cell = Math.max(4, Math.round(b.w / 12));
      for (let y = 0; y < b.h; y += cell)
        for (let x = 0; x < b.w; x += cell) {
          const on = r() < 0.52 + (y / b.h) * 0.4;
          if (!on) continue;
          const c = [P[0], P[1], P[2], P[4]][Math.floor(r() * 4)];
          b.rect(x, y, cell - 1, cell - 1, c);
          b.rect(x, y, cell - 1, 1, mix(c, WHITE, 0.4));
          b.rect(x, y, 1, cell - 1, mix(c, WHITE, 0.25));
          b.rect(x + cell - 2, y + 1, 1, cell - 2, mix(c, BLACK, 0.3));
          b.rect(x + 1, y + cell - 2, cell - 2, 1, mix(c, BLACK, 0.3));
        }
    },
    maze(b, P, r) {
      b.rect(0, 0, b.w, b.h, P[0]);
      const g = Math.max(6, Math.round(b.w / 9));
      const cols = Math.ceil(b.w / g);
      const rows = Math.ceil(b.h / g);
      for (let j = 0; j < rows; j++)
        for (let i = 0; i < cols; i++) {
          const x = i * g;
          const y = j * g;
          if ((i + j) % 2 === 0 && r() < 0.55) {
            const w = r() < 0.5 ? g * 2 : g;
            const h = w === g ? g * 2 : g;
            for (let k = 0; k < w - 2; k++) { b.set(x + 1 + k, y + 1, P[2]); b.set(x + 1 + k, y + h - 2, P[2]); }
            for (let k = 0; k < h - 2; k++) { b.set(x + 1, y + 1 + k, P[2]); b.set(x + w - 2, y + 1 + k, P[2]); }
          } else if (r() < 0.8) {
            b.set(x + g / 2, y + g / 2, mix(P[4], WHITE, 0.4));
          }
        }
      const px = b.w * 0.38;
      const py = b.h * 0.56;
      const pr = g * 0.7;
      b.circle(px, py, pr, P[4]);
      for (let y = -pr; y <= pr; y++) for (let x = 0; x <= pr; x++) if (Math.abs(y) < x * 0.7) b.set(px + x, py + y, P[0]);
      const gc = [[255, 64, 64], [255, 150, 210], [80, 220, 255]];
      gc.forEach((c, k) => {
        const gx = b.w * (0.58 + k * 0.13);
        const gy = b.h * 0.56;
        b.circle(gx, gy - pr * 0.2, pr * 0.8, c);
        b.rect(gx - pr * 0.8, gy - pr * 0.2, pr * 1.6, pr, c);
        for (let i = 0; i < 3; i++) b.set(gx - pr * 0.6 + i * pr * 0.6, gy + pr * 0.8, P[0]);
        b.rect(gx - pr * 0.45, gy - pr * 0.35, 2, 2, WHITE);
        b.rect(gx + pr * 0.15, gy - pr * 0.35, 2, 2, WHITE);
      });
    },
    neon(b, P, r) {
      sky(b, [P[0], P[1], P[0], P[3], P[4]], b.h);
      const hy = b.h * 0.8;
      let x = -2;
      while (x < b.w) {
        const w = 8 + r() * (b.w / 7);
        const h = b.h * (0.2 + r() * 0.45);
        const top = hy - h;
        b.rect(x, top, w, b.h, P[3]);
        if (r() < 0.6) {
          const c = r() < 0.5 ? P[2] : P[4];
          const sy = top + 3 + r() * h * 0.4;
          const sw = w * 0.6;
          for (let i = 0; i < sw; i++) { b.set(x + 2 + i, sy, c); b.set(x + 2 + i, sy + 4, c); }
          b.set(x + 2, sy + 1, c); b.set(x + 2, sy + 2, c); b.set(x + 2, sy + 3, c);
          b.set(x + 1 + sw, sy + 1, c); b.set(x + 1 + sw, sy + 2, c); b.set(x + 1 + sw, sy + 3, c);
          b.rect(x + 1, sy - 1, sw + 2, 7, c, 0.12);
        }
        for (let wy = top + 2; wy < hy; wy += 4) for (let wx = x + 2; wx < x + w - 2; wx += 3) if (r() < 0.18) b.set(wx, wy, mix(P[4], P[2], r()));
        x += w + 1;
      }
      b.rect(0, hy, b.w, b.h, mix(P[3], P[1], 0.3));
      for (let y = hy + 1; y < b.h; y += 2)
        for (let k = 0; k < 4; k++) b.rect(r() * b.w, y, 2 + r() * 5, 1, r() < 0.5 ? P[2] : P[4], 0.45);
      for (let i = 0; i < (b.w * b.h) / 90; i++) {
        const rx = r() * b.w;
        const ry = r() * b.h;
        for (let l = 0; l < 3; l++) b.set(rx - l * 0.5, ry + l, mix(P[4], WHITE, 0.4), 0.35);
      }
    },
  };

  function paintScene(b, game, seedExtra = '') {
    const r = rng(hash(game.id + seedExtra));
    const P = game.art.pal.map(rgb);
    (SCENES[game.art.motif] || SCENES.forest)(b, P, r);
  }

  // ---------- gameplay ----------
  function hudText(b, P) {
    const c = WHITE;
    const sh = mix(P[3], BLACK, 0.4);
    return { c, sh };
  }

  const PLAY = {
    platformer(b, game, v, r, P) {
      paintScene(b, game, 'bg' + v);
      for (let y = 0; y < b.h; y++) for (let x = 0; x < b.w; x++) if ((x + y) % 2 === 0 && y < b.h * 0.7) b.set(x, y, P[1], 0.08);
      const T = 16;
      const gy = b.h - T * 2;
      const gap0 = Math.floor(b.w * (0.5 + r() * 0.2) / T) * T;
      for (let x = 0; x < b.w; x += T) {
        if (x >= gap0 && x < gap0 + T * 2) continue;
        for (let y = gy; y < b.h; y += T) {
          const top = y === gy;
          b.rect(x, y, T, T, top ? mix(P[2], P[4], 0.25) : mix(P[3], P[2], 0.35));
          if (top) {
            b.rect(x, y, T, 3, mix(P[4], P[2], 0.3));
            b.rect(x, y + 3, T, 1, mix(P[3], BLACK, 0.2));
          } else {
            b.rect(x, y + 7, T, 1, mix(P[3], BLACK, 0.3));
            b.rect(x + (((y / T) | 0) % 2 ? 4 : 11), y, 1, 7, mix(P[3], BLACK, 0.3));
            b.rect(x + (((y / T) | 0) % 2 ? 11 : 4), y + 8, 1, 8, mix(P[3], BLACK, 0.3));
          }
        }
      }
      const px = Math.round(b.w * (0.45 + r() * 0.15));
      const py = gy - T * 3 - 8;
      const blocks = 4;
      for (let i = 0; i < blocks; i++) {
        const q = i === 1;
        const bx = px + i * T;
        const c = q ? P[4] : mix(P[3], P[4], 0.2);
        b.rect(bx, py, T, T, mix(c, BLACK, 0.45));
        b.rect(bx + 1, py + 1, T - 2, T - 2, c);
        b.rect(bx + 1, py + 1, T - 2, 1, mix(c, WHITE, 0.45));
        if (q) text(b, '?', bx + 6, py + 5, mix(P[3], BLACK, 0.3), 1);
      }
      for (let i = 0; i < 5; i++) b.sprite(px - 30 + i * 12 - 60, py - 26, COIN, { a: mix(P[4], BLACK, 0.5), b: P[4], c: mix(P[4], WHITE, 0.6) });
      const hx = Math.round(b.w * (0.18 + r() * 0.1));
      b.sprite(hx, gy - 16, HERO, { a: [24, 20, 28], b: mix(P[4], [220, 60, 60], 0.35), c: [255, 214, 170], d: mix(P[2], BLACK, 0.3) }, v === 'b', 1);
      b.sprite(Math.round(b.w * 0.78), gy - 10, BLOB, { a: [30, 16, 20], b: mix(P[2], [150, 70, 40], 0.5), c: [240, 220, 190], w: WHITE });
      const { c, sh } = hudText(b, P);
      const s = b.w >= 240 ? 2 : 1;
      text(b, 'SCORE', 8, 8, c, s, sh);
      text(b, String(1200 + ((r() * 90) | 0) * 50).padStart(6, '0'), 8, 8 + 7 * s, c, s, sh);
      text(b, '×' + String(2 + ((r() * 7) | 0)).padStart(2, '0'), b.w / 2 - 12, 8 + 7 * s, c, s, sh);
      const tl = 'TIME';
      text(b, tl, b.w - 8 - textWidth(tl, s), 8, c, s, sh);
      const tv = String(100 + ((r() * 300) | 0));
      text(b, tv, b.w - 8 - textWidth(tv, s), 8 + 7 * s, c, s, sh);
    },
    topdown(b, game, v, r, P) {
      const T = 16;
      const grass = mix([92, 164, 84], P[2], 0.22);
      const leaf = mix([44, 110, 62], P[3], 0.22);
      const water = mix([64, 140, 220], P[0], 0.22);
      b.rect(0, 0, b.w, b.h, grass);
      for (let y = 0; y < b.h; y++) for (let x = 0; x < b.w; x++) if (r() < 0.05) b.set(x, y, mix(grass, BLACK, 0.16));
      const pathY = Math.floor((b.h * (0.45 + r() * 0.1)) / T) * T;
      const sand = mix([226, 200, 140], P[4], 0.2);
      b.rect(0, pathY, b.w, T * 2, sand);
      const pathX = Math.floor((b.w * (0.3 + r() * 0.3)) / T) * T;
      b.rect(pathX, 0, T * 2, b.h, sand);
      for (let i = 0; i < 90; i++) b.set(r() * b.w, pathY + r() * T * 2, mix(sand, BLACK, 0.12));
      // path edges and wildflowers
      b.rect(0, pathY - 1, b.w, 1, mix(grass, BLACK, 0.2));
      b.rect(0, pathY + T * 2, b.w, 1, mix(grass, WHITE, 0.15));
      const petals = [[255, 255, 255], [255, 214, 92], [255, 142, 178]];
      for (let n = 0; n < b.w / 7; n++) {
        const fx = Math.round(r() * b.w);
        const fy = Math.round(r() * b.h);
        const onPath = (fy > pathY - 4 && fy < pathY + T * 2 + 4) || (fx > pathX - 4 && fx < pathX + T * 2 + 4);
        if (onPath) continue;
        const pc = petals[n % 3];
        b.set(fx, fy, pc);
        b.set(fx + 2, fy + 1, pc);
        b.set(fx + 1, fy + 2, pc);
        b.set(fx + 1, fy + 1, mix(pc, [255, 190, 60], 0.5));
      }
      const wx = Math.floor((b.w * 0.66) / T) * T;
      const wy = Math.floor((b.h * 0.64) / T) * T;
      b.rect(wx, wy, b.w - wx, b.h - wy, water);
      b.rect(wx, wy, b.w - wx, 2, mix(water, WHITE, 0.5));
      for (let i = 0; i < 12; i++) b.rect(wx + 4 + r() * (b.w - wx - 8), wy + 6 + r() * (b.h - wy - 8), 4, 1, mix(water, WHITE, 0.55));
      const hx = Math.floor((b.w * 0.12) / T) * T;
      const hy = Math.floor((b.h * 0.1) / T) * T;
      b.rect(hx, hy + 12, T * 3, T * 2 - 4, mix(P[4], WHITE, 0.55));
      for (let j = 0; j < 14; j++) b.rect(hx - 2 + j * 0.5, hy + j, T * 3 + 4 - j, 1, mix(P[4], [190, 50, 50], 0.55));
      b.rect(hx + T + 2, hy + 26, 10, 14, mix(P[3], BLACK, 0.3));
      b.rect(hx + 6, hy + 20, 7, 6, mix(P[0], WHITE, 0.3));
      b.rect(hx + T * 2 + 6, hy + 20, 7, 6, mix(P[0], WHITE, 0.3));
      // picket fence in front of the house
      const fy0 = hy + T * 2 + 14;
      for (let fx = hx - 6; fx < hx + T * 3 + 6; fx += 4) b.rect(fx, fy0, 2, 6, [236, 226, 206]);
      b.rect(hx - 6, fy0 + 2, T * 3 + 12, 1, [200, 184, 160]);
      const trees = [];
      for (let i = 0; i < 12; i++) {
        const tx = r() * b.w;
        const ty = r() * b.h;
        if (Math.abs(ty - pathY - T) < T * 1.6 || Math.abs(tx - pathX - T) < T * 1.4) continue;
        if (tx > wx - 6 && ty > wy - 6) continue;
        if (tx < hx + T * 3 + 8 && ty < hy + T * 3) continue;
        trees.push([tx, ty]);
      }
      trees.sort((a, c) => a[1] - c[1]).forEach(([tx, ty]) => {
        b.circle(tx + 2, ty + 3, 8, mix(grass, BLACK, 0.35));
        b.circle(tx, ty, 8, leaf);
        b.circle(tx - 2, ty - 2, 5, mix(leaf, grass, 0.55));
        b.rect(tx - 2, ty - 4, 2, 2, mix(grass, WHITE, 0.3));
      });
      b.sprite(pathX + 10, pathY + 8, HERO, { a: [24, 20, 28], b: mix(P[4], [60, 120, 220], 0.4), c: [255, 214, 170], d: mix(P[3], BLACK, 0.2) }, false, 1);
      if (v === 'c') {
        const bx = 8;
        const by = b.h - 50;
        const bw = b.w - 16;
        b.rect(bx, by, bw, 42, [16, 16, 28]);
        b.rect(bx + 2, by + 2, bw - 4, 1, WHITE);
        b.rect(bx + 2, by + 39, bw - 4, 1, WHITE);
        b.rect(bx + 2, by + 2, 1, 38, WHITE);
        b.rect(bx + bw - 3, by + 2, 1, 38, WHITE);
        const lines = ['THE ROAD NORTH IS OPEN.', 'TAKE THIS LANTERN.'];
        const s = b.w >= 240 ? 2 : 1;
        text(b, lines[0], bx + 10, by + 9, WHITE, s);
        text(b, lines[1], bx + 10, by + 9 + 13 * (s === 2 ? 1 : 0.8), WHITE, s);
      }
      const s = b.w >= 240 ? 2 : 1;
      for (let i = 0; i < 5; i++) text(b, '*', 8 + i * 9 * s * 0.6, 7, i < 3 ? [255, 70, 90] : mix(grass, BLACK, 0.4), s);
      const rupees = String(((r() * 900) | 0) + 40).padStart(3, '0');
      text(b, rupees, b.w - 8 - textWidth(rupees, s), 7, WHITE, s, mix(grass, BLACK, 0.5));
    },
    shooter(b, game, v, r, P) {
      const P0 = game.art.pal.map(rgb);
      sky(b, [P0[0], P0[1]], b.h);
      stars(b, r, (b.w * b.h) / 30, mix(P0[4], WHITE, 0.7), b.h);
      const cols = 8;
      const gap = Math.round(b.w / (cols + 3));
      for (let row = 0; row < 4; row++)
        for (let i = 0; i < cols; i++) {
          if (r() < 0.12) continue;
          const c = row < 1 ? mix(P0[4], WHITE, 0.2) : row < 2 ? [240, 90, 90] : [90, 170, 255];
          b.sprite(Math.round(gap * 1.5 + i * gap) + (row % 2) * 4, 28 + row * 16, BUG, { a: mix(c, WHITE, 0.3), b: c, c: mix(c, BLACK, 0.4), w: WHITE });
        }
      const sx = Math.round(b.w * (0.4 + r() * 0.2));
      const sy = b.h - 30;
      b.sprite(sx, sy, SHIP, { a: [70, 80, 110], b: [235, 240, 255], c: [255, 60, 60], d: P0[4] });
      for (let k = 1; k < 5; k++) b.rect(sx + 6, sy - k * 14, 1, 5, P0[4]);
      for (let k = 0; k < 4; k++) b.rect(r() * b.w, 60 + r() * (b.h - 110), 1, 3, [255, 120, 120]);
      const s = b.w >= 240 ? 2 : 1;
      text(b, '1UP', 8, 6, [255, 80, 80], s);
      text(b, String(((r() * 900) | 0) * 10 + 1000).padStart(6, '0'), 8, 6 + 7 * s, WHITE, s);
      const hi = 'HI 030000';
      text(b, hi, b.w - 8 - textWidth(hi, s), 6, WHITE, s);
      for (let i = 0; i < 2; i++) b.sprite(8 + i * 15, b.h - 13, SHIP, { a: [70, 80, 110], b: [235, 240, 255], c: [255, 60, 60], d: P0[4] });
    },
    puzzle(b, game, v, r, P) {
      if (game.art.motif === 'maze') {
        SCENES.maze(b, P, r);
        const s = b.w >= 240 ? 2 : 1;
        text(b, '1UP  ' + String(((r() * 900) | 0) * 10), 8, 4, WHITE, s);
        return;
      }
      b.rect(0, 0, b.w, b.h, P[1]);
      for (let y = 0; y < b.h; y += 8) for (let x = 0; x < b.w; x += 8) {
        b.rect(x, y, 7, 7, mix(P[1], P[2], 0.35));
        b.rect(x, y, 7, 1, mix(P[1], WHITE, 0.2));
      }
      const cell = Math.floor((b.h - 16) / 18);
      const ww = cell * 10;
      const wx = Math.round(b.w * 0.3 - ww / 2 + 10);
      const wy = 8;
      b.rect(wx - 2, wy - 2, ww + 4, cell * 18 + 4, P[3]);
      b.rect(wx, wy, ww, cell * 18, P[0]);
      const heights = Array.from({ length: 10 }, () => 3 + Math.floor(r() * 6));
      heights[Math.floor(r() * 10)] = 0;
      const shades = [P[3], mix(P[3], P[2], 0.5), P[2]];
      const drawCell = (cx, cy, c) => {
        b.rect(wx + cx * cell, wy + cy * cell, cell - 1, cell - 1, c);
        b.rect(wx + cx * cell + 1, wy + cy * cell + 1, cell - 3, 1, mix(c, WHITE, 0.3));
      };
      heights.forEach((h, i) => { for (let j = 0; j < h; j++) drawCell(i, 17 - j, shades[(i + j) % 3]); });
      [[4, 3], [5, 3], [6, 3], [5, 4]].forEach(([cx, cy]) => drawCell(cx, cy + (v.charCodeAt(0) % 5), P[3]));
      const px = wx + ww + 14;
      const panel = (label, value, y) => {
        b.rect(px - 3, y - 3, b.w - px - 6, 24, P[0]);
        text(b, label, px, y, P[3], 1);
        text(b, value, px, y + 9, P[3], 2);
      };
      panel('SCORE', String(((r() * 9000) | 0) + 1000), 12);
      panel('LEVEL', String(3 + ((r() * 6) | 0)), 44);
      panel('LINES', String(20 + ((r() * 60) | 0)), 76);
    },
    racer(b, game, v, r, P) {
      paintScene(b, game, 'bg' + v);
      b.sprite(Math.round(b.w / 2 - 16), b.h - 34, KART, { a: [24, 24, 30], b: [220, 50, 60], c: [120, 200, 255], d: [70, 70, 80] }, false, 2);
      b.sprite(Math.round(b.w * 0.62), Math.round(b.h * 0.6), KART, { a: [24, 24, 30], b: [60, 170, 90], c: [120, 200, 255], d: [70, 70, 80] }, false, 1);
      const s = b.w >= 240 ? 2 : 1;
      text(b, 'LAP 2/3', 8, 8, WHITE, s, [0, 0, 0]);
      const pos = '1ST';
      text(b, pos, b.w - 8 - textWidth(pos, s * 2), 8, P[4], s * 2, [0, 0, 0]);
      text(b, "0'42\"18".replace('"', "'"), 8, 8 + 7 * s, WHITE, s, [0, 0, 0]);
    },
  };

  function game(id) {
    const g = D().games.find((x) => x.id === id);
    if (!g) throw new Error('Unknown game ' + id);
    return g;
  }
  function platformOf(g) {
    return D().platforms.find((p) => p.id === g.platform);
  }

  const KINDS = { box: [60, 80, 6], wide: [128, 72, 5], square: [64, 64, 6], tall: [72, 128, 5], banner: [150, 50, 4] };

  const Art = {
    palette(id) {
      return game(id).art.pal.slice();
    },
    cover(id, kind = 'box') {
      const key = 'cover:' + id + ':' + kind;
      if (cache.has(key)) return cache.get(key);
      const [w, h, s] = KINDS[kind] || KINDS.box;
      const b = new Buf(w, h);
      paintScene(b, game(id), kind);
      const url = b.toURL(s);
      cache.set(key, url);
      return url;
    },
    frame(id, variant = 'a') {
      const key = 'frame:' + id + ':' + variant;
      if (cache.has(key)) return cache.get(key);
      const g = game(id);
      const p = platformOf(g);
      let [w, h] = p.res;
      if (p.id === 'nds' || p.id === '3ds') [w, h] = p.id === 'nds' ? [256, 192] : [400, 240];
      const b = new Buf(w, h);
      const r = rng(hash(id + variant));
      const P = g.art.pal.map(rgb);
      (PLAY[g.art.play] || PLAY.platformer)(b, g, variant, r, P);
      const url = b.toURL(w > 300 ? 2 : 3);
      cache.set(key, url);
      return url;
    },
    dual(id, variant = 'a') {
      const key = 'dual:' + id + ':' + variant;
      if (cache.has(key)) return cache.get(key);
      const g = game(id);
      const p = platformOf(g);
      const top = Art.frame(id, variant);
      const [bw, bh] = p.id === '3ds' ? [320, 240] : [256, 192];
      const b = new Buf(bw, bh);
      const P = g.art.pal.map(rgb);
      const r = rng(hash(id + variant + 'touch'));
      // Touch screen: a map with a party menu.
      b.rect(0, 0, bw, bh, mix(P[3], BLACK, 0.35));
      for (let y = 0; y < bh; y += 8) for (let x = 0; x < bw; x += 8) b.rect(x, y, 7, 7, mix(P[3], P[2], 0.18 + ((x + y) % 16 ? 0 : 0.06)));
      const mx = 12;
      const my = 12;
      const mw = bw * 0.58;
      const mh = bh - 24;
      b.rect(mx, my, mw, mh, mix(P[2], P[3], 0.4));
      for (let i = 0; i < 26; i++) b.rect(mx + 4 + r() * (mw - 16), my + 4 + r() * (mh - 16), 6 + r() * 14, 4 + r() * 10, mix(P[2], P[4], r() * 0.3));
      for (let i = 0; i < 6; i++) b.rect(mx + r() * mw, my + 4, 2, mh - 8, mix(P[1], P[2], 0.4));
      b.circle(mx + mw * 0.44, my + mh * 0.52, 4, P[4]);
      b.circle(mx + mw * 0.44, my + mh * 0.52, 8, P[4], 0.25);
      const px = mx + mw + 10;
      ['ITEMS', 'PARTY', 'MAP', 'SAVE'].forEach((label, k) => {
        const y = my + k * ((mh + 6) / 4);
        const h = (mh + 6) / 4 - 6;
        b.rect(px, y, bw - px - 12, h, k === 2 ? P[4] : mix(P[3], WHITE, 0.12));
        b.rect(px, y + h - 2, bw - px - 12, 2, mix(k === 2 ? P[4] : P[3], BLACK, 0.3));
        text(b, label, px + 6, y + h / 2 - 5, k === 2 ? mix(P[3], BLACK, 0.4) : WHITE, 2);
      });
      const out = { top, bottom: b.toURL(3) };
      cache.set(key, out);
      return out;
    },
    aspect(id) {
      const p = platformOf(game(id));
      if (p.id === 'nds') return 256 / 192;
      if (p.id === '3ds') return 400 / 240;
      // Display aspect for TV consoles is 4:3; handhelds are square-pixel.
      const handheld = ['gb', 'gbc', 'gba', 'gg', 'ngp', 'lynx', 'psp'].includes(p.id);
      return handheld ? p.res[0] / p.res[1] : 4 / 3;
    },
    grain(alpha = 0.09, size = 160) {
      const key = 'grain:' + alpha + ':' + size;
      if (cache.has(key)) return cache.get(key);
      const c = document.createElement('canvas');
      c.width = c.height = size;
      const ctx = c.getContext('2d');
      const img = ctx.createImageData(size, size);
      const r = rng(7);
      for (let i = 0; i < size * size; i++) {
        const v = Math.floor(r() * 255);
        img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v;
        img.data[i * 4 + 3] = Math.floor(alpha * 255);
      }
      ctx.putImageData(img, 0, 0);
      const url = c.toDataURL('image/png');
      cache.set(key, url);
      return url;
    },
  };

  window.StudioArt = Art;
})();
