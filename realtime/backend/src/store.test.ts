import assert from "node:assert/strict";
import test from "node:test";
import { emptyState, reduceState } from "./store.js";
import type { IngestEvent, StockState } from "./types.js";

test("price event updates one stock without removing others", () => {
  const stock: StockState = {
    id: "mona_mining", symbol: "MMI", displayName: "モナカ鉱業", price: 1250,
    previousPrice: 1200, changePercent: 4.16, dailyHigh: 1250, dailyLow: 1100,
    trend: "BULL", halted: false, haltedUntil: null, bankrupt: false, updatedAt: new Date().toISOString(),
  };
  const event: IngestEvent = {
    schemaVersion: 1, eventId: "RT-1", serverId: "main", type: "stock.price.changed",
    timestamp: new Date().toISOString(), pluginVersion: "1.2.0", data: stock,
  };
  assert.deepEqual(reduceState(emptyState(), event).stocks, [stock]);
});
