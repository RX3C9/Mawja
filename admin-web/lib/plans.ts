// SPDX-License-Identifier: GPL-3.0-or-later
// Mirror of backend/functions/src/billing/plans.ts (minor units: piasters / cents).
export type PlanRow = { id: string; tier: "plus" | "pro" | "lifetime" | "family"; interval: "month" | "year" | "once"; egp: number; usd: number; days: number | null };

export const PLANS: readonly PlanRow[] = [
  { id: "plus_monthly", tier: "plus", interval: "month", egp: 4_900, usd: 199, days: 31 },
  { id: "plus_yearly", tier: "plus", interval: "year", egp: 39_900, usd: 1_499, days: 366 },
  { id: "pro_monthly", tier: "pro", interval: "month", egp: 9_900, usd: 399, days: 31 },
  { id: "pro_yearly", tier: "pro", interval: "year", egp: 79_900, usd: 2_999, days: 366 },
  { id: "lifetime", tier: "lifetime", interval: "once", egp: 199_900, usd: 5_900, days: null },
  { id: "family_monthly", tier: "family", interval: "month", egp: 14_900, usd: 599, days: 31 },
  { id: "family_yearly", tier: "family", interval: "year", egp: 119_900, usd: 4_499, days: 366 },
];

/** 4900 EGP piasters -> "49 EGP"; 1499 USD cents -> "$14.99". */
export function formatMoney(minor: number, currency: "EGP" | "USD", lang: string): string {
  const locale = lang === "ar" ? "ar-EG" : "en-US";
  return new Intl.NumberFormat(locale, { style: "currency", currency, maximumFractionDigits: currency === "EGP" ? 0 : 2, minimumFractionDigits: currency === "EGP" ? 0 : 2 }).format(minor / 100);
}

/** Yearly saving vs 12 monthly payments, whole percent, or null. */
export function yearlySaving(tier: PlanRow["tier"]): number | null {
  const m = PLANS.find((p) => p.tier === tier && p.interval === "month");
  const y = PLANS.find((p) => p.tier === tier && p.interval === "year");
  if (!m || !y) return null;
  return Math.round(((m.egp * 12 - y.egp) * 100) / (m.egp * 12));
}

/** Only an https link (or blank) may be saved as the app checkout URL. */
export function validCheckoutUrl(value: string): boolean {
  const v = value.trim();
  if (v === "") return true;
  try {
    return new URL(v).protocol === "https:";
  } catch {
    return false;
  }
}
