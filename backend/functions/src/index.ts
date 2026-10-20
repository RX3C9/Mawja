// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Mawja Cloud Functions.
 * - onUserCreated: gives every new account a profile and a unique Mawja ID.
 * - claimUsername: reserves a username (also what username + password login resolves through).
 * - resolveLogin: username → email for the username/password sign-in.
 * - adminAction: every staff action, permission-checked and written to auditLog.
 */
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import * as functionsV1 from "firebase-functions/v1";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { generateMawjaId } from "./mawjaId";
import { can, isRole, Role } from "./roles";
import { validateUsername } from "./username";

initializeApp();
const db = getFirestore();
const REGION = "europe-west1";

const TIERS = ["free", "plus", "pro", "lifetime", "family"] as const;
const BADGES = ["verified", "creator", "founder", "pro", "team"] as const;

export const onUserCreated = functionsV1.region(REGION).auth.user().onCreate(async (user) => {
  for (let attempt = 0; attempt < 8; attempt++) {
    const mawjaId = generateMawjaId();
    const created = await db.runTransaction(async (tx) => {
      const idRef = db.doc(`mawjaIds/${mawjaId}`);
      if ((await tx.get(idRef)).exists) return false;
      tx.create(idRef, { uid: user.uid });
      tx.set(db.doc(`users/${user.uid}`), {
        mawjaId,
        email: user.email ?? null,
        displayName: user.displayName ?? null,
        photoUrl: user.photoURL ?? null,
        authProviders: user.providerData.map((p) => p.providerId),
        tier: "free",
        status: "active",
        badges: [],
        createdAt: FieldValue.serverTimestamp(),
      }, { merge: true });
      tx.set(db.doc(`profiles/${user.uid}`), {
        mawjaId, displayName: user.displayName ?? null, photoUrl: user.photoURL ?? null, badges: [], username: null,
      });
      return true;
    });
    if (created) {
      await getAuth().setCustomUserClaims(user.uid, { tier: "free", status: "active" });
      return;
    }
  }
  throw new Error(`Could not allocate a Mawja ID for ${user.uid}`);
});

export const claimUsername = onCall({ region: REGION, enforceAppCheck: false }, async (req) => {
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const check = validateUsername(String(req.data?.username ?? ""));
  if (!check.ok) throw new HttpsError("invalid-argument", check.reason);
  const username = check.value;
  await db.runTransaction(async (tx) => {
    const ref = db.doc(`usernames/${username}`);
    const taken = await tx.get(ref);
    if (taken.exists && taken.get("uid") !== uid) throw new HttpsError("already-exists", "taken");
    const userRef = db.doc(`users/${uid}`);
    const previous = (await tx.get(userRef)).get("username") as string | undefined;
    if (previous && previous !== username) tx.delete(db.doc(`usernames/${previous}`));
    tx.set(ref, { uid, email: req.auth?.token.email ?? null });
    tx.set(userRef, { username }, { merge: true });
    tx.set(db.doc(`profiles/${uid}`), { username }, { merge: true });
  });
  return { username };
});

/** Username → email, so the app can sign in with Firebase email/password. Rate limited by App Check. */
export const resolveLogin = onCall({ region: REGION }, async (req) => {
  const check = validateUsername(String(req.data?.username ?? ""));
  if (!check.ok) throw new HttpsError("not-found", "unknown");
  const doc = await db.doc(`usernames/${check.value}`).get();
  const email = doc.get("email") as string | undefined;
  if (!email) throw new HttpsError("not-found", "unknown");
  return { email };
});

type AdminRequest =
  | { action: "user.suspend" | "user.ban" | "user.unban"; uid: string; reason?: string }
  | { action: "user.tier"; uid: string; tier: (typeof TIERS)[number]; days?: number }
  | { action: "user.badge"; uid: string; badge: (typeof BADGES)[number]; grant: boolean }
  | { action: "feature.toggle"; key: string; enabled: boolean; minTier?: string }
  | { action: "config.edit"; patch: Record<string, unknown> }
  | { action: "announcement.send"; title: string; body: string; targetTier?: string }
  | { action: "report.resolve"; reportId: string; note?: string };

export const adminAction = onCall({ region: REGION }, async (req) => {
  const adminUid = req.auth?.uid;
  if (!adminUid) throw new HttpsError("unauthenticated", "Sign in first.");
  const role = req.auth?.token.role;
  if (!isRole(role)) throw new HttpsError("permission-denied", "Not staff.");
  const data = req.data as AdminRequest;
  if (!data || typeof data.action !== "string") throw new HttpsError("invalid-argument", "action");
  if (!can(role as Role, data.action)) throw new HttpsError("permission-denied", data.action);

  let target = "";
  let before: unknown = null;
  let after: unknown = null;

  switch (data.action) {
    case "user.suspend":
    case "user.ban":
    case "user.unban": {
      target = `users/${data.uid}`;
      const status = data.action === "user.unban" ? "active" : data.action === "user.suspend" ? "suspended" : "banned";
      before = (await db.doc(target).get()).get("status") ?? null;
      await db.doc(target).set({ status, banReason: data.reason ?? null }, { merge: true });
      const claims = (await getAuth().getUser(data.uid)).customClaims ?? {};
      await getAuth().setCustomUserClaims(data.uid, { ...claims, status });
      if (status !== "active") await getAuth().revokeRefreshTokens(data.uid);
      after = status;
      break;
    }
    case "user.tier": {
      if (!TIERS.includes(data.tier)) throw new HttpsError("invalid-argument", "tier");
      target = `users/${data.uid}`;
      const expiresAt = data.days ? Date.now() + data.days * 86_400_000 : null;
      before = (await db.doc(target).get()).get("tier") ?? null;
      await db.doc(target).set({ tier: data.tier, tierExpiresAt: expiresAt, tierSource: "admin" }, { merge: true });
      const claims = (await getAuth().getUser(data.uid)).customClaims ?? {};
      await getAuth().setCustomUserClaims(data.uid, { ...claims, tier: data.tier, exp: expiresAt });
      after = { tier: data.tier, expiresAt };
      break;
    }
    case "user.badge": {
      if (!BADGES.includes(data.badge)) throw new HttpsError("invalid-argument", "badge");
      target = `users/${data.uid}`;
      const change = data.grant ? FieldValue.arrayUnion(data.badge) : FieldValue.arrayRemove(data.badge);
      await db.doc(target).set({ badges: change }, { merge: true });
      await db.doc(`profiles/${data.uid}`).set({ badges: change }, { merge: true });
      after = { badge: data.badge, grant: data.grant };
      break;
    }
    case "feature.toggle": {
      target = "config/features";
      before = (await db.doc(target).get()).get(data.key) ?? null;
      after = { enabled: data.enabled, minTier: data.minTier ?? "free" };
      await db.doc(target).set({ [data.key]: after }, { merge: true });
      break;
    }
    case "config.edit": {
      target = "config/app";
      before = (await db.doc(target).get()).data() ?? null;
      await db.doc(target).set(data.patch, { merge: true });
      after = data.patch;
      break;
    }
    case "announcement.send": {
      const ref = await db.collection("announcements").add({
        title: data.title, body: data.body, targetTier: data.targetTier ?? null, startAt: FieldValue.serverTimestamp(),
      });
      target = `announcements/${ref.id}`;
      after = { title: data.title };
      break;
    }
    case "report.resolve": {
      target = `reports/${data.reportId}`;
      await db.doc(target).set({ status: "resolved", resolvedBy: adminUid, note: data.note ?? null }, { merge: true });
      after = "resolved";
      break;
    }
    default:
      throw new HttpsError("invalid-argument", "unknown action");
  }

  await db.collection("auditLog").add({ adminUid, role, action: data.action, target, before, after, at: FieldValue.serverTimestamp() });
  return { ok: true };
});
