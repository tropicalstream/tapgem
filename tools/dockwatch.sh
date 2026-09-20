#!/usr/bin/env bash
# Watch for the launcher's system menu firing while the owner holds the right temple.
#
# The dock is NOT a separate window — it's drawn into the launcher's always-present
# overlay (type 2038), so polling the window list detects nothing. The real signature
# is the launcher (pid 1846, uid 1000) logging a GestureDetector long-press:
#   D Mercury : at[TouchDispatcher$createExtendedGestureListener$1.onLongPress(...)]
#
#   dockwatch.sh <label> [seconds]
set -u
A=/opt/homebrew/bin/adb
export ANDROID_SERIAL=A06B4A96A733283
LABEL="${1:-run}"
SECS="${2:-45}"
OUT="/private/tmp/claude-501/-Volumes-wdblack9-3-26-Developer-Projects-X3Gemini/b48d94da-15e6-4793-878a-7e4b2a45614f/scratchpad/dock-$LABEL"

$A logcat -c 2>/dev/null
echo "watching ${SECS}s — go now"
sleep "$SECS"
$A logcat -d > "$OUT.log" 2>/dev/null

echo
echo "── SYSTEM MENU fires (each line = the menu triggered) ──"
grep -E "onLongPress" "$OUT.log" 2>/dev/null | sed 's/ at\[.*onLongPress/  onLongPress/' | cut -c1-120
grep -cE "onLongPress" "$OUT.log" 2>/dev/null | sed 's/^/   total: /'

echo
echo "── other Mercury gesture traffic from the launcher ──"
grep -E "D Mercury" "$OUT.log" 2>/dev/null \
  | grep -oE "on[A-Z][a-zA-Z]+" | sort | uniq -c | sort -rn | head -8

echo
echo "── TapGem's side ──"
grep -iE "TapGem|WidgetView|DesktopHost" "$OUT.log" 2>/dev/null \
  | grep -iE "touch ACTION|long|drag|Moving|Resizing|Placed|grab" | tail -10 | cut -c1-170
