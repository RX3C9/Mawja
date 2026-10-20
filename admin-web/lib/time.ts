// SPDX-License-Identifier: GPL-3.0-or-later
/** Firestore Timestamp, millis or nothing -> a short local date-time. */
export function formatWhen(value: unknown, lang: string): string {
  let ms: number | null = null;
  if (typeof value === "number") ms = value;
  else if (value && typeof value === "object" && "toMillis" in value && typeof (value as { toMillis: unknown }).toMillis === "function") {
    ms = (value as { toMillis: () => number }).toMillis();
  }
  if (ms === null) return "—";
  return new Intl.DateTimeFormat(lang === "ar" ? "ar-EG" : "en-GB", { dateStyle: "medium", timeStyle: "short" }).format(new Date(ms));
}
