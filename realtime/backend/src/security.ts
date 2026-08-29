import { createHmac, timingSafeEqual } from "node:crypto";

export function createSignature(secret: string, timestamp: string, body: Buffer): string {
  return createHmac("sha256", secret).update(timestamp).update(".").update(body).digest("hex");
}

export function verifySignature(secret: string, timestamp: string, body: Buffer, supplied: string): boolean {
  const normalized = supplied.startsWith("sha256=") ? supplied.slice(7) : supplied;
  if (!/^[a-f0-9]{64}$/i.test(normalized)) return false;
  const expected = Buffer.from(createSignature(secret, timestamp, body), "hex");
  const actual = Buffer.from(normalized, "hex");
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

export function timestampIsFresh(raw: string, maxAgeSeconds: number, nowSeconds = Math.floor(Date.now() / 1000)): boolean {
  const timestamp = Number(raw);
  return Number.isSafeInteger(timestamp) && Math.abs(nowSeconds - timestamp) <= maxAgeSeconds;
}
