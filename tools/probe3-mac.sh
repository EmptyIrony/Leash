#!/bin/bash
# Probe 3: does `agy acp --app-data-dir antigravity` (the desktop app's data dir) replay desktop history?
#   bash ~/workspace/Leash/tools/probe3-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
node "$HERE/acp-probe3.mjs" "$OUT"
echo "done. Tell Claude probe3 finished."
