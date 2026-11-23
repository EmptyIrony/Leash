#!/bin/bash
# Leash - Mac environment probe.
# Run on the Mac:  bash ~/workspace/Leash/tools/probe-mac.sh
# Writes results to ~/workspace/Leash/.probe/ (no tokens / credentials are read).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../.probe"
mkdir -p "$OUT"
to() { perl -e 'alarm shift; exec @ARGV' "$@"; }   # macOS has no 'timeout'
{
  echo "== date"; date
  echo "== sw_vers"; sw_vers
  echo "== arch"; uname -m
  echo "== shell PATH"; echo "$PATH" | tr ':' '\n'
  echo "== node"; command -v node && node -v
  echo "== npm"; command -v npm && npm -v
  echo "== agy"; command -v agy; ls -l "$(command -v agy 2>/dev/null)" 2>/dev/null
  echo "== agy --version"; to 20 agy --version
  echo "== agy acp --help"; to 20 agy acp --help
  echo "== agy models"; to 30 agy models
  echo "== Antigravity apps"; ls -d /Applications/*ntigravity* ~/Applications/*ntigravity* 2>/dev/null
  echo "== bundled binaries (names only)"
  for a in /Applications/*ntigravity*.app; do
    find "$a/Contents" -maxdepth 6 \( -name 'agy*' -o -name 'language_server*' -o -name 'antigravity*' \) -type f 2>/dev/null | head -20
  done
  echo "== ~/.gemini layout (dirs, depth<=3)"; find ~/.gemini -maxdepth 3 -type d 2>/dev/null | sed "s|$HOME|~|" | head -80
  echo "== ~/.gemini files depth<=2 (names only, secrets filtered)"
  find ~/.gemini -maxdepth 2 -type f 2>/dev/null | grep -viE 'token|oauth|cred|key|secret|auth' | sed "s|$HOME|~|" | head -60
  echo "== App Support"; ls ~/Library/Application\ Support 2>/dev/null | grep -iE 'antigravity|gemini'
  echo "== tailscale"; (command -v tailscale || ls /Applications/Tailscale.app/Contents/MacOS/Tailscale) 2>/dev/null
  ifconfig 2>/dev/null | grep -E 'inet 100\.' | awk '{print "tailscale-ip:", $2}'
} > "$OUT/env.txt" 2>&1
echo "env  -> $OUT/env.txt"

if command -v node >/dev/null && command -v agy >/dev/null; then
  node "$HERE/acp-probe.mjs" "$OUT" "$@"
else
  echo "node or agy not on PATH; skipped ACP probe (see env.txt)"
fi
echo "done. Tell Claude the probe finished."
