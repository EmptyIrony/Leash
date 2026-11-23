#!/bin/bash
# Probe 2: where does conversation history come from?
#   bash ~/workspace/Leash/tools/probe2-mac.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
to() { perl -e 'alarm shift; exec @ARGV' "$@"; }
{
  echo "== agy --help"; to 20 agy --help
  for sub in conversations conversation history export resume; do echo "== agy $sub --help"; to 15 agy $sub --help 2>&1 | head -40; done
  for d in antigravity antigravity-cli antigravity-ide; do
    echo "== ~/.gemini/$d/conversations (newest 8, names+sizes)"; ls -lt ~/.gemini/$d/conversations 2>/dev/null | head -9
  done
  f=$(ls -t ~/.gemini/antigravity/conversations/* 2>/dev/null | head -1)
  if [ -n "$f" ]; then echo "== file type of newest desktop conversation"; file "$f"; echo "printable-string count: $(strings -n 8 "$f" | wc -l)"; fi
  echo "== brain/64ac5f86... (names only)"; ls -la ~/.gemini/antigravity/brain/64ac5f86-2d42-47a9-aa8e-3dd0b57dc406 2>/dev/null | head -30
} > "$OUT/env2.txt" 2>&1
echo "env2 -> $OUT/env2.txt"
node "$HERE/acp-probe2.mjs" "$OUT"
echo "done. Tell Claude probe2 finished."
