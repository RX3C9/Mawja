// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import { test } from "node:test";
import { paymobReady, readBillingEnv, stripeReady } from "../src/billing/config";
import { applyPurchase, featuresFor } from "../src/billing/entitlements";
import { paymentDocId } from "../src/billing/grant";
import { buildPaymobIntention, isPaymobTestKey, parsePaymobReference, paymobCheckoutUrl, paymobHmac, paymobHmacString, verifyPaymobHmac } from "../src/billing/paymob";
import { findPlan, PLANS, priceOf, providerFor } from "../src/billing/plans";
import { buildStripeSession, isStripeTestKey, stripeSignatureHeader, verifyStripeSignature } from "../src/billing/stripe";

const DAY = 86_400_000;
const NOW = 1_800_000_000_000;
const plan = (id: string) => findPlan(id)!;

test("catalogue matches PLAN_V2 prices", () => {
  const egp = Object.fromEntries(PLANS.map((p) => [p.id, p.egp / 100]));
  assert.deepEqual(egp, { plus_monthly: 49, plus_yearly: 399, pro_monthly: 99, pro_yearly: 799, lifetime: 1999, family_monthly: 149, family_yearly: 1199 });
  assert.equal(priceOf(plan("pro_yearly"), "USD"), 2999);
  assert.equal(findPlan("nope"), undefined);
  assert.equal(findPlan(42), undefined);
  assert.deepEqual(providerFor("eg"), { provider: "paymob", currency: "EGP" });
  assert.deepEqual(providerFor("SA"), { provider: "stripe", currency: "USD" });
  assert.deepEqual(providerFor(undefined), { provider: "stripe", currency: "USD" });
});

test("purchase from free starts now", () => {
  const u = applyPurchase({ tier: "free" }, plan("plus_monthly"), NOW);
  assert.equal(u.tier, "plus");
  assert.equal(u.tierExpiresAt, NOW + 31 * DAY);
  assert.equal(u.tierPlan, "plus_monthly");
});

test("renewal stacks on remaining time; expired starts fresh", () => {
  const active = applyPurchase({ tier: "plus", tierExpiresAt: NOW + 10 * DAY }, plan("plus_monthly"), NOW);
  assert.equal(active.tierExpiresAt, NOW + 41 * DAY);
  const expired = applyPurchase({ tier: "pro", tierExpiresAt: NOW - DAY }, plan("plus_yearly"), NOW);
  assert.deepEqual([expired.tier, expired.tierExpiresAt], ["plus", NOW + 366 * DAY]);
});

test("never downgrades an active better tier; upgrade carries time", () => {
  const keep = applyPurchase({ tier: "pro", tierExpiresAt: NOW + 5 * DAY }, plan("plus_monthly"), NOW);
  assert.deepEqual([keep.tier, keep.tierExpiresAt], ["pro", NOW + 36 * DAY]);
  const up = applyPurchase({ tier: "plus", tierExpiresAt: NOW + 5 * DAY }, plan("pro_monthly"), NOW);
  assert.deepEqual([up.tier, up.tierExpiresAt], ["pro", NOW + 36 * DAY]);
  const openPro = applyPurchase({ tier: "pro", tierExpiresAt: null }, plan("plus_monthly"), NOW);
  assert.deepEqual([openPro.tier, openPro.tierExpiresAt], ["pro", null]);
  const openPlus = applyPurchase({ tier: "plus", tierExpiresAt: null }, plan("family_monthly"), NOW);
  assert.deepEqual([openPlus.tier, openPlus.tierExpiresAt], ["family", NOW + 31 * DAY]);
});

test("lifetime is forever and sticks", () => {
  const l = applyPurchase({ tier: "plus", tierExpiresAt: NOW + DAY }, plan("lifetime"), NOW);
  assert.deepEqual([l.tier, l.tierExpiresAt], ["lifetime", null]);
  const after = applyPurchase({ tier: "lifetime", tierExpiresAt: null }, plan("family_yearly"), NOW);
  assert.deepEqual([after.tier, after.tierExpiresAt], ["lifetime", null]);
  assert.deepEqual(featuresFor("family"), ["premium_themes", "premium_icons", "ui_sounds", "visualizer", "family_sharing"]);
  assert.deepEqual(featuresFor("free"), []);
});

const txn = {
  amount_cents: 4900, created_at: "2026-10-20T10:00:00", currency: "EGP", error_occured: false, has_parent_transaction: false,
  id: 192036465, integration_id: 4097558, is_3d_secure: true, is_auth: false, is_capture: false, is_refunded: false,
  is_standalone_payment: true, is_voided: false, order: { id: 217503754, merchant_order_id: "uid123:plus_monthly:1" },
  owner: 302852, pending: false, source_data: { pan: "2346", sub_type: "MasterCard", type: "card" }, success: true,
};

test("paymob hmac: field order, verification, tamper detection", () => {
  assert.equal(paymobHmacString(txn), "49002026-10-20T10:00:00EGPfalsefalse1920364654097558truefalsefalsefalsetruefalse217503754302852false2346MasterCardcardtrue");
  const sig = paymobHmac(txn, "s3cret");
  assert.equal(sig.length, 128);
  assert.ok(verifyPaymobHmac(txn, sig, "s3cret"));
  assert.ok(verifyPaymobHmac(txn, sig.toUpperCase(), "s3cret"));
  assert.ok(!verifyPaymobHmac({ ...txn, amount_cents: 100 }, sig, "s3cret"));
  assert.ok(!verifyPaymobHmac(txn, sig, "other"));
  assert.ok(!verifyPaymobHmac(txn, undefined, "s3cret"));
  assert.ok(!verifyPaymobHmac(txn, "zz", "s3cret"));
  assert.deepEqual(parsePaymobReference(txn), { uid: "uid123", planId: "plus_monthly" });
  assert.equal(parsePaymobReference({ order: { merchant_order_id: "bad" } }), null);
});

test("paymob intention + checkout url", () => {
  const b = buildPaymobIntention({ plan: plan("pro_yearly"), uid: "u1", email: null, integrationIds: [11, 22], redirectUrl: "https://x/done" }, 5);
  assert.equal(b.amount, 79900);
  assert.equal(b.currency, "EGP");
  assert.deepEqual(b.payment_methods, [11, 22]);
  assert.equal(b.special_reference, "u1:pro_yearly:5");
  assert.equal(b.redirection_url, "https://x/done");
  assert.equal(paymobCheckoutUrl("egy_pk_test_a", "cs/1"), "https://accept.paymob.com/unifiedcheckout/?publicKey=egy_pk_test_a&clientSecret=cs%2F1");
});

test("stripe signature verification", () => {
  const body = JSON.stringify({ type: "checkout.session.completed" });
  const h = stripeSignatureHeader(body, "whsec_x", 1000);
  assert.ok(verifyStripeSignature(body, h, "whsec_x", 1100));
  assert.ok(verifyStripeSignature(Buffer.from(body), h, "whsec_x", 1100));
  assert.ok(!verifyStripeSignature(body, h, "whsec_x", 1400), "outside tolerance");
  assert.ok(!verifyStripeSignature(body + " ", h, "whsec_x", 1100));
  assert.ok(!verifyStripeSignature(body, h, "whsec_y", 1100));
  assert.ok(!verifyStripeSignature(body, "garbage", "whsec_x", 1100));
  assert.ok(!verifyStripeSignature(body, undefined, "whsec_x", 1100));
  assert.ok(verifyStripeSignature(body, `t=1000,v1=00,${h.split(",")[1]}`, "whsec_x", 1000), "any v1 may match");
});

test("stripe session form", () => {
  const f = buildStripeSession(plan("lifetime"), "u9", "a@b.c", "https://ok", "https://no");
  assert.equal(f.get("mode"), "payment");
  assert.equal(f.get("line_items[0][price_data][unit_amount]"), "5900");
  assert.equal(f.get("metadata[uid]"), "u9");
  assert.equal(f.get("metadata[planId]"), "lifetime");
  assert.equal(f.get("customer_email"), "a@b.c");
});

test("sandbox only: live keys never count as configured", () => {
  assert.ok(isStripeTestKey("sk_test_123"));
  assert.ok(!isStripeTestKey("sk_live_123"));
  assert.ok(isPaymobTestKey("egy_sk_test_1"));
  assert.ok(!isPaymobTestKey("egy_sk_live_1"));
  assert.ok(!paymobReady(readBillingEnv({})));
  assert.ok(!stripeReady(readBillingEnv({ STRIPE_SECRET_KEY: "sk_live_1" })));
  const e = readBillingEnv({ PAYMOB_SECRET_KEY: "egy_sk_test_1", PAYMOB_PUBLIC_KEY: "egy_pk_test_1", PAYMOB_INTEGRATION_IDS: "12, x, 34" });
  assert.deepEqual(e.paymobIntegrations, [12, 34]);
  assert.ok(paymobReady(e));
  assert.ok(!paymobReady({ ...e, paymobPublic: "egy_pk_live_1" }));
  assert.equal(paymentDocId("stripe", "cs_test_a/b"), "stripe_cs_test_ab");
});
