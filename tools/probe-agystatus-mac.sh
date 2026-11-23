#!/bin/bash
# agy status probe: watch conversation_summaries.status / not_fully_idle once a second through one whole turn.
#   1) start this:   bash ~/workspace/Leash/tools/probe-agystatus-mac.sh
#   2) within ~10 s, on the desktop send a task that thinks, runs a command and then writes, e.g.
#      "先想一想，然后在终端运行 sleep 20，最后写一段 200 字的总结"; let it finish and wait until the probe ends (~90 s).
# Reads only ids / status / timestamps (from a temp copy if the live DB can't be opened read-only); no conversation text.
N="${N:-90}"
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../.probe"; mkdir -p "$OUT"; F="$OUT/agystatus.txt"
AG="$HOME/.gemini/antigravity"; DB="$AG/conversation_summaries.db"
TMP="$(mktemp -d)"
SQL="select substr(conversation_id,1,8)||' '||status||' nfi='||not_fully_idle||' mod='||substr(last_modified_time,12,8) from conversation_summaries order by last_modified_time desc limit 3"
q() {
  local r; r=$(sqlite3 -readonly "$DB" "$SQL" 2>&1) && { echo "$r" | paste -sd'|' -; return; }
  cp "$DB" "$TMP/s.db" 2>/dev/null; for x in -wal -shm; do [ -f "$DB$x" ] && cp "$DB$x" "$TMP/s.db$x"; done
  r=$(sqlite3 -readonly "$TMP/s.db" "$SQL" 2>&1); echo "(copy) $(echo "$r" | paste -sd'|' -)"; rm -f "$TMP"/s.db*
}
LS=$(pgrep -f '/Antigravity.app/Contents/Resources/bin/language_server' | head -1)
{
  echo "== agystatus start $(date '+%F %T') language_server=$LS"
  echo "== distinct status values so far: $(sqlite3 -readonly "$DB" 'select group_concat(distinct status) from conversation_summaries' 2>&1)"
  for i in $(seq 1 "$N"); do
    kids=$(pgrep -P "$LS" 2>/dev/null | while read -r c; do ps -o comm= -p "$c"; done | grep -v 'Antigravity Helper' | xargs -n1 basename 2>/dev/null | paste -sd, -)
    locks=$(lsof -nP +d "$AG/presence" 2>/dev/null | awk '$9 ~ /\.lock$/ {n=split($9,a,"/"); print substr(a[n],1,8)}' | sort -u | paste -sd, -)
    echo "$(date '+%T') $(q) || kids=[$kids] locks=[$locks]"
    sleep 1
  done
  echo "== distinct status values at the end: $(sqlite3 -readonly "$DB" 'select group_concat(distinct status) from conversation_summaries' 2>&1)"
} > "$F" 2>&1
rm -rf "$TMP"
tail -5 "$F"; echo "wrote $F. Tell Claude the agy status probe finished."
