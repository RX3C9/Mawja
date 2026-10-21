#!/usr/bin/env bash
# Mawja CI screenshots: boots a headless Android emulator on the runner, installs the github debug
# build, captures real in-app screens in English and Arabic, uploads them and posts the links to the
# ntfy log topic ("screens src=<sha> <name>=<url> ..."). The dev agent commits them into
# docs/screenshots/ for the README. Best effort: any failure only skips the screenshots.
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
  local i booted=""
  for i in $(seq 1 100); do   # up to ~5 min
    kill -0 "$EMU_PID" 2>/dev/null || { log "emulator died during boot"; diag; EMU_PID=""; return 1; }
    [ "$(T=10 a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { booted=1; break; }
    sleep 3
  done
  [ -n "$booted" ] || { log "API $api did not finish booting"; diag; stop_emu; return 1; }
  # Wait for package manager too.
  for i in $(seq 1 40); do T=15 a shell pm path android >/dev/null 2>&1 && break; sleep 3; done
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

shot() { # name, seconds to settle
  sleep "${2:-6}"
  if T=30 a exec-out screencap -p > "$OUT/$1.png" && [ -s "$OUT/$1.png" ]; then
    log "captured $1 ($(stat -c %s "$OUT/$1.png") bytes)"
  else
    rm -f "$OUT/$1.png"; log "screencap failed for $1"
  fi
}
start_app() { T=90 a shell am start -W -n $PKG/$ACT "$@" >/dev/null 2>&1 || log "am start timed out ($*)"; }
open_route() { start_app --ez $BYPASS true --es widget_route "'$1'"; }

capture_set() { # suffix
  local s=$1
  a shell am force-stop $PKG
  start_app --ez $BYPASS true; shot "home-$s" 14
  open_route "subscriptions"; shot "subscriptions-$s" 8
  open_route "library"; shot "library-$s" 6
  open_route "settings"; shot "settings-$s" 5
  open_route "settings?target=theme"; shot "themes-$s" 5
  open_route "settings?target=account"; shot "account-$s" 5
  open_route "settings?target=notifications"; shot "notifications-$s" 5
}

# First launch: onboarding, in English.
start_app; shot "onboarding-en" 10
capture_set en

# Arabic. Android 13+: per-app locale (no reboot). Older: device locale via adb root + zygote restart.
arabic=""
if [ "$BOOTED_API" -ge 33 ] && a shell cmd locale set-app-locales $PKG --locales ar-EG >/dev/null 2>&1; then
  sleep 3
  a shell cmd locale get-app-locales $PKG 2>&1 | sed 's/^/[locale] /'
  arabic=1
elif T=30 a root >/dev/null 2>&1; then
  sleep 4; T=60 a wait-for-device
  a shell setprop persist.sys.locale ar-EG
  a shell setprop ctl.restart zygote
  sleep 25; T=120 a wait-for-device
  for i in $(seq 1 60); do a shell pm list packages $PKG 2>/dev/null | grep -q "$PKG" && break; sleep 3; done
  sleep 10
  a shell input keyevent 82
  arabic=1
fi
if [ -n "$arabic" ]; then capture_set ar; else log "could not switch to Arabic"; fi
stop_emu

count=$(ls "$OUT"/*.png 2>/dev/null | wc -l)
[ "$count" = 0 ] && fail "no screenshots"
log "API $BOOTED_API: $count screenshots"
links=""
for f in "$OUT"/*.png; do
  url=$(curl -fsS -m 60 -F "files[]=@$f" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null)
  [ -n "$url" ] && [ "$url" != "null" ] && links="$links $(basename "$f" .png)=$url"
done
[ -z "$links" ] && { log "uploads failed"; exit 1; }
curl -fsS -H "Title: Mawja CI screens" -d "screens src=${SHA:0:7}$links" "https://ntfy.sh/$TOPIC" >/dev/null || log "ntfy post failed"
log "posted $count screenshots (api $BOOTED_API)"
