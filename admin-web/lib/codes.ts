// SPDX-License-Identifier: GPL-3.0-or-later
// Mawja membership codes: MW-XXXX-XXXX-XXXX. Twin of shared/codes/src/main/kotlin/app/mawja/codes/MawjaCodes.kt (:shared-codes);
// the spec both follow is docs/codes.md. Keep them in step (same test vectors in both test suites).

export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const PREFIX = "MW";
export const PAYLOAD_LENGTH = 11;
export const LENGTH = 12;
export const HASH_PREFIX = "mawja-code-v1:";
export const MAX_BATCH = 500;
const BASE = 31; // ALPHABET.length, a prime
const INV_12 = 13; // 12 × 13 ≡ 1 (mod 31)
const DASHES = new Set(["-", "‐", "‑", "‒", "–", "—", "―", "−", "_", ".", "ـ", "/"]);
const INVISIBLE = new Set(["\u200B", "\u200E", "\u200F", "\uFEFF"]);

export type Parsed =
  | { kind: "valid"; canonical: string }
  | { kind: "wrongLength" }
  | { kind: "badCharacter" }
  | { kind: "badCheck" };

/** NFKC, Arabic-Indic digits to ASCII, upper case, no spaces/dashes, MW prefix dropped at 14 symbols. */
export function clean(input: string): string {
  let out = "";
  for (const ch of input.normalize("NFKC")) {
    const cp = ch.codePointAt(0) ?? 0;
    let c = ch;
    if (cp >= 0x660 && cp <= 0x669) c = String(cp - 0x660);
    else if (cp >= 0x6f0 && cp <= 0x6f9) c = String(cp - 0x6f0);
    if (/\s/u.test(c) || DASHES.has(c) || INVISIBLE.has(c)) continue;
    out += c;
  }
  const upper = out.toUpperCase();
  return upper.length === LENGTH + PREFIX.length && upper.startsWith(PREFIX) ? upper.slice(PREFIX.length) : upper;
}

function weightedSum(code: string): number {
  let sum = 0;
  for (let i = 0; i < code.length; i++) sum += (i + 1) * ALPHABET.indexOf(code[i]);
  return sum;
}

export function parse(input: string): Parsed {
  const code = clean(input);
  for (const ch of code) if (!ALPHABET.includes(ch)) return { kind: "badCharacter" };
  if (code.length !== LENGTH) return { kind: "wrongLength" };
  return weightedSum(code) % BASE === 0 ? { kind: "valid", canonical: code } : { kind: "badCheck" };
}

/** The canonical 12 symbols, or null. */
export function normalize(input: string): string | null {
  const p = parse(input);
  return p.kind === "valid" ? p.canonical : null;
}

export const isValid = (input: string): boolean => normalize(input) !== null;

export function checkSymbol(payload: string): string {
  if (payload.length !== PAYLOAD_LENGTH || [...payload].some((c) => !ALPHABET.includes(c))) throw new Error("payload must be 11 code symbols");
  const s = weightedSum(payload) % BASE;
  return ALPHABET[(((BASE - s) % BASE) * INV_12) % BASE];
}

export function format(code: string): string {
  const c = normalize(code);
  if (!c) throw new Error("not a valid Mawja code");
  return `${PREFIX}-${c.slice(0, 4)}-${c.slice(4, 8)}-${c.slice(8, 12)}`;
}

/** Last group (4 symbols), stored beside the hash so staff can tell codes apart. */
export function hint(code: string): string {
  const c = normalize(code);
  if (!c) throw new Error("not a valid Mawja code");
  return c.slice(-4);
}

/** Firestore doc id: lowercase hex SHA-256 of HASH_PREFIX + canonical code (Web Crypto: browser and Node ≥ 20). */
export async function hash(code: string): Promise<string> {
  const c = normalize(code);
  if (!c) throw new Error("not a valid Mawja code");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(HASH_PREFIX + c));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Unbiased random symbol indexes from a byte source (rejection sampling: bytes ≥ 248 are dropped). */
export function randomIndexes(count: number, bytes: (n: number) => Uint8Array = defaultBytes): number[] {
  const out: number[] = [];
  while (out.length < count) {
    for (const b of bytes(count * 2)) {
      if (b < 248) out.push(b % BASE);
      if (out.length === count) break;
    }
  }
  return out;
}

function defaultBytes(n: number): Uint8Array {
  const buf = new Uint8Array(n);
  globalThis.crypto.getRandomValues(buf);
  return buf;
}

export function generate(bytes?: (n: number) => Uint8Array): string {
  const payload = randomIndexes(PAYLOAD_LENGTH, bytes).map((i) => ALPHABET[i]).join("");
  return format(payload + checkSymbol(payload));
}

export function generateBatch(count: number, bytes?: (n: number) => Uint8Array): string[] {
  if (!Number.isInteger(count) || count < 1 || count > MAX_BATCH) throw new Error(`count must be 1..${MAX_BATCH}`);
  const out = new Set<string>();
  while (out.size < count) out.add(generate(bytes));
  return [...out];
}

export const CODE_TIERS = ["plus", "pro", "family", "lifetime"] as const;
export type CodeTier = (typeof CODE_TIERS)[number];

export interface BatchInput {
  tier: CodeTier;
  days: number;
  count: number;
  maxUses: number;
  validForDays: number;
  note: string;
}

/** Form check mirroring the Firestore rules (newCodeOk). Returns an i18n key or null. */
export function batchInputError(b: BatchInput): string | null {
  if (!(CODE_TIERS as readonly string[]).includes(b.tier)) return "codes.err.tier";
  if (b.tier !== "lifetime" && (!Number.isInteger(b.days) || b.days < 1 || b.days > 3660)) return "codes.err.days";
  if (!Number.isInteger(b.count) || b.count < 1 || b.count > MAX_BATCH) return "codes.err.count";
  if (!Number.isInteger(b.maxUses) || b.maxUses < 1 || b.maxUses > 10_000) return "codes.err.maxUses";
  if (!Number.isInteger(b.validForDays) || b.validForDays < 0 || b.validForDays > 3660) return "codes.err.validFor";
  if (b.note.length > 200) return "codes.err.note";
  return null;
}

export const masked = (h: string): string => `MW-••••-••••-${h}`;
