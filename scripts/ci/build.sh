#!/usr/bin/env bash
# Mawja CI build. Lives outside .github/workflows so it updates with every sync.
# Posts a summary + full log link to ntfy so the dev agent can read results without GitHub auth.
set -uo pipefail
TOPIC="${MAWJA_LOG_TOPIC:-mawja-ci-3998ee696e5d3e8578e1}"
SHA=$(cat .mawja-sync 2>/dev/null || git rev-parse HEAD)
TASKS=${MAWJA_TASKS:-":app:assembleGithubDebug :app:assembleFossDebug :downloader:assembleDebug :admin:assembleDebug"}
chmod +x gradlew
# The app's two flavours are compiled one after the other: compiled side by side they share one
# Kotlin daemon and run it out of heap.
: > build.log
code=0
for group in ":app:assembleGithubDebug" ":app:assembleFossDebug" "$(echo " $TASKS " | sed -e 's/ :app:assembleGithubDebug / /' -e 's/ :app:assembleFossDebug / /' | xargs)"; do
  [ -z "$group" ] && continue
  if [ "$group" = ":app:assembleGithubDebug" ] || [ "$group" = ":app:assembleFossDebug" ]; then
    case " $TASKS " in *" $group "*) ;; *) continue ;; esac
  fi
  ./gradlew $group --stacktrace --max-workers=2 -Pkotlin.compiler.execution.strategy=daemon 2>&1 | tee -a build.log
  c=${PIPESTATUS[0]}
  if [ "$c" != 0 ]; then code=$c; break; fi
done
# Mawja's own JVM unit tests (reported, do not fail the APK build). Only Mawja test classes are
# selected; the class list lives in scripts/ci/unit-tests.txt so it grows with each batch.
unit="skipped"
if [ "$code" = 0 ] && [ -f scripts/ci/unit-tests.txt ]; then
  # app.mawja.admin.* classes are the admin app's tests; everything else runs in :app.
  filters=$(grep -v '^#' scripts/ci/unit-tests.txt | sed '/^\s*$/d' | grep -v '^app\.mawja\.admin\.' | sed 's/^/--tests /' | xargs)
  adminFilters=$(grep -v '^#' scripts/ci/unit-tests.txt | sed '/^\s*$/d' | grep '^app\.mawja\.admin\.' | sed 's/^/--tests /' | xargs)
  if [ -n "$filters$adminFilters" ]; then
    : > unit.log
    uc=0
    if [ -n "$filters" ]; then timeout 30m ./gradlew :app:testFossDebugUnitTest $filters --max-workers=2 >> unit.log 2>&1 || uc=1; fi
    if [ -n "$adminFilters" ]; then timeout 15m ./gradlew :admin:testDebugUnitTest $adminFilters --max-workers=2 >> unit.log 2>&1 || uc=1; fi
    if [ "$uc" = 0 ]; then
      unit="ok"
    else
      unit="FAILED: $(grep -E '^(e: |.*FAILED|.*Test.*>.*FAILED|  .*Exception|.*expected)' unit.log | head -25 | tr "\n" " " | cut -c1-1500)"
    fi
    cat unit.log >> build.log
  fi
fi
./gradlew --stop >/dev/null 2>&1 || true
# Real in-app screenshots from an emulator (reported, do not fail the build). Set MAWJA_SCREENSHOTS=0 to skip.
screens="skipped"
if [ "$code" = 0 ] && [ "${MAWJA_SCREENSHOTS:-1}" = 1 ] && [ -f scripts/ci/screenshots.sh ]; then
  if timeout 35m bash scripts/ci/screenshots.sh > screens.log 2>&1; then sc=0; else sc=$?; fi
  [ "$sc" = 124 ] && echo "[screens] killed by the 35m timeout" >> screens.log
  [ -f emulator.log ] && { echo "---- full emulator.log ----"; tail -400 emulator.log; } >> screens.log
  slog=$(curl -fsS -m 60 -F "files[]=@screens.log" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null || echo none)
  if [ "$sc" = 0 ]; then
    screens="ok ($(grep -c '\] .*captured' screens.log) shots, $(grep -o 'api [0-9]*)' screens.log | tail -1 | tr -d ')') log=$slog)"
  else
    screens="FAILED (exit $sc) log=$slog: $(grep -E 'FAIL|did not|timed out|died|exited|missing' screens.log | head -6 | tr "\n" " " | cut -c1-500)"
  fi
  cat screens.log >> build.log
fi
if [ "$code" = 0 ]; then status=success; else status=failure; fi
# Companion apps ride along in the app artifact folder (the workflow uploads app/build/outputs/apk/**).
for m in downloader admin; do
  if [ -d "$m/build/outputs/apk" ]; then mkdir -p "app/build/outputs/apk/companions/$m"; find "$m/build/outputs/apk" -name "*.apk" -exec cp {} "app/build/outputs/apk/companions/$m/" \; ; fi
done
# Backend: type-check and unit-test the Cloud Functions (reported, does not fail the APK build).
backend="skipped"
if [ -f backend/functions/package.json ] && command -v npm >/dev/null; then
  if (cd backend/functions && npm ci --no-audit --no-fund --loglevel=error && npm test) > backend.log 2>&1; then backend="ok"; else backend="FAILED: $(tail -20 backend.log | tr "\n" " " | cut -c1-600)"; fi
fi
# Firestore security rules on the emulator (membership codes etc.; needs Java, reported, does not fail the build).
rules="skipped"
if [ -f backend/rules-test/package.json ] && command -v npm >/dev/null && command -v java >/dev/null; then
  if (cd backend/rules-test && npm ci --no-audit --no-fund --loglevel=error && timeout 15m npm test) > rules.log 2>&1; then
    rules="ok ($(grep -E '^# (pass|fail) ' rules.log | tr "\n" ' ' | xargs))"
  else
    rules="FAILED: $( { grep -E '^# (pass|fail) ' rules.log; grep -E -A4 '^not ok|Error:|error TS|FirebaseError|Unexpected|line [0-9]+' rules.log; } | head -40 | tr "\n" ' ' | cut -c1-1500)"
  fi
  cat rules.log >> build.log
fi
# Admin web (Next.js static export): tests, type-check and build (reported, does not fail the APK build).
adminweb="skipped"
if [ -f admin-web/package.json ] && command -v npm >/dev/null; then
  if (cd admin-web && npm ci --no-audit --no-fund --loglevel=error && npm test && npm run typecheck && NEXT_TELEMETRY_DISABLED=1 npm run build) > adminweb.log 2>&1; then
    adminweb="ok ($(find admin-web/out -name '*.html' | wc -l) pages, node $(node -v))"
  else
    adminweb="FAILED: $(tail -25 adminweb.log | tr "\n" " " | cut -c1-900)"
  fi
  cat adminweb.log >> build.log
fi
apks=$(find app/build/outputs/apk -name '*.apk' -printf '%f (%s bytes)\n' 2>/dev/null | head -14)
logurl=$(curl -fsS -F "files[]=@build.log" https://uguu.se/upload | jq -r '.files[0].url' 2>/dev/null || echo none)
summary=$( { echo "$status src=${SHA:0:7} run=${GITHUB_RUN_ID:-local} log=$logurl"; echo "backend: $backend"; echo "rules: $rules"; echo "admin-web: $adminweb"; echo "unit: $unit"; echo "screens: $screens"; echo "elapsed: $((SECONDS/60))m"; echo "$apks"; grep -E '^(e: |FAILURE|\* What went wrong|BUILD )' -A3 build.log | head -60; } )
curl -fsS -H "Title: Mawja CI $status" -d "$summary" "https://ntfy.sh/$TOPIC" >/dev/null || true
exit $code
