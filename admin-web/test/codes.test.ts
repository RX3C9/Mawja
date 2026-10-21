// SPDX-License-Identifier: GPL-3.0-or-later
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ALPHABET, LENGTH, batchInputError, checkSymbol, clean, format, generate, generateBatch, hash, hint, isValid, normalize, parse,
  randomIndexes,
} from "../lib/codes";

// Shared vectors: the same table is in app/src/test/java/app/mawja/codes/MawjaCodesTest.kt and docs/codes.md.
const VECTORS: [string, string][] = [
  ["MW-2222-2222-2222", "d87c41c4f35d52bf4fcf895f6441b9e8b0378a01f34a2e8816a7f501b52a5111"],
  ["MW-ABCD-EFGH-JKM4", "2e7dc17e68f222019c828129cf505158ad2646da6a285dbadf2dc2bc895b15af"],
  ["MW-ZZZZ-ZZZZ-ZZZQ", "07acaede33ac2f043887aef08a467e94781f4f45f7fcd70d5316ef98513b484d"],
  ["MW-CZ6E-P34V-K5DG", "2f12de93a40cbf1dd9f7325c01ab2839338bd9959269b3dfe904a54b4c3e59de"],
  ["MW-M3YJ-834F-F49Z", "2f188ef47c2240ee6d5138a5ede5042a927d33794560d9f043838b24262a13e4"],
];

test("codes: vectors match the spec", async () => {
  for (const [code, h] of VECTORS) {
    assert.ok(isValid(code), code);
    assert.equal(format(code), code);
    assert.equal(await hash(code), h);
  }
  assert.equal(checkSymbol("ABCDEFGHJKM"), "4");
  assert.equal(checkSymbol("ZZZZZZZZZZZ"), "Q");
});

test("codes: normalisation accepts how people type it", async () => {
  for (const input of [
    "MW-ABCD-EFGH-JKM4", "mw-abcd-efgh-jkm4", "  abcd efgh jkm4 ", "ABCD–EFGH—JKM4", "MWABCDEFGHJKM4",
    "ＭＷ－ＡＢＣＤ－ＥＦＧＨ－ＪＫＭ４", "ABCD-EFGH-JKM٤", "ABCD-EFGH-JKM۴", "\u200FABCD-EFGH-JKM4\u200E",
  ]) assert.equal(normalize(input), "ABCDEFGHJKM4", input);
  assert.equal(await hash("abcd efgh jkm٤"), VECTORS[1][1]);
  assert.equal(clean("mw-ab"), "MWAB");
});

test("codes: parse explains what is wrong", () => {
  assert.deepEqual(parse("MW-ABCD-EFGH"), { kind: "wrongLength" });
  assert.deepEqual(parse(""), { kind: "wrongLength" });
  assert.deepEqual(parse("MW-ABCD-EFGH-JK0O"), { kind: "badCharacter" });
  assert.deepEqual(parse("MW-1BCD-EFGH-JKM4"), { kind: "badCharacter" });
  assert.deepEqual(parse("MW-ABCD-EFGH-JKL4"), { kind: "badCharacter" });
  assert.deepEqual(parse("MW-ABCD-EFGH-JKM5"), { kind: "badCheck" });
  const own = "MWABCDEFGHJ" + checkSymbol("MWABCDEFGHJ");
  assert.equal(normalize(own), own);
});

test("codes: every single typo and neighbour swap is caught", () => {
  for (let n = 0; n < 100; n++) {
    const code = normalize(generate())!;
    for (let i = 0; i < LENGTH; i++) {
      for (const c of ALPHABET) {
        if (c === code[i]) continue;
        assert.equal(isValid(code.slice(0, i) + c + code.slice(i + 1)), false);
      }
      if (i < LENGTH - 1 && code[i] !== code[i + 1]) {
        assert.equal(isValid(code.slice(0, i) + code[i + 1] + code[i] + code.slice(i + 2)), false);
      }
    }
  }
});

test("codes: generated batches are valid, distinct and unambiguous", async () => {
  const batch = generateBatch(300);
  assert.equal(new Set(batch).size, 300);
  for (const code of batch) {
    assert.match(code, /^MW-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$/);
    assert.equal(hint(code), code.slice(-4));
  }
  assert.equal((await hash(batch[0])).length, 64);
  for (const c of "0O1IL") assert.ok(!ALPHABET.includes(c));
  assert.throws(() => generateBatch(0));
  assert.throws(() => generateBatch(501));
  // Rejection sampling drops bytes >= 248 and maps the rest mod 31.
  let calls = 0;
  const idx = randomIndexes(3, () => (calls++ === 0 ? new Uint8Array([255, 248, 0, 31, 247, 9]) : new Uint8Array([1, 1, 1, 1, 1, 1])));
  assert.deepEqual(idx, [0, 0, 30]);
});

test("codes: batch form mirrors the rules", () => {
  const ok = { tier: "plus" as const, days: 30, count: 10, maxUses: 1, validForDays: 0, note: "" };
  assert.equal(batchInputError(ok), null);
  assert.equal(batchInputError({ ...ok, tier: "lifetime", days: 0 }), null);
  assert.equal(batchInputError({ ...ok, days: 0 }), "codes.err.days");
  assert.equal(batchInputError({ ...ok, count: 501 }), "codes.err.count");
  assert.equal(batchInputError({ ...ok, maxUses: 0 }), "codes.err.maxUses");
  assert.equal(batchInputError({ ...ok, validForDays: -1 }), "codes.err.validFor");
  assert.equal(batchInputError({ ...ok, note: "x".repeat(201) }), "codes.err.note");
});
