// SPDX-License-Identifier: GPL-3.0-or-later
// Bootstrap a staff role (run once for the owner): 
//   GOOGLE_APPLICATION_CREDENTIALS=sa.json node tools/grantRole.js owner@example.com owner
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

async function main() {
  const [email, role] = process.argv.slice(2);
  if (!email || !["owner", "admin", "moderator", "support"].includes(role)) {
    console.error("usage: node tools/grantRole.js <email> <owner|admin|moderator|support>");
    process.exit(1);
  }
  initializeApp();
  const user = await getAuth().getUserByEmail(email);
  await getAuth().setCustomUserClaims(user.uid, { ...(user.customClaims || {}), role });
  await getFirestore().doc(`admins/${user.uid}`).set({ role, addedBy: "cli", addedAt: FieldValue.serverTimestamp() });
  console.log(`${email} is now ${role}`);
}
main().catch((e) => { console.error(e); process.exit(1); });
