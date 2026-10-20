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

## Memberships (sandbox only)

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
