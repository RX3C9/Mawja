// SPDX-License-Identifier: GPL-3.0-or-later
import { test } from "node:test";
import assert from "node:assert/strict";
import { can, isRole, ROLES } from "../lib/roles";
import { classifySearch, normalizeMawjaId } from "../lib/mawjaId";
import { DICTIONARIES, translate } from "../lib/i18n";
import { adminErrorKey } from "../lib/adminErrors";

test("roles mirror the backend permissions", () => {
  assert.ok(can("owner", "config.edit"));
  assert.ok(can("admin", "user.tier"));
  assert.ok(can("admin", "audit.view"));
  assert.ok(!can("moderator", "user.ban"));
  assert.ok(can("moderator", "user.badge"));
  assert.ok(can("support", "report.resolve"));
  assert.ok(!can("support", "user.suspend"));
  assert.ok(!can(null, "report.resolve"));
  assert.ok(can("owner", "codes.manage") && can("admin", "codes.manage"));
  assert.ok(can("support", "codes.view") && !can("support", "codes.manage"));
  assert.ok(can("moderator", "codes.view") && !can("moderator", "codes.manage"));
  assert.ok(ROLES.every(isRole));
  assert.ok(!isRole("superuser"));
});

test("Mawja ID normalisation accepts look-alikes and rejects bad checksums", () => {
  // MW-0000-0000: body 0000000 sums to 0, check symbol "0".
  assert.equal(normalizeMawjaId("mw00000000"), "MW-0000-0000");
  assert.equal(normalizeMawjaId("MW-OOOO-OOOO"), "MW-0000-0000");
  assert.equal(normalizeMawjaId("MW-0000-0001"), null);
  // body 1000000: 1*1 = 1 -> "1".
  assert.equal(normalizeMawjaId("mw-1000-0001"), "MW-1000-0001");
  assert.equal(normalizeMawjaId("MW-L000-000I"), "MW-1000-0001");
});

test("search box classification", () => {
  assert.deepEqual(classifySearch("MW-1000-0001"), { kind: "mawjaId", value: "MW-1000-0001" });
  assert.deepEqual(classifySearch("Omar@Example.com"), { kind: "email", value: "omar@example.com" });
  assert.deepEqual(classifySearch("@Omar_99"), { kind: "username", value: "omar_99" });
  assert.deepEqual(classifySearch("aB3dE5fG7hI9jK1lM3nO5pQ7"), { kind: "uid", value: "aB3dE5fG7hI9jK1lM3nO5pQ7" });
  assert.equal(classifySearch("   "), null);
  assert.equal(classifySearch("no spaces allowed here!"), null);
});

test("Arabic and English dictionaries have the same keys", () => {
  assert.deepEqual(Object.keys(DICTIONARIES.ar).sort(), Object.keys(DICTIONARIES.en).sort());
  assert.equal(translate("ar", "nav.users"), "المستخدمين");
  assert.equal(translate("en", "missing.key"), "missing.key");
});

test("errors map to dictionary keys", () => {
  assert.equal(adminErrorKey("functions/not-found"), "err.functions");
  assert.equal(adminErrorKey("auth/invalid-credential"), "err.login");
  assert.equal(adminErrorKey(undefined), "err.unknown");
  for (const key of ["err.functions", "err.login", "err.unknown", "err.permission"]) assert.ok(key in DICTIONARIES.en);
});

test("plan catalogue mirrors PLAN_V2 prices and formats money", async () => {
  const { PLANS, formatMoney, yearlySaving, validCheckoutUrl } = await import("../lib/plans");
  assert.deepEqual(PLANS.map((p) => p.egp / 100), [49, 399, 99, 799, 1999, 149, 1199]);
  assert.equal(yearlySaving("plus"), 32);
  assert.equal(yearlySaving("pro"), 33);
  assert.equal(yearlySaving("lifetime"), null);
  assert.equal(formatMoney(1499, "USD", "en"), "$14.99");
  assert.match(formatMoney(4900, "EGP", "en"), /49/);
  assert.ok(validCheckoutUrl(""));
  assert.ok(validCheckoutUrl("https://mawja.app/checkout"));
  assert.ok(!validCheckoutUrl("http://mawja.app"));
  assert.ok(!validCheckoutUrl("nope"));
});
