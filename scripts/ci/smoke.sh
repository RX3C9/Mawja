#!/usr/bin/env bash
# Mawja CI device smoke test. Sourced by screenshots.sh while the emulator is still up, so it
# reuses its helpers (a, start_app, stop_app, PKG, DL_PKG, VIDEO_ID, MUSIC_ID, WORK, log).
# Checks: real video playback, real music playback, a seeded monkey run (random taps/swipes),
# the downloader app launch, and every crash/ANR the app hit during the whole screenshot run.
# Writes $WORK/smoke.txt (one line per check) and posts "smoke src=<sha> ..." to the log topic.
SMOKE="$WORK/smoke.txt"; : > "$SMOKE"
sres() { echo "$1: $2" >> "$SMOKE"; log "smoke $1: $2"; }

playback_state() { # -> numeric PlaybackState of the app's media session (3 = playing), or none
  T=20 a shell dumpsys media_session 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '
    /package=/ { inpkg = index($0, p) > 0 }
    inpkg && /state=PlaybackState/ { if (match($0, /state=[0-9]+/)) { print substr($0, RSTART + 6, RLENGTH - 6); exit } }'
}
wait_playing() { # seconds -> 0 once a session reports PLAYING
  local i st
  for i in $(seq 1 "$1"); do st=$(playback_state); [ "$st" = 3 ] && return 0; sleep 1; done
  echo "${st:-none}" > "$WORK/last_state"; return 1
}

# Crashes during the screenshot passes (crash buffer survives app restarts).
pre=$(T=30 a logcat -b crash -d 2>/dev/null | tr -d '\r')
echo "$pre" > "$WORK/crash-screens.txt"
pre_n=$(echo "$pre" | grep -c "Process: $PKG" || true)
sres crashes_during_screens "$pre_n"
T=10 a logcat -b crash -c >/dev/null 2>&1 || true
T=10 a logcat -c >/dev/null 2>&1 || true

# 1) Video playback (real stream from the runner's network).
stop_app
start_app --ez $BYPASS true --es video_id "$VIDEO_ID"
if wait_playing 45; then sres video_playback ok; else sres video_playback "FAILED (state $(cat "$WORK/last_state" 2>/dev/null))"; fi
sleep 5; pos1=$(T=20 a shell dumpsys media_session 2>/dev/null | tr -d '\r' | grep -o 'position=[0-9]*' | head -1)
sleep 6; pos2=$(T=20 a shell dumpsys media_session 2>/dev/null | tr -d '\r' | grep -o 'position=[0-9]*' | head -1)
[ -n "$pos1" ] && [ "$pos1" != "$pos2" ] && sres video_advances "ok ($pos1 -> $pos2)" || sres video_advances "unclear ($pos1 -> $pos2)"
stop_app

# 2) Music playback through the widget route.
music_flow
if wait_playing 45; then sres music_playback ok; else sres music_playback "FAILED (state $(cat "$WORK/last_state" 2>/dev/null))"; fi
stop_app

# 3) Monkey: 2000 seeded random events inside the app only, no system keys.
start_app --ez $BYPASS true; sleep 5
T=420 a shell monkey -p "$PKG" -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 5 \
  --ignore-security-exceptions -v 2000 > "$WORK/monkey.txt" 2>&1
mc=$(grep -c '// CRASH' "$WORK/monkey.txt" || true); ma=$(grep -c 'NOT RESPONDING\|// ANR' "$WORK/monkey.txt" || true)
done_ev=$(grep -o 'Events injected: [0-9]*' "$WORK/monkey.txt" | tail -1)
sres monkey "crashes=$mc anrs=$ma ${done_ev:-events unknown}"
stop_app

# 4) Downloader companion launches.
if T=20 a shell pm path "$DL_PKG" >/dev/null 2>&1; then
  T=10 a logcat -b crash -c >/dev/null 2>&1
  T=60 a shell monkey -p "$DL_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  if T=20 a shell pidof "$DL_PKG" >/dev/null 2>&1; then sres downloader_launch ok; else sres downloader_launch "FAILED (not running)"; fi
  T=20 a shell am force-stop "$DL_PKG" >/dev/null 2>&1
fi

# Crashes during the smoke test itself, with the first stack.
post=$(T=30 a logcat -b crash -d 2>/dev/null | tr -d '\r')
anr=$(T=30 a logcat -d -s ActivityManager:E 2>/dev/null | tr -d '\r' | grep -c "ANR in $PKG" || true)
{ echo "=== crash buffer (screens) ==="; cat "$WORK/crash-screens.txt"; echo "=== crash buffer (smoke) ==="; echo "$post"; echo "=== monkey ==="; tail -120 "$WORK/monkey.txt"; } > "$WORK/smoke-log.txt"
post_n=$(echo "$post" | grep -c "Process: $PKG" || true)
first=$( { echo "$pre"; echo "$post"; } | grep -A3 'FATAL EXCEPTION' | grep -E 'Exception|Error' | head -2 | tr '\n' ' ' | cut -c1-300)
sres crashes_during_smoke "$post_n anr=$anr ${first:+first: $first}"

slogurl=$(curl -fsS -m 60 -F "files[]=@$WORK/smoke-log.txt" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null || echo none)
verdict=PASS
grep -qE 'FAILED|crashes=[1-9]|anrs=[1-9]' "$SMOKE" && verdict=FAIL
[ "$pre_n" != 0 ] || [ "$post_n" != 0 ] && verdict=FAIL
curl -fsS -H "Title: Mawja CI smoke $verdict" -d "smoke $verdict src=${SHA:0:7} log=$slogurl
$(cat "$SMOKE")" "https://ntfy.sh/$TOPIC" >/dev/null || log "smoke ntfy post failed"
log "smoke $verdict"
