# Mawja Admin (Android control panel)

`:admin` (`app.mawja.admin`) is the main control panel for every part of Mawja. It runs on the free
Firebase Spark plan: every action is a direct Firestore write that `backend/firestore.rules` checks
by staff role, and every staff write carries an `auditId` pointing at an `auditLog` entry written in
the same batch (the rules refuse the write otherwise).

| Area | Who | What |
| --- | --- | --- |
| Dashboard | all staff | users, members by tier, active timed memberships, codes made / activations (7/30 days, `count()`), open reports, 14-day charts, recent activations and staff actions |
| Users | view: all; note: support+; suspend/restore & badges: moderator+; ban, tier, sync reset: admin+ | search by email, @username, Mawja ID or UID; browse newest / by tier / by status; redemptions and devices |
| Codes | view: all; generate/revoke: admin+ | batch generate (shown once), copy/share/CSV export, QR per code, lookup + who activated, revoke code or batch, batch CSV (hints only) |
| Messages | view: all; edit: admin+ | `announcements/{id}`: banner/dialog/news, tier + language targeting, schedule (start/end), active switch, preview |
| Remote config | view: all; edit: admin+ | `config/{app,downloader,admin}`: kill switch, maintenance + message (en/ar), min/latest version, update/checkout URL, feature flags, tier gating map |
| Reports | all staff | status (open / in progress / resolved / dismissed), assign, internal note, reply |
| Audit log | admin+ | newest first, category + text filters, details |
| Staff | view: all; manage: owner | `admins/{uid}` roles admin/moderator/support/removed; owners only from the console, never yourself |
| Settings | everyone | language (ar/en/system), theme, account, about (GPL-3.0, based on Flow by A-EDev) |

The admin app itself honours `config/admin` (kill switch / minimum version), except for owners.

## Setup (once)

1. Publish `backend/firestore.rules` (see `work/deploy_rules.sh`).
2. Sign in to the admin app; the "Not authorized" screen shows your UID with a copy button.
3. Firebase console → Firestore → create `admins/<UID>` with `role = "owner"`. Tap **Check again**.
4. Add the rest of the team from **Staff**.

Google sign-in needs the admin app's SHA-1 (shared debug key `keystore/mawja-debug.p12`) on the
`app.mawja.admin.debug` Firebase Android app.

Users appear in the directory after they sign in to an app build that lists them (`users/{uid}`
with email, name, createdAt; the rules allow only those fields and only the account's own email).
