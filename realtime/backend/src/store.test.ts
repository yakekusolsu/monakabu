import assert from "node:assert/strict";
import test from "node:test";
import { emptyState, reduceState } from "./store.js";
import type { DailyMarketReport, IngestEvent, StockState } from "./types.js";

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

test("daily report survives later market snapshots", () => {
  const report: DailyMarketReport = {
    reportDate: "2026-08-29",
    generatedAt: "2026-08-29T12:00:00Z",
    stocks: [{
      stockId: "mona_mining", symbol: "MMI", displayName: "モナカ鉱業",
      currentPrice: 1250, dailyHigh: 1400, dailyLow: 1000, range: 400, rangePercent: 40,
    }],
  };
  const reportEvent: IngestEvent = {
    schemaVersion: 1, eventId: "RT-REPORT", serverId: "main", type: "market.daily.report",
    timestamp: report.generatedAt, pluginVersion: "1.4.0", data: report,
  };
  const snapshotEvent: IngestEvent = {
    schemaVersion: 1, eventId: "RT-SNAPSHOT", serverId: "main", type: "market.snapshot",
    timestamp: report.generatedAt, pluginVersion: "1.4.0",
    data: { currency: "MONA", marketOpen: true, season: null, stocks: [], activeEvents: [] },
  };

  const afterReport = reduceState(emptyState(), reportEvent);
  assert.deepEqual(reduceState(afterReport, snapshotEvent).dailyReport, report);
});
