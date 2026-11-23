#!/bin/bash
# agy lock probe: does the Antigravity desktop app / agy leave a trace of "conversation X is open in a window"?
# Differential — keep that conversation closed on the phone (the bridge's own agy acp would touch it too), then:
#   1) open it in the Antigravity desktop app and send it one message (so it is the newest), then
#        bash ~/workspace/Leash/tools/probe-agylock-mac.sh open   [conversationId]
#   2) switch the app to a different conversation (app still running), then
#        bash ~/workspace/Leash/tools/probe-agylock-mac.sh switched
#   3) quit the Antigravity app (Cmd-Q), then
#        bash ~/workspace/Leash/tools/probe-agylock-mac.sh quit
# Only process lists (secrets in args redacted), open-file paths, file names/sizes/mtimes and DB key names are
# recorded — never file contents. Output: .probe/agylock-<label>.txt
LABEL="${1:-open}"; CID="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
F="$OUT/agylock-$LABEL.txt"
AG="$HOME/.gemini/antigravity"
APP="$HOME/Library/Application Support/Antigravity"
DIRS=(); for d in "$HOME/.gemini" "$APP"; do [ -d "$d" ] && DIRS+=("$d"); done
# the conversation: argument, else the one saved by the first run, else the newest brain dir
[ -z "$CID" ] && [ -f "$OUT/agylock-cid" ] && [ "$LABEL" != open ] && CID="$(cat "$OUT/agylock-cid")"
[ -z "$CID" ] && [ -d "$AG/brain" ] && CID="$(ls -t "$AG/brain" | grep -E '^[0-9a-f]{8}-[0-9a-f-]{27}$' | head -1)"
echo "$CID" > "$OUT/agylock-cid"
redact() { perl -pe 's/((?:--?|\b)[\w.-]*(?:token|key|secret|csrf|auth|cookie|password)[\w.-]*[= :]+)\S+/$1<redacted>/gi; s/\b[A-Za-z0-9_\-]{40,}\b/<long>/g'; }
st() { stat -f '%Sm %10z %N' -t '%H:%M:%S' "$@" 2>/dev/null; }
{
  echo "== probe12 label=$LABEL cid=$CID at $(date '+%F %T')"
  echo "== agy"; agy --version 2>&1 | head -2
  echo "== bridge pid(s) (its agy acp children also use the data dir): $(pgrep -f 'bridge/src/index.mjs' | paste -sd, -)"
  echo "== processes (pid ppid start command)"
  ps -axo pid,ppid,lstart,command | grep -iE '/Antigravity[^/]*\.app/|[/ ]agy( |$)|language_server|jetski' | grep -vE 'grep|workspace/Leash' | redact | cut -c1-400
  PIDS=$(ps -axo pid,command | grep -iE '/Antigravity[^/]*\.app/|[/ ]agy( |$)|language_server|jetski' | grep -vE 'grep|workspace/Leash' | awk '{print $1}' | paste -sd, -)
  echo "== open files of those processes under the data dirs (pids $PIDS)"
  [ -n "$PIDS" ] && lsof -nP -p "$PIDS" 2>/dev/null | grep -E '\.gemini|Application Support/Antigravity' \
     | grep -vE '/(Cache|CachedData|GPUCache|Code Cache|DawnCache|DawnGraphiteCache|Service Worker)/' \
     | awk '{c=$1; p=$2; fd=$4; $1=$2=$3=$4=$5=$6=$7=$8=""; sub(/^ +/,""); print c, p, fd, $0}' | sort -u | head -300
  echo "== listening sockets of those processes"
  [ -n "$PIDS" ] && lsof -nP -a -p "$PIDS" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1, $2, $9}' | sort -u
  echo "== lock / pid / socket / window / session-like files"
  find "${DIRS[@]}" -maxdepth 7 \( -iname '*lock*' -o -iname '*.pid' -o -iname '*.sock' -o -iname '*window*' -o -iname '*session*' -o -iname '*active*' -o -iname '*open*' \) \
     -not -path '*/node_modules/*' -not -path '*/extensions/*' -not -path '*Cache*' 2>/dev/null | head -200 | while read -r p; do st "$p"; done
  echo "== files modified in the last ${MIN:-20} min"
  find "${DIRS[@]}" -type f -mmin -"${MIN:-20}" -not -path '*Cache*' -not -path '*/logs/*' 2>/dev/null | head -300 | while read -r p; do st "$p"; done
  if [ -n "$CID" ]; then
    echo "== paths containing the conversation id"
    find "${DIRS[@]}" -maxdepth 8 -path "*$CID*" 2>/dev/null | head -100 | while read -r p; do st "$p"; done
    echo "== files whose bytes mention the conversation id (names only, files < 5MB)"
    find "${DIRS[@]}" -type f -size -5M -not -path '*Cache*' -not -path '*/extensions/*' -not -path "*$CID*" 2>/dev/null \
       | while read -r p; do grep -qa -- "$CID" "$p" 2>/dev/null && st "$p"; done | head -100
  fi
  echo "== sqlite state DBs (key names + value sizes only)"
  if command -v sqlite3 >/dev/null; then
    find "$APP" -name '*.vscdb' -not -name '*.backup' 2>/dev/null | while read -r db; do
      echo "-- $(st "$db")"
      sqlite3 -readonly "$db" "select key, length(value) from ItemTable where key like '%conversation%' or key like '%cascade%' or key like '%agent%' or key like '%chat%' or key like '%session%' or key like '%window%' or key like '%jetski%' or key like '%brain%' order by key;" 2>&1 | head -80
      [ -n "$CID" ] && { echo "   keys whose value mentions the cid:"; sqlite3 -readonly "$db" "select '   ' || key from ItemTable where cast(value as text) like '%$CID%';" 2>&1 | head -40; }
    done
  else echo "(no sqlite3)"; fi
  echo "== data dir layout (2 levels)"
  for d in "${DIRS[@]}"; do find "$d" -maxdepth 2 -not -path '*Cache*' 2>/dev/null | head -120; done
} > "$F" 2>&1
wc -l "$F"
echo "wrote $F (conversation $CID)"
case "$LABEL" in
  open) echo "next: switch the desktop app to another conversation, then: bash $0 switched" ;;
  switched) echo "next: quit the Antigravity app (Cmd-Q), then: bash $0 quit" ;;
  *) echo "done. Tell Claude the agy lock probe finished." ;;
esac
