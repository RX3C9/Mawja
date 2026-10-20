// SPDX-License-Identifier: GPL-3.0-or-later
// Mirror of backend/functions/src/mawjaId.ts normalisation, for searching by Mawja ID.
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford: no I, L, O, U

function checkSymbol(body: string): string {
  let sum = 0;
  for (let i = 0; i < body.length; i++) sum += ALPHABET.indexOf(body[i]) * (i + 1);
  return ALPHABET[sum % 32];
}

/** Accepts lower case, missing dashes and look-alikes (O→0, I/L→1); null when the check symbol is wrong. */
export function normalizeMawjaId(input: string): string | null {
  const raw = input.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  const body = raw.startsWith("MW") ? raw.slice(2) : raw;
  if (body.length !== 8 || [...body].some((c) => !ALPHABET.includes(c))) return null;
  if (checkSymbol(body.slice(0, 7)) !== body[7]) return null;
  return `MW-${body.slice(0, 4)}-${body.slice(4)}`;
}

/** What a search box string most likely is. */
export type SearchKind = { kind: "mawjaId"; value: string } | { kind: "email"; value: string } | { kind: "username"; value: string } | { kind: "uid"; value: string };

export function classifySearch(input: string): SearchKind | null {
  const q = input.trim();
  if (!q) return null;
  const id = normalizeMawjaId(q);
  if (id) return { kind: "mawjaId", value: id };
  if (q.includes("@") && !q.startsWith("@")) return { kind: "email", value: q.toLowerCase() };
  const username = q.replace(/^@/, "").toLowerCase();
  if (/^[a-z0-9_.]{3,20}$/.test(username)) return { kind: "username", value: username };
  if (/^[A-Za-z0-9]{20,40}$/.test(q)) return { kind: "uid", value: q };
  return null;
}
