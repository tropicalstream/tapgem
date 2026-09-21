#!/usr/bin/env bash
# Read-along verification: run the book reader for N pages and collect the evidence.
#
# The reader page records every highlight change (which word, when, where the view was) and can
# audit itself. This script starts a reading, waits until the requested number of pages has been
# spoken, then pulls the audit, the raw trace, and periodic screenshots into one folder so the
# result can be judged from the record rather than from watching the screen.
#
#   readalong-test.sh <out-dir> [start-offset] [pages]      (a page ≈ 1800 characters)
set -u
A=/opt/homebrew/bin/adb
export ANDROID_SERIAL=A06B4A96A733283
OUT="${1:?out dir}"; FROM="${2:-5000}"; PAGES="${3:-3}"
TARGET=$(( PAGES * 1800 ))
BOOK="Alice in Wonderland"
mkdir -p "$OUT"

T(){ $A shell "am broadcast -a com.tapgem.app.TOOL --es name $1 --es args '$2'" >/dev/null 2>&1; }
# JS evaluated inside the Read-along page; result comes back through logcat.
# Each query carries a nonce and the answer is picked out by it, so the log is never cleared —
# run 2's `logcat -c` before every query threw away the reader's own error messages, and the
# reason a passage failed was lost with them.
q(){ local N="rq$RANDOM$RANDOM"
python3 - "$1" "$N" > "$OUT/.ev.sh" <<'PY'
import json,sys
js="'%s|'+String(%s)"%(sys.argv[2], sys.argv[1])
args=json.dumps({"action":"eval","target":"Alice in Wonderland","js":js})
print("am broadcast -a com.tapgem.app.TOOL --es name web --es args " + "'" + args.replace("'", "'\\''") + "'")
PY
$A shell < "$OUT/.ev.sh" >/dev/null 2>&1; sleep 2
$A logcat -d -b main -t 600 'TapGemApp:*' '*:S' 2>/dev/null | grep "TOOL web → $N|" | tail -1 | sed "s/.*TOOL web → $N|//"; }
shot(){ $A exec-out screencap -p > "$OUT/.raw.png"; ffmpeg -y -loglevel error -i "$OUT/.raw.png" -vf "crop=640:480:0:0" "$OUT/$1.png"; }

echo "== read-along test: from $FROM, $PAGES page(s) = $TARGET chars ==" | tee "$OUT/run.log"
T web "{\"action\":\"stop_reading\",\"target\":\"$BOOK\"}"; sleep 2
# The main log buffer is 64 KiB by default and the kernel spams its own; run 4's reader log came
# back empty because the reader's lines had already rolled out. Read only the main buffer, and
# make it big enough to hold a whole run. (The size resets on reboot.)
$A logcat -G 8M -b main >/dev/null 2>&1
$A logcat -c -b main
T web "{\"action\":\"read_aloud\",\"target\":\"$BOOK\",\"from\":$FROM}"; sleep 3
$A logcat -d -b main 'TapGemApp:*' '*:S' 2>/dev/null | grep "TOOL web" | cut -c1-140 | tee -a "$OUT/run.log"

START_TS=$(date +%s); N=0
while :; do
  sleep 12; N=$((N+1))
  shot "t$(printf %03d $((N*12)))s"
  P=$(q "(function(){var e=document.querySelector('w.now');var box=document.getElementById('text');return JSON.stringify({word:e?e.textContent:null,scroll:box?Math.round(box.scrollTop):-1,passages:document.querySelectorAll('p.passage').length,pct:(document.getElementById('pos')||{}).textContent||''})})()")
  # Completed = every word of the passage was lit. The first run gated on passages merely
  # appended, which counted a passage as read the moment its text arrived.
  AUD=$(q "window.__read?JSON.parse(__read.audit()).charsCompleted:-1")
  echo "t+$((N*12))s  charsCompleted=$AUD  $P" | tee -a "$OUT/run.log"
  [ "${AUD:-0}" -ge "$TARGET" ] 2>/dev/null && break
  [ $(( $(date +%s) - START_TS )) -gt 780 ] && { echo "TIMEOUT before $TARGET chars" | tee -a "$OUT/run.log"; break; }
done

# Pull the record BEFORE stopping: the read-along lives in the book's own window now, and a stop
# gives the window back to the chapter, taking the page and its trace with it.
echo "== pulling record, then stopping ==" | tee -a "$OUT/run.log"
q "window.__read&&__read.audit()" > "$OUT/audit.json"
LEN=$(python3 -c "import json;print(json.load(open('$OUT/audit.json')).get('traceLen',0))" 2>/dev/null || echo 0)
: > "$OUT/trace.jsonl"
i=0
while [ "$i" -lt "${LEN:-0}" ]; do
  q "__read.slice($i,$((i+28)))" | python3 -c "import json,sys;[print(json.dumps(e)) for e in json.load(sys.stdin)]" >> "$OUT/trace.jsonl" 2>/dev/null
  i=$((i+28))
done
echo "trace entries saved: $(wc -l < "$OUT/trace.jsonl")" | tee -a "$OUT/run.log"
T web "{\"action\":\"stop_reading\",\"target\":\"$BOOK\"}"; sleep 3
# the reader's own account of the run, for when something stops early
$A logcat -d -b main -s BookReader GeminiRest WebTool WebCommandBus 2>/dev/null > "$OUT/reader.log"
echo "reader log lines: $(wc -l < "$OUT/reader.log")" | tee -a "$OUT/run.log"
echo "== audit ==" | tee -a "$OUT/run.log"
python3 -c "import json;d=json.load(open('$OUT/audit.json'));[print(f'  {k}: {v}') for k,v in d.items() if k not in ('skips','backs','offscreenSamples','audioMs')]" | tee -a "$OUT/run.log"
