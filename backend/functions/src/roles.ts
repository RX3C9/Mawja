// SPDX-License-Identifier: GPL-3.0-or-later
export type Role = "owner" | "admin" | "moderator" | "support";

/** What each staff role may do. The owner can do everything. */
const PERMISSIONS: Record<Role, ReadonlySet<string>> = {
  owner: new Set(["*"]),
  admin: new Set(["user.suspend", "user.ban", "user.unban", "user.tier", "user.badge", "feature.toggle",
    "config.edit", "announcement.send", "report.resolve", "staff.view"]),
  moderator: new Set(["user.suspend", "user.unban", "user.badge", "report.resolve"]),
  support: new Set(["report.resolve"]),
};

export function isRole(value: unknown): value is Role {
  return value === "owner" || value === "admin" || value === "moderator" || value === "support";
}

export function can(role: Role | undefined, action: string): boolean {
  if (!role) return false;
  const set = PERMISSIONS[role];
  return set.has("*") || set.has(action);
}
