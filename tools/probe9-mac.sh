#!/bin/bash
# Probe 9: can the Mac reach Claude Code on the Ubuntu box through the 'ubuntu' command with clean stdio?
#   bash ~/workspace/Leash/tools/probe9-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
to() { perl -e 'alarm shift; exec @ARGV' "$@"; }
# load the user's shell so aliases/functions named 'ubuntu' exist
SHELL_RC="source ~/.zshrc >/dev/null 2>&1;"
{
  echo "== what is ubuntu"; zsh -ic 'whence -v ubuntu; type ubuntu' 2>&1 | head -5
  P=$(zsh -ic 'whence -p ubuntu' 2>/dev/null); [ -n "$P" ] && { echo "-- script $P (first 25 lines, secrets filtered)"; head -25 "$P" | grep -viE 'password|token|secret|BEGIN .*KEY'; }
  echo "== echo test"; to 20 zsh -ic 'ubuntu echo hello-from-ubuntu' 2>&1 | tail -3
  echo "== stdin passthrough"; echo ping-stdin | to 20 zsh -ic 'ubuntu cat' 2>&1 | od -c | head -3
  echo "== ACP initialize over ubuntu"
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}' \
    | to 40 zsh -ic 'ubuntu /config/workspace/Leash/bridge/remote/run-acp.sh' 2>/dev/null | head -c 400; echo
  echo "== helper over ubuntu"; to 60 zsh -ic 'ubuntu node /config/workspace/Leash/bridge/remote/helper.mjs browse /config/workspace' 2>&1 | head -c 300; echo
} > "$OUT/env9.txt" 2>&1
cat "$OUT/env9.txt"
echo "done. Tell Claude probe9 finished."
