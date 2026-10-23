# Network: proxy, private DNS, profiles and clean links

PLAN_V2 #71 (custom network) and #72 (link cleaner). Free for every tier. Settings → Network.

## Proxy (existing)

`network/AppProxyManager` holds the proxy in force (HTTP or SOCKS5, optional sign-in, optional
"skip on VPN"). Clients get it two ways:

- `AppProxyManager.buildLive(builder)` for process-long clients (`di/NetworkModule`, `DownloadUtil`):
  `AppProxySelector` + `AppProxyAuthenticator` + `AppDns` read the current state per connection, and
  their connection pools are evicted on every proxy or DNS change.
- `AppProxyManager.applyTo(builder)` (also via `ProxyAwareClient`) for clients cached per
  `AppProxyManager.currentSignature()`, which now covers proxy **and** DNS.
- InnerTube builds its own Ktor/OkHttp client; it is rebuilt when `YouTube.proxy`/`proxyAuth` change
  and uses `AppDns` for lookups.

SOCKS5 sign-in goes through the JVM-wide `java.net.Authenticator`. One dynamic authenticator answers
for the saved proxy and, during a connection test, for the proxy being tested.

## Private DNS (DNS over HTTPS)

- `network/DnsSettings` (pure): System, Cloudflare, Google, Quad9, AdGuard, or a custom `https://`
  server; "fall back to system DNS" (on by default). Known providers carry literal bootstrap IPs so
  the DoH host is reached without the system resolver. A custom server's host is resolved by the
  system. An invalid custom URL means system DNS.
- `network/AppDns` (`okhttp3.Dns`): DoH via `okhttp-dnsoverhttps`, 5-minute in-memory answer cache,
  local names (`localhost`, `*.local`, private literals) always go to the system resolver. DoH queries
  follow the app proxy. Stored in SharedPreferences `mawja_custom_network`.
- The DNS choice is saved with the proxy form (Save button), never while typing.

## Connection profiles

Named proxy + DNS setups (`network/ConnectionProfilesCodec`, pure JSON codec, max 20, names ≤ 40
chars, case-insensitive unique). Stored as one JSON value in `mawja_custom_network` / `profiles`;
proxy passwords are sealed with `KeystoreSecretBox` (the JSON never holds them in the clear, and a
restored backup on another device opens them as empty). Tap a profile to use or delete it; the
profile matching the saved setup is shown selected.

## Test connection

`network/ConnectionTester` fetches `https://www.youtube.com/generate_204` with the form as it is
(saved or not) through a one-off client: proxy, its sign-in and the DNS choice. Results: connected in
N ms, DNS failure, proxy sign-in refused (407), unexpected answer (e.g. a captive portal), or the error.

## Remote switch

`config/app features.customNetwork` (on unless set to false), pushed from `MainActivity` like
`batterySaver` and cached in `mawja_custom_network` / `remote_enabled` for cold processes. Off: every
lookup uses the system resolver and profiles are hidden. The proxy itself is not affected by it.

## Clean links (#72)

`network/LinkCleaner`: drops `utm_*`, `fbclid`, `gclid`, `dclid`, `gbraid`, `wbraid`, `msclkid`,
`igshid`, `igsh`, `mc_cid`, `mc_eid`, `yclid`, `_hsenc`, `_hsmi`, `ref_src`, `twclid`, `ttclid`,
`srsltid` from every link, and `si`, `feature`, `pp` on YouTube/Spotify hosts only. Everything else
(`v`, `t`, `list`, `index`, other parameters, `#fragment`) stays, in its order. `cleanText` rewrites
only the links inside a text and keeps trailing punctuation. Switch "Remove trackers from links" (on
by default, SharedPreferences `mawja_link_cleaner`).

Applied at: links opened from another app (`ui/LinkDestination.linkTextOf`, always), `utils/Clipboard
.copyPlainText` (not for sensitive copies), `utils/ShareVideo`, the video quick-actions copy link, the
player's copy link / copy link at time, channel and community-post share, artist page copy link,
recap share, notes share.

## Known gaps

- The separate downloader app (`:downloader`) has its own network stack and does not use the app
  proxy or private DNS.
- `PoTokenWebView` and any other WebView ignore the app proxy and private DNS (WebView follows the
  system network settings).
- With a proxy on, `AppDns` resolves only the proxy's host; the proxy resolves the sites (for SOCKS5
  the hostname is sent unresolved).
- Kept-alive InnerTube connections stay on the previously resolved address after a DNS change until
  they close; new connections use the new DNS.
- ExoPlayer/Media3 streams use the app clients (so they follow), but a long stream already playing
  keeps its connection until it reconnects.
