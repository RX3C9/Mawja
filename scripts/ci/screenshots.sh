#!/usr/bin/env bash
# Mawja CI screenshots: boots a headless Android emulator on the runner, installs the github debug
# build, captures real in-app screens in English and Arabic, uploads them and posts the links to the
# ntfy log topic ("screens src=<sha> <name>=<url> ..."). The dev agent commits them into
# docs/screenshots/ for the README. Best effort: any failure only skips the screenshots.
set -uo pipefail
TOPIC="${MAWJA_LOG_TOPIC:-mawja-ci-3998ee696e5d3e8578e1}"
SHA=$(cat .mawja-sync 2>/dev/null || git rev-parse HEAD)
OUT=build/screens
PKG=app.mawja.android.debug
ACT=app.mawja.android.MainActivity
BYPASS=app.mawja.android.extra.BENCHMARK_BYPASS_ONBOARDING
API=${MAWJA_SCREEN_API:-34}
IMG="system-images;android-${API};google_apis;x86_64"
mkdir -p "$OUT"
log() { echo "[screens] $*"; }

apk=$(find app/build/outputs/apk -name 'app-github-x86_64-debug.apk' | head -1)
[ -z "$apk" ] && apk=$(find app/build/outputs/apk -name 'app-github-universal-debug.apk' | head -1)
[ -z "$apk" ] && { log "no github debug apk"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"

# KVM for the hosted runner.
if [ -e /dev/kvm ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
  sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
else
  log "no /dev/kvm"; exit 1
fi

yes | "$SDKM" --licenses >/dev/null 2>&1
"$SDKM" --install "emulator" "platform-tools" "$IMG" >/dev/null 2>&1 || { log "sdkmanager failed"; exit 1; }
echo no | "$AVDM" create avd -n mawja -k "$IMG" -d pixel_6 --force >/dev/null 2>&1 || { log "avd create failed"; exit 1; }

emulator -avd mawja -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -camera-back none -memory 3072 >emulator.log 2>&1 &
adb wait-for-device
for i in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 3
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { log "emulator did not boot"; exit 1; }
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent 82
adb shell cmd uimode night yes

adb install -r -g "$apk" >/dev/null || { log "install failed"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true

shot() { # name, seconds to settle
  sleep "${2:-6}"
  adb exec-out screencap -p > "$OUT/$1.png" && log "captured $1 ($(stat -c %s "$OUT/$1.png") bytes)"
}
open_route() { # route
  adb shell am start -W -n $PKG/$ACT --ez $BYPASS true --es widget_route "'$1'" >/dev/null
}

capture_set() { # suffix
  local s=$1
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/$ACT --ez $BYPASS true >/dev/null; shot "home-$s" 14
  open_route "subscriptions"; shot "subscriptions-$s" 8
  open_route "library"; shot "library-$s" 6
  open_route "settings"; shot "settings-$s" 5
  open_route "settings?target=theme"; shot "themes-$s" 5
  open_route "settings?target=account"; shot "account-$s" 5
  open_route "settings?target=notifications"; shot "notifications-$s" 5
}

# First launch: onboarding, in English.
adb shell am start -W -n $PKG/$ACT >/dev/null; shot "onboarding-en" 10
capture_set en

# Arabic: switch the whole device locale (google_apis images allow adb root).
if adb root >/dev/null 2>&1; then
  sleep 4; adb wait-for-device
  adb shell setprop persist.sys.locale ar-EG
  adb shell setprop ctl.restart zygote
  sleep 25; adb wait-for-device
  for i in $(seq 1 60); do adb shell pm list packages $PKG 2>/dev/null | grep -q "$PKG" && break; sleep 3; done
  sleep 10
  adb shell input keyevent 82
  capture_set ar
fi
adb emu kill >/dev/null 2>&1 || true

count=$(ls "$OUT"/*.png 2>/dev/null | wc -l)
[ "$count" = 0 ] && { log "no screenshots"; exit 1; }
# Upload in small groups and post the links.
links=""
for f in "$OUT"/*.png; do
  url=$(curl -fsS -m 60 -F "files[]=@$f" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null)
  [ -n "$url" ] && [ "$url" != "null" ] && links="$links $(basename "$f" .png)=$url"
done
curl -fsS -H "Title: Mawja CI screens" -d "screens src=${SHA:0:7}$links" "https://ntfy.sh/$TOPIC" >/dev/null || true
log "posted $count screenshots"
