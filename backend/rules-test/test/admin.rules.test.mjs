// SPDX-License-Identifier: GPL-3.0-or-later
// Firestore rules for the admin apps' direct staff writes (Spark plan, no Cloud Functions):
// users, sync reset, devices, remote config, announcements, reports, staff roles, audit log.
// Every staff write must come with an auditLog entry written in the same batch.
import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Timestamp, collection, deleteDoc, doc, getDoc, getDocs, runTransaction, serverTimestamp, setDoc, updateDoc, writeBatch,
} from "firebase/firestore";

const DAY = 86_400_000;
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-mawja-admin",
    firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
  });
});
after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());

const user = (uid, claims) => env.authenticatedContext(uid, claims).firestore();
const owner = () => user("omar", { role: "owner" });
const admin = () => user("boss", { role: "admin" });
const mod = () => user("mo", { role: "moderator" });
const support = () => user("sam", { role: "support" });

async function seed(path, data) {
  await env.withSecurityRulesDisabled(async (ctx) => setDoc(doc(ctx.firestore(), path), data));
}
async function read(path) {
  let out;
  await env.withSecurityRulesDisabled(async (ctx) => {
    const s = await getDoc(doc(ctx.firestore(), path));
    out = s.exists() ? s.data() : null;
  });
  return out;
}

/**
 * The admin app's write pattern: one batch with an auditLog entry and the change, which names the
 * entry in `auditId`. [opts] lets a test break the audit entry on purpose.
 */
function audited(db, actor, target, action, write, opts = {}) {
  const batch = writeBatch(db);
  const ref = doc(collection(db, "auditLog"));
  if (!opts.skipAudit) {
    batch.set(ref, {
      action, actorUid: opts.auditActor ?? actor, target: opts.auditTarget ?? target, details: {}, at: serverTimestamp(), source: "test",
    });
  }
  write(batch, opts.auditId ?? ref.id);
  return batch.commit();
}

const tierFields = (actor, tier, until, auditId) => ({
  tier, tierUntil: until, tierSource: "admin", tierUpdatedAt: serverTimestamp(), tierUpdatedBy: actor, auditId,
});
const statusFields = (actor, status, auditId) => ({ status, banReason: "", statusUpdatedAt: serverTimestamp(), statusBy: actor, auditId });

test("staff user edits need an audit entry from the same batch", async () => {
  await seed("users/alice", { tier: "free", status: "active", email: "a@x.io" });
  const db = admin();
  const until = Timestamp.fromMillis(Date.now() + 30 * DAY);
  await assertSucceeds(audited(db, "boss", "users/alice", "user.tier", (b, id) => b.update(doc(db, "users/alice"), tierFields("boss", "plus", until, id))));
  assert.equal((await read("users/alice")).tier, "plus");
  // No entry, an entry about another doc, someone else's entry, or an old entry: refused.
  await assertFails(audited(db, "boss", "users/alice", "user.tier", (b, id) => b.update(doc(db, "users/alice"), tierFields("boss", "pro", until, id)), { skipAudit: true }));
  await assertFails(audited(db, "boss", "users/alice", "user.tier", (b, id) => b.update(doc(db, "users/alice"), tierFields("boss", "pro", until, id)), { auditTarget: "users/bob" }));
  await assertFails(audited(db, "boss", "users/alice", "user.tier", (b, id) => b.update(doc(db, "users/alice"), tierFields("boss", "pro", until, id)), { auditActor: "omar" }));
  await seed("auditLog/old", { action: "user.tier", actorUid: "boss", target: "users/alice", details: {}, at: Timestamp.fromMillis(Date.now() - 60_000), source: "x" });
  await assertFails(audited(db, "boss", "users/alice", "user.tier", (b, id) => b.update(doc(db, "users/alice"), tierFields("boss", "pro", until, id)), { skipAudit: true, auditId: "old" }));
  assert.equal((await read("users/alice")).tier, "plus");
});

test("manual tiers: owner/admin only, valid tiers and end dates", async () => {
  await seed("users/alice", { tier: "free", status: "active" });
  const until = Timestamp.fromMillis(Date.now() + 30 * DAY);
  const set = (db, actor, tier, end, extra = {}) =>
    audited(db, actor, "users/alice", "user.tier", (b, id) => b.update(doc(db, "users/alice"), { ...tierFields(actor, tier, end, id), ...extra }));
  await assertSucceeds(set(owner(), "omar", "lifetime", null));
  await assertSucceeds(set(admin(), "boss", "pro", null)); // open-ended grant
  await assertSucceeds(set(admin(), "boss", "free", null));
  await assertFails(set(admin(), "boss", "lifetime", until));
  await assertFails(set(admin(), "boss", "free", until));
  await assertFails(set(admin(), "boss", "gold", until));
  await assertFails(set(admin(), "boss", "plus", until, { tierSource: "code" }));
  await assertFails(set(admin(), "boss", "plus", until, { tierUpdatedBy: "omar" }));
  await assertFails(set(mod(), "mo", "plus", until));
  await assertFails(set(support(), "sam", "plus", until));
  await assertFails(set(user("alice"), "alice", "plus", until));
  // A staff edit cannot touch other fields (role, email, mawjaId...).
  await assertFails(set(admin(), "boss", "plus", until, { role: "owner" }));
  // A user without a doc yet (Spark: no onUserCreated) can be given a tier: the doc is created.
  const db = admin();
  await assertSucceeds(audited(db, "boss", "users/newbie", "user.tier", (b, id) => b.set(doc(db, "users/newbie"), tierFields("boss", "plus", until, id))));
  assert.equal((await read("users/newbie")).tierSource, "admin");
});

test("status: moderators suspend and restore, only admins ban, never yourself", async () => {
  await seed("users/alice", { tier: "free", status: "active" });
  await seed("users/mo", { tier: "free", status: "active" });
  const st = (db, actor, uid, status) => audited(db, actor, `users/${uid}`, `user.${status}`, (b, id) => b.update(doc(db, `users/${uid}`), statusFields(actor, status, id)));
  await assertSucceeds(st(mod(), "mo", "alice", "suspended"));
  await assertFails(st(mod(), "mo", "alice", "banned"));
  await assertSucceeds(st(mod(), "mo", "alice", "active"));
  await assertSucceeds(st(admin(), "boss", "alice", "banned"));
  await assertFails(st(admin(), "boss", "alice", "deleted"));
  await assertFails(st(mod(), "mo", "mo", "suspended"));
  await assertFails(st(support(), "sam", "alice", "suspended"));
  assert.equal((await read("users/alice")).status, "banned");
  // A banned user cannot redeem (status checked by the code rules).
});

test("badges, verification and staff notes", async () => {
  await seed("users/alice", { tier: "free", status: "active", badges: [] });
  const db = mod();
  await assertSucceeds(audited(db, "mo", "users/alice", "user.badge", (b, id) => b.update(doc(db, "users/alice"), { badges: ["verified", "creator"], verified: true, auditId: id })));
  await assertFails(audited(db, "mo", "users/alice", "user.badge", (b, id) => b.update(doc(db, "users/alice"), { badges: ["god"], auditId: id })));
  await assertFails(audited(db, "mo", "users/alice", "user.badge", (b, id) => b.update(doc(db, "users/alice"), { verified: "yes", auditId: id })));
  const s = support();
  await assertSucceeds(audited(s, "sam", "users/alice", "user.note", (b, id) => b.update(doc(s, "users/alice"), { staffNote: "Paid via Vodafone Cash", auditId: id })));
  await assertFails(audited(s, "sam", "users/alice", "user.badge", (b, id) => b.update(doc(s, "users/alice"), { badges: ["team"], auditId: id })));
  await assertFails(updateDoc(doc(user("alice"), "users/alice"), { badges: ["verified"] }));
});

test("users list themselves in the directory without touching protected fields", async () => {
  const a = user("alice", { email: "a@x.io" });
  await assertFails(setDoc(doc(a, "users/alice"), { email: "other@x.io", createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(a, "users/alice"), { email: "a@x.io", createdAt: serverTimestamp(), tier: "pro" }));
  await assertFails(setDoc(doc(a, "users/alice"), { email: "a@x.io", createdAt: serverTimestamp(), status: "active" }));
  await assertFails(setDoc(doc(user("bob"), "users/alice"), { createdAt: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(a, "users/alice"), { email: "a@x.io", displayName: "Alice", createdAt: serverTimestamp(), lastSeenAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(a, "users/alice"), { lastSeenAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(a, "users/alice"), { email: "other@x.io" }));
  await assertFails(updateDoc(doc(a, "users/alice"), { createdAt: Timestamp.fromMillis(0) }));
  // Code redemption still works on a directory doc.
  const hash = createHash("sha256").update("mawja-code-v1:ABCDEFGHJKM4").digest("hex");
  await seed(`codes/${hash}`, { tier: "plus", days: 30, maxUses: 1, used: 0, expiresAt: null, revoked: false, createdBy: "boss", createdAt: Timestamp.now(), batchId: "b1", note: "", hint: "JKM4" });
  await assertSucceeds(setDoc(doc(a, "users/alice/meta/redeem"), { hash, lastAttempt: serverTimestamp(), windowStart: serverTimestamp(), count: 1 }));
  await assertSucceeds(runTransaction(a, async (tx) => {
    const c = await tx.get(doc(a, "codes", hash));
    await tx.get(doc(a, "users/alice"));
    await tx.get(doc(a, "redemptions", `${hash}_alice`));
    const until = Timestamp.fromMillis(Date.now() + 30 * DAY);
    tx.update(doc(a, "codes", hash), { used: c.data().used + 1 });
    tx.set(doc(a, "redemptions", `${hash}_alice`), {
      uid: "alice", codeHash: hash, codeTier: "plus", days: 30, tier: "plus", tierUntil: until, batchId: "b1", previousTier: "free", createdAt: serverTimestamp(),
    });
    tx.set(doc(a, "users/alice"), { tier: "plus", tierUntil: until, tierSource: "code", tierCodeHash: hash, tierUpdatedAt: serverTimestamp() }, { merge: true });
  }));
  assert.equal((await read("users/alice")).tier, "plus");
  // Staff can find people by email (directory query).
  await assertSucceeds(getDocs(collection(support(), "users")));
});

test("sync reset and devices", async () => {
  await seed("users/alice", { tier: "plus", status: "active" });
  await seed("users/alice/syncData/library", { items: 3 });
  await seed("users/alice/devices/pixel", { model: "Pixel 8" });
  await assertSucceeds(getDocs(collection(support(), "users/alice/devices")));
  await assertFails(getDocs(collection(user("bob"), "users/alice/devices")));
  await assertFails(getDocs(collection(support(), "users/alice/syncData")));
  const db = admin();
  await assertSucceeds(getDocs(collection(db, "users/alice/syncData")));
  await assertFails(deleteDoc(doc(db, "users/alice/syncData/library")));
  await assertSucceeds(audited(db, "boss", "users/alice", "user.sync_reset", (b, id) => {
    b.update(doc(db, "users/alice"), { syncResetAt: serverTimestamp(), auditId: id });
    b.delete(doc(db, "users/alice/syncData/library"));
  }));
  assert.equal(await read("users/alice/syncData/library"), null);
  await seed("users/alice/syncData/library", { items: 3 });
  const m = mod();
  await assertFails(audited(m, "mo", "users/alice", "user.sync_reset", (b, id) => {
    b.update(doc(m, "users/alice"), { syncResetAt: serverTimestamp(), auditId: id });
    b.delete(doc(m, "users/alice/syncData/library"));
  }));
});

test("remote config: owner/admin edit app, downloader and admin docs", async () => {
  const cfg = (actor, id, extra = {}) => ({
    killSwitch: false, maintenance: true, maintenanceMessage: "Back soon", maintenanceMessageAr: "نعود قريبا", minVersion: 19, latestVersion: 20,
    features: { smartNotifications: true }, tierGates: { PREMIUM_THEMES: "plus" }, updatedAt: serverTimestamp(), updatedBy: actor, auditId: id, ...extra,
  });
  const db = admin();
  for (const name of ["app", "downloader", "admin"]) {
    await assertSucceeds(audited(db, "boss", `config/${name}`, "config.edit", (b, id) => b.set(doc(db, `config/${name}`), cfg("boss", id), { merge: true })));
  }
  assert.equal((await read("config/app")).minVersion, 19);
  await assertSucceeds(getDoc(doc(user("anyone"), "config/app")));
  await assertFails(audited(db, "boss", "config/secrets", "config.edit", (b, id) => b.set(doc(db, "config/secrets"), cfg("boss", id))));
  await assertFails(audited(db, "boss", "config/features", "config.edit", (b, id) => b.set(doc(db, "config/features"), cfg("boss", id))));
  await assertFails(audited(db, "boss", "config/app", "config.edit", (b, id) => b.set(doc(db, "config/app"), cfg("boss", id, { apiKey: "x" }))));
  await assertFails(audited(db, "boss", "config/app", "config.edit", (b, id) => b.set(doc(db, "config/app"), cfg("boss", id, { minVersion: "19" }))));
  await assertFails(audited(db, "boss", "config/app", "config.edit", (b, id) => b.set(doc(db, "config/app"), cfg("boss", id, { killSwitch: "no" }))));
  await assertFails(audited(db, "boss", "config/app", "config.edit", (b, id) => b.set(doc(db, "config/app"), cfg("boss", id)), { skipAudit: true }));
  const m = mod();
  await assertFails(audited(m, "mo", "config/app", "config.edit", (b, id) => b.set(doc(m, "config/app"), cfg("mo", id))));
  await assertFails(deleteDoc(doc(owner(), "config/app")));
});

test("announcements: create, schedule, edit; never deleted; validated", async () => {
  const ann = (actor, id, extra = {}) => ({
    title: "Ramadan offer", body: "Plus for 3 months", targetTier: "free", locale: "ar", kind: "banner", ctaLabel: "Upgrade", ctaUrl: "mawja://upgrade",
    active: true, startAt: Timestamp.fromMillis(Date.now() + DAY), endAt: Timestamp.fromMillis(Date.now() + 8 * DAY),
    createdBy: actor, createdAt: serverTimestamp(), updatedBy: actor, updatedAt: serverTimestamp(), auditId: id, ...extra,
  });
  const db = admin();
  await assertSucceeds(audited(db, "boss", "announcements/a1", "announcement.create", (b, id) => b.set(doc(db, "announcements/a1"), ann("boss", id))));
  const o = owner();
  await assertSucceeds(audited(o, "omar", "announcements/a1", "announcement.update", (b, id) =>
    b.update(doc(o, "announcements/a1"), { active: false, title: "Edited", updatedBy: "omar", updatedAt: serverTimestamp(), auditId: id })));
  assert.equal((await read("announcements/a1")).title, "Edited");
  await assertFails(audited(o, "omar", "announcements/a1", "announcement.update", (b, id) =>
    b.update(doc(o, "announcements/a1"), { createdBy: "omar", updatedBy: "omar", updatedAt: serverTimestamp(), auditId: id })));
  await assertFails(audited(db, "boss", "announcements/a2", "announcement.create", (b, id) => b.set(doc(db, "announcements/a2"), ann("boss", id, { targetTier: "gold" }))));
  await assertFails(audited(db, "boss", "announcements/a2", "announcement.create", (b, id) => b.set(doc(db, "announcements/a2"), ann("boss", id, { locale: "fr" }))));
  await assertFails(audited(db, "boss", "announcements/a2", "announcement.create", (b, id) => b.set(doc(db, "announcements/a2"), ann("boss", id, { title: "" }))));
  await assertFails(audited(db, "boss", "announcements/a2", "announcement.create", (b, id) => b.set(doc(db, "announcements/a2"), ann("boss", id, { createdBy: "omar" }))));
  const m = mod();
  await assertFails(audited(m, "mo", "announcements/a3", "announcement.create", (b, id) => b.set(doc(m, "announcements/a3"), ann("mo", id))));
  await assertFails(deleteDoc(doc(o, "announcements/a1")));
  await assertSucceeds(getDoc(doc(user("anyone"), "announcements/a1")));
});

test("reports inbox: any staff role handles reports, the report itself stays", async () => {
  await seed("reports/r1", { uid: "alice", type: "bug", message: "Crash on start", status: "open", createdAt: Timestamp.now() });
  const s = support();
  const handle = (db, actor, fields) => audited(db, actor, "reports/r1", "report.update", (b, id) =>
    b.update(doc(db, "reports/r1"), { ...fields, updatedAt: serverTimestamp(), updatedBy: actor, auditId: id }));
  await assertSucceeds(handle(s, "sam", { status: "in_progress", assignee: "sam", note: "Repro on Android 14" }));
  await assertSucceeds(handle(s, "sam", { status: "resolved", reply: "Fixed in 3.0.1", resolvedBy: "sam" }));
  await assertFails(handle(s, "sam", { status: "archived" }));
  await assertFails(handle(s, "sam", { message: "edited" }));
  await assertFails(handle(s, "sam", { uid: "bob" }));
  await assertFails(handle(user("alice"), "alice", { status: "resolved" }));
  await assertFails(deleteDoc(doc(owner(), "reports/r1")));
  assert.equal((await read("reports/r1")).status, "resolved");
});

test("staff roles: only the owner manages them, never owners or themselves", async () => {
  const o = owner();
  const put = (db, actor, uid, role) => audited(db, actor, `admins/${uid}`, "staff.set", (b, id) =>
    b.set(doc(db, `admins/${uid}`), { role, email: `${uid}@x.io`, note: "", updatedAt: serverTimestamp(), updatedBy: actor, auditId: id }));
  await assertSucceeds(put(o, "omar", "sara", "admin"));
  await assertSucceeds(put(o, "omar", "sara", "support"));
  await assertFails(put(o, "omar", "sara", "owner"));
  await assertFails(put(o, "omar", "omar", "admin"));
  await assertFails(put(o, "omar", "sara", "superuser"));
  await assertFails(put(admin(), "boss", "zed", "support"));
  await assertFails(put(user("zed"), "zed", "zed", "admin"));
  await seed("admins/co", { role: "owner" });
  await assertFails(put(o, "omar", "co", "removed"));
  await assertFails(deleteDoc(doc(o, "admins/sara")));
  // An owner known only by the admins doc (Spark, no custom claims) can manage roles too.
  await seed("admins/root", { role: "owner" });
  const r = user("root");
  await assertSucceeds(put(r, "root", "sara", "moderator"));
  // "removed" switches the role off.
  await assertSucceeds(put(o, "omar", "sara", "removed"));
  await assertFails(getDocs(collection(user("sara"), "codes")));
  await assertSucceeds(getDocs(collection(support(), "admins")));
  await assertFails(getDocs(collection(user("zed"), "admins")));
});

test("audit log: staff append only, known actions, admins read", async () => {
  const s = support();
  const entry = (actor, action, extra = {}) => ({ action, actorUid: actor, target: "reports/r1", details: {}, at: serverTimestamp(), source: "test", ...extra });
  await assertSucceeds(setDoc(doc(s, "auditLog/x1"), entry("sam", "report.update")));
  await assertSucceeds(setDoc(doc(s, "auditLog/x2"), entry("sam", "user.sync_reset")));
  await assertFails(setDoc(doc(s, "auditLog/x3"), entry("sam", "drop.tables")));
  await assertFails(setDoc(doc(s, "auditLog/x4"), entry("boss", "report.update")));
  await assertFails(setDoc(doc(s, "auditLog/x5"), entry("sam", "report.update", { at: Timestamp.fromMillis(0) })));
  await assertFails(setDoc(doc(s, "auditLog/x6"), entry("sam", "report.update", { target: "x".repeat(201) })));
  await assertFails(setDoc(doc(user("alice"), "auditLog/x7"), entry("alice", "report.update")));
  await assertFails(updateDoc(doc(admin(), "auditLog/x1"), { action: "report.view" }));
  await assertFails(deleteDoc(doc(owner(), "auditLog/x1")));
  await assertFails(getDocs(collection(s, "auditLog")));
  await assertSucceeds(getDocs(collection(admin(), "auditLog")));
});
