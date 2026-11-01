#!/usr/bin/env bash
# Mawja CI screenshots: boots a headless Android emulator on the runner, installs the github debug
# build (plus the admin and downloader debug apps when built), and captures every v1.0 feature in
# Arabic and English, light and dark: build/screens/<screen>_<lang>_<mode>.png plus a manifest,
# build/screens/index.json. Everything is zipped and uploaded; the ntfy log topic gets
# "screens src=<sha> count=N zip=<url> index=<url> home-en=<url> ..." (the README set under its
# legacy names). See docs/screenshots.md for the screen list, how each one is reached, and knobs.
# Best effort: each screen is its own timed step; a failure is logged and recorded, never fatal.
#
# Robustness notes (run 39467934212 hung right after "adb daemon started" with no clue why):
#  - every SDK/AVD path is exported explicitly (newer avdmanager can write AVDs under
#    ~/.config/.android while the emulator looks in ~/.android -> "unknown AVD", silent hang);
#  - the emulator is started by full path, its PID is watched, every adb call has a timeout;
#  - if an API level does not boot, the next one in MAWJA_SCREEN_APIS is tried;
#  - on any failure the emulator log, accel-check and disk state are printed (build.sh uploads
#    the whole screens.log and links it in the "screens:" line).
set -uo pipefail
TOPIC="${MAWJA_LOG_TOPIC:-mawja-ci-3998ee696e5d3e8578e1}"
SHA=$(cat .mawja-sync 2>/dev/null || git rev-parse HEAD)
OUT=build/screens
PKG=app.mawja.android.debug
ACT=app.mawja.android.MainActivity
BYPASS=app.mawja.android.extra.BENCHMARK_BYPASS_ONBOARDING
APIS=${MAWJA_SCREEN_APIS:-${MAWJA_SCREEN_API:-34} 31 30}
ADMIN_PKG=app.mawja.admin.debug
DL_PKG=app.mawja.downloader.debug
VIDEO_ID=${MAWJA_SCREEN_VIDEO:-jNQXAC9IVRw}
MUSIC_ID=${MAWJA_SCREEN_MUSIC:-kJQP7kiw5Fk}
# Captures stop at this many seconds after the script starts (boot included); uploads follow.
START_EPOCH=$(date +%s)
DEADLINE_EPOCH=$((START_EPOCH + ${MAWJA_SCREEN_BUDGET_SEC:-1440}))
EMU_LOG="$PWD/emulator.log"
mkdir -p "$OUT"
log() { echo "[screens] $(date -u +%H:%M:%S) $*"; }

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
export ANDROID_USER_HOME="$HOME/.android" ANDROID_EMULATOR_HOME="$HOME/.android"
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
EMU_PID=""

diag() { # print everything useful for debugging a failed boot
  log "---- diagnostics ----"
  log "SDK=$SDK AVD_HOME=$ANDROID_AVD_HOME"
  df -h / "$HOME" 2>&1 | sed 's/^/[diag] /'
  free -m 2>&1 | sed 's/^/[diag] /'
  ls -la /dev/kvm 2>&1 | sed 's/^/[diag] /'
  [ -x "$EMU" ] && timeout 60 "$EMU" -accel-check 2>&1 | sed 's/^/[accel] /'
  ls -la "$ANDROID_AVD_HOME" 2>&1 | sed 's/^/[diag] /'
  [ -n "$EMU_PID" ] && { kill -0 "$EMU_PID" 2>/dev/null && log "emulator pid $EMU_PID alive" || log "emulator pid $EMU_PID DEAD"; }
  timeout 10 "$ADB" devices -l 2>&1 | sed 's/^/[diag] /'
  if [ -f "$EMU_LOG" ]; then log "---- emulator.log (tail 80) ----"; tail -80 "$EMU_LOG" | sed 's/^/[emu] /'; fi
}
fail() { log "FAIL: $*"; diag; stop_emu; exit 1; }
stop_emu() {
  timeout 20 "$ADB" emu kill >/dev/null 2>&1 || true
  if [ -n "$EMU_PID" ]; then sleep 3; kill "$EMU_PID" 2>/dev/null; sleep 2; kill -9 "$EMU_PID" 2>/dev/null; fi
  EMU_PID=""
}
a() { timeout "${T:-60}" "$ADB" "$@"; }   # adb with a timeout (override per call: T=120 a ...)

apk=$(find app/build/outputs/apk -name 'app-github-x86_64-debug.apk' | head -1)
[ -z "$apk" ] && apk=$(find app/build/outputs/apk -name 'app-github-universal-debug.apk' | head -1)
[ -z "$apk" ] && fail "no github debug apk"
log "apk: $apk ($(stat -c %s "$apk") bytes); APIs to try: $APIS"
[ -x "$SDKM" ] || fail "sdkmanager missing at $SDKM"

# KVM for the hosted runner.
if [ -e /dev/kvm ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
  sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
  sleep 1; sudo chmod 666 /dev/kvm 2>/dev/null || true
else
  fail "no /dev/kvm"
fi

# Licences without `yes |` (which spams "Broken pipe" when sdkmanager exits first).
printf 'y\n%.0s' $(seq 1 40) | timeout 300 "$SDKM" --licenses >/dev/null 2>&1 || true
log "installing emulator + platform-tools"
timeout 600 "$SDKM" --install "emulator" "platform-tools" > sdk-tools.log 2>&1 || { tail -20 sdk-tools.log; fail "sdkmanager (tools) failed"; }
[ -x "$EMU" ] || { tail -20 sdk-tools.log; fail "emulator binary missing at $EMU"; }
log "emulator: $("$EMU" -version 2>/dev/null | head -1)"
timeout 30 "$ADB" start-server >/dev/null 2>&1 || true

boot_api() { # api -> 0 when booted
  local api=$1 img="system-images;android-$1;google_apis;x86_64"
  local imgdir="$SDK/system-images/android-$1/google_apis/x86_64"
  log "API $api: installing $img"
  timeout 900 "$SDKM" --install "$img" > "sdk-$api.log" 2>&1 || { tail -15 "sdk-$api.log"; log "sdkmanager failed for $img"; return 1; }
  [ -f "$imgdir/system.img" ] || [ -f "$imgdir/system.img.qcow2" ] || ls "$imgdir"/*.img >/dev/null 2>&1 \
    || { tail -15 "sdk-$api.log"; ls -la "$imgdir" 2>&1; log "system image dir missing/empty: $imgdir"; return 1; }
  log "API $api: creating AVD"
  echo no | timeout 120 "$AVDM" create avd -n mawja -k "$img" -d pixel_6 --force -p "$ANDROID_AVD_HOME/mawja.avd" > avd.log 2>&1 \
    || { cat avd.log; log "avd create failed"; return 1; }
  [ -f "$ANDROID_AVD_HOME/mawja.ini" ] || { cat avd.log; ls -la "$ANDROID_AVD_HOME" "$HOME/.config/.android/avd" 2>&1; log "AVD ini not where the emulator looks"; return 1; }
  # Bigger data partition + heap; hardware keyboard on so `input text` behaves.
  { echo "disk.dataPartition.size=4G"; echo "hw.keyboard=yes"; echo "hw.ramSize=3072"; } >> "$ANDROID_AVD_HOME/mawja.avd/config.ini"
  "$EMU" -list-avds 2>&1 | sed 's/^/[avds] /'

  log "API $api: starting emulator"
  : > "$EMU_LOG"
  "$EMU" -avd mawja -accel on -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
    -camera-back none -camera-front none -memory 3072 -no-metrics >"$EMU_LOG" 2>&1 &
  EMU_PID=$!
  sleep 10
  kill -0 "$EMU_PID" 2>/dev/null || { log "emulator exited immediately"; diag; EMU_PID=""; return 1; }
  T=240 a wait-for-device || { log "adb wait-for-device timed out"; diag; stop_emu; return 1; }
  log "API $api: device visible, waiting for boot_completed"
  local booted=""
  for _ in $(seq 1 100); do   # up to ~5 min
    kill -0 "$EMU_PID" 2>/dev/null || { log "emulator died during boot"; diag; EMU_PID=""; return 1; }
    [ "$(T=10 a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { booted=1; break; }
    sleep 3
  done
  [ -n "$booted" ] || { log "API $api did not finish booting"; diag; stop_emu; return 1; }
  # Wait for package manager too.
  for _ in $(seq 1 40); do T=15 a shell pm path android >/dev/null 2>&1 && break; sleep 3; done
  log "API $api: booted"
  BOOTED_API=$api
  return 0
}

BOOTED_API=""
for api in $APIS; do
  boot_api "$api" && break
  timeout 20 "$ADB" kill-server >/dev/null 2>&1; timeout 20 "$ADB" start-server >/dev/null 2>&1
done
[ -n "$BOOTED_API" ] || fail "no API level booted ($APIS)"

for s in window_animation_scale transition_animation_scale animator_duration_scale; do a shell settings put global $s 0; done
a shell settings put system screen_off_timeout 1800000
a shell svc power stayon true
a shell input keyevent 82
a shell cmd uimode night yes

T=240 a install -r -g "$apk" > install.log 2>&1 || { cat install.log; fail "install failed"; }
log "installed"
a shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# The companion apps are optional: a missing APK only skips their screens.
for extra in "admin:$ADMIN_PKG" "downloader:$DL_PKG"; do
  mod=${extra%%:*}
  eapk=$(find "$mod/build/outputs/apk" -name '*debug*.apk' 2>/dev/null | head -1)
  if [ -n "$eapk" ] && T=180 a install -r -g "$eapk" > "install-$mod.log" 2>&1; then
    log "installed $mod ($eapk)"
  else
    log "skip $mod app: no apk or install failed"; [ -f "install-$mod.log" ] && tail -3 "install-$mod.log"
  fi
done

# ---------------------------------------------------------------------------------------------
# Capture helpers. Every screen runs as its own killable step (`step`): a hang, a crash or a
# missing screen is logged and recorded in the manifest, never aborts the run.
# ---------------------------------------------------------------------------------------------
WORK="$PWD/build/screens-work"; mkdir -p "$WORK"
ROWS="$WORK/rows.tsv"; : > "$ROWS"
size=$(a shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${size%x*}; H=${size#*x}
case "$W$H" in *[!0-9]*|"") W=1080; H=2400 ;; esac
LANG_=en; MODE=dark
export ADB PKG ACT BYPASS OUT WORK ROWS W H LANG_ MODE DEADLINE_EPOCH ADMIN_PKG DL_PKG VIDEO_ID MUSIC_ID BOOTED_API

over_budget() { [ "$(date +%s)" -ge "$DEADLINE_EPOCH" ]; }
record() { # screen status bytes note
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$LANG_" "$MODE" "${1}_${LANG_}_${MODE}.png" "$2" "${3:-0}" "${4:-}" >> "$ROWS"
}
shot() { # screen, seconds to settle -> build/screens/<screen>_<lang>_<mode>.png
  local f="$OUT/${1}_${LANG_}_${MODE}.png"
  sleep "${2:-4}"
  if T=30 a exec-out screencap -p > "$f" && [ -s "$f" ]; then
    log "captured ${1}_${LANG_}_${MODE} ($(stat -c %s "$f") bytes)"
    record "$1" captured "$(stat -c %s "$f")"
  else
    rm -f "$f"; log "screencap failed for ${1}_${LANG_}_${MODE}"; record "$1" failed 0 "screencap failed"
  fi
}
miss() { log "skip ${1}_${LANG_}_${MODE}: $2"; record "$1" skipped 0 "$2"; }
stop_app() { a shell am force-stop "${1:-$PKG}" >/dev/null 2>&1; }
start_app() { T=90 a shell am start -W -n $PKG/$ACT "$@" >/dev/null 2>&1 || log "am start timed out ($*)"; }
# Any FlowNavigation route, through the same extra the home-screen widgets use (WidgetDeepLink).
open_route() { start_app --ez $BYPASS true --es widget_route "'$1'"; }
# settings?target=<SettingsDestination.id>[~tab][#highlight]; '#' must travel as %23 in the route.
open_settings() { open_route "settings?target=$1"; }
swipe_up() { a shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 250 >/dev/null 2>&1; sleep 1; }
ui_dump() {
  rm -f "$WORK/ui.xml"
  T=25 a shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  T=15 a exec-out cat /sdcard/ui.xml > "$WORK/ui.xml" 2>/dev/null
  grep -q '<hierarchy' "$WORK/ui.xml" 2>/dev/null
}
find_text() { # python regex on text or content-desc -> "x y" of the first match
  ui_dump || return 1
  python3 - "$WORK/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[2])
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
for n in root.iter("node"):
    for attr in ("text", "content-desc"):
        v = (n.get(attr) or "").strip()
        if v and pat.fullmatch(v):
            m = re.findall(r"\d+", n.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
PY
}
# shellcheck disable=SC2086 # "x y" splits into two args on purpose
tap_text() { local xy; xy=$(find_text "$1") || return 1; [ -n "$xy" ] || return 1; a shell input tap $xy; }
# App SharedPreferences, written while the app is stopped (debug builds are debuggable: run-as).
prefs_write() { # file, xml entries
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n%s\n</map>\n" "$2" \
    | T=20 "$ADB" exec-in "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs && cat > /data/data/$PKG/shared_prefs/$1.xml'"
}
prefs_rm() { T=20 a shell "run-as $PKG rm -f /data/data/$PKG/shared_prefs/$1.xml" >/dev/null 2>&1; }
# Preferences DataStore: append one string entry to <store>.preferences_pb. The file is a protobuf
# PreferenceMap; a repeated map key parses last-wins, so appending overrides without decoding.
ds_put() { # store key value
  python3 - "$2" "$3" > "$WORK/ds.bin" <<'PY' || return 1
import sys
def v(n):
    out = b""
    while True:
        b = n & 0x7F; n >>= 7
        if n: out += bytes([b | 0x80])
        else: return out + bytes([b])
def f(tag, data): return bytes([tag]) + v(len(data)) + data
key, val = sys.argv[1].encode(), sys.argv[2].encode()
entry = f(0x0A, key) + f(0x12, f(0x2A, val))      # MapEntry{1:key, 2:Value{5:string}}
sys.stdout.buffer.write(f(0x0A, entry))           # PreferenceMap{1: entry}
PY
  T=20 "$ADB" exec-in "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/files/datastore && cat >> /data/data/$PKG/files/datastore/$1.preferences_pb'" < "$WORK/ds.bin"
}
airplane() { # on|off
  local en=enable flag=1; [ "$1" = off ] && { en=disable; flag=0; }
  if ! T=20 a shell cmd connectivity airplane-mode $en >/dev/null 2>&1; then
    a shell settings put global airplane_mode_on $flag >/dev/null 2>&1
    a shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "$([ $flag = 1 ] && echo true || echo false)" >/dev/null 2>&1
  fi
}
a11y_reset() { prefs_rm mawja_accessibility; a shell settings put system font_scale 1.0 >/dev/null 2>&1; }
launcher_of() { T=20 a shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" 2>/dev/null | tr -d '\r' | grep '/' | tail -1; }
music_flow() { # plays MUSIC_ID through the widget route, then expands the full music player
  open_route "musicPlayer/$MUSIC_ID"; sleep 8
  start_app --ez $BYPASS true --ez open_music_player true
}

# ---- screens (each one independent) ---------------------------------------------------------
s_home() { stop_app; start_app --ez $BYPASS true; shot home 10; }
s_route() { open_route "$2"; shot "$1" "${3:-3}"; }
s_settings() { open_settings "$2"; shot "$1" "${3:-2}"; }
s_player() { start_app --ez $BYPASS true --es video_id "$VIDEO_ID"; shot player 12; stop_app; }
s_music() { music_flow; shot music_player 6; stop_app; }
s_shorts() { open_route shorts; shot shorts 10; stop_app; }
s_theme() { open_settings theme; shot theme 4; swipe_up; swipe_up; swipe_up; shot theme_premium 2; }
s_app_icon() {
  open_settings "appearance%23appearance.app_icon"; sleep 3
  tap_text 'App [Ii]con|أيقونة التطبيق' || { swipe_up; tap_text 'App [Ii]con|أيقونة التطبيق'; } || { miss app_icon "App icon row not found"; return 0; }
  shot app_icon 3
  a shell input keyevent 4
}
s_upgrade() { open_settings upgrade; shot upgrade 6; swipe_up; swipe_up; shot codes_redeem 2; }
s_a11y_large() { # Mawja largest text + high contrast + colour-blind + reduce motion, system font 200%
  stop_app
  prefs_write mawja_accessibility '<string name="text_size">largest</string>
<boolean name="high_contrast" value="true" />
<boolean name="reduce_motion" value="true" />
<string name="color_vision">red_green</string>' || { miss accessibility_large "could not write prefs"; return 0; }
  a shell settings put system font_scale 2.0
  open_settings accessibility; shot accessibility_large 6
  open_route home; shot home_large 8
  stop_app; a11y_reset
}
s_offline() {
  airplane on; sleep 4
  open_route home; shot offline 6
  open_route downloads; shot offline_downloads 3
  airplane off; sleep 2
}
s_companion() { # screen pkg
  local comp; comp=$(launcher_of "$2")
  [ -n "$comp" ] || { miss "$1" "$2 not installed"; return 0; }
  T=60 a shell am start -W -n "$comp" >/dev/null 2>&1
  shot "$1" 8
  stop_app "$2"
}
s_bg() { # MusicPlayerBackgroundStyle name
  stop_app
  ds_put player_preferences music_player_background_style "$1" || { miss "music_player_bg_$(echo "$1" | tr '[:upper:]' '[:lower:]')" "datastore write failed"; return 0; }
  music_flow; shot "music_player_bg_$(echo "$1" | tr '[:upper:]' '[:lower:]')" 7
  stop_app; ds_put player_preferences music_player_background_style BLUR_GRADIENT
}
s_live() { # live background id (mawja_visual_player/live_background)
  stop_app
  prefs_write mawja_visual_player "<string name=\"live_background\">$1</string>" || { miss "music_player_live_$1" "prefs write failed"; return 0; }
  music_flow; shot "music_player_live_$1" 7
  stop_app; prefs_rm mawja_visual_player
}
s_theme_nile() {
  stop_app
  ds_put flow_preferences theme_mode NILE || { miss theme_nile_home "datastore write failed"; return 0; }
  start_app --ez $BYPASS true; shot theme_nile_home 10
  open_settings theme; shot theme_nile_picker 4
  stop_app; ds_put flow_preferences theme_mode SYSTEM
}
s_widget_picker() { # launcher > Widgets > Mawja (launcher follows the device locale, not the app's)
  a shell input keyevent 3; sleep 2
  a shell input swipe $((W / 2)) $((H * 55 / 100)) $((W / 2)) $((H * 55 / 100)) 1500; sleep 2
  tap_text '(?i)widgets|الأدوات|أدوات|التطبيقات المصغّرة' || { miss widget_picker "launcher Widgets entry not found"; a shell input keyevent 3; return 0; }
  sleep 3

  for _ in 1 2 3 4 5 6 7 8; do
    if tap_text '(?i)mawja.*|موجة.*'; then shot widget_picker 3; a shell input keyevent 3; return 0; fi
    swipe_up
  done
  shot widget_picker_list 1; record widget_picker skipped 0 "Mawja row not found in picker"
  a shell input keyevent 3
}
export -f log a over_budget record shot miss stop_app start_app open_route open_settings swipe_up ui_dump \
  find_text tap_text prefs_write prefs_rm ds_put airplane a11y_reset launcher_of music_flow \
  s_home s_route s_settings s_player s_music s_shorts s_theme s_app_icon s_upgrade s_a11y_large s_offline \
  s_companion s_bg s_live s_theme_nile s_widget_picker

ONLY=",${MAWJA_SCREEN_ONLY:-},"
step() { # step-name fn args... ; hard timeout per step, never aborts the run
  local name=$1 rc; shift
  [ "$ONLY" = ",," ] || case "$ONLY" in *",$name,"*) ;; *) return 0 ;; esac
  if over_budget; then log "budget: skip $name ($LANG_/$MODE)"; record "$name" skipped 0 "time budget"; return 0; fi
  timeout "${STEP_T:-100}" bash -c '"$@"' _ "$@"; rc=$?
  if [ "$rc" = 124 ]; then log "step $name timed out ($LANG_/$MODE)"; record "$name" failed 0 "step timeout"; stop_app; fi
  [ "$rc" != 0 ] && [ "$rc" != 124 ] && log "step $name exited $rc ($LANG_/$MODE)"
  return 0
}

set_variant() { # lang mode
  LANG_=$1; MODE=$2; export LANG_ MODE
  log "==== variant $LANG_ / $MODE ===="
  stop_app; airplane off; a11y_reset
  a shell cmd uimode night "$([ "$MODE" = dark ] && echo yes || echo no)" >/dev/null 2>&1
  local tag="en-US" pref="en"; [ "$LANG_" = ar ] && { tag="ar-EG"; pref="ar"; }
  # API < 33: the app reads its own language pref (AppLanguageManager); 33+: the per-app locale wins.
  prefs_write flow_language_prefs "<string name=\"app_language_tag\">$pref</string>
<boolean name=\"app_language_handed_to_system\" value=\"true\" />" || log "language pref write failed"
  if [ "$BOOTED_API" -ge 33 ]; then
    for p in $PKG $ADMIN_PKG $DL_PKG; do a shell cmd locale set-app-locales "$p" --locales "$tag" >/dev/null 2>&1; done
    a shell cmd locale get-app-locales $PKG 2>&1 | sed 's/^/[locale] /'
  fi
  sleep 2
}

# Music player background styles and live backgrounds, read from the source so new ones are covered.
BG_STYLES=${MAWJA_PLAYER_BACKGROUNDS:-$(f=$(grep -rl 'enum class MusicPlayerBackgroundStyle' app/src/main/java 2>/dev/null | head -1); \
  [ -n "$f" ] && sed -n '/enum class MusicPlayerBackgroundStyle/,/}/p' "$f" | grep -oE '^\s+[A-Z][A-Z0-9_]*' | tr -d ' ' | grep -vx BLUR_GRADIENT | xargs)}
LIVE_BGS=${MAWJA_LIVE_BACKGROUNDS:-$(f=$(grep -rl 'enum class LiveBackgroundStyle' app/src/main/java 2>/dev/null | head -1); \
  [ -n "$f" ] && sed -n '/enum class LiveBackgroundStyle/,/companion/p' "$f" | grep -oE '^\s+[A-Z][A-Z0-9_]*\("[a-z0-9_]+"' | grep -oE '"[a-z0-9_]+"' | tr -d '"' | grep -vx off | xargs)}
log "player background styles: ${BG_STYLES:-none}; live backgrounds: ${LIVE_BGS:-none (pref not in this build)}"

core_pass() {
  step home s_home
  step search s_route search search 3
  step player s_player
  step music_player s_music
  step shorts s_shorts
  step library s_route library library 4
  step history s_route history history 4
  step downloads s_route downloads downloads 3
  step subscriptions s_route subscriptions subscriptions 6
  step music_home s_route music_home music 7
  step notifications_inbox s_route notifications_inbox notifications 3
  step settings s_settings settings home 3
  step appearance s_settings appearance appearance
  step theme s_theme
  step app_icon s_app_icon
  step player_appearance s_settings player_appearance player_appearance
  step accessibility s_settings accessibility accessibility
  step accessibility_large s_a11y_large
  step equalizer s_settings equalizer equalizer
  step subtitles s_settings subtitles "playback%23playback.secondary_subtitle_language"
  step offline s_offline
  step islamic s_settings islamic islamic 3
  step qibla s_settings qibla qibla 3
  step notifications s_settings notifications notifications
  step account s_settings account account 3
  step upgrade s_upgrade
  step background_jobs s_settings background_jobs background_jobs
  step battery_saver s_settings battery_saver battery_saver
  step widgets s_settings widgets widgets
  step language s_settings language language_region
  step admin_main s_companion admin_main "$ADMIN_PKG"
  step downloader_main s_companion downloader_main "$DL_PKG"
}
extras_pass() {
  local s
  for s in $BG_STYLES; do step "music_player_bg_$(echo "$s" | tr '[:upper:]' '[:lower:]')" s_bg "$s"; done
  for s in $LIVE_BGS; do step "music_player_live_$s" s_live "$s"; done
  step theme_nile s_theme_nile
  step widget_picker s_widget_picker
  step music_search s_route music_search "musicSearch?query=Amr%20Diab" 10
}

# Islamic section on with a fixed city (Cairo, bundled list) so prayer times and qibla have data.
prefs_write mawja_islamic '<boolean name="enabled" value="true" />
<string name="location_source">city</string>
<string name="city">cairo</string>
<boolean name="show_hijri" value="true" />' || log "islamic prefs write failed (run-as?)"

# First launch: onboarding (fresh install), English, dark.
set_variant en dark
step onboarding bash -c 'start_app; shot onboarding 10; stop_app'

VARIANTS=${MAWJA_SCREEN_VARIANTS:-"en:dark ar:dark en:light ar:light"}
# Pass 1: every feature in every variant. Pass 2: the per-style extras, as long as time is left.
for v in $VARIANTS; do set_variant "${v%%:*}" "${v##*:}"; core_pass; done
for v in $VARIANTS; do over_budget && break; set_variant "${v%%:*}" "${v##*:}"; extras_pass; done
stop_app; airplane off; a11y_reset
stop_emu

# ---- manifest + blank detection (FLAG_SECURE windows and failed renders capture as one colour) ----
python3 - "$ROWS" "$OUT" "${SHA:0:7}" "$BOOTED_API" "$VARIANTS" > "$OUT/index.json" <<'PY' || log "manifest failed"
import json, os, struct, sys, time, zlib
rows_path, out, sha, api, variants = sys.argv[1:6]
def blank(path):
    # Heuristic without decoding: a one-colour 1080x2400 capture (FLAG_SECURE window, nothing
    # rendered) deflates >200x; real screens, even sparse dark settings pages, stay far below.
    try:
        data = open(path, "rb").read()
        if data[:8] != b"\x89PNG\r\n\x1a\n": return False
        w, h, _, ct = struct.unpack(">IIBB", data[16:26])
        raw = h * (w * {6: 4, 2: 3}.get(ct, 4) + 1)
        return len(data) < 25000 or raw / max(1, len(data)) > 200
    except Exception:
        return False
shots, counts = [], {}
for line in open(rows_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) < 7: continue
    screen, lang, mode, fname, status, size, note = p[:7]
    if status == "captured" and blank(os.path.join(out, fname)):
        status, note = "blank", "looks single-colour (FLAG_SECURE or not rendered; size heuristic)"
    counts[status] = counts.get(status, 0) + 1
    shots.append({"screen": screen, "lang": lang, "mode": mode, "file": fname if status in ("captured", "blank") else None,
                  "status": status, "bytes": int(size or 0), "note": note})
print(json.dumps({"sha": sha, "api": api, "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                  "variants": variants.split(), "counts": counts,
                  "screens": sorted({s["screen"] for s in shots}), "shots": shots}, ensure_ascii=False, indent=1))
PY

count=$(find "$OUT" -maxdepth 1 -name '*.png' | wc -l)
[ "$count" = 0 ] && fail "no screenshots"
counts=$(jq -c '.counts' "$OUT/index.json" 2>/dev/null || echo '{}')
log "API $BOOTED_API: $count screenshots, $(jq '.screens | length' "$OUT/index.json" 2>/dev/null || echo '?') screens, counts $counts, $((($(date +%s) - START_EPOCH) / 60))m"

# Upload: one archive with everything + the manifest, and the README set under its legacy names.
zipurl=none; idxurl=none
(cd "$OUT" && zip -q -r "$WORK/mawja-screens.zip" ./*.png index.json) && \
  zipurl=$(curl -fsS -m 300 -F "files[]=@$WORK/mawja-screens.zip" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null)
idxurl=$(curl -fsS -m 60 -F "files[]=@$OUT/index.json" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null)
links=""
for pair in home:home subscriptions:subscriptions library:library settings:settings theme:themes account:account \
            notifications:notifications player:player music_player:music-player islamic:islamic; do
  for l in en ar; do
    f="$OUT/${pair%%:*}_${l}_dark.png"; [ -f "$f" ] || continue
    url=$(curl -fsS -m 60 -F "files[]=@$f" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null)
    [ -n "$url" ] && [ "$url" != "null" ] && links="$links ${pair##*:}-$l=$url"
  done
done
f="$OUT/onboarding_en_dark.png"
[ -f "$f" ] && url=$(curl -fsS -m 60 -F "files[]=@$f" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null) && [ -n "$url" ] && links="$links onboarding-en=$url"
log "summary: zip=$zipurl index=$idxurl counts=$counts"
[ -z "$links" ] && [ "${zipurl:-null}" = null ] && { log "uploads failed"; exit 1; }
curl -fsS -H "Title: Mawja CI screens" -d "screens src=${SHA:0:7} count=$count zip=$zipurl index=$idxurl$links" "https://ntfy.sh/$TOPIC" >/dev/null || log "ntfy post failed"
log "posted $count screenshots (api $BOOTED_API)"
