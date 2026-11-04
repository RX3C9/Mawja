#!/bin/bash
# The app calls the Gemini API directly with the member's own free key. This only proves the endpoint
# is reachable and answers like Gemini does (a fake key must be refused with API_KEY_INVALID).
for MODEL in gemini-3.5-flash-lite gemini-3.8-flash; do
  code=$(curl -sS -m 40 -o /tmp/ai_probe.json -w '%{http_code}' -X POST \
    "https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent" \
    -H "x-goog-api-key: AIzaSyFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE0" -H 'Content-Type: application/json' \
    -d '{"contents":[{"role":"user","parts":[{"text":"ping"}]}]}')
  reason=$(jq -r '.error.details[0].reason // .error.status // "?"' /tmp/ai_probe.json 2>/dev/null)
  echo "aiprobe: endpoint model=$MODEL http=$code reason=$reason"
done
exit 0
