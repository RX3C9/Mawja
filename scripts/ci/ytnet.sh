#!/usr/bin/env bash
# Picks a network route for the device smoke test. GitHub-hosted runner IPs get YouTube's
# "Sign in to confirm you're not a bot" wall, which says nothing about the app. Free routes, in order:
#   direct -> Cloudflare WARP (anonymous consumer registration, no account) -> Tor (HTTP tunnel port
#   handed to the emulator as its global proxy). Sourced by smoke.sh; sets YT_ROUTE and ytnet_cleanup.
YT_ROUTE=direct; YT_PROXY=""

yt_probe() { # -> "OK" / "LOGIN_REQUIRED Sign in to confirm..." / "ERR"
  local body r
  body='{"context":{"client":{"clientName":"WEB","clientVersion":"2.20251101.00.00","hl":"en","gl":"US"}},"videoId":"'"${VIDEO_ID:-jNQXAC9IVRw}"'"}'
  r=$(curl -sS -m 25 ${YT_PROXY:+-x "$YT_PROXY"} -H 'Content-Type: application/json' -A 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36' \
      -d "$body" 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false' 2>/dev/null \
      | jq -r '(.playabilityStatus.status // "ERR") + " " + (.playabilityStatus.reason // "")' 2>/dev/null)
  echo "${r:-ERR}"
}
yt_walled() { case "$1" in OK*) return 1;; *) return 0;; esac; }

warp_up() {
  command -v warp-cli >/dev/null 2>&1 || {
    curl -fsSL https://pkg.cloudflareclient.com/pubkey.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/cloudflare-warp-archive-keyring.gpg || return 1
    echo "deb [signed-by=/usr/share/keyrings/cloudflare-warp-archive-keyring.gpg] https://pkg.cloudflareclient.com/ $(lsb_release -cs) main" \
      | sudo tee /etc/apt/sources.list.d/cloudflare-client.list >/dev/null
    timeout 240 sudo apt-get update -qq >/dev/null 2>&1; timeout 300 sudo apt-get install -y -qq cloudflare-warp >/dev/null 2>&1 || return 1
  }
  sudo systemctl start warp-svc 2>/dev/null; sleep 3
  timeout 30 warp-cli --accept-tos registration new >/dev/null 2>&1 || timeout 30 warp-cli --accept-tos register >/dev/null 2>&1
  timeout 20 warp-cli --accept-tos mode warp >/dev/null 2>&1
  timeout 20 warp-cli --accept-tos connect >/dev/null 2>&1
  local i; for i in $(seq 1 20); do
    curl -fsS -m 8 https://www.cloudflare.com/cdn-cgi/trace 2>/dev/null | grep -q 'warp=on' && return 0; sleep 2
  done
  return 1
}
warp_down() { timeout 20 warp-cli --accept-tos disconnect >/dev/null 2>&1 || true; }

tor_up() {
  command -v tor >/dev/null 2>&1 || { timeout 240 sudo apt-get update -qq >/dev/null 2>&1; timeout 300 sudo apt-get install -y -qq tor >/dev/null 2>&1 || return 1; }
  sudo systemctl stop tor 2>/dev/null || true
  mkdir -p "$WORK/tor"
  printf 'SocksPort 9050\nHTTPTunnelPort 0.0.0.0:9080\nDataDirectory %s/tor\nExitNodes {us},{de},{nl},{gb},{fr},{ca}\nStrictNodes 0\n' "$WORK" > "$WORK/torrc"
  (tor -f "$WORK/torrc" > "$WORK/tor.log" 2>&1 &)
  local i; for i in $(seq 1 60); do grep -q 'Bootstrapped 100%' "$WORK/tor.log" 2>/dev/null && return 0; sleep 2; done
  return 1
}
tor_newnym() { # new circuit = new exit IP
  pkill -HUP -f "tor -f $WORK/torrc" 2>/dev/null; sleep 6
}

ytnet_select() {
  local p; p=$(yt_probe); log "ytnet direct: $p"
  yt_walled "$p" || { YT_ROUTE=direct; return 0; }
  if warp_up; then
    p=$(yt_probe); log "ytnet warp: $p"
    yt_walled "$p" || { YT_ROUTE=warp; return 0; }
    warp_down
  else log "ytnet warp: could not connect"; fi
  if tor_up; then
    YT_PROXY="http://127.0.0.1:9080"; local k
    for k in 1 2 3 4 5 6; do
      p=$(yt_probe); log "ytnet tor#$k: $p"
      if ! yt_walled "$p"; then
        T=10 a shell settings put global http_proxy 10.0.2.2:9080 >/dev/null 2>&1
        YT_ROUTE="tor"; return 0
      fi
      tor_newnym
    done
  else log "ytnet tor: did not bootstrap"; fi
  YT_PROXY=""; YT_ROUTE="direct-walled"; return 1
}
ytnet_cleanup() {
  case "$YT_ROUTE" in
    warp) warp_down ;;
    tor) T=10 a shell settings put global http_proxy :0 >/dev/null 2>&1; pkill -f "tor -f $WORK/torrc" 2>/dev/null ;;
  esac
}
