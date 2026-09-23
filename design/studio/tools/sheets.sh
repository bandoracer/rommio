#!/usr/bin/env bash
# Contact sheets of one version, one image per flow (quick visual review).
#   design/studio/tools/sheets.sh <version> <out-dir> [flags]
# flags are studio hash flags such as "+dark", "+both", "+duo-open", "+duo-closed+dark".
set -euo pipefail
v="${1:?version}"
out="${2:?out dir}"
flags="${3:-}"
tag="$(echo "$flags" | tr -c 'a-z0-9\n' '-' | sed 's/^-*//;s/-*$//')"
tag="${tag:+-$tag}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out"
export SCALE="${SCALE:-1}"
zoom=0.5
w1=1400; w2=1700; w3=820; w4=1900; h=820
case "$flags" in
  *duo-open*) zoom=0.3 ;;
  *duo-closed*) zoom=0.45 ;;
esac
case "$flags" in *both*) w1=2400; w2=2800; w3=1500; w4=2900 ;; esac
"$here/shoot.sh" "$v~Onboarding@$zoom$flags" "$out/sheet-onboarding$tag.png" "$w1" "$h" >/dev/null &
"$here/shoot.sh" "$v~Library@$zoom$flags" "$out/sheet-library$tag.png" "$w2" "$h" >/dev/null &
"$here/shoot.sh" "$v~Settings@$zoom$flags" "$out/sheet-settings$tag.png" "$w3" "$h" >/dev/null &
"$here/shoot.sh" "$v~Player@$(python3 -c "print(round($zoom*0.8,2))")$flags" "$out/sheet-player$tag.png" "$w4" "$h" >/dev/null &
wait
ls "$out"/sheet-*"$tag".png
