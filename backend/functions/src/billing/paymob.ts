// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Paymob (Egypt) — SANDBOX code paths only. Real keys are refused (see assertSandboxKey).
 * Docs: Intention API (POST /v1/intention/) + Unified Checkout, transaction-processed callback with HMAC.
 */
import { createHmac, timingSafeEqual } from "node:crypto";
import type { Plan } from "./plans";

export const PAYMOB_BASE = "https://accept.paymob.com";

/** Only test-mode secret keys are accepted while Mawja billing is in sandbox. */
export function isPaymobTestKey(secretKey: string): boolean {
  return /^egy_sk_test_/.test(secretKey);
}

/** Field order Paymob uses to build the transaction HMAC (SHA-512, hex). */
const HMAC_FIELDS = [
  "amount_cents", "created_at", "currency", "error_occured", "has_parent_transaction", "id", "integration_id",
  "is_3d_secure", "is_auth", "is_capture", "is_refunded", "is_standalone_payment", "is_voided", "order.id",
  "owner", "pending", "source_data.pan", "source_data.sub_type", "source_data.type", "success",
] as const;

function pick(obj: Record<string, unknown>, path: string): string {
  let v: unknown = obj;
  for (const k of path.split(".")) v = v && typeof v === "object" ? (v as Record<string, unknown>)[k] : undefined;
  if (v === undefined || v === null) return "";
  return typeof v === "boolean" ? String(v) : String(v);
}

export function paymobHmacString(obj: Record<string, unknown>): string {
  return HMAC_FIELDS.map((f) => pick(obj, f)).join("");
}

export function paymobHmac(obj: Record<string, unknown>, hmacSecret: string): string {
  return createHmac("sha512", hmacSecret).update(paymobHmacString(obj)).digest("hex");
}

export function verifyPaymobHmac(obj: Record<string, unknown>, received: string | undefined, hmacSecret: string): boolean {
  if (!received || !hmacSecret) return false;
  const expected = Buffer.from(paymobHmac(obj, hmacSecret), "hex");
  const got = Buffer.from(received.trim().toLowerCase(), "hex");
  return got.length === expected.length && timingSafeEqual(got, expected);
}

export interface PaymobIntentionInput {
  plan: Plan;
  uid: string;
  email: string | null;
  integrationIds: number[];
  redirectUrl?: string;
  notificationUrl?: string;
}

/** Body for POST /v1/intention/. special_reference carries uid + plan back on the callback. */
export function buildPaymobIntention(i: PaymobIntentionInput, now: number) {
  return {
    amount: i.plan.egp,
    currency: "EGP",
    payment_methods: i.integrationIds,
    items: [{ name: `Mawja ${i.plan.id}`, amount: i.plan.egp, quantity: 1 }],
    billing_data: { email: i.email ?? "member@mawja.app", first_name: "Mawja", last_name: "Member", phone_number: "NA" },
    special_reference: `${i.uid}:${i.plan.id}:${now}`,
    extras: { uid: i.uid, planId: i.plan.id },
    ...(i.redirectUrl ? { redirection_url: i.redirectUrl } : {}),
    ...(i.notificationUrl ? { notification_url: i.notificationUrl } : {}),
  };
}

export function paymobCheckoutUrl(publicKey: string, clientSecret: string): string {
  return `${PAYMOB_BASE}/unifiedcheckout/?publicKey=${encodeURIComponent(publicKey)}&clientSecret=${encodeURIComponent(clientSecret)}`;
}

/** uid + planId from special_reference ("uid:plan:ts") on a processed transaction. */
export function parsePaymobReference(obj: Record<string, unknown>): { uid: string; planId: string } | null {
  const ref = pick(obj, "order.merchant_order_id") || pick(obj, "special_reference");
  const parts = ref.split(":");
  if (parts.length < 2 || !parts[0] || !parts[1]) return null;
  return { uid: parts[0], planId: parts[1] };
}
