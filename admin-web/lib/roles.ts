// SPDX-License-Identifier: GPL-3.0-or-later
// Mirror of backend/functions/src/roles.ts: the UI hides what the server would refuse anyway.
export type Role = "owner" | "admin" | "moderator" | "support";

export const ROLES: readonly Role[] = ["owner", "admin", "moderator", "support"];

export type AdminActionName =
  | "user.suspend"
  | "user.ban"
  | "user.unban"
  | "user.tier"
  | "user.badge"
  | "feature.toggle"
  | "config.edit"
  | "announcement.send"
  | "report.resolve"
  | "staff.view"
  | "audit.view";

const PERMISSIONS: Record<Role, ReadonlySet<string>> = {
  owner: new Set(["*"]),
  admin: new Set([
    "user.suspend", "user.ban", "user.unban", "user.tier", "user.badge", "feature.toggle",
    "config.edit", "announcement.send", "report.resolve", "staff.view", "audit.view",
  ]),
  moderator: new Set(["user.suspend", "user.unban", "user.badge", "report.resolve"]),
  support: new Set(["report.resolve"]),
};

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}

export function can(role: Role | null | undefined, action: AdminActionName): boolean {
  if (!role) return false;
  const set = PERMISSIONS[role];
  return set.has("*") || set.has(action);
}

export const TIERS = ["free", "plus", "pro", "lifetime", "family"] as const;
export type Tier = (typeof TIERS)[number];

export const BADGES = ["verified", "creator", "founder", "pro", "team"] as const;
export type Badge = (typeof BADGES)[number];
