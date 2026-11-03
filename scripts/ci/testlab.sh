#!/usr/bin/env bash
# Real-device playback check on Firebase Test Lab (free Spark quota: 10 virtual + 5 physical runs a day).
# GitHub runner IPs get YouTube's bot wall; Test Lab phones sit on Google's lab networks. Runs the debug
# APK's game loop (app/src/debug SmokeLoopActivity), then reads the "MAWJA_LOOP" verdict from logcat.
# Needs MAWJA_SA_B64 (service account with Test Lab access). Posts "testlab: ..." to the CI log topic.
set -uo pipefail
TOPIC="${MAWJA_LOG_TOPIC:-mawja-ci-3998ee696e5d3e8578e1}"
SHA=$(cat .mawja-sync 2>/dev/null || git rev-parse HEAD)
post() { echo "$*"; curl -fsS -m 20 -H "Title: Mawja CI testlab" -d "$* src=${SHA:0:7}" "https://ntfy.sh/$TOPIC" >/dev/null || true; }
[ -n "${MAWJA_SA_B64:-}" ] || { post "testlab: skipped (no service account secret)"; exit 0; }
apk=$(find apks -name 'app-github-universal-debug.apk' | head -1)
[ -n "$apk" ] || apk=$(find apks -name 'app-github-*-debug.apk' | grep -v x86 | head -1)
[ -n "$apk" ] || { post "testlab: skipped (no github debug apk in artifact)"; exit 0; }
echo "$MAWJA_SA_B64" | base64 -d > /tmp/sa.json
PROJECT=$(jq -r .project_id /tmp/sa.json); SA=$(jq -r .client_email /tmp/sa.json)
gcloud -q auth activate-service-account --key-file=/tmp/sa.json >/dev/null 2>&1 || { post "testlab: FAILED (service account auth)"; exit 0; }
gcloud -q config set project "$PROJECT" >/dev/null 2>&1
for s in testing.googleapis.com toolresults.googleapis.com; do
  gcloud -q services enable "$s" >/dev/null 2>&1 || echo "could not enable $s as $SA (may already be on)"
done

# Devices: one physical phone (real hardware) and one virtual, unless overridden.
PHYS="${MAWJA_TL_PHYSICAL:-}"
if [ -z "$PHYS" ]; then
  PHYS=$(gcloud firebase test android models list --filter='form=PHYSICAL' --format='value(id,supportedVersionIds.list())' 2>/dev/null \
    | awk '{n=split($2,v,","); if (v[n]>=30) {print "model=" $1 ",version=" v[n]; exit}}')
fi
VIRT="${MAWJA_TL_VIRTUAL:-model=MediumPhone.arm,version=34}"
dev=(); [ -n "$PHYS" ] && dev+=(--device "$PHYS,locale=en,orientation=portrait"); dev+=(--device "$VIRT,locale=en,orientation=portrait")
if [ -n "${MAWJA_TL_FETCH:-}" ]; then
  # Re-read an earlier run's results (no new device run, no quota): "<bucket>/<dir>".
  bucket="gs://${MAWJA_TL_FETCH%%/*}"; DIR="${MAWJA_TL_FETCH#*/}"; rc=0
else
  DIR="mawja-${SHA:0:7}-$(date +%s)"
  echo "testlab devices: $PHYS | $VIRT"
  timeout 1500 gcloud firebase test android run --type game-loop --app "$apk" --scenario-numbers 1 "${dev[@]}" \
    --timeout 6m --results-dir "$DIR" --no-record-video --format=json > /tmp/tl.json 2> /tmp/tl.err
  rc=$?
  b=$(grep -oE 'storage/browser/[^/ ]+' /tmp/tl.err | head -1 | sed 's#storage/browser/##')
  bucket=$(grep -oE 'gs://[^/ ]+' /tmp/tl.err | head -1); [ -n "$b" ] && bucket="gs://$b"
  grep -v "Test matrix status" /tmp/tl.err | tail -15
  jq -r '.[]? | "outcome \(.axis_value // .axisValue): \(.outcome) \(.test_details // .testDetails // "")"' /tmp/tl.json 2>/dev/null
fi
gsutil ls -r "$bucket/$DIR" 2>/dev/null | head -60
summary=""
if [ -n "$bucket" ]; then
  for log in $(gsutil ls -r "$bucket/$DIR" 2>/dev/null | grep -E '/logcat$'); do
    gsutil cat "$log" 2>/dev/null | grep -E "MAWJA_LOOP|MawjaSmokeLoop|BOT_WALL|FATAL EXCEPTION|AndroidRuntime" | head -40
    d=$(echo "$log" | awk -F/ '{print $(NF-1)}')
    v=$(gsutil cat "$log" 2>/dev/null | grep -o 'MAWJA_LOOP video [A-Za-z]*' | tail -1 | awk '{print $3}')
    m=$(gsutil cat "$log" 2>/dev/null | grep -o 'MAWJA_LOOP music [A-Za-z]*' | tail -1 | awk '{print $3}')
    wall=$(gsutil cat "$log" 2>/dev/null | grep -c 'BOT_WALL' || true)
    crash=$(gsutil cat "$log" 2>/dev/null | grep -c 'FATAL EXCEPTION' || true)
    summary="$summary $d:video=${v:-none},music=${m:-none},botwall=$wall,crash=$crash"
  done
fi
post "testlab: rc=$rc${summary:- no logcat (see run log)} bucket=${bucket:-none}"
