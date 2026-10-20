// SPDX-License-Identifier: GPL-3.0-or-later
import type { Plan } from "./plans";

/** Rank used so a purchase never downgrades an active better membership. */
const RANK: Record<string, number> = { free: 0, plus: 1, pro: 2, family: 3, lifetime: 4 };
const DAY = 86_400_000;

export interface CurrentMembership {
  tier?: string | null;
  tierExpiresAt?: number | null;
}

export interface MembershipUpdate {
  tier: string;
  tierExpiresAt: number | null;
  tierSource: "purchase";
  tierPlan: string;
}

function isActive(m: CurrentMembership, now: number): boolean {
  const tier = m.tier ?? "free";
  if (tier === "free") return false;
  return m.tierExpiresAt == null || m.tierExpiresAt > now;
}

/**
 * What the users doc becomes after a paid [plan] lands.
 * - Lifetime: tier "lifetime", no expiry, and stays so whatever is bought later.
 * - Same tier still active: the new period is added on top of the remaining time.
 * - A better tier still active (e.g. Pro while buying Plus): the better tier is kept and the bought
 *   days are added to it, so nobody loses time they paid for.
 * - Otherwise the plan's tier starts now.
 */
export function applyPurchase(current: CurrentMembership, plan: Plan, now: number): MembershipUpdate {
  const active = isActive(current, now);
  const curTier = active ? (current.tier ?? "free") : "free";
  if (curTier === "lifetime" || plan.days === null) {
    return { tier: "lifetime", tierExpiresAt: null, tierSource: "purchase", tierPlan: plan.id };
  }
  const add = plan.days * DAY;
  if (active && current.tierExpiresAt == null) {
    // Open-ended grant (e.g. admin) of an equal or better tier: keep it as is.
    if ((RANK[curTier] ?? 0) >= RANK[plan.tier]) {
      return { tier: curTier, tierExpiresAt: null, tierSource: "purchase", tierPlan: plan.id };
    }
    return { tier: plan.tier, tierExpiresAt: now + add, tierSource: "purchase", tierPlan: plan.id };
  }
  const base = active ? (current.tierExpiresAt as number) : now;
  const tier = (RANK[curTier] ?? 0) > RANK[plan.tier] ? curTier : plan.tier;
  return { tier, tierExpiresAt: base + add, tierSource: "purchase", tierPlan: plan.id };
}

/** Feature keys a tier unlocks, mirrored from the app's Entitlements table (for the users doc). */
export function featuresFor(tier: string): string[] {
  const plus = ["premium_themes", "premium_icons"];
  const pro = [...plus, "ui_sounds", "visualizer"];
  switch (tier) {
    case "plus": return plus;
    case "pro": case "lifetime": return pro;
    case "family": return [...pro, "family_sharing"];
    default: return [];
  }
}
