# Mawja backend (Firebase)

| Piece | What it does |
|---|---|
| `firestore.rules` | Users read/write only their own profile fields; tier, status, badges, Mawja ID and roles are server-only. |
| `functions/src/index.ts` | `onUserCreated` (profile + unique Mawja ID `MW-XXXX-XXXX`), `claimUsername`, `resolveLogin` (username → email for password sign-in), `adminAction` (role-checked staff actions, every one written to `auditLog`). |
| `functions/src/roles.ts` | owner / admin / moderator / support permissions. |

## Run locally
```bash
cd backend/functions && npm ci && npm test
firebase emulators:start   # from backend/
```

## Deploy
```bash
firebase use <project-id>
firebase deploy --only firestore,functions
GOOGLE_APPLICATION_CREDENTIALS=sa.json node functions/tools/grantRole.js you@example.com owner
```
Then enable Google and Email/Password providers in Firebase Auth, and App Check (Play Integrity is not available off-Play, so use the custom provider planned in M2).

## Membership codes (live path, Spark plan)

Memberships are activated with codes (`MW-XXXX-XXXX-XXXX`), not card payments. Spec, data model and
redeem flow: [`docs/codes.md`](../docs/codes.md). No function is involved: the app redeems in one client
transaction and `firestore.rules` only accepts a valid one. Rules tests: `cd rules-test && npm ci && npm test`
(starts the Firestore emulator; needs Java 21). Publish the rules with `firebase deploy --only firestore:rules`
or by pasting `firestore.rules` into Firebase console → Firestore → Rules (no billing needed).

Staff without functions: add `admins/{uid}` with `role: "owner"` in the console (or run `tools/grantRole.js`).

## Memberships (sandbox only, dormant)

Kept for later; the app no longer shows a checkout (payment is code-only, DECISIONS 2026-10-22).


`src/billing/` holds the plan catalogue (`plans.ts`, mirrored in the app's `MembershipPlans.kt` and
`admin-web/lib/plans.ts`), the purchase → tier math (`entitlements.ts`), Paymob (Egypt, EGP) and Stripe
(international, USD) helpers, and the idempotent grant (`grant.ts`, `payments/{provider}_{id}`).

Functions: `billingPlans`, `createCheckout` (callable; returns `{url}` or `{comingSoon:true}`),
`paymobWebhook`, `stripeWebhook` (HTTP; signature-verified; write `tier`, `tierExpiresAt`, `tierPlan`,
`entitlements` to `users/{uid}` and refresh the `tier` claim).

Environment (never committed). **Only test keys are accepted** — a live key leaves the provider "coming soon":

| Var | Example |
|---|---|
| `PAYMOB_SECRET_KEY` | `egy_sk_test_…` |
| `PAYMOB_PUBLIC_KEY` | `egy_pk_test_…` |
| `PAYMOB_HMAC_SECRET` | from the Paymob dashboard |
| `PAYMOB_INTEGRATION_IDS` | `1234567,7654321` (card, wallet…) |
| `STRIPE_SECRET_KEY` | `sk_test_…` |
| `STRIPE_WEBHOOK_SECRET` | `whsec_…` |
| `MAWJA_BILLING_RETURN_URL` | page shown after checkout |

Webhook URLs once deployed: `https://europe-west1-<project>.cloudfunctions.net/paymobWebhook` (Paymob
"Transaction processed callback") and `…/stripeWebhook` (event `checkout.session.completed`).
