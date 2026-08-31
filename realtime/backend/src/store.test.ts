import assert from "node:assert/strict";
import test from "node:test";
import { emptyState, reduceState } from "./store.js";
import type { DailyMarketReport, IngestEvent, MonaPriceState, StockState } from "./types.js";

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

test("MonaPrice state is reduced and survives MonaKabu market snapshots", () => {
  const now = new Date().toISOString();
  const monaPrice: MonaPriceState = {
    currency: "MONA", capturedAt: now, sourceVersion: "1.1.0", nextUpdateSeconds: 300,
    index: { current: 102.5, previous: 100, changePercent: 2.5, updatedAt: now },
    items: [{
      id: "DIAMOND", displayName: "ダイヤモンド", category: "mineral", categoryName: "鉱物",
      price: 1200, previousPrice: 1100, changePercent: 9.09, buyPrice: 1320, sellPrice: 1080,
      highPrice: 1250, lowPrice: 900, buyVolume: 12, sellVolume: 4, updatedAt: now,
    }],
  };
  const priceEvent: IngestEvent = {
    schemaVersion: 1, eventId: "RT-PRICE", serverId: "main", type: "monaprice.snapshot",
    timestamp: now, pluginVersion: "1.7.0", data: monaPrice,
  };
  const marketEvent: IngestEvent = {
    schemaVersion: 1, eventId: "RT-MARKET", serverId: "main", type: "market.snapshot",
    timestamp: now, pluginVersion: "1.7.0",
    data: { currency: "MONA", marketOpen: true, season: null, stocks: [], activeEvents: [] },
  };

  const afterPrice = reduceState(emptyState(), priceEvent);
  assert.deepEqual(afterPrice.monaPrice, monaPrice);
  assert.deepEqual(reduceState(afterPrice, marketEvent).monaPrice, monaPrice);
});
