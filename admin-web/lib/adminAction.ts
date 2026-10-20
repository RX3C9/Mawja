// SPDX-License-Identifier: GPL-3.0-or-later
import { httpsCallable } from "firebase/functions";
import { functions } from "./firebase";
import type { Badge, Tier } from "./roles";

/** The requests the adminAction Cloud Function accepts (backend/functions/src/index.ts). */
export type AdminRequest =
  | { action: "user.suspend" | "user.ban" | "user.unban"; uid: string; reason?: string }
  | { action: "user.tier"; uid: string; tier: Tier; days?: number }
  | { action: "user.badge"; uid: string; badge: Badge; grant: boolean }
  | { action: "feature.toggle"; key: string; enabled: boolean; minTier?: string }
  | { action: "config.edit"; patch: Record<string, unknown> }
  | { action: "announcement.send"; title: string; body: string; targetTier?: string }
  | { action: "report.resolve"; reportId: string; note?: string };

export async function adminAction(request: AdminRequest): Promise<void> {
  await httpsCallable<AdminRequest, { ok: boolean }>(functions(), "adminAction")(request);
}
