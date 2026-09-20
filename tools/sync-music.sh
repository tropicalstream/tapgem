#!/usr/bin/env bash
# Push audio files (or whole folders) onto the RayNeo X3 Pro's Music library.
#
# Why this exists: the glasses' firmware ships with com.android.mtp disabled, so
# no Mac file manager (OpenMTP, Android File Transfer, etc.) can ever see the
# device over USB — it simply never advertises the MTP interface. adb push is
# the only channel onto /sdcard that works on this hardware. This script wraps
# it so dropping a folder on the glasses feels like a sync, not a one-off copy:
# it skips files that are already there unchanged (by size) and reports what
# moved, same as a real file manager would.
#
# Usage:
#   tools/sync-music.sh <file-or-folder> [more...]
#   tools/sync-music.sh ~/Music/SomeAlbum
#   tools/sync-music.sh track1.mp3 track2.flac ~/Music/Podcasts
#
# Destination: /sdcard/Music on the glasses. A folder argument keeps its own
# name as a subfolder there (so an album stays grouped); loose files land
# directly in /sdcard/Music. TapGem's player walks 4 levels deep, so nested
# album/artist folders are found without a rescan.

set -euo pipefail
ADB="${ADB:-/opt/homebrew/bin/adb}"
SERIAL="${ANDROID_SERIAL:-A06B4A96A733283}"
DEST_ROOT="/sdcard/Music"
EXTS="mp3 m4a flac wav ogg aac"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <file-or-folder> [more...]" >&2
  exit 1
fi

adb() { "$ADB" -s "$SERIAL" "$@"; }

if ! adb get-state >/dev/null 2>&1; then
  echo "No device at serial $SERIAL — plug in the glasses and unlock them." >&2
  exit 1
fi

is_audio() {
  local ext="${1##*.}"
  ext="$(echo "$ext" | tr '[:upper:]' '[:lower:]')"
  for e in $EXTS; do [ "$e" = "$ext" ] && return 0; done
  return 1
}

remote_size() {
  # stat -c%s isn't on every Android toolbox; ls -l parses everywhere. A missing
  # remote file makes `ls` (and so the whole pipe, under pipefail) exit non-zero —
  # that's the normal "not pushed yet" case, not an error, so swallow it here.
  adb shell "ls -l '$1' 2>/dev/null" 2>/dev/null | awk '{print $5}' | tr -d '\r' || true
}

pushed=0; skipped=0; failed=0

push_one() {
  local local_path="$1" remote_path="$2"
  local remote_dir; remote_dir="$(dirname "$remote_path")"
  adb shell "mkdir -p '$remote_dir'" >/dev/null 2>&1 || true

  local lsize rsize
  lsize=$(stat -f%z "$local_path" 2>/dev/null || stat -c%s "$local_path" 2>/dev/null || echo -1)
  rsize=$(remote_size "$remote_path")

  if [ -n "$rsize" ] && [ "$rsize" = "$lsize" ]; then
    skipped=$((skipped + 1))
    echo "  = $(basename "$local_path") (already there, same size)"
    return
  fi

  if adb push "$local_path" "$remote_path" >/tmp/sync-music-push.log 2>&1; then
    pushed=$((pushed + 1))
    echo "  + $(basename "$local_path")"
  else
    failed=$((failed + 1))
    echo "  ! $(basename "$local_path") FAILED — $(tail -1 /tmp/sync-music-push.log)"
  fi
}

for arg in "$@"; do
  if [ -f "$arg" ]; then
    if is_audio "$arg"; then
      push_one "$arg" "$DEST_ROOT/$(basename "$arg")"
    else
      echo "  skip $(basename "$arg") (not an audio file)"
    fi
  elif [ -d "$arg" ]; then
    folder_name="$(basename "$arg")"
    echo "$folder_name/"
    while IFS= read -r -d '' f; do
      rel="${f#"$arg"/}"
      push_one "$f" "$DEST_ROOT/$folder_name/$rel"
    done < <(find "$arg" -type f \( -iname "*.mp3" -o -iname "*.m4a" -o -iname "*.flac" -o -iname "*.wav" -o -iname "*.ogg" -o -iname "*.aac" \) -print0)
  else
    echo "  ! $arg (not found)" >&2
    failed=$((failed + 1))
  fi
done

echo
echo "pushed $pushed, skipped $skipped (unchanged), failed $failed"
if [ "$pushed" -gt 0 ]; then
  # Nudge the media scanner so MediaStore picks the new files up immediately;
  # MusicPlayer's own directory walk would find them anyway on next rescan.
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$DEST_ROOT" >/dev/null 2>&1 || true
  echo "Say \"rescan the library\" (or ask the player to list tracks) to pick them up."
fi
