#!/usr/bin/env bash
# Fails when an APK requests a network permission (AGENTS.md offline policy), and also when the
# permissions can't be read at all, so a missing tool can never let the check pass silently.
set -euo pipefail

aapt="${AAPT:-$ANDROID_HOME/build-tools/36.0.0/aapt}"
if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <apk>..." >&2
  exit 1
fi

for apk in "$@"; do
  if [[ ! -f "$apk" ]]; then
    echo "APK not found: $apk" >&2
    exit 1
  fi
  permissions=$("$aapt" dump permissions "$apk")
  if ! grep -q '^package:' <<< "$permissions"; then
    echo "Could not read the permissions of $apk" >&2
    exit 1
  fi
  if grep -E 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)' <<< "$permissions"; then
    echo "Offline policy violated: $apk requests a network permission" >&2
    exit 1
  fi
  echo "OK: $apk requests no network permission"
done
