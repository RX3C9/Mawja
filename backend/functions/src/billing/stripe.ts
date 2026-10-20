// SPDX-License-Identifier: GPL-3.0-or-later
/** Stripe (international) — TEST MODE code paths only. Live keys are refused. */
import { createHmac, timingSafeEqual } from "node:crypto";
import type { Plan } from "./plans";

export const STRIPE_API = "https://api.stripe.com/v1";

export function isStripeTestKey(secretKey: string): boolean {
  return /^(sk|rk)_test_/.test(secretKey);
}

/** Verifies the Stripe-Signature header (t=..., v1=...) over `${t}.${rawBody}` (HMAC-SHA256). */
export function verifyStripeSignature(rawBody: string | Buffer, header: string | undefined, secret: string,
  nowSec: number, toleranceSec = 300): boolean {
  if (!header || !secret) return false;
  const parts = header.split(",").map((p) => p.trim().split("="));
  const t = parts.find(([k]) => k === "t")?.[1];
  const sigs = parts.filter(([k]) => k === "v1").map(([, v]) => v);
  if (!t || sigs.length === 0 || !/^\d+$/.test(t)) return false;
  if (Math.abs(nowSec - Number(t)) > toleranceSec) return false;
  const expected = createHmac("sha256", secret).update(`${t}.`).update(rawBody).digest();
  return sigs.some((s) => {
    const got = Buffer.from(s, "hex");
    return got.length === expected.length && timingSafeEqual(got, expected);
  });
}

export function stripeSignatureHeader(rawBody: string, secret: string, tSec: number): string {
  return `t=${tSec},v1=${createHmac("sha256", secret).update(`${tSec}.${rawBody}`).digest("hex")}`;
}

/** Form fields for POST /v1/checkout/sessions (one-off payment per period; renewals are re-purchases). */
export function buildStripeSession(plan: Plan, uid: string, email: string | null, successUrl: string, cancelUrl: string): URLSearchParams {
  const f = new URLSearchParams();
  f.set("mode", "payment");
  f.set("client_reference_id", uid);
  f.set("success_url", successUrl);
  f.set("cancel_url", cancelUrl);
  if (email) f.set("customer_email", email);
  f.set("line_items[0][quantity]", "1");
  f.set("line_items[0][price_data][currency]", "usd");
  f.set("line_items[0][price_data][unit_amount]", String(plan.usd));
  f.set("line_items[0][price_data][product_data][name]", `Mawja ${plan.id}`);
  f.set("metadata[uid]", uid);
  f.set("metadata[planId]", plan.id);
  return f;
}
