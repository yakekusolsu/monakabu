export interface StockState {
  id: string; symbol: string; displayName: string; price: number; previousPrice: number;
  changePercent: number; dailyHigh: number; dailyLow: number; trend: "BULL" | "NORMAL" | "BEAR";
  halted: boolean; haltedUntil: string | null; bankrupt: boolean; updatedAt: string;
}

export interface SeasonState {
  id: number; number: number; startsAt: string; endsAt: string;
  status: "OPEN" | "CLOSING" | "SETTLEMENT" | "CLOSED" | "OPENING"; settledAt: string | null;
}

export interface MarketNews {
  instanceId: string; eventId: string; stockId: string; name: string; message: string;
  stockIds?: string[];
  modifier: number; startedAt: string; endsAt: string;
}

export interface DailyStockRange {
  stockId: string; symbol: string; displayName: string;
  currentPrice: number; dailyHigh: number; dailyLow: number; range: number; rangePercent: number;
}

export interface DailyMarketReport {
  reportDate: string; generatedAt: string; stocks: DailyStockRange[];
}

export interface MarketState {
  currency: string; marketOpen: boolean; season: SeasonState | null;
  stocks: StockState[]; activeEvents: MarketNews[]; dailyReport: DailyMarketReport | null;
}

export interface RealtimeEvent { type: string; data: unknown; timestamp: string; }
export interface HistoryPoint { price: number; recordedAt: string; }
