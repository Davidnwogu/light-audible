#!/usr/bin/env bash
# Saves the Tool Manager key for `uploadTool`.
#
#   tool/scripts/save-device-key.sh
#
# With the Tool Manager open in the browser, run copy(sessionStorage.getItem("apiKey")) in that
# tab's DevTools console, then run this script and paste (a URL ending in #<key> also works).
# Input is hidden and kept out of shell history. The key changes each time the Tool Manager
# restarts, so run this again when uploads fail with HTTP 401. The key is written to
# ~/.config/light-audible/device-token.txt (mode 600) and never printed.
set -euo pipefail

out="${LIGHT_DEVICE_TOKEN_FILE:-$HOME/.config/light-audible/device-token.txt}"

read -r -s -p "Paste the Tool Manager key (or a URL ending in #<key>): " url
echo
url="${url//[[:space:]\"]/}"

if [[ "$url" =~ ^[0-9a-fA-F]+$ ]]; then
  key="$url"
else
  fragment="${url#*#}"
  if [ "$fragment" = "$url" ] || [ -z "${fragment%%/*}" ]; then
    echo "No key found. In the Tool Manager page's browser console, run:" >&2
    echo "  copy(sessionStorage.getItem(\"apiKey\"))" >&2
    echo "then run this script again and paste." >&2
    exit 1
  fi
  key="${fragment%%/*}"
fi

if ! [[ "$key" =~ ^[0-9a-fA-F]+$ ]] || [ $(( ${#key} % 2 )) -ne 0 ]; then
  echo "The part after '#' isn't a hex key. Copy the URL exactly as the phone shows it." >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
chmod 700 "$(dirname "$out")"
( umask 077; printf '%s' "$key" > "$out" )
echo "Saved the key to $out."
