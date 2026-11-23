#!/bin/bash
# Probe 8: does a hidden agy TUI with a session-only statusLine deliver quota JSON?
# Uses --settings (session only, never saved). Sends no prompt. ~25 s.
#   bash ~/workspace/Leash/tools/probe8-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
TMP="$(mktemp -d)"; DUMP="$TMP/status.jsonl"
cat > "$TMP/sl.sh" <<EOF
#!/bin/bash
cat >> "$DUMP"; echo >> "$DUMP"; echo "agr"
EOF
chmod +x "$TMP/sl.sh"
SETTINGS="{\"statusLine\":{\"type\":\"command\",\"command\":\"$TMP/sl.sh\"}}"
# 'script' gives agy a pseudo-terminal so the TUI starts; stdin from a sleeping pipe so it does nothing.
( sleep 25 | script -q /dev/null agy --settings "$SETTINGS" > "$TMP/tui.log" 2>&1 ) &
PID=$!
sleep 22; pkill -P $PID 2>/dev/null; kill $PID 2>/dev/null; pkill -f "agy --settings" 2>/dev/null
sleep 1
{
  echo "== statusline invocations: $(grep -c . "$DUMP" 2>/dev/null || echo 0)"
  if [ -s "$DUMP" ]; then
    node -e '
      const fs=require("fs"); const lines=fs.readFileSync(process.argv[1],"utf8").split("\n").filter(Boolean);
      let last=null; for (const l of lines) { try { last=JSON.parse(l); } catch {} }
      if (!last) { console.log("no JSON parsed; first line:", lines[0]?.slice(0,300)); process.exit(0); }
      console.log("top-level keys:", Object.keys(last).join(", "));
      console.log("plan_tier:", last.plan_tier ?? null);
      console.log("quota:", JSON.stringify(last.quota ?? null, null, 1));
      console.log("model:", JSON.stringify(last.model ?? null));
    ' "$DUMP"
  else
    echo "no statusline output. TUI log tail (control chars stripped):"
    tail -c 1500 "$TMP/tui.log" | tr -cd "[:print:]\n" | tail -20
  fi
} > "$OUT/env8.txt" 2>&1
rm -rf "$TMP"
cat "$OUT/env8.txt" | head -5
echo "done. Tell Claude probe8 finished."
