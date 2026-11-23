#!/bin/bash
# Probe 11: copy agy's built-in generative_ui skill (the host contract for <agent-embed> HTML).
#   bash ~/workspace/Leash/tools/probe11-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe/genui"; mkdir -p "$OUT"
SRC="$HOME/.gemini/antigravity/builtin/skills/generative_ui"
ls -laR "$SRC" | head -60
cp -R "$SRC/." "$OUT/" && echo "copied to .probe/genui"
grep -rl "agent-embed" "$HOME/.gemini/antigravity/builtin" 2>/dev/null | head -20
echo "done. Tell Claude probe11 finished."
