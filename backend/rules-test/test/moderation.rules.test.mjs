// SPDX-License-Identifier: GPL-3.0-or-later
// Firestore rules for Mawja 2.0 moderation written by the admin app (Spark plan, no Cloud Functions):
// temporary bans, warnings (3 = ban), mute, feature locks, private message, sign-out-everywhere,
// and the restrictions they put on the account's own writes.
import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { Timestamp, collection, doc, getDoc, serverTimestamp, setDoc, writeBatch } from "firebase/firestore";

const HOUR = 3_600_000;
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-mawja-moderation",
    firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
  });
});
after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());

const user = (uid, claims) => env.authenticatedContext(uid, claims).firestore();
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

function audited(db, actor, uid, action, fields) {
  const batch = writeBatch(db);
  const ref = doc(collection(db, "auditLog"));
  batch.set(ref, { action, actorUid: actor, target: `users/${uid}`, details: {}, at: serverTimestamp(), source: "test" });
  batch.update(doc(db, `users/${uid}`), { ...fields, auditId: ref.id });
  return batch.commit();
}
const moderation = (actor, fields) => ({ ...fields, moderatedAt: serverTimestamp(), moderatedBy: actor });
const status = (actor, st, extra = {}) => ({ status: st, banReason: "", statusUpdatedAt: serverTimestamp(), statusBy: actor, ...extra });

test("temporary ban: a suspension with an end time; only suspensions carry one", async () => {
  await seed("users/alice", { tier: "free", status: "active" });
  const until = Timestamp.fromMillis(Date.now() + 24 * HOUR);
  await assertSucceeds(audited(mod(), "mo", "alice", "user.suspended", status("mo", "suspended", { banUntil: until })));
  assert.equal((await read("users/alice")).banUntil.toMillis(), until.toMillis());
  await assertFails(audited(admin(), "boss", "alice", "user.banned", status("boss", "banned", { banUntil: until })));
  await assertSucceeds(audited(admin(), "boss", "alice", "user.banned", status("boss", "banned", { banUntil: null })));
  await assertFails(audited(mod(), "mo", "alice", "user.suspended", status("mo", "suspended", { banUntil: "tomorrow" })));
});

test("warnings: moderators warn; the third warning lets a moderator ban", async () => {
  await seed("users/alice", { tier: "free", status: "active", warnings: 1 });
  await assertSucceeds(audited(mod(), "mo", "alice", "user.warning", moderation("mo", { warnings: 2 })));
  await assertFails(audited(mod(), "mo", "alice", "user.banned", status("mo", "banned")));
  await assertSucceeds(
    audited(mod(), "mo", "alice", "user.warning", { ...moderation("mo", { warnings: 3 }), ...status("mo", "banned", { banReason: "3 warnings" }) }),
  );
  const a = await read("users/alice");
  assert.equal(a.warnings, 3);
  assert.equal(a.status, "banned");
  await assertFails(audited(mod(), "mo", "alice", "user.warning", moderation("mo", { warnings: 11 })));
  await assertFails(audited(mod(), "mo", "alice", "user.warning", moderation("mo", { warnings: -1 })));
  await assertFails(audited(support(), "sam", "alice", "user.warning", moderation("sam", { warnings: 4 })));
});

test("mute, feature locks, private message and sign-out-everywhere", async () => {
  await seed("users/alice", { tier: "free", status: "active" });
  const soon = Timestamp.fromMillis(Date.now() + HOUR);
  const msg = { id: "m1", text: "Please keep reports respectful.", at: Timestamp.now() };
  await assertSucceeds(
    audited(mod(), "mo", "alice", "user.moderate", moderation("mo", {
      mutedUntil: soon, lockedFeatures: ["reports", "cloud_sync"], adminMessage: msg, signOutAt: Timestamp.now(),
    })),
  );
  await assertFails(audited(mod(), "mo", "alice", "user.moderate", moderation("mo", { lockedFeatures: Array(21).fill("x") })));
  await assertFails(audited(mod(), "mo", "alice", "user.moderate", moderation("mo", { adminMessage: { id: "m2", text: "", at: Timestamp.now() } })));
  await assertFails(audited(mod(), "mo", "alice", "user.moderate", moderation("mo", { adminMessage: { id: "m2", text: "x", at: Timestamp.now(), html: "<b>" } })));
  await assertFails(audited(mod(), "mo", "alice", "user.moderate", { mutedUntil: soon, moderatedAt: Timestamp.fromMillis(0), moderatedBy: "mo" }));
  await assertFails(audited(mod(), "mo", "mo", "user.moderate", moderation("mo", { mutedUntil: soon })));
  await assertSucceeds(audited(mod(), "mo", "alice", "user.moderate", moderation("mo", { adminMessage: null, mutedUntil: null, lockedFeatures: [] })));
});

test("muted, suspended and banned accounts cannot file reports; a lapsed ban or mute can", async () => {
  const report = (uid) => setDoc(doc(user(uid), `reports/${uid}-1`), { uid, type: "bug", message: "hi", status: "open", createdAt: serverTimestamp() });
  await seed("users/muted", { status: "active", mutedUntil: Timestamp.fromMillis(Date.now() + HOUR) });
  await seed("users/susp", { status: "suspended", banUntil: Timestamp.fromMillis(Date.now() + HOUR) });
  await seed("users/forever", { status: "suspended" });
  await seed("users/banned", { status: "banned" });
  await seed("users/lapsed", { status: "suspended", banUntil: Timestamp.fromMillis(Date.now() - HOUR), mutedUntil: Timestamp.fromMillis(Date.now() - HOUR) });
  await seed("users/fine", { status: "active" });
  for (const uid of ["muted", "susp", "forever", "banned"]) await assertFails(report(uid));
  await assertSucceeds(report("lapsed"));
  await assertSucceeds(report("fine"));
  await assertSucceeds(report("nodoc"));
});

test("a 'reports' lock stops new reports; other locks do not", async () => {
  const report = (uid) => setDoc(doc(user(uid), `reports/${uid}-1`), { uid, type: "bug", message: "hi", status: "open", createdAt: serverTimestamp() });
  await seed("users/locked", { status: "active", lockedFeatures: ["reports"] });
  await seed("users/other", { status: "active", lockedFeatures: ["codes", "cloud_sync"] });
  await assertFails(report("locked"));
  await assertSucceeds(report("other"));
});
