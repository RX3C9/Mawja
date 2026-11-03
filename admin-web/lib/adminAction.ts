// SPDX-License-Identifier: GPL-3.0-or-later
import { httpsCallable } from "firebase/functions";
import { arrayRemove, arrayUnion, collection, doc, getDoc, serverTimestamp, writeBatch, type DocumentData } from "firebase/firestore";
import { auth, db, functions } from "./firebase";
import type { Badge, Tier } from "./roles";

/** The requests the adminAction Cloud Function accepts (backend/functions/src/index.ts). */
export type AdminRequest =
  | { action: "user.suspend" | "user.ban" | "user.unban"; uid: string; reason?: string }
  | { action: "user.tier"; uid: string; tier: Tier; days?: number }
  | { action: "user.badge"; uid: string; badge: Badge; grant: boolean }
  | { action: "feature.toggle"; key: string; enabled: boolean; minTier?: string }
  | { action: "config.edit"; patch: Record<string, unknown> }
  | { action: "announcement.send"; title: string; body: string; targetTier?: string; kind?: AnnouncementKind; locale?: "ar" | "en" }
  | { action: "report.resolve"; reportId: string; note?: string };

export type AnnouncementKind = "banner" | "dialog" | "news" | "push";
export const ANNOUNCEMENT_KINDS: AnnouncementKind[] = ["banner", "dialog", "news", "push"];

/**
 * Announcements are written straight to Firestore (the project runs on the free Spark plan, so no
 * Cloud Functions): one batch with the audit entry and the document, which the rules check together.
 */
async function sendAnnouncement(r: Extract<AdminRequest, { action: "announcement.send" }>): Promise<void> {
  const uid = auth().currentUser?.uid;
  if (!uid) throw Object.assign(new Error("unauthenticated"), { code: "unauthenticated" });
  const ref = doc(collection(db(), "announcements"));
  const audit = doc(collection(db(), "auditLog"));
  const now = serverTimestamp();
  const batch = writeBatch(db());
  batch.set(audit, {
    action: "announcement.send", actorUid: uid, target: `announcements/${ref.id}`, at: now, source: "admin-web",
    details: { kind: r.kind ?? "banner", targetTier: r.targetTier ?? "all" },
  });
  batch.set(ref, {
    title: r.title, body: r.body, targetTier: r.targetTier ?? null, locale: r.locale ?? null, kind: r.kind ?? "banner",
    active: true, startAt: now, createdBy: uid, createdAt: now, updatedBy: uid, updatedAt: now, auditId: audit.id,
  });
  await batch.commit();
}

/** One audited write: the audit entry and the change go in one batch, as the rules require. */
async function audited(action: string, path: string, details: Record<string, unknown>, fields: DocumentData, mode: "merge" | "update" = "merge") {
  const uid = auth().currentUser?.uid;
  if (!uid) throw Object.assign(new Error("unauthenticated"), { code: "unauthenticated" });
  const audit = doc(collection(db(), "auditLog"));
  const batch = writeBatch(db());
  batch.set(audit, { action, actorUid: uid, target: path, at: serverTimestamp(), source: "admin-web", details });
  const ref = doc(db(), path);
  if (mode === "update") batch.update(ref, { ...fields, auditId: audit.id });
  else batch.set(ref, { ...fields, auditId: audit.id }, { merge: true });
  await batch.commit();
  return uid;
}

/**
 * Every action is a direct Firestore write checked by the rules (free Spark plan: no Cloud Functions).
 * A ban only flags the user document; the CI auth sync then disables the Firebase Auth account.
 */
export async function adminAction(request: AdminRequest): Promise<void> {
  const me = () => auth().currentUser?.uid ?? "";
  switch (request.action) {
    case "announcement.send":
      return sendAnnouncement(request);
    case "user.ban":
    case "user.unban":
    case "user.suspend": {
      const status = request.action === "user.ban" ? "banned" : request.action === "user.unban" ? "active" : "suspended";
      await audited("user.status", `users/${request.uid}`, { status, reason: request.reason ?? "" }, {
        status, banReason: (request.reason ?? "").slice(0, 500), banUntil: null, statusUpdatedAt: serverTimestamp(), statusBy: me(),
      });
      return;
    }
    case "user.tier": {
      const until = request.days ? new Date(Date.now() + request.days * 86_400_000) : null;
      await audited("user.tier", `users/${request.uid}`, { tier: request.tier, until: until?.getTime() ?? null }, {
        tier: request.tier, tierUntil: until, tierSource: "admin", tierUpdatedAt: serverTimestamp(), tierUpdatedBy: me(),
      });
      return;
    }
    case "user.badge":
      await audited("user.badges", `users/${request.uid}`, { badge: request.badge, grant: request.grant }, {
        badges: request.grant ? arrayUnion(request.badge) : arrayRemove(request.badge),
      });
      return;
    case "feature.toggle": {
      const cur = (await getDoc(doc(db(), "config", "app"))).data()?.features ?? {};
      const features = { ...cur, [request.key]: { enabled: request.enabled, minTier: request.minTier ?? "free" } };
      await audited("config.edit", "config/app", { keys: ["features"], feature: request.key }, { features, updatedAt: serverTimestamp(), updatedBy: me() });
      return;
    }
    case "config.edit":
      await audited("config.edit", "config/app", { keys: Object.keys(request.patch).sort() }, { ...request.patch, updatedAt: serverTimestamp(), updatedBy: me() });
      return;
    case "report.resolve":
      await audited("report.update", `reports/${request.reportId}`, { status: "resolved" }, {
        status: "resolved", note: (request.note ?? "").slice(0, 2000), updatedAt: serverTimestamp(), updatedBy: me(), resolvedBy: me(),
      }, "update");
      return;
    default:
      await httpsCallable<AdminRequest, { ok: boolean }>(functions(), "adminAction")(request);
  }
}
