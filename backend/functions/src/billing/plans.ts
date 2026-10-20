// SPDX-License-Identifier: GPL-3.0-or-later
/** The membership catalogue (PLAN_V2 §3). Prices in minor units: piasters for EGP, cents for USD. */
export type PlanTier = "plus" | "pro" | "lifetime" | "family";
export type PlanInterval = "month" | "year" | "once";

export interface Plan {
  id: string;
  tier: PlanTier;
  interval: PlanInterval;
  /** EGP piasters (Paymob). */
  egp: number;
  /** USD cents (Stripe). */
  usd: number;
  /** Days of access one payment buys; null = never expires. */
  days: number | null;
}

export const PLANS: readonly Plan[] = [
  { id: "plus_monthly", tier: "plus", interval: "month", egp: 4_900, usd: 199, days: 31 },
  { id: "plus_yearly", tier: "plus", interval: "year", egp: 39_900, usd: 1_499, days: 366 },
  { id: "pro_monthly", tier: "pro", interval: "month", egp: 9_900, usd: 399, days: 31 },
  { id: "pro_yearly", tier: "pro", interval: "year", egp: 79_900, usd: 2_999, days: 366 },
  { id: "lifetime", tier: "lifetime", interval: "once", egp: 199_900, usd: 5_900, days: null },
  { id: "family_monthly", tier: "family", interval: "month", egp: 14_900, usd: 599, days: 31 },
  { id: "family_yearly", tier: "family", interval: "year", egp: 119_900, usd: 4_499, days: 366 },
];

export function findPlan(id: unknown): Plan | undefined {
  return typeof id === "string" ? PLANS.find((p) => p.id === id) : undefined;
}

export type Currency = "EGP" | "USD";

/** Amount in minor units for a plan in a currency. */
export function priceOf(plan: Plan, currency: Currency): number {
  return currency === "EGP" ? plan.egp : plan.usd;
}

/** Egypt pays in EGP through Paymob, everyone else in USD through Stripe. */
export function providerFor(country: string | undefined | null): { provider: "paymob" | "stripe"; currency: Currency } {
  return (country ?? "").toUpperCase() === "EG" ? { provider: "paymob", currency: "EGP" } : { provider: "stripe", currency: "USD" };
}
