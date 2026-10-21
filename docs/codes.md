# Mawja membership codes

Mawja has no in-app payment. Memberships (Plus, Pro, Family, Lifetime) are sold as **activation codes**
through Mawja's official channels; staff generate them in the control panel (Mawja Admin for Android,
mirrored in admin-web → Membership codes), and the user types the code in
**Settings → Account → Mawja membership → Activate a code**.

Everything works on the free Firebase **Spark** plan: no Cloud Functions, no billing, no payment keys.

## Code format

```
MW-XXXX-XXXX-XXXX
```

* 12 symbols after the `MW-` prefix, from the 31-symbol alphabet
  `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (no `0 O 1 I L`, so nothing can be misread).
* Symbols 1–11 are random (≈ 54 bits; generated with `SecureRandom` / `crypto.getRandomValues`,
  unbiased). Symbol 12 is a **check symbol**.
* Check rule: with the symbol values `v₁…v₁₂` (index in the alphabet) and weights `1…12`,
  `Σ i·vᵢ ≡ 0 (mod 31)`. So `v₁₂ = (−Σᵢ₌₁¹¹ i·vᵢ) · 13 mod 31` (13 is the inverse of 12 mod 31).
  Because 31 is prime and every weight is below 31, this catches **every single wrong symbol** and
  **every swap of two neighbouring symbols**, including the check symbol. The app checks it before
  anything goes to the network.

### Normalisation

Before validating or hashing, input is:

1. Unicode NFKC (full-width letters/digits become ASCII);
2. Arabic-Indic `٠–٩` and Extended Arabic-Indic `۰–۹` digits become `0–9`;
3. stripped of whitespace, invisible marks (ZWSP, LRM, RLM, BOM) and dashes/separators
   (`- ‐ ‑ ‒ – — ― − _ . / ـ`);
4. upper-cased (locale-independent);
5. the leading `MW` is removed **only** when 14 symbols remain (a code may itself start with `MW`).

The result must be exactly 12 alphabet symbols with a valid check symbol. That 12-symbol string is the
**canonical code**.

### Hash (Firestore document id)

```
codes/{ sha256_hex( "mawja-code-v1:" + canonical ) }
```

Lowercase hex. The plain code is **never stored**; staff only ever see its last group (`hint`, e.g.
`MW-••••-••••-JKM4`). The full code is shown once, right after generating, to copy / share / download.

### Test vectors

Both implementations are tested against these (also in `MawjaCodesTest.kt` and `admin-web/test/codes.test.ts`):

| Code | sha256("mawja-code-v1:" + canonical) |
|---|---|
| `MW-2222-2222-2222` | `d87c41c4f35d52bf4fcf895f6441b9e8b0378a01f34a2e8816a7f501b52a5111` |
| `MW-ABCD-EFGH-JKM4` | `2e7dc17e68f222019c828129cf505158ad2646da6a285dbadf2dc2bc895b15af` |
| `MW-ZZZZ-ZZZZ-ZZZQ` | `07acaede33ac2f043887aef08a467e94781f4f45f7fcd70d5316ef98513b484d` |
| `MW-CZ6E-P34V-K5DG` | `2f12de93a40cbf1dd9f7325c01ab2839338bd9959269b3dfe904a54b4c3e59de` |
| `MW-M3YJ-834F-F49Z` | `2f188ef47c2240ee6d5138a5ede5042a927d33794560d9f043838b24262a13e4` |

### Implementations

| Where | File |
|---|---|
| App + Mawja Admin (Kotlin, compiled into both) | `shared/codes/kotlin/app/mawja/codes/MawjaCodes.kt`, `CodeRedeemPolicy.kt` |
| admin-web (TypeScript) | `admin-web/lib/codes.ts` |
| Security rules | `backend/firestore.rules` (`codes`, `redemptions`, `codeBatches`, `users/*/meta/redeem`, `tierMath`) |
| Rules tests (Firestore emulator, run in CI as `rules:`) | `backend/rules-test/test/codes.rules.test.mjs` |

## Firestore model

| Path | Fields | Who writes |
|---|---|---|
| `codes/{hash}` | `tier` (plus/pro/family/lifetime), `days` (0 for lifetime), `maxUses`, `used`, `expiresAt` (redeem-by date or null), `revoked`, `createdBy`, `createdAt`, `batchId`, `note`, `hint`; on revoke `revokedAt`, `revokedBy` | owner/admin create & revoke; a redeeming user may only do `used + 1` |
| `codeBatches/{id}` | `tier`, `days`, `count`, `maxUses`, `expiresAt`, `note`, `createdBy`, `createdAt`, `revoked` | owner/admin |
| `redemptions/{hash}_{uid}` | `uid`, `codeHash`, `codeTier`, `days`, `tier` (result), `tierUntil` (result), `batchId`, `previousTier`, `createdAt` | the redeeming user, once per code |
| `users/{uid}` | `tier`, `tierUntil` (null = no end), `tierSource: "code"`, `tierCodeHash`, `tierUpdatedAt` | the user, only together with a valid redemption |
| `users/{uid}/meta/redeem` | `hash`, `lastAttempt`, `windowStart`, `count` | the user (throttle) |
| `auditLog/{id}` | `action` (`code.batch.create`, `code.revoke`, `code.batch.revoke`), `actorUid`, `target`, `details`, `at`, `source` | staff |

Nobody but staff can list or query `codes`; a user can only `get` one code by its hash, and only right
after announcing that hash in `users/{uid}/meta/redeem`.

## Redeeming (client transaction, enforced by rules)

1. **Local check**: normalise, alphabet, length, check symbol → clear error without a network call.
2. **Throttle**: write `users/{uid}/meta/redeem` `{hash, lastAttempt: serverTime, windowStart, count}`.
   Rules: at least 2 s after the previous attempt, at most 12 per rolling hour window. Reading
   `codes/{hash}` is only allowed within 10 minutes of announcing that exact hash.
3. **Transaction**: read `codes/{hash}`, `users/{uid}`, `redemptions/{hash}_{uid}`; refuse if missing,
   revoked, expired, used up or already redeemed by this user; compute the new membership with
   `CodeRedeemPolicy`; commit together: `codes.used + 1`, create the redemption, set the users tier
   fields.
4. The rules accept the users write only when the same commit creates the redemption
   (`!exists` before, `existsAfter` after), the code was valid **before** the commit, `used` goes up by
   exactly one, and the new tier/end date match `tierMath`:
   * an active **Lifetime** takes nothing more (refused, code stays unused);
   * a **Lifetime** code → `tier: lifetime`, `tierUntil: null`;
   * an open-ended (staff-granted) membership only accepts a better tier, for `now + days`;
   * otherwise the days are added to the remaining time of an active membership (or to now), and the
     better tier of the two is kept, so nobody loses time.
   `tierUntil` must be within ±10 minutes of the exact value (client and server clocks differ).

The app talks to Firestore over its REST API with the user's Firebase ID token (no Firestore SDK in the
app), so it all goes through these rules.

## Staff

A staff role is either the `role` custom claim (`backend/functions/tools/grantRole.js`, uses a service
account, works on Spark) **or** an `admins/{uid}` document with `role: "owner" | "admin" | "moderator" | "support"`
created by hand in the Firebase console. Owner/admin generate and revoke; every staff role can look codes
up and see batches and redemptions; owner/admin read the audit log.

Generating writes the batch, the audit entry and up to 8 codes per write batch (keeps every commit under
the rules' 20 document-read limit). A batch of up to 500 codes can be generated at once.

## Publishing the rules

`firebase deploy --only firestore:rules` from `backend/`, or paste `backend/firestore.rules` into
**Firebase console → Firestore Database → Rules → Publish**. Both work on the free Spark plan (rules are
not a billed feature; Cloud Functions are).
