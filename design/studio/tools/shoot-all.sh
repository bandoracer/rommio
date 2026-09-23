#!/usr/bin/env bash
# Screenshot every screen of one version, in parallel.
#   design/studio/tools/shoot-all.sh <version> <out-dir> [suffix]
# suffix is appended to each route, e.g. "dark", "both", "duo-open", "duo-open-dark", "android".
set -euo pipefail
v="${1:?version}"
out="${2:?out dir}"
suffix="${3:+-$3}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out"
screens=$(grep -oE "id: '[a-z-]+', flow" "$here/../core/screens.js" | sed -E "s/id: '([a-z-]+)', flow/\1/")
pids=()
for s in $screens; do
  # Retry once with a longer budget: headless Chrome occasionally stalls under load.
  ( "$here/shoot.sh" "shot-$v-$s$suffix" "$out/$s$suffix.png" >/dev/null 2>&1 || BUDGET=9000 "$here/shoot.sh" "shot-$v-$s$suffix" "$out/$s$suffix.png" >/dev/null ) &
  pids+=($!)
  if (( ${#pids[@]} >= 4 )); then wait "${pids[0]}" || true; pids=("${pids[@]:1}"); fi
done
wait || true
ls "$out" | grep -c png | xargs echo "screens:"
