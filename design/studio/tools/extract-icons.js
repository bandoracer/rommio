#!/usr/bin/env node
// Regenerates core/icons.js from a Lucide UMD build.
//   curl -sL https://cdn.jsdelivr.net/npm/lucide@0.469.0/dist/umd/lucide.js -o /tmp/lucide.js
//   node design/studio/tools/extract-icons.js /tmp/lucide.js design/studio/core/icons.js
// Add names to WANT (studio name -> Lucide export name) and rerun.
const fs = require('fs');
const path = require('path');

const [src, dest] = process.argv.slice(2);
if (!src || !dest) {
  console.error('usage: extract-icons.js <lucide.js> <icons.js>');
  process.exit(1);
}

const WANT = {
  home: 'House', library: 'LibraryBig', grid: 'LayoutGrid', collections: 'Layers', download: 'Download',
  downloads: 'ArrowDownToLine', settings: 'Settings', sliders: 'SlidersHorizontal', search: 'Search',
  play: 'Play', pause: 'Pause', 'chevron-left': 'ChevronLeft', 'chevron-right': 'ChevronRight',
  'chevron-down': 'ChevronDown', 'chevron-up': 'ChevronUp', 'arrow-left': 'ArrowLeft', 'arrow-right': 'ArrowRight',
  'arrow-up-right': 'ArrowUpRight', x: 'X', more: 'Ellipsis', 'more-v': 'EllipsisVertical', cloud: 'Cloud',
  'cloud-check': 'CloudCheck', 'cloud-off': 'CloudOff', 'cloud-upload': 'CloudUpload', 'cloud-alert': 'CloudAlert',
  sync: 'RefreshCw', alert: 'TriangleAlert', 'alert-circle': 'CircleAlert', check: 'Check', 'check-circle': 'CircleCheck',
  'wifi-off': 'WifiOff', wifi: 'Wifi', server: 'Server', lock: 'LockKeyhole', user: 'User', users: 'Users',
  gamepad: 'Gamepad2', controller: 'Gamepad', hand: 'Hand', pointer: 'Pointer', 'fast-forward': 'FastForward',
  rewind: 'Rewind', save: 'Save', bookmark: 'Bookmark', history: 'History', clock: 'Clock3', trash: 'Trash2',
  eye: 'Eye', 'eye-off': 'EyeOff', sun: 'Sun', moon: 'Moon', storage: 'HardDrive', cpu: 'Cpu', link: 'Link2',
  qr: 'QrCode', shield: 'ShieldCheck', plus: 'Plus', minus: 'Minus', filter: 'ListFilter', sort: 'ArrowUpDown',
  star: 'Star', heart: 'Heart', menu: 'Menu', camera: 'Camera', volume: 'Volume2', 'volume-off': 'VolumeX',
  restart: 'RotateCcw', exit: 'LogOut', swap: 'ArrowLeftRight', keyboard: 'Keyboard', zap: 'Zap',
  sparkles: 'Sparkles', globe: 'Globe', key: 'KeyRound', mail: 'Mail', external: 'ExternalLink', info: 'Info',
  branch: 'GitBranch', merge: 'GitMerge', smartphone: 'Smartphone', tablet: 'Tablet', monitor: 'Monitor', tv: 'Tv',
  layers: 'Layers2', list: 'List', 'layout-list': 'LayoutList', rows: 'Rows3', maximize: 'Maximize2',
  minimize: 'Minimize2', vibrate: 'Vibrate', rotate: 'RotateCw', flip: 'FlipHorizontal2', battery: 'BatteryMedium',
  signal: 'Signal', scan: 'ScanLine', box: 'Box', package: 'Package', folder: 'Folder', file: 'File',
  archive: 'Archive', circle: 'Circle', square: 'Square', triangle: 'Triangle', crosshair: 'Crosshair',
  joystick: 'Joystick', trophy: 'Trophy', calendar: 'Calendar', timer: 'Timer', upload: 'Upload', pin: 'Pin',
  flag: 'Flag', tag: 'Tag', palette: 'Palette', contrast: 'Contrast', accessibility: 'Accessibility',
  'log-in': 'LogIn', at: 'AtSign', fingerprint: 'Fingerprint', radar: 'Radar', waves: 'AudioWaveform',
  shuffle: 'Shuffle', copy: 'Copy', image: 'Image', 'badge-check': 'BadgeCheck', loader: 'LoaderCircle',
  grip: 'GripVertical', move: 'Move', scaling: 'Scaling', expand: 'Expand', sidebar: 'PanelLeft', bell: 'Bell',
  power: 'Power', cast: 'Cast', disc: 'Disc3', cartridge: 'MemoryStick', dice: 'Dices', 'split': 'SquareSplitVertical',
  'monitor-play': 'MonitorPlay', 'circle-dot': 'CircleDot', 'hard-drive-download': 'HardDriveDownload',
  // v2 additions
  mic: 'Mic', newspaper: 'Newspaper', 'book-open': 'BookOpen', chart: 'ChartColumn', activity: 'Activity',
  flame: 'Flame', film: 'Film', gallery: 'GalleryVerticalEnd', compass: 'Compass', map: 'Map',
  dashboard: 'LayoutDashboard', hourglass: 'Hourglass', pen: 'PenLine', medal: 'Medal', crown: 'Crown',
  devices: 'MonitorSmartphone', 'square-stack': 'SquareStack', 'list-ordered': 'ListOrdered', 'columns': 'Columns2',
  table: 'Table2', 'arrow-down': 'ArrowDown', 'arrow-up': 'ArrowUp', 'circle-play': 'CirclePlay', 'rotate-ccw': 'RotateCcw',
  'text-search': 'TextSearch', 'panel-right': 'PanelRight', 'scroll': 'ScrollText', 'quote': 'Quote', 'shapes': 'Shapes',
  'party': 'PartyPopper', 'ticket': 'Ticket', 'cloud-download': 'CloudDownload', 'infinity': 'Infinity', 'moon-star': 'MoonStar',
  'sunrise': 'Sunrise', 'wand': 'WandSparkles', 'blocks': 'Blocks', 'orbit': 'Orbit', 'gauge': 'Gauge', 'plug': 'Plug',
};

const lucide = require(path.resolve(src));
const out = {};
for (const [name, lucideName] of Object.entries(WANT)) {
  const node = lucide.icons[lucideName];
  if (!node) {
    console.error('missing', name, lucideName);
    continue;
  }
  const children = node[0] === 'svg' ? node[2] : node;
  out[name] = children
    .map(([tag, attrs]) => `<${tag} ${Object.entries(attrs).map(([a, v]) => `${a}="${v}"`).join(' ')}/>`)
    .join('');
}

const body = Object.entries(out).map(([k, v]) => `  ${JSON.stringify(k)}: ${JSON.stringify(v)}`).join(',\n');
fs.writeFileSync(
  dest,
  `/* Icon subset from Lucide v0.469.0 (ISC License, https://lucide.dev).\n   Generated by tools/extract-icons.js — edit WANT there, not this file. */\nwindow.StudioIcons = {\n${body}\n};\n`,
);
console.log(`${Object.keys(out).length} icons -> ${dest}`);
