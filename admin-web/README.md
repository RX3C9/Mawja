# Mawja Admin (web)

The web control room for Mawja staff, alongside the Android **Mawja Admin** app. Next.js (App
Router, TypeScript) built as a **static export**, so it can be hosted for free on Firebase Hosting
(Spark plan) or GitHub Pages. Arabic first (RTL) with an English switch.

| Page | Who | What |
|---|---|---|
| Dashboard | all staff | users, reports, announcements, staff counts |
| Users | all staff (actions by role) | find by Mawja ID (`MW-XXXX-XXXX`, typo-checked), `@username`, email or UID; set membership tier (+ expiry days), grant/revoke badges, suspend / ban / restore |
| Features | owner, admin | feature switches in `config/features` with a minimum tier |
| Announcements | owner, admin | send general announcements, optionally to one tier |
| Reports | owner, admin, moderator, support | open / resolved user reports, resolve with a note |
| Audit log | owner, admin | every admin action, who and when |

## Security model

- Sign-in with Firebase Auth (email/password or Google). Only accounts with a staff **custom claim**
  `role` (`owner` / `admin` / `moderator` / `support`) get in; grant one with
  `backend/functions/tools/grantRole.js`.
- Reads go straight to Firestore and are enforced by `backend/firestore.rules` (staff-only
  collections).
- Every write goes through the `adminAction` Cloud Function, which re-checks the role
  (`backend/functions/src/roles.ts`, mirrored in `lib/roles.ts`) and writes the audit log.
  Until the functions are deployed (Firebase Blaze plan) the dashboard is effectively read-only and
  says so.

## Run

```bash
cp .env.example .env.local   # fill in the Firebase *web* app config
npm ci
npm run dev                  # http://localhost:3000
npm test && npm run typecheck && npm run build   # static site in out/
```

Deploy `out/` with `firebase deploy --only hosting` (see `firebase.json`) or any static host.

Licensed GPL-3.0-or-later, like the rest of Mawja.
