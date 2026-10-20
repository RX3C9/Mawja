#!/usr/bin/env bash
# Mawja CI build. Lives outside .github/workflows so it updates with every sync.
# Posts a summary + full log link to ntfy so the dev agent can read results without GitHub auth.
set -uo pipefail
TOPIC="${MAWJA_LOG_TOPIC:-mawja-ci-3998ee696e5d3e8578e1}"
SHA=$(cat .mawja-sync 2>/dev/null || git rev-parse HEAD)
TASKS=${MAWJA_TASKS:-":app:assembleGithubDebug :app:assembleFossDebug"}
chmod +x gradlew
./gradlew $TASKS --stacktrace --no-daemon 2>&1 | tee build.log
code=${PIPESTATUS[0]}
if [ "$code" = 0 ]; then status=success; else status=failure; fi
apks=$(find app/build/outputs/apk -name '*.apk' -printf '%f (%s bytes)\n' 2>/dev/null | head -10)
logurl=$(curl -fsS -F "files[]=@build.log" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null || echo none)
summary=$( { echo "$status src=${SHA:0:7} run=${GITHUB_RUN_ID:-local} log=$logurl"; echo "$apks"; grep -E '^(e: |FAILURE|\* What went wrong|BUILD )' -A3 build.log | head -60; } )
curl -fsS -H "Title: Mawja CI $status" -d "$summary" "https://ntfy.sh/$TOPIC" >/dev/null || true
exit $code
