export type MarketStatus = "OPEN" | "CLOSING" | "SETTLEMENT" | "CLOSED" | "OPENING";

export interface StockState {
  id: string;
  symbol: string;
  displayName: string;
  price: number;
  previousPrice: number;
  changePercent: number;
  dailyHigh: number;
  dailyLow: number;
  trend: "BULL" | "NORMAL" | "BEAR";
  halted: boolean;
  haltedUntil: string | null;
  bankrupt: boolean;
  updatedAt: string;
}

export interface SeasonState {
  id: number;
  number: number;
  startsAt: string;
  endsAt: string;
  status: MarketStatus;
  settledAt: string | null;
}

export interface MarketNews {
  instanceId: string;
  eventId: string;
  stockId: string;
  stockIds?: string[];
  name: string;
  message: string;
  modifier: number;
  startedAt: string;
  endsAt: string;
}

export interface DailyStockRange {
  stockId: string;
  symbol: string;
  displayName: string;
  currentPrice: number;
  dailyHigh: number;
  dailyLow: number;
  range: number;
  rangePercent: number;
}

export interface DailyMarketReport {
  reportDate: string;
  generatedAt: string;
  stocks: DailyStockRange[];
}

export interface MonaPriceItemState {
  id: string;
  displayName: string;
  category: string;
  categoryName: string;
  price: number;
  previousPrice: number;
  changePercent: number;
  buyPrice: number;
  sellPrice: number;
  highPrice: number;
  lowPrice: number;
  buyVolume: number;
  sellVolume: number;
  updatedAt: string;
}

export interface MonaPriceIndexState {
  current: number;
  previous: number;
  changePercent: number;
  updatedAt: string;
}

export interface MonaPriceState {
  currency: string;
  index: MonaPriceIndexState;
  nextUpdateSeconds: number;
  capturedAt: string;
  sourceVersion: string;
  items: MonaPriceItemState[];
}

export interface MarketState {
  currency: string;
  marketOpen: boolean;
  season: SeasonState | null;
  stocks: StockState[];
  activeEvents: MarketNews[];
  dailyReport: DailyMarketReport | null;
  monaPrice: MonaPriceState | null;
}

export interface IngestEvent {
  schemaVersion: number;
  eventId: string;
  serverId: string;
  type: string;
  timestamp: string;
  pluginVersion: string;
  data: unknown;
}

export interface WebPortfolioPosition {
  stockId: string;
  shares: number;
  averageCost: number;
}

export interface WebAccountSnapshot {
  balance: number;
  portfolio: WebPortfolioPosition[];
  capturedAt: string;
}

export interface WebIdentity {
  serverId: string;
  playerUuid: string;
  playerName: string;
  canBuy: boolean;
  canSell: boolean;
  expiresAt: string;
}

export interface WebTradeOrder {
  orderId: string;
  serverId: string;
  playerUuid: string;
  playerName: string;
  type: "BUY" | "SELL" | "REFRESH";
  stockId: string | null;
  shares: number;
  claimToken: string;
  createdAt: string;
}
