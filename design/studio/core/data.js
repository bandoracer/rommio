/*
 * Mock data for every mockup. Everything a screen shows should come from here
 * so that all five versions tell the same story and can be compared honestly.
 *
 * Times are minutes-ago (number) and are formatted by kit.ago().
 * Sizes are in MB and are formatted by kit.size().
 * `art` drives the procedural cover/screen generator in core/art.js:
 *   motif  — scene type (space, castle, forest, ocean, city, desert, dungeon,
 *            sky, snow, volcano, track, arena, puzzle, maze, cave, neon)
 *   pal    — 5 colours: [sky-top, sky-bottom, far, near, accent]
 *   play   — gameplay generator (platformer, topdown, shooter, puzzle, racer)
 */
(function () {
  const platforms = [
    { id: 'nes', name: 'Nintendo Entertainment System', short: 'NES', maker: 'Nintendo', year: 1985, tier: 'touch', buttons: 2, color: '#d6312f', games: 216, installed: 12, core: 'FCEUmm', res: [256, 240], orient: 'any' },
    { id: 'snes', name: 'Super Nintendo', short: 'SNES', maker: 'Nintendo', year: 1991, tier: 'touch', buttons: 6, color: '#7b6fd6', games: 241, installed: 14, core: 'Snes9x', res: [256, 224], orient: 'landscape' },
    { id: 'n64', name: 'Nintendo 64', short: 'N64', maker: 'Nintendo', year: 1996, tier: 'touch', buttons: 8, color: '#1f9b5a', games: 88, installed: 4, core: 'Mupen64Plus-Next', res: [320, 240], orient: 'landscape' },
    { id: 'gb', name: 'Game Boy', short: 'GB', maker: 'Nintendo', year: 1989, tier: 'touch', buttons: 2, color: '#8fa34a', games: 132, installed: 6, core: 'Gambatte', res: [160, 144], orient: 'any' },
    { id: 'gbc', name: 'Game Boy Color', short: 'GBC', maker: 'Nintendo', year: 1998, tier: 'touch', buttons: 2, color: '#e0a526', games: 97, installed: 5, core: 'Gambatte', res: [160, 144], orient: 'any' },
    { id: 'gba', name: 'Game Boy Advance', short: 'GBA', maker: 'Nintendo', year: 2001, tier: 'touch', buttons: 4, color: '#5a4fcf', games: 184, installed: 13, core: 'mGBA', res: [240, 160], orient: 'landscape' },
    { id: 'nds', name: 'Nintendo DS', short: 'DS', maker: 'Nintendo', year: 2004, tier: 'touch', buttons: 6, color: '#9aa3ad', games: 73, installed: 3, core: 'melonDS DS', res: [256, 384], orient: 'portrait' },
    { id: '3ds', name: 'Nintendo 3DS', short: '3DS', maker: 'Nintendo', year: 2011, tier: 'touch', buttons: 6, color: '#c8323c', games: 41, installed: 2, core: 'Azahar', res: [400, 480], orient: 'portrait' },
    { id: 'genesis', name: 'Sega Genesis', short: 'Genesis', maker: 'Sega', year: 1989, tier: 'touch', buttons: 6, color: '#2b2b2b', games: 172, installed: 8, core: 'Genesis Plus GX', res: [320, 224], orient: 'landscape' },
    { id: 'sms', name: 'Master System', short: 'SMS', maker: 'Sega', year: 1986, tier: 'touch', buttons: 2, color: '#c4262e', games: 64, installed: 1, core: 'Genesis Plus GX', res: [256, 192], orient: 'any' },
    { id: 'gg', name: 'Game Gear', short: 'GG', maker: 'Sega', year: 1991, tier: 'touch', buttons: 2, color: '#3a3f47', games: 52, installed: 1, core: 'Genesis Plus GX', res: [160, 144], orient: 'any' },
    { id: 'psx', name: 'PlayStation', short: 'PS1', maker: 'Sony', year: 1995, tier: 'touch', buttons: 8, color: '#8d96a0', games: 146, installed: 6, core: 'Beetle PSX', res: [320, 240], orient: 'landscape' },
    { id: 'psp', name: 'PlayStation Portable', short: 'PSP', maker: 'Sony', year: 2005, tier: 'touch', buttons: 6, color: '#1d1f24', games: 38, installed: 2, core: 'PPSSPP', res: [480, 272], orient: 'landscape' },
    { id: 'pce', name: 'TurboGrafx-16', short: 'TG-16', maker: 'NEC', year: 1989, tier: 'touch', buttons: 2, color: '#e6e1d6', games: 47, installed: 2, core: 'Beetle PCE', res: [256, 232], orient: 'any' },
    { id: 'ngp', name: 'Neo Geo Pocket Color', short: 'NGPC', maker: 'SNK', year: 1999, tier: 'touch', buttons: 2, color: '#2f6fd1', games: 22, installed: 1, core: 'Beetle NGP', res: [160, 152], orient: 'any' },
    { id: 'lynx', name: 'Atari Lynx', short: 'Lynx', maker: 'Atari', year: 1989, tier: 'touch', buttons: 2, color: '#3b3b3b', games: 19, installed: 0, core: 'Handy', res: [160, 102], orient: 'any' },
    { id: 'arcade', name: 'Arcade', short: 'Arcade', maker: 'Various', year: 1978, tier: 'touch', buttons: 4, color: '#f2b705', games: 352, installed: 10, core: 'FBNeo', res: [288, 224], orient: 'landscape' },
    { id: 'dc', name: 'Dreamcast', short: 'DC', maker: 'Sega', year: 1999, tier: 'controller', buttons: 4, color: '#e86a1c', games: 44, installed: 1, core: 'Flycast', res: [640, 480], orient: 'landscape' },
    { id: 'gc', name: 'GameCube', short: 'GC', maker: 'Nintendo', year: 2001, tier: 'controller', buttons: 8, color: '#5b4a9e', games: 36, installed: 0, core: 'Dolphin', res: [640, 480], orient: 'landscape' },
    { id: 'vb', name: 'Virtual Boy', short: 'VB', maker: 'Nintendo', year: 1995, tier: 'controller', buttons: 4, color: '#c01d1d', games: 14, installed: 0, core: 'Beetle VB', res: [384, 224], orient: 'landscape' },
    { id: 'saturn', name: 'Sega Saturn', short: 'Saturn', maker: 'Sega', year: 1995, tier: 'unsupported', buttons: 6, color: '#4b5160', games: 29, installed: 0, core: null, res: [320, 224], orient: 'landscape', blocked: 'No validated core on this device yet' },
    { id: 'ps2', name: 'PlayStation 2', short: 'PS2', maker: 'Sony', year: 2000, tier: 'unsupported', buttons: 8, color: '#23315e', games: 57, installed: 0, core: null, res: [640, 448], orient: 'landscape', blocked: 'Browse only — no embedded player' },
  ];

  // prettier-ignore
  const games = [
    { id: 'smetroid', title: 'Super Metroid', platform: 'snes', year: 1994, publisher: 'Nintendo', genre: 'Action · Exploration', players: 1, rating: 96, size: 3.0, installed: true, played: 42, hours: 11.4, favorite: true, sync: 'synced',
      blurb: 'A lone bounty hunter descends into the caverns of planet Zebes to recover a stolen hatchling. Every sealed door is a promise the map will keep later.',
      art: { motif: 'cave', pal: ['#0b0620', '#3a1450', '#5d2a6e', '#1c0c2b', '#ff7a3d'], play: 'platformer' } },
    { id: 'chrono', title: 'Chrono Trigger', platform: 'snes', year: 1995, publisher: 'Square', genre: 'RPG', players: 1, rating: 97, size: 4.0, installed: true, played: 60 * 26, hours: 23.8, favorite: true, sync: 'pending',
      blurb: 'A festival mishap throws a boy from Truce across the ages. Thirteen endings, one clock tower, and a party that outgrows time itself.',
      art: { motif: 'sky', pal: ['#f7b267', '#f4845f', '#6c4a8c', '#2b2d5c', '#ffe066'], play: 'topdown' } },
    { id: 'alttp', title: 'A Link to the Past', platform: 'snes', year: 1992, publisher: 'Nintendo', genre: 'Action · Adventure', players: 1, rating: 95, size: 1.0, installed: true, played: 60 * 24 * 3, hours: 8.1, sync: 'synced',
      blurb: 'Rain, a lantern, and a castle full of secrets. The light world and the dark world fold into one of the tightest maps ever drawn.',
      art: { motif: 'forest', pal: ['#8fd694', '#3f8f5a', '#2c5d3f', '#173826', '#f6d04d'], play: 'topdown' } },
    { id: 'dkc2', title: "Diddy's Kong Quest", platform: 'snes', year: 1995, publisher: 'Rare', genre: 'Platformer', players: 2, rating: 90, size: 4.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Pirate ships, bramble mazes and a soundtrack that still gets covered weekly.',
      art: { motif: 'ocean', pal: ['#ffb36b', '#e8575a', '#6b2f5e', '#261437', '#ffd8a8'], play: 'platformer' } },
    { id: 'contra3', title: 'Contra III', platform: 'snes', year: 1992, publisher: 'Konami', genre: 'Run and gun', players: 2, rating: 88, size: 1.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Two soldiers, one alien invasion, zero patience for standing still.',
      art: { motif: 'city', pal: ['#ff9a3c', '#c2410c', '#5b1f14', '#1a0b09', '#ffe28a'], play: 'platformer' } },
    { id: 'earthbound', title: 'EarthBound', platform: 'snes', year: 1995, publisher: 'Nintendo', genre: 'RPG', players: 1, rating: 93, size: 3.0, installed: true, played: 60 * 24 * 9, hours: 14.2, sync: 'conflict',
      blurb: 'A meteorite lands in Onett and a kid with a baseball bat walks out into a very strange America.',
      art: { motif: 'city', pal: ['#b8e0f6', '#7cc0e8', '#e97fa3', '#5e3a6e', '#fff27a'], play: 'topdown' } },

    { id: 'smw', title: 'Super Mario World', platform: 'snes', year: 1991, publisher: 'Nintendo', genre: 'Platformer', players: 2, rating: 94, size: 0.5, installed: true, played: 60 * 24 * 14, hours: 4.2, sync: 'synced',
      blurb: 'Dinosaur Land, a cape feather, and ninety-six exits hiding in plain sight.',
      art: { motif: 'sky', pal: ['#9fe3ff', '#5ab7ff', '#58c26b', '#2e7d3a', '#ffd23f'], play: 'platformer' } },
    { id: 'mmx', title: 'Mega Man X', platform: 'snes', year: 1994, publisher: 'Capcom', genre: 'Action · Platformer', players: 1, rating: 91, size: 1.5, installed: true, played: 60 * 24 * 6, hours: 2.9, sync: 'synced',
      blurb: 'A new blue hero, a dash that changes everything, and a highway intro stage that teaches without a word.',
      art: { motif: 'city', pal: ['#0d1b3d', '#2d4f9e', '#5b86d9', '#0a1330', '#9ff0ff'], play: 'platformer' } },
    { id: 'ff6', title: 'Final Fantasy VI', platform: 'snes', year: 1994, publisher: 'Square', genre: 'RPG', players: 1, rating: 94, size: 3.0, installed: true, played: 60 * 24 * 40, hours: 18.5, sync: 'synced',
      blurb: 'An opera house, a world that ends halfway through, and fourteen heroes who each get a song.',
      art: { motif: 'castle', pal: ['#1a1420', '#4a2f45', '#7a5a6a', '#140f18', '#f0d27a'], play: 'topdown' } },
    { id: 'yoshi', title: "Yoshi's Island", platform: 'snes', year: 1995, publisher: 'Nintendo', genre: 'Platformer', players: 1, rating: 93, size: 2.0, installed: true, played: 60 * 24 * 25, hours: 1.7, sync: 'synced',
      blurb: 'Crayon skies, a crying baby, and eggs that bounce exactly where you aim them.',
      art: { motif: 'sky', pal: ['#fff4d6', '#ffd9a8', '#9ad96b', '#4f9a3a', '#ff6fa8'], play: 'platformer' } },
    { id: 'som', title: 'Secret of Mana', platform: 'snes', year: 1993, publisher: 'Square', genre: 'Action RPG', players: 3, rating: 90, size: 2.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'A rusty sword pulled from a stream, and a ring menu friends can share.',
      art: { motif: 'forest', pal: ['#d7f5c8', '#86d17a', '#3f9a52', '#1e5a31', '#ff9fc8'], play: 'topdown' } },
    { id: 'starfox', title: 'Star Fox', platform: 'snes', year: 1993, publisher: 'Nintendo', genre: 'Shooter', players: 1, rating: 84, size: 1.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Polygons on a 16-bit console, flown through the arches of Corneria.',
      art: { motif: 'space', pal: ['#050814', '#10204a', '#2f5fb0', '#08102a', '#ff5d5d'], play: 'shooter' } },
    { id: 'fzero', title: 'F-Zero', platform: 'snes', year: 1991, publisher: 'Nintendo', genre: 'Racing', players: 1, rating: 85, size: 0.5, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Mode 7 at four hundred kilometres an hour, and Big Blue on the speakers.',
      art: { motif: 'track', pal: ['#1b1340', '#5b2a86', '#3a2f7a', '#171238', '#ffe14d'], play: 'racer' } },
    { id: 'castle4', title: 'Super Castlevania IV', platform: 'snes', year: 1991, publisher: 'Konami', genre: 'Action', players: 1, rating: 90, size: 1.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'A whip that swings in eight directions and a rotating room nobody forgets.',
      art: { motif: 'castle', pal: ['#120b16', '#2e1630', '#5a2a3f', '#0c070f', '#e8c46a'], play: 'platformer' } },
    { id: 'kss', title: 'Kirby Super Star', platform: 'snes', year: 1996, publisher: 'HAL Laboratory', genre: 'Platformer', players: 2, rating: 87, size: 4.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Eight games in one cartridge, and a helper for a friend on the second pad.',
      art: { motif: 'sky', pal: ['#ffe3f1', '#ffb3d6', '#b8e6ff', '#6aa9e0', '#ffe066'], play: 'platformer' } },

    { id: 'mm2', title: 'Mega Man 2', platform: 'nes', year: 1989, publisher: 'Capcom', genre: 'Action · Platformer', players: 1, rating: 92, size: 0.26, installed: true, played: 60 * 5, hours: 3.1, sync: 'synced',
      blurb: 'Eight robot masters, one blue bomber, and the most famous stage-select screen in games.',
      art: { motif: 'city', pal: ['#0f1b4d', '#1f3b8f', '#3b6fd8', '#0a1030', '#6fe0ff'], play: 'platformer' } },
    { id: 'kirbyadv', title: "Kirby's Adventure", platform: 'nes', year: 1993, publisher: 'Nintendo', genre: 'Platformer', players: 1, rating: 88, size: 0.77, installed: true, played: 60 * 24 * 2, hours: 2.4, sync: 'synced',
      blurb: 'Dream Land has lost its fountain and Kirby has discovered he can copy anything he swallows.',
      art: { motif: 'sky', pal: ['#ffd6e8', '#ff9ec7', '#9ad9ff', '#4d8ed9', '#ffffff'], play: 'platformer' } },
    { id: 'galaga', title: 'Galaga: Demons of Death', platform: 'nes', year: 1988, publisher: 'Namco', genre: 'Shooter', players: 2, rating: 79, size: 0.04, installed: true, played: 60 * 24 * 12, hours: 0.9, sync: 'synced',
      blurb: 'The arcade shooter, carefully squeezed into a cartridge. Let them capture your ship on purpose.',
      art: { motif: 'space', pal: ['#02010a', '#0b0a2e', '#2a1a6e', '#0d0b24', '#5cff9d'], play: 'shooter' } },
    { id: 'ninja', title: 'Ninja Gaiden', platform: 'nes', year: 1989, publisher: 'Tecmo', genre: 'Action', players: 1, rating: 86, size: 0.26, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Cinematic cutscenes before anyone called them that, and birds placed with pure malice.',
      art: { motif: 'volcano', pal: ['#2b0a0a', '#8a1c1c', '#3d0f16', '#120407', '#ffb84d'], play: 'platformer' } },

    { id: 'linksawak', title: "Link's Awakening DX", platform: 'gbc', year: 1998, publisher: 'Nintendo', genre: 'Adventure', players: 1, rating: 91, size: 1.0, installed: true, played: 60 * 3, hours: 6.7, sync: 'syncing',
      blurb: 'Shipwrecked on Koholint, a hero collects eight instruments to wake a sleeping whale — and wonders what happens when it wakes.',
      art: { motif: 'ocean', pal: ['#bdf2ff', '#6fd3f0', '#2f8fb8', '#1a4d6b', '#ffe066'], play: 'topdown' } },
    { id: 'pkcrystal', title: 'Pokémon Crystal', platform: 'gbc', year: 2000, publisher: 'Game Freak', genre: 'RPG', players: 1, rating: 90, size: 2.0, installed: true, played: 60 * 24 * 5, hours: 31.0, sync: 'synced',
      blurb: 'Johto by day and night, with a real-time clock ticking inside the cartridge.',
      art: { motif: 'forest', pal: ['#d6f5ff', '#8ee3ef', '#4fb68d', '#2c6e5a', '#b9f3ff'], play: 'topdown' } },
    { id: 'tetris', title: 'Tetris', platform: 'gb', year: 1989, publisher: 'Nintendo', genre: 'Puzzle', players: 2, rating: 94, size: 0.03, installed: true, played: 60 * 24 * 20, hours: 5.5, sync: 'local',
      blurb: 'Seven shapes and a Russian folk song. The reason a whole generation bought a Game Boy.',
      art: { motif: 'puzzle', pal: ['#c4cfa1', '#8b956d', '#4d533c', '#1f1f1f', '#f2f7d0'], play: 'puzzle' } },

    { id: 'metfusion', title: 'Metroid Fusion', platform: 'gba', year: 2002, publisher: 'Nintendo', genre: 'Action · Exploration', players: 1, rating: 92, size: 8.0, installed: true, played: 60 * 24 * 4, hours: 4.9, sync: 'synced',
      blurb: 'A research station, a parasite that copies its host, and something wearing your old suit.',
      art: { motif: 'space', pal: ['#040b1a', '#0b2a4a', '#1f6f8b', '#07172b', '#ffcc33'], play: 'platformer' } },
    { id: 'aria', title: 'Aria of Sorrow', platform: 'gba', year: 2003, publisher: 'Konami', genre: 'Action · Exploration', players: 1, rating: 91, size: 8.0, installed: true, played: 60 * 24 * 6, hours: 7.2, sync: 'pending',
      blurb: 'A solar eclipse in 2035 pulls an exchange student into a castle that should not exist.',
      art: { motif: 'castle', pal: ['#1a0f2e', '#3d1e5c', '#6e3a8a', '#140a22', '#ff5d8f'], play: 'platformer' } },
    { id: 'advwars', title: 'Advance Wars', platform: 'gba', year: 2001, publisher: 'Intelligent Systems', genre: 'Strategy', players: 4, rating: 92, size: 4.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Bright little tanks, very serious tactics.',
      art: { motif: 'desert', pal: ['#ffe9a8', '#ffc766', '#d98841', '#7a4a2a', '#4fb3ff'], play: 'topdown' } },
    { id: 'goldensun', title: 'Golden Sun', platform: 'gba', year: 2001, publisher: 'Camelot', genre: 'RPG', players: 1, rating: 89, size: 8.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Psynergy, puzzles and the prettiest sprites the handheld ever pushed.',
      art: { motif: 'sky', pal: ['#fff1b8', '#ffc94d', '#e0822a', '#6e3a14', '#ffffff'], play: 'topdown' } },

    { id: 'sonic2', title: 'Sonic the Hedgehog 2', platform: 'genesis', year: 1992, publisher: 'Sega', genre: 'Platformer', players: 2, rating: 90, size: 1.0, installed: true, played: 60 * 30, hours: 1.6, sync: 'synced',
      blurb: 'Emerald Hill, a new sidekick, and the spin dash — the moment speed became a design language.',
      art: { motif: 'forest', pal: ['#5fb8ff', '#3c7fe0', '#2fb35a', '#1b6b36', '#ffd23f'], play: 'platformer' } },
    { id: 'sor2', title: 'Streets of Rage 2', platform: 'genesis', year: 1992, publisher: 'Sega', genre: "Beat 'em up", players: 2, rating: 91, size: 2.0, installed: true, played: 60 * 24 * 8, hours: 2.2, sync: 'synced',
      blurb: 'Neon streets, a Yuzo Koshiro soundtrack, and a friend on the couch.',
      art: { motif: 'neon', pal: ['#120c2e', '#3b1266', '#ff2e88', '#0a0620', '#2de2e6'], play: 'platformer' } },
    { id: 'gunstar', title: 'Gunstar Heroes', platform: 'genesis', year: 1993, publisher: 'Treasure', genre: 'Run and gun', players: 2, rating: 89, size: 1.0, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Treasure turned the 16-bit hardware into a fireworks show.',
      art: { motif: 'desert', pal: ['#ffe0a3', '#ff9f5a', '#c4553b', '#5a2020', '#ffffff'], play: 'platformer' } },

    { id: 'sotn', title: 'Symphony of the Night', platform: 'psx', year: 1997, publisher: 'Konami', genre: 'Action · Exploration', players: 1, rating: 95, size: 612, installed: true, played: 60 * 24 * 11, hours: 9.3, sync: 'synced',
      blurb: 'Dracula’s castle, inverted. The game that gave a genre half its name.',
      art: { motif: 'castle', pal: ['#07040f', '#1b0b2e', '#4a1537', '#0a0510', '#c9a86a'], play: 'platformer' } },
    { id: 'crash', title: 'Crash Bandicoot', platform: 'psx', year: 1996, publisher: 'Naughty Dog', genre: 'Platformer', players: 1, rating: 80, size: 498, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Wumpa fruit, crates, and a marsupial who spins first and asks later.',
      art: { motif: 'forest', pal: ['#ffcf6b', '#ff8a3d', '#2f9e5b', '#145a32', '#fff3b0'], play: 'platformer' } },
    { id: 'ff7', title: 'Final Fantasy VII', platform: 'psx', year: 1997, publisher: 'Square', genre: 'RPG', players: 1, rating: 92, size: 1900, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Three discs, one reactor, and a city stacked on top of another city.',
      art: { motif: 'city', pal: ['#0d1a14', '#133a2a', '#3f7a52', '#07130e', '#9dffb0'], play: 'topdown' } },

    { id: 'sm64', title: 'Super Mario 64', platform: 'n64', year: 1996, publisher: 'Nintendo', genre: '3D Platformer', players: 1, rating: 94, size: 8.0, installed: true, played: 60 * 24 * 15, hours: 6.0, sync: 'synced',
      blurb: 'A castle full of paintings, and the first time a camera had to learn to follow.',
      art: { motif: 'sky', pal: ['#8fd3ff', '#4aa7f0', '#5fd068', '#2c7a3a', '#ffe14d'], play: 'platformer' } },
    { id: 'mk64', title: 'Mario Kart 64', platform: 'n64', year: 1997, publisher: 'Nintendo', genre: 'Racing', players: 4, rating: 87, size: 12, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Four controllers, split screen, and friendships tested on Rainbow Road.',
      art: { motif: 'track', pal: ['#ff9ad5', '#b36bff', '#2e2a6e', '#120f33', '#ffffff'], play: 'racer' } },

    { id: 'pkblack', title: 'Pokémon Black', platform: 'nds', year: 2011, publisher: 'Game Freak', genre: 'RPG', players: 1, rating: 87, size: 256, installed: true, played: 60 * 24 * 7, hours: 12.6, sync: 'synced',
      blurb: 'Unova, a region of bridges and big cities, with an all-new cast from the first route.',
      art: { motif: 'city', pal: ['#e9eef5', '#b9c4d4', '#4a5568', '#1a202c', '#ffd23f'], play: 'topdown' } },
    { id: 'twewy', title: 'The World Ends with You', platform: 'nds', year: 2008, publisher: 'Square Enix', genre: 'Action RPG', players: 1, rating: 88, size: 128, installed: true, played: 60 * 24 * 2, hours: 3.3, sync: 'pending',
      blurb: 'Shibuya, seven days, and a battle system that uses both screens at once.',
      art: { motif: 'neon', pal: ['#1a1a1a', '#3a3a3a', '#f8e71c', '#0d0d0d', '#ff3e9d'], play: 'topdown' } },
    { id: 'mkds', title: 'Mario Kart DS', platform: 'nds', year: 2005, publisher: 'Nintendo', genre: 'Racing', players: 8, rating: 91, size: 32, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'The map on the bottom screen is doing more work than you think.',
      art: { motif: 'track', pal: ['#9ee7ff', '#5ab4ff', '#3a8f4d', '#1f5a2c', '#ff5d5d'], play: 'racer' } },

    { id: 'oot3d', title: 'Ocarina of Time 3D', platform: '3ds', year: 2011, publisher: 'Nintendo', genre: 'Action · Adventure', players: 1, rating: 94, size: 512, installed: true, played: 60 * 24 * 1, hours: 4.1, sync: 'synced',
      blurb: 'Hyrule Field at dawn, now with the inventory where your thumb already is.',
      art: { motif: 'forest', pal: ['#ffd9a0', '#f29b54', '#3d7a4f', '#1c3d2a', '#9df7ff'], play: 'topdown' } },

    { id: 'bonk', title: "Bonk's Adventure", platform: 'pce', year: 1990, publisher: 'Hudson Soft', genre: 'Platformer', players: 1, rating: 82, size: 0.38, installed: true, played: 60 * 24 * 18, hours: 1.1, sync: 'local',
      blurb: 'A caveman with a very hard head headbutts his way across a prehistoric world.',
      art: { motif: 'volcano', pal: ['#ffe29a', '#ff9a52', '#b8462e', '#4a1d12', '#8cf07a'], play: 'platformer' } },

    { id: 'sf2', title: 'Street Fighter II Turbo', platform: 'arcade', year: 1992, publisher: 'Capcom', genre: 'Fighting', players: 2, rating: 90, size: 4.6, installed: true, played: 60 * 24 * 3, hours: 1.8, sync: 'local',
      blurb: 'Eight world warriors and a quarter on the cabinet glass.',
      art: { motif: 'arena', pal: ['#ffcc6b', '#ff7b39', '#9a2f2f', '#2a0f14', '#fff2b3'], play: 'platformer' } },
    { id: 'pacman', title: 'Pac-Man', platform: 'arcade', year: 1980, publisher: 'Namco', genre: 'Maze', players: 2, rating: 85, size: 0.02, installed: true, played: 60 * 24 * 30, hours: 0.5, sync: 'local',
      blurb: 'Four ghosts with four personalities and a maze that never changes. It never needs to.',
      art: { motif: 'maze', pal: ['#000000', '#0a0a2a', '#2121de', '#000000', '#ffe600'], play: 'puzzle' } },

    { id: 'jetset', title: 'Jet Set Radio', platform: 'dc', year: 2000, publisher: 'Sega', genre: 'Action', players: 1, rating: 88, size: 1100, installed: true, played: 60 * 24 * 21, hours: 2.0, sync: 'local',
      blurb: 'Tokyo-to, inline skates, spray cans and a pirate radio DJ.',
      art: { motif: 'neon', pal: ['#ffe14d', '#ff8a00', '#ff2e63', '#1b1b3a', '#00e0ff'], play: 'platformer' } },
    { id: 'windwaker', title: 'The Wind Waker', platform: 'gc', year: 2003, publisher: 'Nintendo', genre: 'Adventure', players: 1, rating: 96, size: 1400, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'A flooded Hyrule, a talking boat, and cel-shading that never aged.',
      art: { motif: 'ocean', pal: ['#bff4ff', '#58c7ff', '#1b7ad1', '#0b3f78', '#fff8c2'], play: 'topdown' } },
    { id: 'nights', title: 'NiGHTS into Dreams', platform: 'saturn', year: 1996, publisher: 'Sega', genre: 'Action', players: 1, rating: 86, size: 420, installed: false, played: null, hours: 0, sync: 'none',
      blurb: 'Flight through dreams on a controller built for it.',
      art: { motif: 'sky', pal: ['#241a5c', '#6a3fb5', '#ff7ac6', '#130d33', '#ffe9a8'], play: 'shooter' } },
  ];

  const collections = [
    { id: 'couch', name: 'Couch co-op', kind: 'manual', games: ['sor2', 'sonic2', 'contra3', 'mk64', 'gunstar', 'dkc2', 'sf2'], note: 'Two controllers, one sofa.' },
    { id: 'metroidvania', name: 'Metroidvanias', kind: 'manual', games: ['smetroid', 'sotn', 'aria', 'metfusion'], note: 'Every locked door is a promise.' },
    { id: 'finish', name: 'Finish in 2026', kind: 'manual', games: ['chrono', 'earthbound', 'pkblack', 'ff7', 'goldensun'], note: 'The backlog, confronted.' },
    { id: 'handhelds', name: 'Pocket classics', kind: 'manual', games: ['linksawak', 'pkcrystal', 'tetris', 'metfusion', 'twewy'], note: 'Made for bus rides.' },
    { id: 'recent', name: 'Recently added', kind: 'smart', games: ['oot3d', 'jetset', 'twewy', 'bonk', 'nights', 'windwaker'], rule: 'Added in the last 30 days' },
    { id: 'unplayed', name: 'Never played', kind: 'smart', games: ['dkc2', 'contra3', 'ninja', 'advwars', 'goldensun', 'gunstar', 'crash', 'ff7', 'mk64', 'mkds', 'windwaker', 'nights'], rule: 'Playtime is zero' },
    { id: 'rpg', name: 'Role-playing', kind: 'auto', games: ['chrono', 'earthbound', 'pkcrystal', 'goldensun', 'ff7', 'pkblack', 'twewy'], rule: 'Genre · RPG' },
    { id: 'platformer', name: 'Platformers', kind: 'auto', games: ['sonic2', 'kirbyadv', 'mm2', 'dkc2', 'sm64', 'crash', 'bonk'], rule: 'Genre · Platformer' },
    { id: 'nineties', name: 'The 1990s', kind: 'auto', games: ['smetroid', 'chrono', 'alttp', 'sonic2', 'sotn', 'sm64', 'sf2'], rule: 'Release year · 1990–1999' },
  ];

  const downloads = [
    { game: 'ff7', state: 'running', progress: 0.62, done: 1178, total: 1900, rate: 18.4, eta: '39 s' },
    { game: 'windwaker', state: 'queued', progress: 0, done: 0, total: 1400 },
    { game: 'mkds', state: 'queued', progress: 0, done: 0, total: 32 },
    { game: 'advwars', state: 'waiting', progress: 0, done: 0, total: 4.0, reason: 'Waiting for Wi-Fi' },
    { game: 'crash', state: 'failed', progress: 0.31, done: 154, total: 498, reason: 'Server closed the connection' },
    { game: 'oot3d', state: 'done', at: 60 * 24, total: 512 },
    { game: 'jetset', state: 'done', at: 60 * 24 * 2, total: 1100 },
    { game: 'bonk', state: 'done', at: 60 * 24 * 3, total: 0.38 },
  ];

  // Progress for the game shown in the player / states screens.
  const progress = {
    game: 'smetroid',
    session: 42, // minutes in the current play session
    gameSave: { at: 3, device: 'This iPhone', remote: 'synced', label: 'Game-save data', detail: 'SRAM · 8 KB · 3 files' },
    resume: { at: 1, device: 'This iPhone', remote: 'waiting', label: 'Automatic resume', frame: 'b', where: 'Maridia glass tube' },
    slots: [
      { n: 1, name: 'Before Kraid', at: 60 * 24 * 2, device: 'Pixel 9', remote: 'synced', frame: 'c' },
      { n: 2, name: 'Wrecked Ship power', at: 60 * 26, device: 'This iPhone', remote: 'synced', frame: 'd' },
      { n: 3, name: 'Maridia glass tube', at: 42, device: 'This iPhone', remote: 'syncing', frame: 'a' },
      { n: 4, name: null, empty: true },
    ],
    history: [
      { kind: 'Automatic resume', at: 1, device: 'This iPhone', reason: 'Paused' },
      { kind: 'Save state · Slot 3', at: 42, device: 'This iPhone', reason: 'Manual' },
      { kind: 'Automatic resume', at: 60 * 5, device: 'iPad', reason: 'Left game' },
      { kind: 'Game-save data', at: 60 * 26, device: 'Pixel 9', reason: 'Imported' },
    ],
  };

  // A resume conflict between two devices (EarthBound).
  const conflict = {
    game: 'earthbound',
    kind: 'Automatic resume',
    branches: [
      { id: 'a', device: 'This iPhone', at: 38, playtime: '14 h 12 m', reason: 'Paused', where: 'Threed', frame: 'a', compatible: true },
      { id: 'b', device: 'Pixel 9', at: 60 * 3 + 12, playtime: '13 h 51 m', reason: 'Left game', where: 'Twoson', frame: 'c', compatible: true },
    ],
  };

  const devices = [
    { name: 'This iPhone', kind: 'smartphone', last: 0 },
    { name: 'Pixel 9', kind: 'smartphone', last: 60 * 3 },
    { name: 'iPad', kind: 'tablet', last: 60 * 5 },
  ];

  const profiles = [
    { id: 'home', name: 'Home server', user: 'ryan', url: 'romm.homelab.dev', version: 'RomM 5.2.0', active: true, state: 'online', lastSync: 2, hydrated: true, access: 'Cloudflare Access' },
    { id: 'friends', name: "Sam's library", user: 'guest', url: 'games.samnet.io', version: 'RomM 5.1.4', active: false, state: 'offline-ready', lastSync: 60 * 24 * 4, hydrated: true, access: 'Direct' },
  ];

  const storage = {
    total: 64000, // device free+used budget shown in settings (MB)
    roms: 18420,
    saves: 312,
    states: 488,
    cache: 640,
    cores: 212,
    bios: 38,
  };

  const cores = [
    { name: 'FCEUmm', family: 'NES / Famicom', version: '2026.08', state: 'ready' },
    { name: 'Snes9x', family: 'Super Nintendo', version: '1.63', state: 'ready' },
    { name: 'Gambatte', family: 'Game Boy / Color', version: '2026.07', state: 'ready' },
    { name: 'mGBA', family: 'Game Boy Advance', version: '0.10.5', state: 'ready' },
    { name: 'Genesis Plus GX', family: 'Sega 8/16-bit', version: '1.7.4', state: 'ready' },
    { name: 'Mupen64Plus-Next', family: 'Nintendo 64', version: '2.6', state: 'ready' },
    { name: 'Beetle PSX', family: 'PlayStation', version: '0.9.44', state: 'bios', note: 'Needs scph5501.bin' },
    { name: 'melonDS DS', family: 'Nintendo DS', version: '1.2.0', state: 'ready' },
    { name: 'Azahar', family: 'Nintendo 3DS', version: '2126.0', state: 'setup', note: 'Bundled — finish setup' },
    { name: 'PPSSPP', family: 'PlayStation Portable', version: '1.19', state: 'ready' },
    { name: 'Flycast', family: 'Dreamcast', version: '2.5', state: 'controller' },
  ];

  const controls = {
    showTouch: true,
    autoHideOnController: true,
    leftHanded: false,
    opacity: 0.72,
    size: 1.0,
    deadzone: 0.12,
    haptics: true,
    oled: true,
    consoleColors: false,
    controllerType: 'Automatic',
    connectedController: 'DualSense Wireless Controller',
  };

  const server = {
    discovered: [
      { name: 'romm.homelab.dev', note: 'Saved · Home server', secure: true },
      { name: '192.168.1.40:8080', note: 'Found on this network', secure: false },
    ],
  };

  // ---- v2: history and activity, so directions can organise around time, devices or habits ----

  // Play sessions, newest first. `day` = days ago (0 = today), `start` = local time.
  // `ended` is how the session left progress: resume | state | save | quit.
  const sessions = [
    { game: 'smetroid', device: 'This iPhone', day: 0, start: '8:59', minutes: 42, ended: 'resume', note: 'Maridia glass tube' },
    { game: 'linksawak', device: 'This iPhone', day: 0, start: '6:12', minutes: 25, ended: 'resume', note: 'Bottle Grotto' },
    { game: 'earthbound', device: 'This iPhone', day: 0, start: '7:30', minutes: 31, ended: 'resume', note: 'Threed' },
    { game: 'earthbound', device: 'Pixel 9', day: 0, start: '5:40', minutes: 18, ended: 'resume', note: 'Twoson' },
    { game: 'chrono', device: 'iPad', day: 1, start: '21:05', minutes: 74, ended: 'state', note: 'End of Time' },
    { game: 'smetroid', device: 'This iPhone', day: 1, start: '12:40', minutes: 36, ended: 'state', note: 'Wrecked Ship power' },
    { game: 'mm2', device: 'This iPhone', day: 1, start: '8:15', minutes: 22, ended: 'quit', note: 'Wily Castle 1' },
    { game: 'kirbyadv', device: 'Pixel 9', day: 2, start: '19:20', minutes: 40, ended: 'resume', note: 'Grape Garden' },
    { game: 'smetroid', device: 'Pixel 9', day: 2, start: '13:02', minutes: 55, ended: 'state', note: 'Before Kraid' },
    { game: 'alttp', device: 'iPad', day: 3, start: '22:10', minutes: 63, ended: 'save', note: 'Palace of Darkness' },
    { game: 'sonic2', device: 'This iPhone', day: 3, start: '9:30', minutes: 12, ended: 'quit', note: 'Chemical Plant' },
    { game: 'metfusion', device: 'This iPhone', day: 5, start: '20:45', minutes: 48, ended: 'resume', note: 'Sector 3' },
    { game: 'pkcrystal', device: 'This iPhone', day: 5, start: '18:00', minutes: 90, ended: 'save', note: 'Ecruteak City' },
    { game: 'aria', device: 'iPad', day: 6, start: '21:30', minutes: 57, ended: 'state', note: 'Floating Garden' },
    { game: 'pkblack', device: 'This iPhone', day: 7, start: '8:05', minutes: 34, ended: 'save', note: 'Castelia City' },
    { game: 'sor2', device: 'iPad', day: 8, start: '20:00', minutes: 40, ended: 'quit', note: 'Stage 5 with Sam' },
    { game: 'earthbound', device: 'This iPhone', day: 9, start: '22:40', minutes: 45, ended: 'resume', note: 'Happy Happy Village' },
    { game: 'sotn', device: 'Pixel 9', day: 11, start: '23:10', minutes: 70, ended: 'save', note: 'Inverted Castle' },
    { game: 'tetris', device: 'This iPhone', day: 12, start: '7:50', minutes: 15, ended: 'quit', note: 'Level 9 · 33 lines' },
    { game: 'ff6', device: 'iPad', day: 14, start: '21:00', minutes: 88, ended: 'save', note: 'Opera House' },
    { game: 'sm64', device: 'This iPhone', day: 15, start: '19:15', minutes: 26, ended: 'resume', note: 'Cool, Cool Mountain' },
    { game: 'galaga', device: 'This iPhone', day: 20, start: '8:40', minutes: 10, ended: 'quit', note: 'Stage 14' },
    { game: 'yoshi', device: 'Pixel 9', day: 25, start: '17:30', minutes: 32, ended: 'save', note: 'World 2-4' },
    { game: 'pacman', device: 'This iPhone', day: 30, start: '12:10', minutes: 8, ended: 'quit', note: 'Level 6' },
  ];

  // Every resume point and save state across the library, newest first.
  // kind: resume | state | save. `frame` picks the gameplay moment (a–d).
  const moments = [
    { game: 'smetroid', kind: 'resume', name: 'Maridia glass tube', at: 1, device: 'This iPhone', remote: 'waiting', frame: 'b' },
    { game: 'earthbound', kind: 'resume', name: 'Threed', at: 38, device: 'This iPhone', remote: 'conflict', frame: 'a' },
    { game: 'smetroid', kind: 'state', slot: 3, name: 'Maridia glass tube', at: 42, device: 'This iPhone', remote: 'syncing', frame: 'a' },
    { game: 'linksawak', kind: 'resume', name: 'Bottle Grotto', at: 60 * 3, device: 'This iPhone', remote: 'syncing', frame: 'a' },
    { game: 'earthbound', kind: 'resume', name: 'Twoson', at: 60 * 3 + 12, device: 'Pixel 9', remote: 'conflict', frame: 'c' },
    { game: 'chrono', kind: 'state', slot: 1, name: 'End of Time', at: 60 * 26, device: 'iPad', remote: 'waiting', frame: 'c' },
    { game: 'smetroid', kind: 'state', slot: 2, name: 'Wrecked Ship power', at: 60 * 26, device: 'This iPhone', remote: 'synced', frame: 'd' },
    { game: 'kirbyadv', kind: 'resume', name: 'Grape Garden', at: 60 * 24 * 2, device: 'Pixel 9', remote: 'synced', frame: 'a' },
    { game: 'smetroid', kind: 'state', slot: 1, name: 'Before Kraid', at: 60 * 24 * 2, device: 'Pixel 9', remote: 'synced', frame: 'c' },
    { game: 'alttp', kind: 'save', name: 'Palace of Darkness', at: 60 * 24 * 3, device: 'iPad', remote: 'synced', frame: 'b' },
    { game: 'metfusion', kind: 'resume', name: 'Sector 3', at: 60 * 24 * 5, device: 'This iPhone', remote: 'synced', frame: 'a' },
    { game: 'aria', kind: 'state', slot: 2, name: 'Floating Garden', at: 60 * 24 * 6, device: 'iPad', remote: 'waiting', frame: 'b' },
    { game: 'pkcrystal', kind: 'save', name: 'Ecruteak City', at: 60 * 24 * 5, device: 'This iPhone', remote: 'synced', frame: 'd' },
    { game: 'sotn', kind: 'save', name: 'Inverted Castle', at: 60 * 24 * 11, device: 'Pixel 9', remote: 'synced', frame: 'b' },
    { game: 'tetris', kind: 'save', name: 'High score 3,161', at: 60 * 24 * 12, device: 'This iPhone', remote: 'local', frame: 'a' },
  ];

  // Sync and download activity, newest first (minutes ago).
  const activity = [
    { at: 0.5, kind: 'download', text: 'Downloading Final Fantasy VII', detail: '1.2 of 1.9 GB', state: 'running' },
    { at: 1, kind: 'resume', text: 'Automatic resume updated · Super Metroid', detail: 'Waiting to sync', state: 'waiting' },
    { at: 3, kind: 'save', text: 'Game-save data synced · Super Metroid', detail: 'SRAM · 8 KB', state: 'synced' },
    { at: 38, kind: 'conflict', text: 'Conflict · EarthBound resume', detail: 'This iPhone and Pixel 9', state: 'conflict' },
    { at: 42, kind: 'state', text: 'Save state saved · Slot 3', detail: 'Super Metroid · Syncing', state: 'syncing' },
    { at: 60 * 3, kind: 'resume', text: "Automatic resume updated · Link's Awakening DX", detail: 'Syncing', state: 'syncing' },
    { at: 60 * 5, kind: 'failed', text: 'Download failed · Crash Bandicoot', detail: 'Connection closed at 31%', state: 'failed' },
    { at: 60 * 24, kind: 'download', text: 'Installed Ocarina of Time 3D', detail: '512 MB', state: 'synced' },
    { at: 60 * 26, kind: 'state', text: 'Save state synced · Chrono Trigger', detail: 'From iPad', state: 'synced' },
  ];

  // Devices signed in to this profile and what each holds.
  const fleet = [
    { name: 'This iPhone', kind: 'smartphone', model: 'iPhone Duo', last: 0, pending: 2, installed: 41, storage: 18420, state: 'online' },
    { name: 'Pixel 9', kind: 'smartphone', model: 'Pixel 9', last: 60 * 3, pending: 0, installed: 27, storage: 11200, state: 'idle' },
    { name: 'iPad', kind: 'tablet', model: 'iPad Air', last: 60 * 5, pending: 1, installed: 23, storage: 30900, state: 'idle' },
  ];

  const stats = {
    month: { minutes: 981, sessions: 24, games: 20, streak: 4, bestStreak: 9 },
    // Minutes per day for the last 28 days, oldest first: the sums of D.sessions (today last).
    // Four days in a row, so the day before the streak has no play.
    days: [0, 0, 32, 0, 0, 0, 0, 10, 0, 0, 0, 0, 26, 88, 0, 15, 70, 0, 45, 40, 34, 57, 138, 0, 75, 95, 132, 116],
    topPlatforms: [
      { platform: 'snes', minutes: 484 },
      { platform: 'gbc', minutes: 115 },
      { platform: 'gba', minutes: 105 },
      { platform: 'nes', minutes: 72 },
    ],
  };

  const search = {
    recent: ['metroid', 'zelda', 'two players'],
    suggestions: ['Installed on this iPhone', 'Has save states', 'SNES', 'RPG', 'Never played'],
  };

  const user = { name: 'Ryan', initials: 'R', handle: 'ryan', hue: 214 };

  const now = { time: '9:41', battery: 0.82 };

  window.StudioData = {
    platforms,
    games,
    collections,
    downloads,
    progress,
    conflict,
    devices,
    profiles,
    storage,
    cores,
    controls,
    server,
    sessions,
    moments,
    activity,
    fleet,
    stats,
    search,
    user,
    now,
  };
})();
