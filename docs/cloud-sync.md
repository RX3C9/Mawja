# Mawja cloud sync — design (PLAN_V2 #61, Plus)

Status: part 1 (rules, merge engine, REST client, **subscriptions** end-to-end) — 2026-10-22.
Part 2 (2026-10-22): playlists per entry, liked videos, watch history (newest 2 000), whitelisted
settings, one-off full pulls, tombstone clean-up, "delete my cloud data" (§8).
Later: privacy page hooks (#67), job-center polish.

## 1. Constraints
- **No Firestore SDK in :app** (its protolite clashes with protobuf-javalite). The app talks to the
  Firestore REST API (`FirestoreRest`, now in `app/src/main/.../account/`) with the signed-in
  user's **Firebase Auth ID token** (`AccountBackend.idToken()`), so every call passes the rules.
- **Spark plan only**: no Cloud Functions, no billing, no scheduled server jobs. All merge logic
  runs on the device; the server only stores docs and enforces rules.
- **foss flavor = no sync** (GuestOnlyAccountBackend: `available=false`, `idToken()=null`).
- **Gate**: `MawjaFeature.CLOUD_SYNC` (Plus and above via the tier table; a remote
  `tierGates.CLOUD_SYNC` can move it) **and** remote switch `features.cloudSync`
  (`RemoteSettings.isEnabled`, default on). The rules additionally require a paid, unexpired tier
  (`users/{uid}.tier/tierUntil` or a `tier` claim) — same predicate as the old `syncData` rule.

## 2. Data model
One flat subcollection per user (re-uses the existing `syncData` path, so the admin
"reset sync data" action keeps deleting it):

```
users/{uid}/syncData/{c}_{k}
  c   string   collection: subscriptions | playlists | plitems | liked | history | settings   (no "_")
  k   string   item key, ^[A-Za-z0-9_.:-]{1,128}$ (subscriptions: channel id)
  m   int      client "modified at", epoch ms — the LWW clock (rules: 0 < m < now+1 day)
  dev string   device id (random UUID per install, 1..64) — tie-break + "who wrote it"
  del bool     tombstone; when true `d` must be {}
  v   int      schema version of `d` (1..100), currently 1
  at  timestamp  server REQUEST_TIME of the write (rules: == request.time) — the pull cursor
  d   map      payload, validated per collection
```
Payloads (v1):
- `subscriptions`: `n` name ≤300, `a` avatar URL ≤1000, `mu` isMusic bool. (Notification bell,
  last video, feed timestamps stay device-local.)
- later (part 2, rules must be extended + redeployed): `playlists` (`k`=playlist id; name, item
  ids as a capped list ≤ 500, per-item order — playlist *entries* may need their own `c` so a
  playlist edit does not rewrite 500 ids), `liked` (`k`=video id; title, channel, thumb),
  `history` (`k`=video id; position ms, duration, watchedAt — capped to the last 2 000 items and
  90 days), `settings` (`k`=setting key; `val` string ≤ 500; whitelisted keys only: theme,
  language, default qualities, SponsorBlock categories… never device paths/tokens).

Why flat + one doc per item: an edit costs exactly one write, a pull reads only changed docs, the
1 MiB doc limit is irrelevant, and the admin reset still finds everything under one collection.

## 3. Merge: last-writer-wins register per item (LWW-element-set)
PLAN_V2 asks for "CRDT-like per entity instead of last-write-wins". Part 1 uses a per-item
LWW register with tombstones (an LWW-element-set, which *is* a state-based CRDT: merge is
commutative, associative, idempotent). Whole-entity LWW is avoided because each item is its own
register. Fields inside an item are tiny and overwritten together. Part 2 for playlists should go
finer (per-entry registers + fractional order keys) so concurrent adds on two devices both survive.

`wins(a, b)`: higher `m`; equal `m` → tombstone beats live; still equal → greater `dev`.
Deterministic on every device, so all replicas converge.

Per sync run (`SyncMerge.plan`, pure, `SyncMergeTest`):
1. local state → records (subscription present: `m = subscribedAt`; unsubscribed: tombstone with
   the stored unsubscribe time). Future clocks are clamped to `now`.
2. `base[k]` = the `m` last agreed with the server for that key (local file).
3. remote changes since the cursor; for each key:
   - remote beats local (or no local) → apply locally, base = remote.m
   - local beats remote → push local
   - same state → base = m
   - no remote change and `local.m > base[k]` (or no base) → push local
4. push winners in commits of ≤ 400 writes, ≤ 2 000 per run (rest deferred to the next run);
   base updated only after a commit succeeds.
5. first sync never pushes tombstones older than 180 days (quota), but applies remote ones.

Applying remote subscriptions: live & missing → `subscribeAll` with `subscribedAt = m`; live &
present → keep the local row (bell/last-video stay); tombstone & present → `unsubscribe(at = m)`;
tombstone & absent → record the tombstone (so it keeps propagating to device sync peers).

Known LWW limits: a device with a wrong clock can win wrongly (rules cap m at now+1 day);
the existing local device sync (sync/…) keeps working independently — both converge because they
use the same subscribedAt / unsubscribe timestamps.

## 4. Pull cursor
`runQuery` on `users/{uid}` → `syncData`, `where at >= cursor − 2 min`, `orderBy at, __name__`,
paged 300 with `startAt(after)` on (at, name) — a 400-write commit shares one `at`, so the name is
needed. The new cursor is the largest `at` seen. The 2-minute overlap re-reads a handful of docs
(idempotent merge) and covers commit-time skew. Docs of collections this build does not sync are
skipped; when part 2 enables a collection it does a one-off full pull for it
(`where c == X`, single-field index, no composite index needed).

## 5. Limits & Spark free quota (50k reads, 20k writes, 20k deletes /day per **project**)
- Periodic job every 6 h (network connected, battery not low, skipped while battery saver is
  active); "Sync now" on the account page.
- Steady state per user per run: 1 query (≥1 read billed) + 1 rules `get(users/{uid})` per request
  + only the changed docs. ~4 runs/day ≈ 10 reads, writes only when something changed.
- First sync of 500 subscriptions = 500 writes (+ reads on other devices). With 20k writes/day
  that is ~40 first-time users/day — fine for launch; history (part 2) must be capped (last 2 000,
  ≤ 50 writes/run batched by "position changed > 30 s") or it will dominate writes.
- Caps: subscriptions 5 000 items, ≤ 2 000 writes per run, key regex, payload string caps in rules.
- 403 from the rules (not entitled / rules not deployed) → status "not allowed", no retry storm.

## 6. Client pieces (part 1)
- `cloudsync/SyncMerge.kt` — records, `wins`, `plan`, base codec, `CloudSyncGate` (pure).
- `cloudsync/SubscriptionSyncMapper.kt` — local ↔ records (pure).
- `cloudsync/CloudSyncRemote.kt` — Firestore REST pull/push (OkHttp via FirestoreRest).
- `cloudsync/CloudSyncStore.kt` — SharedPreferences `mawja_cloud_sync`: device id, cursor, base,
  last sync time/result.
- `cloudsync/CloudSyncRepository.kt` — gate, run, status StateFlow, schedules/cancels the worker.
- `cloudsync/CloudSyncWorker.kt` — periodic unique work `mawja_cloud_sync` (also in Job center).
- Account page: "Cloud sync" card (status, last sync, Sync now; locked → Upgrade for Free).

## 7. Rules (backend/firestore.rules → `match /syncData/{doc}`)
Owner-only (not banned, paid tier) read; create/update validated as above (doc id == c+'_'+k,
known keys only, types, sizes, `at == request.time`, `m` bounded); owner may delete only
tombstones (future GC); admin read + audited reset delete unchanged. **Needs a rules redeploy**
(`firebase deploy --only firestore:rules`) before sync works in production.

## 8. Part 2 collections (payloads v1, validated in the rules)
- `playlists` — `k` = playlist sync id (`reserved:watch-later` for Watch later); `d`: `t` title ≤200
  (required), `ds` ≤1000, `mu`, `uc` user-created, `or` local|youtube, `y` YouTube id, `ca` created
  at. Watch later / saved Shorts are never deleted by sync.
- `plitems` — one doc **per entry**, `k` = `{playlist}.{video}`; `d`: `p`, `v` (rules: `k == p+'.'+v`),
  `o` fractional order key (`^[0-9A-Za-z]{0,63}[1-9A-Za-z]$`, `OrderKeys`), `t`, `ch`, `ci`, `th`,
  `du`, `mu`, `aa` added at. Each entry is its own LWW register, so concurrent adds on two devices
  both survive; display order = order-key order. Unchanged entries keep their key (longest
  increasing run of prior keys); moved/new entries get a key between their neighbours; respread
  only past 64 chars. A winning playlist tombstone deletes the playlist; entries of a playlist the
  device neither has nor received are dropped.
- `liked` — `k` = video id, clock = like time; unlike = tombstone. `d`: `t` (required), `ch`, `th`,
  `mu`, `ci`, `du`.
- `history` — `k` = video id, clock = watch time; only the newest **2 000** entries are offered
  (plus keys the server just sent), **≤ 50 writes per run** newest first; deletions are sent only
  for keys the server holds. `d`: `t`, `ch`, `ci`, `th`, `pr` progress 0..1, `du` s, `mu`, `sh`.
- `settings` — `k` = canonical key from `SettingsMapper.WHITELIST` entries that are applied on
  other devices (never `app_language`, paths, tokens); `d = {val}` bool | number | string ≤4000.
  Rules hold the same list (`syncSettingKeys()`, checked by `CollectionSyncMappersTest`).

Change detection: these stores keep no edit times or deletion traces, so each has a cloud-only
`CollectionLedger` (`sync_ledger_cloud_*.json`, reset with the device-sync ledgers on a backup
restore): `observe` stamps edits/removals, `settle` adopts the winners' clocks after applying, so
applied remote changes are not echoed back. Per-run push caps: playlists 200, entries 1 000,
liked 1 000, history 50, settings 100 (subscriptions 2 000).

Full pull: a device that already had a cursor when a collection was added reads that collection
whole once (`where c == X orderBy __name__`, single-field index only, ≤ 40 pages; retried next run
when cut short). A first sync reads everything through the cursor pull.

Tombstone clean-up: at most once a day, `where del == true limit 300`, deletes those whose server
time `at` is older than **30 days**. A device offline longer keeps its unchanged copy (it is not
re-pushed because its base already matches).

Delete my cloud data (account page): deletes every `syncData` doc of the account in pages of 300
(one commit each), then turns sync **off on this device** (`CloudSyncAvailability.OFF`, "Turn on
cloud sync" re-uploads everything) and forgets cursor/bases/order keys. Local data stays. Other
signed-in devices only re-push items they change afterwards.

Rules (part 2): owners may **read and delete** all their own `syncData` docs at any time (also
after the membership ended or when banned — privacy); create/update still need a paid tier, not
banned, and a valid payload. Not yet verified in production: whether a 400-write commit stays
within the rules' per-request limits (the emulator test commits 400 entries).
