#!/bin/bash
# Probe 12: compile check for the iOS app (agent-embed support). Read-only apart from DerivedData under /tmp.
#   bash ~/workspace/Leash/tools/probe12-mac.sh
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/.."; OUT="$ROOT/.probe"; mkdir -p "$OUT"
LOG="$OUT/build12.txt"; FULL="/tmp/leash-build12.log"
{
  echo "== xcodebuild"; xcodebuild -version 2>&1 | head -3
  if xcodebuild -version >/dev/null 2>&1; then
    cd "$ROOT/ios"
    xcodebuild -project Leash.xcodeproj -scheme Leash -configuration Debug \
      -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/leash-dd \
      CODE_SIGNING_ALLOWED=NO build > "$FULL" 2>&1
    echo "exit $?"
    grep -E "error:|warning:|nearly matches|BUILD (SUCCEEDED|FAILED)" "$FULL" | grep -v "^ld: warning" | sort -u | head -150
  else
    echo "== no Xcode: syntax-only swiftc -parse"
    for f in Views/AgentEmbedView.swift Views/MarkdownView.swift Views/ChatView.swift; do
      echo "-- $f"; xcrun swiftc -parse "$ROOT/ios/Leash/$f" 2>&1 | head -40
    done
  fi
} > "$LOG" 2>&1
tail -60 "$LOG"
echo "done. Tell Claude probe12 finished."
