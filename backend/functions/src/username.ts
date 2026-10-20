// SPDX-License-Identifier: GPL-3.0-or-later
const RESERVED = new Set([
  "admin", "administrator", "mawja", "support", "help", "root", "system", "moderator", "official",
  "team", "staff", "security", "null", "undefined", "api", "www",
]);

/** 3–20 chars, lower-case latin letters, digits, underscore and dot; no leading/trailing dot. */
export function validateUsername(input: string): { ok: true; value: string } | { ok: false; reason: string } {
  const value = input.trim().toLowerCase();
  if (value.length < 3 || value.length > 20) return { ok: false, reason: "length" };
  if (!/^[a-z0-9_.]+$/.test(value)) return { ok: false, reason: "characters" };
  if (value.startsWith(".") || value.endsWith(".") || value.includes("..")) return { ok: false, reason: "dots" };
  if (RESERVED.has(value) || value.startsWith("mawja")) return { ok: false, reason: "reserved" };
  return { ok: true, value };
}
