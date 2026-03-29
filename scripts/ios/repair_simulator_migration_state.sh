#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/ios/repair_simulator_migration_state.sh [--reboot] <simulator-udid> [<simulator-udid> ...]

Repairs an iOS simulator whose migration plugins all report success but whose
aggregate migration result is still marked as failed. This clears repeated
"Data Migration Failed" bootstatus errors seen on some iOS 26 runtimes.
EOF
}

reboot_after_fix=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --reboot)
      reboot_after_fix=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      break
      ;;
  esac
done

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

simctl_list="$(xcrun simctl list devices)"

for udid in "$@"; do
  plist="$HOME/Library/Developer/CoreSimulator/Devices/$udid/data/Library/Preferences/com.apple.migration.plist"

  if [[ ! -f "$plist" ]]; then
    echo "skip $udid: migration plist not found"
    continue
  fi

  success="$(plutil -extract DMLastMigrationResults.success raw -o - "$plist" 2>/dev/null || echo missing)"
  if [[ "$success" == "true" ]]; then
    echo "ok   $udid: aggregate migration result already healthy"
    continue
  fi

  plugin_lines="$(plutil -p "$plist" | rg 'kDMMigrationPluginResultsPhaseDescription' || true)"
  non_success_lines="$(printf '%s\n' "$plugin_lines" | rg -v 'kDMMigrationPhaseDescriptionDidFinishWithSuccess' || true)"
  if [[ -n "$non_success_lines" ]]; then
    echo "skip $udid: at least one migration plugin is not marked successful"
    continue
  fi

  if printf '%s\n' "$simctl_list" | rg -q "$udid.*\\(Booted\\)"; then
    xcrun simctl shutdown "$udid"
  fi

  cp "$plist" "$plist.pre-fix.bak"
  plutil -replace DMLastMigrationResults.success -bool YES "$plist"
  echo "fix  $udid: patched aggregate migration result"

  if [[ "$reboot_after_fix" == "true" ]]; then
    xcrun simctl boot "$udid"
    xcrun simctl bootstatus "$udid" -b >/dev/null
    echo "boot $udid: rebooted cleanly"
  fi
done
