#!/usr/bin/env python3
"""Reference maths for the touch analog stick (docs/player-controls.md, "Touch sticks").

Both apps implement the same functions and pin their unit tests to the vectors this script
prints (`python3 scripts/touch_stick_reference.py`), the way the Aurora light maths is pinned
to aurora.js. Units are points; y grows downward (screen and libretro agree).
"""
from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class StickProfile:
    name: str
    travel: float          # pt of thumb travel for full tilt, before saturation
    dead_zone: float       # fraction of travel with no output
    saturation: float      # fraction of travel (after the dead zone) that already reads as full tilt
    curve: float           # response exponent (>1 = finer near the centre)
    diagonal_reach: float  # per-axis value at 45° full tilt; 0.7071 = circle, higher = octagon/square gate
    follow: bool           # drag the centre along when the thumb goes past full travel


PROFILES = {
    'n64':     StickProfile('n64', travel=48, dead_zone=0.10, saturation=0.90, curve=1.4, diagonal_reach=0.86, follow=True),
    '3ds':     StickProfile('3ds', travel=44, dead_zone=0.10, saturation=0.92, curve=1.2, diagonal_reach=0.7071, follow=True),
    'psp':     StickProfile('psp', travel=36, dead_zone=0.12, saturation=0.90, curve=1.0, diagonal_reach=0.7071, follow=True),
    'psx':     StickProfile('psx', travel=44, dead_zone=0.10, saturation=0.90, curve=1.3, diagonal_reach=0.95, follow=True),
    'psx-right': StickProfile('psx-right', travel=40, dead_zone=0.12, saturation=0.90, curve=1.6, diagonal_reach=0.95, follow=True),
}


def gate_scale(angle: float, diagonal_reach: float) -> float:
    """How far full tilt reaches along `angle` for a gate whose 45° corner is at
    (diagonal_reach, diagonal_reach): 1 at the cardinals, straight edges between. A reach of
    0.7071 is treated as a true circle (scale 1 everywhere)."""
    if abs(diagonal_reach - math.sqrt(0.5)) < 1e-4:  # 0.7071 in the profiles means a circle
        return 1.0
    folded = abs(angle) % (math.pi / 2)
    if folded > math.pi / 4:
        folded = math.pi / 2 - folded
    return 1.0 / (math.cos(folded) + math.sin(folded) * (1.0 - diagonal_reach) / diagonal_reach)


def stick_output(dx: float, dy: float, p: StickProfile) -> tuple[float, float]:
    """Thumb offset from the stick centre (pt) → (x, y) in [-1, 1] for the core."""
    distance = math.hypot(dx, dy)
    if distance == 0:
        return (0.0, 0.0)
    m = min(distance / p.travel, 1.0)
    if m <= p.dead_zone:
        return (0.0, 0.0)
    m = (m - p.dead_zone) / (1.0 - p.dead_zone)
    m = min(m / p.saturation, 1.0)
    m = m ** p.curve
    angle = math.atan2(dy, dx)
    reach = m * gate_scale(angle, p.diagonal_reach)
    x = max(-1.0, min(1.0, math.cos(angle) * reach))
    y = max(-1.0, min(1.0, math.sin(angle) * reach))
    return (x, y)


def follow_centre(cx: float, cy: float, tx: float, ty: float, p: StickProfile) -> tuple[float, float]:
    """With `follow`, a thumb past full travel drags the centre so it sits exactly at the rim."""
    dx, dy = tx - cx, ty - cy
    distance = math.hypot(dx, dy)
    if not p.follow or distance <= p.travel:
        return (cx, cy)
    pull = 1.0 - p.travel / distance
    return (cx + dx * pull, cy + dy * pull)


def full_tilt(dx: float, dy: float, p: StickProfile) -> bool:
    """Where the full-tilt haptic tick fires (entering saturation)."""
    m = min(math.hypot(dx, dy) / p.travel, 1.0)
    if m <= p.dead_zone:
        return False
    return (m - p.dead_zone) / (1.0 - p.dead_zone) >= p.saturation


VECTORS = [(0, 0), (3, 0), (5, 0), (12, 0), (24, 0), (40, 0), (60, 0), (0, -30), (20, 20), (34, 34), (-15, 26)]

if __name__ == '__main__':
    for key, p in PROFILES.items():
        print(f'## {key}')
        for dx, dy in VECTORS:
            x, y = stick_output(dx, dy, p)
            print(f'  ({dx:>4}, {dy:>4}) -> ({x:+.4f}, {y:+.4f})  full_tilt={full_tilt(dx, dy, p)}')
        cx, cy = follow_centre(0, 0, 80, 0, p)
        print(f'  follow: centre (0,0), thumb (80,0) -> centre ({cx:.1f}, {cy:.1f})')
