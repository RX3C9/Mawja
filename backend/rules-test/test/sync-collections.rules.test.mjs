// SPDX-License-Identifier: GPL-3.0-or-later
// Firestore rules for cloud sync part 2 (docs/cloud-sync.md): payloads of playlists, playlist
// entries (plitems), liked, history and settings; full "delete my cloud data"; commit sizes.
import { after, before, beforeEach, test } from "node:test";
import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import {
  Timestamp, collection, deleteDoc, doc, getDocs, limit, orderBy, query, serverTimestamp, setDoc, where, writeBatch,
} from "firebase/firestore";

const DAY = 86_400_000;
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-mawja-sync2",
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
const rec = (c, k, d, over = {}) => ({ c, k, m: Date.now(), dev: "dev-1", del: false, v: 1, at: serverTimestamp(), d, ...over });
const at = (c, k, uid = "alice") => `users/${uid}/syncData/${c}_${k}`;
const put = (db, c, k, d, over) => setDoc(doc(db, at(c, k)), rec(c, k, d, over));

const PL = "0123456789abcdef0123456789abcdef";
const VID = "dQw4w9WgXcQ";
const playlist = { t: "Road trip", ds: "For the car", mu: true, uc: true, or: "local", ca: 1_700_000_000_000 };
const entry = (over = {}) => ({
  p: PL, v: VID, o: "V", t: "Song", ch: "Artist", ci: "UCabc", th: "https://i.ytimg.com/vi/x/hq.jpg", du: 212, mu: true, aa: 1_700_000_000_000,
  ...over,
});
const liked = { t: "A video", ch: "Channel", th: "https://i.ytimg.com/vi/x/hq.jpg", mu: false, ci: "UCabc", du: 600 };
const history = { t: "A video", ch: "Channel", ci: "UCabc", th: "https://i.ytimg.com/vi/x/hq.jpg", pr: 0.42, du: 600, mu: false, sh: false };

test("sync2: valid playlist, entry, liked, history and settings docs are accepted", async () => {
  await plus();
  const db = user("alice");
  await assertSucceeds(put(db, "playlists", PL, playlist));
  await assertSucceeds(put(db, "playlists", "reserved:watch-later", { t: "Watch later" }));
  await assertSucceeds(put(db, "playlists", "PLyt123", { t: "Saved", uc: false, or: "youtube", y: "PLyt123" }));
  await assertSucceeds(put(db, "plitems", `${PL}.${VID}`, entry()));
  await assertSucceeds(put(db, "plitems", `reserved:watch-later.${VID}`, entry({ p: "reserved:watch-later", o: "0V" })));
  await assertSucceeds(put(db, "plitems", `${PL}.${VID}`, {}, { del: true }));
  await assertSucceeds(put(db, "liked", VID, liked));
  await assertSucceeds(put(db, "liked", VID, { t: "" }));
  await assertSucceeds(put(db, "history", VID, history));
  await assertSucceeds(put(db, "history", VID, { pr: 1 }));
  await assertSucceeds(put(db, "history", VID, { pr: 0 }));
  await assertSucceeds(put(db, "settings", "autoplay", { val: true }));
  await assertSucceeds(put(db, "settings", "playback_speed", { val: 1.25 }));
  await assertSucceeds(put(db, "settings", "default_quality_wifi", { val: "1080p" }));
  await assertSucceeds(put(db, "settings", "equalizer", { val: "{\"enabled\":true}" }));
  await assertSucceeds(put(db, "settings", "autoplay", {}, { del: true }));
});

test("sync2: bad payloads are refused", async () => {
  await plus();
  const db = user("alice");
  const bad = [
    ["playlists", PL, { ds: "no title" }],
    ["playlists", PL, { ...playlist, t: "x".repeat(201) }],
    ["playlists", PL, { ...playlist, ds: "x".repeat(1001) }],
    ["playlists", PL, { ...playlist, or: "spotify" }],
    ["playlists", PL, { ...playlist, items: [VID] }],
    ["playlists", PL, { ...playlist, ca: -1 }],
    ["playlists", PL, { ...playlist, y: "bad id/with slash" }],
    ["plitems", `${PL}.${VID}`, entry({ v: "other" })], // k != p + '.' + v
    ["plitems", `${PL}.${VID}`, entry({ p: "other" })],
    ["plitems", `${PL}.${VID}`, (({ o, ...rest }) => rest)(entry())], // no order key
    ["plitems", `${PL}.${VID}`, entry({ o: "V0" })], // trailing zero
    ["plitems", `${PL}.${VID}`, entry({ o: "a-b" })],
    ["plitems", `${PL}.${VID}`, entry({ o: "x".repeat(65) })],
    ["plitems", `${PL}.${VID}`, entry({ t: "x".repeat(301) })],
    ["plitems", `${PL}.${VID}`, entry({ du: "212" })],
    ["plitems", `${PL}.${VID}`, entry({ extra: 1 })],
    ["liked", VID, { ch: "no title" }],
    ["liked", VID, { ...liked, th: "x".repeat(1001) }],
    ["liked", VID, { ...liked, du: -5 }],
    ["liked", VID, { ...liked, position: 1 }],
    ["history", VID, { ...history, pr: 1.5 }],
    ["history", VID, { ...history, pr: -0.1 }],
    ["history", VID, { ...history, pr: "0.5" }],
    ["history", VID, { ...history, path: "/sdcard/x.mp4" }],
    ["settings", "app_language", { val: "ar" }], // never synced
    ["settings", "proxy_password", { val: "secret" }],
    ["settings", "autoplay", { val: true, extra: 1 }],
    ["settings", "autoplay", {}],
    ["settings", "autoplay", { val: ["a"] }],
    ["settings", "equalizer", { val: "x".repeat(4001) }],
  ];
  for (const [c, k, d] of bad) await assertFails(put(db, c, k, d));
});

test("sync2: a 400-write commit of entries passes (the app's commit size)", async () => {
  await plus();
  const db = user("alice");
  const b = writeBatch(db);
  for (let i = 0; i < 400; i++) {
    const v = `v${String(i).padStart(9, "0")}x`;
    b.set(doc(db, at("plitems", `${PL}.${v}`)), rec("plitems", `${PL}.${v}`, entry({ v, o: `V${i + 1}z` })));
  }
  await assertSucceeds(b.commit());
});

test("sync2: the app's queries work for the owner only", async () => {
  await plus();
  const db = user("alice");
  await put(db, "liked", VID, liked);
  // One-off full pull of a collection, paged on the document name.
  await assertSucceeds(getDocs(query(collection(db, "users/alice/syncData"), where("c", "==", "liked"), orderBy("__name__"), limit(300))));
  // Tombstone clean-up.
  await assertSucceeds(getDocs(query(collection(db, "users/alice/syncData"), where("del", "==", true), limit(300))));
  await assertFails(getDocs(query(collection(user("bob", { tier: "pro" }), "users/alice/syncData"), where("del", "==", true))));
});

test("sync2: delete my cloud data removes every own doc in batches", async () => {
  await plus();
  for (let i = 0; i < 30; i++) await seed(at("liked", `v${i}`), { ...rec("liked", `v${i}`, liked), at: Timestamp.now() });
  await seed(at("playlists", PL), { ...rec("playlists", PL, playlist), at: Timestamp.now() });
  const db = user("alice");
  const page = await getDocs(query(collection(db, "users/alice/syncData"), orderBy("__name__"), limit(300)));
  const b = writeBatch(db);
  page.docs.forEach((d) => b.delete(d.ref));
  await assertSucceeds(b.commit());
  const left = await env.withSecurityRulesDisabled(async (ctx) => getDocs(collection(ctx.firestore(), "users/alice/syncData")));
  if (left.size !== 0) throw new Error(`expected no docs left, found ${left.size}`);
  // Another user cannot wipe Alice's data.
  await seed(at("liked", VID), { ...rec("liked", VID, liked), at: Timestamp.now() });
  await assertFails(deleteDoc(doc(user("bob", { tier: "pro" }), at("liked", VID))));
});
