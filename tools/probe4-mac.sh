#!/bin/bash
# Probe 4: is quota / usage info cached in local state files? Prints key names and numbers only.
#   bash ~/workspace/Leash/tools/probe4-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
{
  for f in ~/.gemini/antigravity/antigravity_state.pbtxt ~/.gemini/antigravity-cli/jetski_state.pbtxt; do
    echo "== $f ($(wc -c < "$f" 2>/dev/null) bytes)"
    # field names that look quota-related, with numeric/time values only (no free text)
    grep -n -iE 'quota|limit|remaining|reset|usage|window|credit|fraction|tier|rate' "$f" 2>/dev/null \
      | sed -E 's/"[^"]{40,}"/"<long>"/g' | head -80
    echo "-- top-level field names:"; grep -oE '^[[:space:]]*[a-z_]+[[:space:]]*[:{]' "$f" 2>/dev/null | tr -d ' :{' | sort | uniq -c | sort -rn | head -40
  done
  echo "== files mentioning quota under ~/.gemini (names only)"
  grep -rIl -iE 'remainingFraction|remaining_fraction|quotaInfo|quota_info|resetTime|reset_time' ~/.gemini 2>/dev/null | grep -viE 'node_modules|/brain/|/conversations/' | head -20
} > "$OUT/env4.txt" 2>&1
echo "done. Tell Claude probe4 finished."
