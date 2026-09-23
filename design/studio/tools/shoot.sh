#!/usr/bin/env bash
# Headless screenshot of a studio page.
#
#   design/studio/tools/shoot.sh <route> <out.png> [width] [height] [page]
#
# Routes (see core/studio.js):
#   shot-void-home                  one screen, iPhone, light
#   shot-void-home-dark             …dark
#   shot-void-home-both             light and dark side by side
#   shot-void-home-duo-open-dark    iPhone Duo open, dark (devices: iphone android duo-closed duo-open)
#   void~Library@0.4+duo-closed     canvas of one flow (local-only extras: @zoom ~flow +device +dark +both +v1)
#   compare-player  ·  matrix-library
#
# For shot-* routes the size is worked out from the device, the screen's kind
# and the appearance; other routes default to 1600x1000.
set -euo pipefail

route="${1:?route}"
out="${2:?out.png}"
page="${5:-index.html}"
here="$(cd "$(dirname "$0")/.." && pwd)"

chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [[ ! -x "$chrome" ]]; then
  chrome="$(command -v google-chrome || command -v chromium || true)"
fi
[[ -x "$chrome" ]] || { echo "Chrome not found; set CHROME=/path/to/chrome" >&2; exit 1; }

width="${3:-}"
height="${4:-}"
if [[ -z "$width" ]]; then
  if [[ "$route" == shot-* ]]; then
    read -r width height < <(python3 - "$route" <<'PY'
import sys
r = sys.argv[1]
dev = 'iphone'
for d in ('duo-closed', 'duo-open', 'android', 'iphone'):
    if f'-{d}' in r:
        dev = d
        break
pairs = 2 if r.endswith('-both') else 1
kind = 'app'
if any(s in r for s in ('-player-portrait', '-dual')):
    kind = 'portrait'
elif any(s in r for s in ('-player', '-pause', '-states')):
    kind = 'landscape'
size = {
    'iphone': {'app': (402, 874), 'portrait': (402, 874), 'landscape': (874, 402)},
    'android': {'app': (402, 874), 'portrait': (402, 874), 'landscape': (874, 402)},
    'duo-closed': {'app': (466, 678), 'portrait': (466, 678), 'landscape': (678, 466)},
    'duo-open': {'app': (951, 669), 'portrait': (669, 951), 'landscape': (951, 669)},
}[dev][kind]
w = size[0] * pairs + 48 * (pairs - 1) + 32
print(w, size[1] + 32)
PY
)
  else
    width=1600; height=1000
  fi
fi

profile="$(mktemp -d)"
rm -f "$out"

# Headless Chrome on macOS can linger after writing the screenshot, so run it
# in the background and stop it once the file has landed.
"$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check \
  --user-data-dir="$profile" --force-device-scale-factor="${SCALE:-2}" \
  --window-size="${width},${height}" --virtual-time-budget="${BUDGET:-6000}" \
  --allow-file-access-from-files \
  --screenshot="$out" "file://$here/$page#$route" >/dev/null 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; rm -rf "$profile" 2>/dev/null || true' EXIT

for _ in $(seq 1 240); do
  if [[ -s "$out" ]] || ! kill -0 "$pid" 2>/dev/null; then break; fi
  sleep 0.25
done
sleep 0.3
[[ -s "$out" ]] || { echo "screenshot failed: $route" >&2; exit 1; }
echo "$out"
