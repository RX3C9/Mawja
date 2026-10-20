// SPDX-License-Identifier: GPL-3.0-or-later
import { isPaymobTestKey } from "./paymob";
import { isStripeTestKey } from "./stripe";

/** Billing settings, read from function env (never committed). Sandbox-only: live keys disable the provider. */
export interface BillingEnv {
  paymobSecret: string;
  paymobPublic: string;
  paymobHmac: string;
  paymobIntegrations: number[];
  stripeSecret: string;
  stripeWebhookSecret: string;
  returnUrl: string;
}

export function readBillingEnv(env: NodeJS.ProcessEnv): BillingEnv {
  return {
    paymobSecret: env.PAYMOB_SECRET_KEY ?? "",
    paymobPublic: env.PAYMOB_PUBLIC_KEY ?? "",
    paymobHmac: env.PAYMOB_HMAC_SECRET ?? "",
    paymobIntegrations: (env.PAYMOB_INTEGRATION_IDS ?? "").split(",").map((s) => Number(s.trim())).filter((n) => Number.isInteger(n) && n > 0),
    stripeSecret: env.STRIPE_SECRET_KEY ?? "",
    stripeWebhookSecret: env.STRIPE_WEBHOOK_SECRET ?? "",
    returnUrl: env.MAWJA_BILLING_RETURN_URL ?? "https://mawja-app.web.app/billing/done",
  };
}

export function paymobReady(e: BillingEnv): boolean {
  return isPaymobTestKey(e.paymobSecret) && /^egy_pk_test_/.test(e.paymobPublic) && e.paymobIntegrations.length > 0;
}

export function stripeReady(e: BillingEnv): boolean {
  return isStripeTestKey(e.stripeSecret);
}
