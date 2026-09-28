/** Zero means no operator-defined cap; JSON numbers must still be exact. */
export function webShareLimit(raw = "0"): number {
  const configured = Number(raw);
  if (!raw.trim() || !Number.isSafeInteger(configured) || configured < 0) {
    throw new Error("WEB_MAX_SHARES_PER_ORDER must be zero or a positive safe integer");
  }
  return configured === 0 ? Number.MAX_SAFE_INTEGER : configured;
}

export function validShareCount(shares: number, maximum: number): boolean {
  return Number.isSafeInteger(shares) && shares > 0 && shares <= maximum;
}

export function validOrderShareCount(type: string, shares: number, maximum: number): boolean {
  return type === "SELL" && shares === -1 || validShareCount(shares, maximum);
}
