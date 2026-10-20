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
