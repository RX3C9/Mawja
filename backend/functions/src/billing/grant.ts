// SPDX-License-Identifier: GPL-3.0-or-later
import type { Firestore } from "firebase-admin/firestore";
import { FieldValue } from "firebase-admin/firestore";
import { applyPurchase, featuresFor, MembershipUpdate } from "./entitlements";
import type { Plan } from "./plans";

export interface PaymentRecord {
  provider: "paymob" | "stripe";
  providerPaymentId: string;
  uid: string;
  plan: Plan;
  amount: number;
  currency: string;
  sandbox: boolean;
}

/** payments/{provider}_{id}: the doc id that makes webhook retries idempotent. */
export function paymentDocId(provider: string, id: string): string {
  return `${provider}_${id.replace(/[^A-Za-z0-9_-]/g, "")}`;
}

/** Records a paid payment once and writes the new tier + entitlements to users/{uid}. Returns null if already processed. */
export async function grantPurchase(db: Firestore, p: PaymentRecord, now: number): Promise<MembershipUpdate | null> {
  const payRef = db.doc(`payments/${paymentDocId(p.provider, p.providerPaymentId)}`);
  const userRef = db.doc(`users/${p.uid}`);
  return db.runTransaction(async (tx) => {
    if ((await tx.get(payRef)).exists) return null;
    const user = await tx.get(userRef);
    if (!user.exists) throw new Error(`unknown user ${p.uid}`);
    const update = applyPurchase({ tier: user.get("tier"), tierExpiresAt: user.get("tierExpiresAt") }, p.plan, now);
    tx.create(payRef, {
      uid: p.uid, provider: p.provider, providerPaymentId: p.providerPaymentId, planId: p.plan.id,
      amount: p.amount, currency: p.currency, sandbox: p.sandbox, status: "paid",
      tierAfter: update.tier, tierExpiresAt: update.tierExpiresAt, at: FieldValue.serverTimestamp(),
    });
    tx.set(userRef, { ...update, entitlements: featuresFor(update.tier), tierUpdatedAt: now }, { merge: true });
    return update;
  });
}
