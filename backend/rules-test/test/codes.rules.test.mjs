// SPDX-License-Identifier: GPL-3.0-or-later
// Firestore rules for membership codes (docs/codes.md). Run: npm test (starts the emulator; needs Java 21).
import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Timestamp, collection, doc, getDoc, getDocs, query, runTransaction, serverTimestamp, setDoc, updateDoc, where, writeBatch,
} from "firebase/firestore";

const DAY = 86_400_000;
const RANK = { free: 0, plus: 1, pro: 2, family: 3, lifetime: 4 };
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-mawja",
    firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
  });
});
after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());

const hashOf = (canonical) => createHash("sha256").update("mawja-code-v1:" + canonical).digest("hex");
const H1 = hashOf("ABCDEFGHJKM4");
const H2 = hashOf("222222222222");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const user = (uid, claims) => env.authenticatedContext(uid, claims).firestore();

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
const codeDoc = (over = {}) => ({
  tier: "plus", days: 30, maxUses: 1, used: 0, expiresAt: null, revoked: false, createdBy: "boss",
  createdAt: Timestamp.now(), batchId: "b1", note: "", hint: "JKM4", ...over,
});

/** The app's throttle step: announce the code hash before reading it. */
async function attempt(db, uid, hash) {
  const ref = doc(db, `users/${uid}/meta/redeem`);
  const snap = await getDoc(ref);
  const prev = snap.exists() ? snap.data() : null;
  const fresh = !prev || prev.windowStart.toMillis() < Date.now() - 3_600_000;
  return setDoc(ref, fresh
    ? { hash, lastAttempt: serverTimestamp(), windowStart: serverTimestamp(), count: 1 }
    : { hash, lastAttempt: serverTimestamp(), windowStart: prev.windowStart, count: prev.count + 1 });
}

/** Mirrors the app's redeem transaction (CodeRedeemPolicy). [tamper] edits what gets written. */
function redeem(db, uid, hash, tamper = (w) => w) {
  return runTransaction(db, async (tx) => {
    const cref = doc(db, "codes", hash);
    const uref = doc(db, "users", uid);
    const rref = doc(db, "redemptions", `${hash}_${uid}`);
    const c = await tx.get(cref);
    const u = await tx.get(uref);
    await tx.get(rref);
    if (!c.exists()) throw new Error("code missing");
    const cd = c.data();
    const old = u.exists() ? u.data() : {};
    const now = Date.now();
    const active = ["plus", "pro", "family", "lifetime"].includes(old.tier) && (old.tierUntil == null || old.tierUntil.toMillis() > now);
    const cur = active ? old.tier : "free";
    let tier;
    let until;
    if (cd.tier === "lifetime") { tier = "lifetime"; until = null; }
    else if (active && old.tierUntil == null) { tier = cd.tier; until = now + cd.days * DAY; }
    else { tier = RANK[cur] > RANK[cd.tier] ? cur : cd.tier; until = (active ? old.tierUntil.toMillis() : now) + cd.days * DAY; }
    const w = tamper({ tier, tierUntil: until == null ? null : Timestamp.fromMillis(until), used: cd.used + 1, previousTier: cur });
    tx.update(cref, { used: w.used });
    tx.set(rref, {
      uid, codeHash: hash, codeTier: cd.tier, days: cd.days, tier: w.tier, tierUntil: w.tierUntil, batchId: cd.batchId,
      previousTier: w.previousTier, createdAt: serverTimestamp(),
    });
    tx.set(uref, { tier: w.tier, tierUntil: w.tierUntil, tierSource: "code", tierCodeHash: hash, tierUpdatedAt: serverTimestamp() }, { merge: true });
  });
}

test("users cannot list codes or read one without a fresh attempt", async () => {
  await seed(`codes/${H1}`, codeDoc());
  const db = user("alice");
  await assertFails(getDocs(collection(db, "codes")));
  await assertFails(getDocs(query(collection(db, "codes"), where("tier", "==", "plus"))));
  await assertFails(getDoc(doc(db, "codes", H1)));
  await assertSucceeds(attempt(db, "alice", H1));
  await assertSucceeds(getDoc(doc(db, "codes", H1)));
  await assertFails(getDoc(doc(db, "codes", H2)));
  await assertFails(getDoc(doc(user("bob"), "codes", H1)));
});

test("unknown code reads as missing after an attempt", async () => {
  const db = user("alice");
  await assertSucceeds(attempt(db, "alice", H2));
  const snap = await assertSucceeds(getDoc(doc(db, "codes", H2)));
  assert.equal(snap.exists(), false);
});

test("redeeming a Plus code creates the membership", async () => {
  await seed(`codes/${H1}`, codeDoc());
  const db = user("alice");
  await assertSucceeds(attempt(db, "alice", H1));
  await assertSucceeds(redeem(db, "alice", H1));
  const u = await read("users/alice");
  assert.equal(u.tier, "plus");
  assert.ok(Math.abs(u.tierUntil.toMillis() - (Date.now() + 30 * DAY)) < 60_000);
  assert.equal((await read(`codes/${H1}`)).used, 1);
  assert.equal((await read(`redemptions/${H1}_alice`)).uid, "alice");
  // Her own redemption is readable to her, not to others.
  await assertSucceeds(getDoc(doc(db, "redemptions", `${H1}_alice`)));
  await assertFails(getDoc(doc(user("bob"), "redemptions", `${H1}_alice`)));
  await assertSucceeds(getDocs(collection(user("sam", { role: "support" }), "redemptions")));
  await assertFails(getDocs(collection(user("bob"), "redemptions")));
});

test("a single-use code cannot be used twice", async () => {
  await seed(`codes/${H1}`, codeDoc());
  const a = user("alice");
  await attempt(a, "alice", H1);
  await assertSucceeds(redeem(a, "alice", H1));
  const b = user("bob");
  await attempt(b, "bob", H1);
  await assertFails(redeem(b, "bob", H1));
  assert.equal(await read("users/bob"), null);
});

test("a multi-use code works once per person", async () => {
  await seed(`codes/${H1}`, codeDoc({ maxUses: 5 }));
  const a = user("alice");
  await attempt(a, "alice", H1);
  await assertSucceeds(redeem(a, "alice", H1));
  await sleep(2_100);
  await assertSucceeds(attempt(a, "alice", H1));
  await assertFails(redeem(a, "alice", H1));
  const b = user("bob");
  await attempt(b, "bob", H1);
  await assertSucceeds(redeem(b, "bob", H1));
  assert.equal((await read(`codes/${H1}`)).used, 2);
});

test("revoked and expired codes are refused", async () => {
  await seed(`codes/${H1}`, codeDoc({ revoked: true }));
  await seed(`codes/${H2}`, codeDoc({ expiresAt: Timestamp.fromMillis(Date.now() - 1_000) }));
  const a = user("alice");
  await attempt(a, "alice", H1);
  await assertFails(redeem(a, "alice", H1));
  const b = user("bob");
  await attempt(b, "bob", H2);
  await assertFails(redeem(b, "bob", H2));
});

test("tampered writes are refused", async () => {
  await seed(`codes/${H1}`, codeDoc({ maxUses: 10 }));
  const cases = [
    (w) => ({ ...w, tier: "pro" }),
    (w) => ({ ...w, tier: "lifetime", tierUntil: null }),
    (w) => ({ ...w, tierUntil: Timestamp.fromMillis(w.tierUntil.toMillis() + 30 * DAY) }),
    (w) => ({ ...w, used: w.used + 2 }),
    (w) => ({ ...w, used: w.used - 1 }),
  ];
  for (const [i, tamper] of cases.entries()) {
    const uid = `t${i}`;
    const db = user(uid);
    await attempt(db, uid, H1);
    await assertFails(redeem(db, uid, H1, tamper));
  }
  // Without an attempt for this code the redemption is refused even if the code was read earlier.
  const db = user("zed");
  await attempt(db, "zed", H2);
  await assertFails(redeem(db, "zed", H1));
  assert.equal((await read(`codes/${H1}`)).used, 0);
});

test("users cannot set their own tier without redeeming", async () => {
  await seed("users/alice", { displayName: "A", tier: "free", status: "active" });
  await seed(`codes/${H1}`, codeDoc());
  const db = user("alice");
  await assertFails(updateDoc(doc(db, "users/alice"), { tier: "pro" }));
  await assertFails(setDoc(doc(db, "users/alice"), {
    tier: "plus", tierUntil: Timestamp.fromMillis(Date.now() + 30 * DAY), tierSource: "code", tierCodeHash: H1, tierUpdatedAt: serverTimestamp(),
  }, { merge: true }));
  await assertFails(updateDoc(doc(db, "users/alice"), { role: "owner" }));
  await assertSucceeds(updateDoc(doc(db, "users/alice"), { displayName: "Alice" }));
  await assertFails(updateDoc(doc(user("bob"), "users/alice"), { displayName: "x" }));
});

test("days stack and a better active tier is kept", async () => {
  const until = Date.now() + 10 * DAY;
  await seed("users/alice", { tier: "pro", tierUntil: Timestamp.fromMillis(until), status: "active" });
  await seed(`codes/${H1}`, codeDoc());
  const db = user("alice");
  await attempt(db, "alice", H1);
  await assertSucceeds(redeem(db, "alice", H1));
  const u = await read("users/alice");
  assert.equal(u.tier, "pro");
  assert.equal(u.tierUntil.toMillis(), until + 30 * DAY);
  // Downgrading the tier instead is refused.
  await seed(`codes/${H2}`, codeDoc({ tier: "plus" }));
  await seed("users/bob", { tier: "pro", tierUntil: Timestamp.fromMillis(until), status: "active" });
  const b = user("bob");
  await attempt(b, "bob", H2);
  await assertFails(redeem(b, "bob", H2, (w) => ({ ...w, tier: "plus" })));
});

test("lifetime codes and lifetime members", async () => {
  await seed(`codes/${H1}`, codeDoc({ tier: "lifetime", days: 0 }));
  const a = user("alice");
  await attempt(a, "alice", H1);
  await assertSucceeds(redeem(a, "alice", H1));
  const u = await read("users/alice");
  assert.equal(u.tier, "lifetime");
  assert.equal(u.tierUntil, null);
  await seed(`codes/${H2}`, codeDoc({ tier: "pro", days: 30 }));
  await sleep(2_100);
  await attempt(a, "alice", H2);
  await assertFails(redeem(a, "alice", H2));
  await assertFails(redeem(a, "alice", H2, (w) => ({ ...w, tier: "lifetime", tierUntil: null })));
});

test("banned or suspended accounts cannot redeem", async () => {
  await seed(`codes/${H1}`, codeDoc({ maxUses: 3 }));
  await seed("users/alice", { status: "suspended", tier: "free" });
  const a = user("alice");
  await attempt(a, "alice", H1);
  await assertFails(redeem(a, "alice", H1));
  const b = user("bob", { status: "banned" });
  await assertFails(attempt(b, "bob", H1));
});

test("redeem attempts are throttled", async () => {
  const db = user("alice");
  await assertSucceeds(attempt(db, "alice", H1));
  await assertFails(attempt(db, "alice", H2)); // < 2 s later
  const recent = Timestamp.fromMillis(Date.now() - 10_000);
  await seed("users/alice/meta/redeem", { hash: H1, lastAttempt: recent, windowStart: Timestamp.fromMillis(Date.now() - 600_000), count: 12 });
  await assertFails(attempt(db, "alice", H2));
  await seed("users/alice/meta/redeem", { hash: H1, lastAttempt: recent, windowStart: Timestamp.fromMillis(Date.now() - 600_000), count: 11 });
  await assertSucceeds(attempt(db, "alice", H2));
  await seed("users/alice/meta/redeem", { hash: H1, lastAttempt: recent, windowStart: Timestamp.fromMillis(Date.now() - 2 * 3_600_000), count: 12 });
  await assertSucceeds(attempt(db, "alice", H2));
  // Counters cannot be reset by hand, and nobody writes another user's throttle.
  await seed("users/alice/meta/redeem", { hash: H1, lastAttempt: recent, windowStart: Timestamp.fromMillis(Date.now() - 600_000), count: 12 });
  await assertFails(setDoc(doc(db, "users/alice/meta/redeem"), { hash: H2, lastAttempt: serverTimestamp(), windowStart: serverTimestamp(), count: 1 }));
  await assertFails(setDoc(doc(user("bob"), "users/alice/meta/redeem"), { hash: H2, lastAttempt: serverTimestamp(), windowStart: serverTimestamp(), count: 1 }));
});

function newCode(uid, over = {}) {
  return { ...codeDoc({ createdBy: uid, createdAt: serverTimestamp(), ...over }) };
}

test("admins generate, list and revoke codes; others cannot", async () => {
  const admin = user("boss", { role: "admin" });
  const batch = writeBatch(admin);
  batch.set(doc(admin, "codeBatches", "b1"), {
    tier: "plus", days: 30, count: 2, maxUses: 1, expiresAt: null, note: "launch", createdBy: "boss", createdAt: serverTimestamp(), revoked: false,
  });
  batch.set(doc(admin, "codes", H1), newCode("boss"));
  batch.set(doc(admin, "codes", H2), newCode("boss", { expiresAt: Timestamp.fromMillis(Date.now() + 90 * DAY) }));
  batch.set(doc(admin, "auditLog", "a1"), { action: "code.batch.create", actorUid: "boss", target: "b1", details: { count: 2 }, at: serverTimestamp(), source: "test" });
  await assertSucceeds(batch.commit());
  const listed = await assertSucceeds(getDocs(query(collection(admin, "codes"), where("batchId", "==", "b1"))));
  assert.equal(listed.size, 2);
  await assertSucceeds(updateDoc(doc(admin, "codes", H1), { revoked: true, revokedAt: serverTimestamp(), revokedBy: "boss" }));
  await assertFails(updateDoc(doc(admin, "codes", H2), { used: 0, maxUses: 99 }));
  await assertSucceeds(updateDoc(doc(admin, "codeBatches", "b1"), { revoked: true, revokedAt: serverTimestamp(), revokedBy: "boss" }));

  // Plain text ids, pre-used codes and bad tiers are refused.
  await assertFails(setDoc(doc(admin, "codes", "MW-ABCD-EFGH-JKM4"), newCode("boss")));
  await assertFails(setDoc(doc(admin, "codes", hashOf("333333333333")), newCode("boss", { used: 3 })));
  await assertFails(setDoc(doc(admin, "codes", hashOf("444444444444")), newCode("boss", { tier: "gold" })));
  await assertFails(setDoc(doc(admin, "codes", hashOf("555555555555")), newCode("boss", { tier: "lifetime", days: 30 })));
  await assertFails(setDoc(doc(admin, "codes", hashOf("666666666666")), newCode("other")));

  const support = user("sam", { role: "support" });
  await assertSucceeds(getDocs(collection(support, "codes")));
  await assertFails(setDoc(doc(support, "codes", hashOf("777777777777")), newCode("sam")));
  await assertFails(updateDoc(doc(support, "codes", H2), { revoked: true, revokedAt: serverTimestamp(), revokedBy: "sam" }));
  const nobody = user("alice");
  await assertFails(setDoc(doc(nobody, "codes", hashOf("888888888888")), newCode("alice")));
  await assertFails(getDocs(collection(nobody, "codeBatches")));
  await assertFails(setDoc(doc(nobody, "auditLog", "x"), { action: "code.revoke", actorUid: "alice", target: "x", details: {}, at: serverTimestamp(), source: "x" }));
  await assertFails(getDocs(collection(support, "auditLog")));
  await assertSucceeds(getDocs(collection(admin, "auditLog")));
});

test("an admins/{uid} doc grants the role without a custom claim", async () => {
  await seed("admins/carol", { role: "admin" });
  const carol = user("carol");
  await assertSucceeds(getDoc(doc(carol, "admins/carol")));
  await assertFails(getDoc(doc(user("alice"), "admins/carol")));
  await assertSucceeds(setDoc(doc(carol, "codes", H1), newCode("carol")));
  await assertSucceeds(getDocs(collection(carol, "codes")));
  await assertFails(setDoc(doc(carol, "admins/carol"), { role: "owner" }));
});
