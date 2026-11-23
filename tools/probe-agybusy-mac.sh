#!/bin/bash
# agy busy probe: what tells "the desktop app is running a turn in conversation X" apart from "X is just loaded"?
# Keep that conversation closed on the phone. Each run samples for ~30 s (every 2 s):
#   1) on the desktop, give the agent a task that takes over a minute (e.g. "run \`sleep 90\` in the terminal, then say done"),
#      and while it is still working run:
#        bash ~/workspace/Leash/tools/probe-agybusy-mac.sh busy  [conversationId]
#   2) after it has finished (leave the conversation open, don't type), run:
#        bash ~/workspace/Leash/tools/probe-agybusy-mac.sh idle
# Records file names/sizes/mtimes, open-file paths, process names (secrets redacted), CPU, and SQLite schema /
# id / status-like columns only (blobs as their length) — no conversation text.
LABEL="${1:-busy}"; CID="${2:-}"; N="${N:-15}"
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"
F="$OUT/agybusy-$LABEL.txt"
AG="$HOME/.gemini/antigravity"
# conversation: argument, else the one the busy run used, else the conversation db written most recently
[ -z "$CID" ] && [ "$LABEL" != busy ] && [ -f "$OUT/agybusy-cid" ] && CID="$(cat "$OUT/agybusy-cid")"
[ -z "$CID" ] && CID="$(ls -t "$AG/conversations" 2>/dev/null | grep -E '^[0-9a-f]{8}-[0-9a-f-]{27}\.db' | head -1 | cut -c1-36)"
echo "$CID" > "$OUT/agybusy-cid"
redact() { perl -pe 's/((?:--?|\b)[\w.-]*(?:token|key|secret|csrf|auth|cookie|password)[\w.-]*[= :]+)\S+/$1<redacted>/gi; s/\b[A-Za-z0-9_\-]{40,}\b/<long>/g'; }
st() { stat -f '%Sm %10z %N' -t '%H:%M:%S' "$@" 2>/dev/null; }
LS=$(pgrep -f '/Antigravity.app/Contents/Resources/bin/language_server' | paste -sd, -)
BR=$(pgrep -f '[/ ]agy acp' | paste -sd, -)
TMP="$(mktemp -d)"; MARK="$TMP/mark"; touch "$MARK"
{
  echo "== agybusy label=$LABEL cid=$CID at $(date '+%F %T') language_server=$LS bridge-agy-acp=$BR"
  echo "== agy subcommands"; agy --help 2>&1 | head -40
  for i in $(seq 1 "$N"); do
    sleep 2
    echo "== sample $i $(date '+%T')"
    echo "-- changed since last sample"
    find "$AG" -type f -newer "$MARK" -not -path '*/log/*' -not -path '*Cache*' 2>/dev/null | head -60 | while read -r p; do st "$p"; done
    touch "$MARK"
    if [ -n "$LS" ]; then
      echo "-- language_server cpu: $(ps -o %cpu= -p "${LS%%,*}" 2>/dev/null)"
      echo "-- language_server children"
      for p in ${LS//,/ }; do pgrep -P "$p" | while read -r c; do ps -o pid=,%cpu=,command= -p "$c" | redact | cut -c1-200; done; done
      echo "-- language_server open files mentioning the conversation / presence / terminals"
      lsof -nP -p "$LS" 2>/dev/null | grep -E "$CID|presence|persistent_terminals|daemon" | awk '{fd=$4; $1=$2=$3=$4=$5=$6=$7=$8=""; sub(/^ +/,""); print fd, $0}' | sort -u | head -30
      echo "-- established TCP: $(lsof -nP -a -p "$LS" -iTCP -sTCP:ESTABLISHED 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')"
    fi
  done
  echo "== presence locks held at the end"
  lsof -nP +d "$AG/presence" 2>/dev/null | awk 'NR>1{print $1, $2, $4, $9}'
  echo "== sqlite: schema, id / status-like columns (blobs as length)"
  if command -v sqlite3 >/dev/null; then
    for db in "$AG/conversation_summaries.db" "$AG/conversations/$CID.db"; do
      [ -f "$db" ] || continue
      echo "-- $(st "$db")"
      sqlite3 -readonly "$db" .schema 2>&1 | head -40 | cut -c1-300
      for t in $(sqlite3 -readonly "$db" "select name from sqlite_master where type='table'" 2>/dev/null); do
        expr=$(sqlite3 -readonly "$db" "select group_concat('case typeof(\"'||name||'\") when ''blob'' then ''blob:''||length(\"'||name||'\") when ''text'' then substr(\"'||name||'\",1,60) else \"'||name||'\" end as \"'||name||'\"', ', ') from pragma_table_info('$t') where name like '%status%' or name like '%state%' or name like '%running%' or name like '%busy%' or name like '%active%' or name like '%id' or name like '%_id%' or name like '%updated%' or name like '%time%'" 2>/dev/null)
        [ -n "$expr" ] || continue
        echo "  table $t"
        sqlite3 -readonly -header "$db" "select $expr from \"$t\" order by rowid desc limit 5" 2>&1 | cut -c1-300 | sed 's/^/    /'
      done
    done
  else echo "(no sqlite3)"; fi
} > "$F" 2>&1
rm -rf "$TMP"
wc -l "$F"; echo "wrote $F (conversation $CID)"
case "$LABEL" in
  busy) echo "next: once the task has finished (conversation still open), run: bash $0 idle" ;;
  *) echo "done. Tell Claude the agy busy probe finished." ;;
esac
