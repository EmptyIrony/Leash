#!/bin/bash
# Probe 10: how does agy deliver the inline interactive visualization / side-panel artifact?
#   bash ~/workspace/Leash/tools/probe10-mac.sh [title-substring-or-sessionId]
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
WANT="${1:-万花筒图形绘制构思}"
node "$HERE/acp-probe10.mjs" "$OUT" "$WANT" | tee "$OUT/env10.txt"
SID=$(node -e 'try{console.log(require(process.argv[1]).info.session.sessionId)}catch{}' "$OUT/kaleido-summary.json")
{
  echo "== brain dir for $SID"
  for D in "$HOME/.gemini/antigravity/brain/$SID" "$HOME/.gemini/antigravity/conversations"; do
    [ -e "$D" ] && find "$D" -maxdepth 3 \( -name "*$SID*" -o -path "*$SID*" \) -exec ls -la {} \; 2>/dev/null | head -60
  done
  echo "== copy brain artifacts (text files <2MB)"
  mkdir -p "$OUT/brain10"
  [ -d "$HOME/.gemini/antigravity/brain/$SID" ] && find "$HOME/.gemini/antigravity/brain/$SID" -type f -size -2M \
      \( -name "*.html" -o -name "*.md" -o -name "*.json" -o -name "*.js" -o -name "*.css" -o -name "*.svg" -o -name "*.jsx" -o -name "*.tsx" -o -name "*.txt" \) \
      -exec cp {} "$OUT/brain10/" \; -print
  echo "== agy version"; agy --version 2>&1 | head -2
} >> "$OUT/env10.txt" 2>&1
tail -40 "$OUT/env10.txt"
echo "done. Tell Claude probe10 finished."
