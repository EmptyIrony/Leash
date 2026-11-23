#!/bin/bash
# Probe 5: sample every tool-call shape agy emits (one example per tool name), from a desktop conversation.
#   bash ~/workspace/Leash/tools/probe5-mac.sh [conversationId]
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
node "$HERE/acp-probe5.mjs" "$OUT" "${1:-55076391-e9cc-4744-bf70-a9a35b7aa454}"
echo "done. Tell Claude probe5 finished."
