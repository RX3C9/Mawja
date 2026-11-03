#!/bin/bash
# Probes the free Gemini path the app uses (Firebase AI Logic, Spark plan, API-key + Android app headers).
# Prints one line, never the key. Cloud AI features in the app depend on this answering 200.
KEY=$(grep -E '^mawjaFirebaseApiKey=' gradle.properties | cut -d= -f2-)
PKG=app.mawja.android.debug
CERT=608A7F311D2DA0B388D7FD7B3FAAA7C39E0FB686
for MODEL in gemini-2.5-flash gemini-2.0-flash gemini-2.5-flash-lite; do
  code=$(curl -sS -m 40 -o /tmp/ai_probe.json -w '%{http_code}' -X POST \
    "https://firebasevertexai.googleapis.com/v1beta/projects/mawja-app/models/${MODEL}:generateContent" \
    -H "x-goog-api-key: ${KEY}" -H "X-Android-Package: ${PKG}" -H "X-Android-Cert: ${CERT}" \
    -H 'Content-Type: application/json' \
    -d '{"contents":[{"role":"user","parts":[{"text":"Reply with the single word: pong"}]}]}')
  if [ "$code" = 200 ]; then
    txt=$(jq -r '.candidates[0].content.parts[0].text // "?"' /tmp/ai_probe.json | tr -d '\n' | cut -c1-40)
    echo "aiprobe: ok model=$MODEL reply=$txt"; exit 0
  fi
  msg=$(jq -r '"\(.error.status // "?") | \(.error.message // "?") | \(.error.details[0].reason // "")"' /tmp/ai_probe.json 2>/dev/null | cut -c1-260)
  echo "aiprobe: fail model=$MODEL http=$code $msg"
done
exit 0
