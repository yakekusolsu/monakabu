import assert from "node:assert/strict";
import test from "node:test";
import { createSignature, timestampIsFresh, verifySignature } from "./security.js";

test("HMAC signature verifies the exact timestamp and body", () => {
  const body = Buffer.from('{"ok":true}');
  const signature = createSignature("a".repeat(32), "1000", body);
  assert.equal(verifySignature("a".repeat(32), "1000", body, `sha256=${signature}`), true);
  assert.equal(verifySignature("a".repeat(32), "1001", body, `sha256=${signature}`), false);
});

test("timestamp freshness rejects replayed requests", () => {
  assert.equal(timestampIsFresh("1000", 300, 1200), true);
  assert.equal(timestampIsFresh("1000", 300, 1400), false);
});
