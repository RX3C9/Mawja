// SPDX-License-Identifier: GPL-3.0-or-later
// Firestore rules for cloud sync (docs/cloud-sync.md): users/{uid}/syncData/{c}_{k}, owner-only,
// paid tier, validated item shape. Run: npm test (starts the emulator; needs Java 21).
import { after, before, beforeEach, test } from "node:test";
import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Timestamp, collection, deleteDoc, doc, getDoc, getDocs, orderBy, query, serverTimestamp, setDoc, where, writeBatch,
} from "firebase/firestore";

const DAY = 86_400_000;
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-mawja-sync",
    firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
  });
});
after(async () => env?.cleanup());
beforeEach(async () => env.clearFirestore());

const user = (uid, claims) => env.authenticatedContext(uid, claims).firestore();
async function seed(path, data) {
  await env.withSecurityRulesDisabled(async (ctx) => setDoc(doc(ctx.firestore(), path), data));
}
const plus = () => seed("users/alice", { tier: "plus", tierUntil: Timestamp.fromMillis(Date.now() + 30 * DAY), status: "active" });
const CH = "UCabc_DEF-123";
const item = (over = {}) => ({
  c: "subscriptions", k: CH, m: Date.now(), dev: "dev-1", del: false, v: 1, at: serverTimestamp(),
  d: { n: "Channel", a: "https://yt3.ggpht.com/x", mu: false }, ...over,
});
const path = (uid = "alice", id = `subscriptions_${CH}`) => `users/${uid}/syncData/${id}`;
const pull = (db, uid = "alice") =>
  getDocs(query(collection(db, `users/${uid}/syncData`), where("at", ">=", Timestamp.fromMillis(0)), orderBy("at")));

test("sync: a Plus owner writes, reads and pulls their items", async () => {
  await plus();
  const db = user("alice");
  await assertSucceeds(setDoc(doc(db, path()), item()));
  await assertSucceeds(getDoc(doc(db, path())));
  await assertSucceeds(pull(db));
  // Tombstone update (unsubscribe) clears the payload.
  await assertSucceeds(setDoc(doc(db, path()), item({ del: true, d: {}, m: Date.now() + 1000 })));
  // Batched push, as the app commits them.
  const b = writeBatch(db);
  for (let i = 0; i < 20; i++) b.set(doc(db, path("alice", `subscriptions_UC${i}`)), item({ k: `UC${i}` }));
  await assertSucceeds(b.commit());
});

test("sync: a tier claim is enough, without a users doc", async () => {
  const db = user("alice", { tier: "pro" });
  await assertSucceeds(setDoc(doc(db, path()), item()));
  await assertSucceeds(pull(db));
});

test("sync: Free, expired, banned and other users are refused", async () => {
  await seed("users/alice", { tier: "free", status: "active" });
  await assertFails(setDoc(doc(user("alice"), path()), item()));
  await assertFails(pull(user("alice")));
  await seed("users/alice", { tier: "plus", tierUntil: Timestamp.fromMillis(Date.now() - DAY), status: "active" });
  await assertFails(setDoc(doc(user("alice"), path()), item()));
  await plus();
  await assertFails(setDoc(doc(user("alice", { status: "banned" }), path()), item()));
  await assertFails(setDoc(doc(user("bob", { tier: "pro" }), path()), item()));
  await assertFails(pull(user("bob", { tier: "pro" })));
  await seed(path(), { ...item(), at: Timestamp.now() });
  await assertFails(getDoc(doc(user("bob", { tier: "pro" }), path())));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), path())));
});

test("sync: item shape is validated", async () => {
  await plus();
  const db = user("alice");
  const bad = [
    [path("alice", `subscriptions_UCother`), item()], // doc id != c_k
    [path(), item({ extra: 1 })],
    [path(), (({ dev, ...rest }) => rest)(item())], // missing key
    [path(), item({ at: Timestamp.fromMillis(Date.now() - 60_000) })], // client time instead of server time
    [path(), item({ m: Date.now() + 3 * DAY })], // far-future clock would win forever
    [path(), item({ m: 0 })],
    [path(), item({ m: "1" })],
    [path(), item({ del: true })], // tombstone with a payload
    [path(), item({ v: 0 })],
    [path(), item({ dev: "" })],
    [path(), item({ d: { n: "x".repeat(301) } })],
    [path(), item({ d: { n: "ok", a: "x".repeat(1001) } })],
    [path(), item({ d: { a: "no name" } })],
    [path(), item({ d: { n: "ok", bell: true } })],
    [path("alice", "subscriptions_UC bad"), item({ k: "UC bad" })],
    [path("alice", "playlists_PL1"), item({ c: "playlists", k: "PL1" })], // not synced yet
    [path("alice", "music_X"), item({ c: "music", k: "X" })],
  ];
  for (const [p, data] of bad) await assertFails(setDoc(doc(db, p), data));
  await assertSucceeds(setDoc(doc(db, path()), item({ d: { n: "Name only" } })));
  await assertSucceeds(setDoc(doc(db, path("alice", "playlists_PL1")), item({ c: "playlists", k: "PL1", del: true, d: {} })));
});

test("sync: owners delete only tombstones", async () => {
  await plus();
  const db = user("alice");
  await seed(path(), { ...item(), at: Timestamp.now() });
  await assertFails(deleteDoc(doc(db, path())));
  await seed(path(), { ...item({ del: true, d: {} }), at: Timestamp.now() });
  await assertSucceeds(deleteDoc(doc(db, path())));
  await seed(path(), { ...item({ del: true, d: {} }), at: Timestamp.now() });
  await assertFails(deleteDoc(doc(user("bob", { tier: "pro" }), path())));
});
