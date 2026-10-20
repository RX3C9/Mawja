// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Mawja ID: MW-XXXX-XXXX. Seven random Crockford base32 symbols plus one check symbol
 * (sum of values weighted by position, mod 32), so typos are caught before a lookup.
 */
import { randomInt } from "node:crypto";

const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford: no I, L, O, U

function checkSymbol(body: string): string {
  let sum = 0;
  for (let i = 0; i < body.length; i++) sum += ALPHABET.indexOf(body[i]) * (i + 1);
  return ALPHABET[sum % 32];
}

export function generateMawjaId(rand: (max: number) => number = randomInt): string {
  let body = "";
  for (let i = 0; i < 7; i++) body += ALPHABET[rand(32)];
  const full = body + checkSymbol(body);
  return `MW-${full.slice(0, 4)}-${full.slice(4)}`;
}

/** Accepts lower case, missing dashes and the usual look-alikes (O→0, I/L→1). */
export function normalizeMawjaId(input: string): string | null {
  const raw = input.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  const body = raw.startsWith("MW") ? raw.slice(2) : raw;
  if (body.length !== 8 || [...body].some((c) => !ALPHABET.includes(c))) return null;
  if (checkSymbol(body.slice(0, 7)) !== body[7]) return null;
  return `MW-${body.slice(0, 4)}-${body.slice(4)}`;
}
