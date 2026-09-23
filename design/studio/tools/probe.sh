#!/usr/bin/env bash
# Print computed layout for elements in a rendered screen (debugging aid).
#   design/studio/tools/probe.sh '<css selector>' shot-<version>-<screen>
#   RULES=1 design/studio/tools/probe.sh '.player .dpad' shot-void-player   # also list matching CSS rules
set -euo pipefail
sel="${1:?selector}"
route="${2:?route}"
here="$(cd "$(dirname "$0")" && pwd)"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

# Build a throwaway copy of index.html (one level down, so paths gain ../) with the probe injected.
page="$here/.probe.html"
sed -e 's#href="core/#href="../core/#g; s#src="core/#src="../core/#g' \
    -e 's#href="versions/#href="../versions/#g; s#src="versions/#src="../versions/#g' \
    -e 's#</body>#<script src="probe-inject.js"></script></body>#' \
    "$here/../index.html" >"$page"

q=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$sel")
profile="$(mktemp -d)"
tmp="$(mktemp)"
"$chrome" --headless=new --disable-gpu --user-data-dir="$profile" --virtual-time-budget=5000 \
  --allow-file-access-from-files --window-size=434,906 \
  --dump-dom "file://$page?q=$q&rules=${RULES:-}#$route" >"$tmp" 2>/dev/null &
pid=$!
for _ in $(seq 1 60); do
  if grep -q '</html>' "$tmp" 2>/dev/null; then break; fi
  sleep 0.25
done
kill "$pid" 2>/dev/null || true
sed -n '/<pre id="probe">/,/<\/pre>/p' "$tmp" | sed -e 's/<[^>]*>//g'
rm -rf "$profile" "$tmp" "$page" 2>/dev/null || true
