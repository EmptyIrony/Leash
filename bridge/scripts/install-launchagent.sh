#!/bin/bash
# Install the Leash bridge as a macOS LaunchAgent (starts at login, restarts on crash).
#   bash ~/workspace/Leash/bridge/scripts/install-launchagent.sh
#   bash ~/workspace/Leash/bridge/scripts/install-launchagent.sh uninstall
set -euo pipefail
LABEL=dev.leash.bridge
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/leash-bridge.log"
BRIDGE="$(cd "$(dirname "$0")/.." && pwd)"

if [[ "${1:-}" == "uninstall" ]]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "removed $LABEL"
  exit 0
fi

NODE="$(command -v node || true)"
AGY="$(command -v agy || true)"
[[ -z "$NODE" ]] && { echo "node not found on PATH"; exit 1; }
if [[ -z "$AGY" ]]; then
  echo "note: agy not found on PATH; the bridge starts whatever agent it detects (see: node src/index.mjs doctor)"
fi

( cd "$BRIDGE" && [[ -d node_modules ]] || npm install --no-audit --no-fund )

# LaunchAgents get a minimal PATH; bake in the dirs of node and agy plus the current PATH.
AGENT_PATH="$(dirname "${AGY:-$NODE}"):$(dirname "$NODE"):$PATH"

mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$NODE</string>
    <string>$BRIDGE/src/index.mjs</string>
  </array>
  <key>WorkingDirectory</key><string>$BRIDGE</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$AGENT_PATH</string>
    <key>HOME</key><string>$HOME</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL  (log: $LOG)"
sleep 2
tail -n 5 "$LOG" || true
echo
"$NODE" "$BRIDGE/src/index.mjs" pair
