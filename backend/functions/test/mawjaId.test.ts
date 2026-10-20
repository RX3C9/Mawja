// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import { test } from "node:test";
import { generateMawjaId, normalizeMawjaId } from "../src/mawjaId";
import { can } from "../src/roles";
import { validateUsername } from "../src/username";

test("generated ids have the MW-XXXX-XXXX shape and validate", () => {
  for (let i = 0; i < 2000; i++) {
    const id = generateMawjaId();
    assert.match(id, /^MW-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
    assert.equal(normalizeMawjaId(id), id);
  }
});

test("normalisation accepts sloppy input and rejects typos", () => {
  const id = generateMawjaId(() => 0); // MW-0000-0000
  assert.equal(id, "MW-0000-0000");
  assert.equal(normalizeMawjaId("mw0000oooo"), id);
  assert.equal(normalizeMawjaId("MW-0000-0001"), null);
  assert.equal(normalizeMawjaId("hello"), null);
});

test("usernames", () => {
  assert.deepEqual(validateUsername(" Omar_99 "), { ok: true, value: "omar_99" });
  assert.equal(validateUsername("ab").ok, false);
  assert.equal(validateUsername("admin").ok, false);
  assert.equal(validateUsername("mawja_team").ok, false);
  assert.equal(validateUsername("a..b").ok, false);
});

test("roles", () => {
  assert.equal(can("owner", "anything"), true);
  assert.equal(can("support", "user.ban"), false);
  assert.equal(can("moderator", "user.suspend"), true);
  assert.equal(can(undefined, "report.resolve"), false);
});
