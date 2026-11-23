#!/bin/bash
# Probe 6: shape of agy-powertools quota samples + how the plugin obtains quota (read-only).
#   bash ~/workspace/Leash/tools/probe6-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
Q=~/.gemini/antigravity/plugin_data/agy-powertools/quota-samples.json
L=~/.gemini/config/plugins/agy-powertools/lib/quota.mjs
{
  echo "== $Q"; ls -la "$Q"
  node -e '
    const fs=require("fs"); const j=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
    const shape=(v,d=0)=>{ if(Array.isArray(v)) return "["+v.length+"] "+(v.length?shape(v[v.length-1],d+1):""); if(v&&typeof v==="object"){ if(d>4) return "{…}"; return "{"+Object.entries(v).slice(0,25).map(([k,x])=>k+": "+shape(x,d+1)).join(", ")+"}"; } return typeof v==="string" ? JSON.stringify(v.length>60? v.slice(0,60)+"…" : v) : String(v); };
    console.log("shape:", shape(j));
    const arr = Array.isArray(j) ? j : (j.samples || Object.values(j).find(Array.isArray) || []);
    console.log("last sample:", JSON.stringify(arr[arr.length-1], null, 1).slice(0, 3000));
  ' "$Q" 2>&1 | grep -viE "email|token|@" 
  echo "== how quota.mjs fetches (matching lines, secrets filtered)"
  grep -nE "fetch\(|https?://|RPC|rpc|Connect|GetUserStatus|quota|retrieve|language_server|csrf|remaining|reset|window|5h|weekly|hours" "$L" 2>/dev/null | grep -viE "token|secret|key\s*[:=]" | head -80
  echo "== exported functions"; grep -nE "^export (async )?function|^export const" "$L" 2>/dev/null | head -40
} > "$OUT/env6.txt" 2>&1
echo "done. Tell Claude probe6 finished."
