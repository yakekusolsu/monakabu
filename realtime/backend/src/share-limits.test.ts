import assert from "node:assert/strict";
import test from "node:test";
import { validShareCount, webShareLimit } from "./share-limits.js";

test("default and zero disable the arbitrary order cap", () => {
  assert.equal(webShareLimit(), Number.MAX_SAFE_INTEGER);
  assert.equal(webShareLimit("0"), Number.MAX_SAFE_INTEGER);
  for (const shares of [1, 1001, 50_000, 3_000_000_000, Number.MAX_SAFE_INTEGER]) {
    assert.equal(validShareCount(shares, webShareLimit("0")), true);
  }
});

test("optional positive limits remain enforced", () => {
  assert.equal(validShareCount(1000, webShareLimit("1000")), true);
  assert.equal(validShareCount(1001, webShareLimit("1000")), false);
});

test("invalid and imprecise quantities are rejected", () => {
  for (const shares of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(validShareCount(shares, webShareLimit("0")), false);
  }
  for (const raw of ["", " ", "-1", "1.5", "NaN", "Infinity", "9007199254740992"]) {
    assert.throws(() => webShareLimit(raw));
  }
});
