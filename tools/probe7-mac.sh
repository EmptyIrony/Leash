#!/bin/bash
# Probe 7: where does agy-powertools get quota buckets? (read-only; secrets filtered)
#   bash ~/workspace/Leash/tools/probe7-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
PT=~/.gemini/config/plugins/agy-powertools
SDK=~/.gemini/config/node_modules/sidecar_sdk
F='token|secret|password|cookie|authorization|bearer|@gmail|refresh'
{
  echo "== powertools layout"; ls -la "$PT" "$PT/lib" 2>/dev/null | head -60
  echo "== plugin manifest(s)"; for m in "$PT"/plugin.json "$PT"/manifest.json "$PT"/package.json "$PT"/*.json; do [ -f "$m" ] && { echo "-- $m"; grep -viE "$F" "$m" | head -60; }; done
  echo "== who produces buckets (bucketId / remainingFraction / quota calls)"
  grep -rnE "bucketId|remainingFraction|buckets|getQuota|quota\(|Quota[A-Z][a-zA-Z]*\(|GetUserStatus|fetchAvailableModels|retrieveUserQuota|loadCodeAssist|cloudcode|sidecar|sdk\." "$PT/lib" "$PT"/*.mjs "$PT"/*.js 2>/dev/null | grep -viE "$F" | cut -c1-220 | head -80
  echo "== sidecar_sdk"; ls -la "$SDK" 2>/dev/null | head -30
  [ -f "$SDK/package.json" ] && grep -vE "$F" "$SDK/package.json" | head -40
  for d in "$SDK"/*.d.ts "$SDK"/dist/*.d.ts "$SDK"/lib/*.d.ts; do [ -f "$d" ] && { echo "-- $d"; grep -nE "quota|Quota|usage|Usage|export (declare )?(function|class|interface|const)|^\s+[a-zA-Z]+\(" "$d" | head -60; }; done
  echo "== sidecars config"; ls -la ~/.gemini/config/sidecars 2>/dev/null | head; for s in ~/.gemini/config/sidecars/*; do [ -f "$s" ] && { echo "-- $s"; grep -viE "$F" "$s" | head -30; }; done
} > "$OUT/env7.txt" 2>&1
echo "done. Tell Claude probe7 finished."
