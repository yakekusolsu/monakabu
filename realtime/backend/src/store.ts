import pg from "pg";
import type { IngestEvent, MarketNews, MarketState, SeasonState, StockState } from "./types.js";

const { Pool } = pg;

export interface StoredEvent {
  duplicate: boolean;
  sequence: number;
  state: MarketState;
}

export class Store {
  private readonly pool: pg.Pool;

  constructor(databaseUrl: string, useSsl: boolean) {
    this.pool = new Pool({
      connectionString: databaseUrl,
      ssl: useSsl ? { rejectUnauthorized: false } : undefined,
      max: Number(process.env.DATABASE_POOL_SIZE ?? 10),
    });
  }

  async migrate(): Promise<void> {
    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS realtime_events (
        sequence BIGSERIAL PRIMARY KEY,
        server_id VARCHAR(64) NOT NULL,
        event_id VARCHAR(96) NOT NULL,
        event_type VARCHAR(64) NOT NULL,
        occurred_at TIMESTAMPTZ NOT NULL,
        received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        payload JSONB NOT NULL,
        UNIQUE(server_id, event_id)
      );
      CREATE TABLE IF NOT EXISTS realtime_state (
        server_id VARCHAR(64) PRIMARY KEY,
        state JSONB NOT NULL,
        last_sequence BIGINT NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE IF NOT EXISTS realtime_prices (
        id BIGSERIAL PRIMARY KEY,
        server_id VARCHAR(64) NOT NULL,
        stock_id VARCHAR(64) NOT NULL,
        price NUMERIC(20,2) NOT NULL,
        recorded_at TIMESTAMPTZ NOT NULL,
        event_id VARCHAR(96) NOT NULL,
        UNIQUE(server_id, event_id, stock_id)
      );
      CREATE INDEX IF NOT EXISTS idx_realtime_events_server_sequence
        ON realtime_events(server_id, sequence DESC);
      CREATE INDEX IF NOT EXISTS idx_realtime_prices_lookup
        ON realtime_prices(server_id, stock_id, recorded_at DESC);
    `);
  }

  async ingest(event: IngestEvent): Promise<StoredEvent> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const inserted = await client.query<{ sequence: string }>(
        `INSERT INTO realtime_events(server_id,event_id,event_type,occurred_at,payload)
         VALUES($1,$2,$3,$4,$5::jsonb)
         ON CONFLICT(server_id,event_id) DO NOTHING
         RETURNING sequence`,
        [event.serverId, event.eventId, event.type, event.timestamp, JSON.stringify(event)],
      );

      if (inserted.rowCount === 0) {
        const existing = await client.query<{ sequence: string }>(
          "SELECT sequence FROM realtime_events WHERE server_id=$1 AND event_id=$2",
          [event.serverId, event.eventId],
        );
        const snapshot = await this.getSnapshotWithClient(client, event.serverId);
        await client.query("COMMIT");
        return { duplicate: true, sequence: Number(existing.rows[0]?.sequence ?? snapshot.sequence), state: snapshot.state };
      }

      const sequence = Number(inserted.rows[0]!.sequence);
      await client.query(
        `INSERT INTO realtime_state(server_id,state,last_sequence)
         VALUES($1,$2::jsonb,0) ON CONFLICT(server_id) DO NOTHING`,
        [event.serverId, JSON.stringify(emptyState())],
      );
      const locked = await client.query<{ state: MarketState }>(
        "SELECT state FROM realtime_state WHERE server_id=$1 FOR UPDATE",
        [event.serverId],
      );
      const state = reduceState(locked.rows[0]?.state ?? emptyState(), event);
      await client.query(
        "UPDATE realtime_state SET state=$2::jsonb,last_sequence=$3,updated_at=NOW() WHERE server_id=$1",
        [event.serverId, JSON.stringify(state), sequence],
      );

      if (event.type === "stock.price.changed") {
        const stock = event.data as StockState;
        await client.query(
          `INSERT INTO realtime_prices(server_id,stock_id,price,recorded_at,event_id)
           VALUES($1,$2,$3,$4,$5) ON CONFLICT(server_id,event_id,stock_id) DO NOTHING`,
          [event.serverId, stock.id, stock.price, stock.updatedAt || event.timestamp, event.eventId],
        );
      }
      await client.query("COMMIT");
      return { duplicate: false, sequence, state };
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async snapshot(serverId: string): Promise<{ state: MarketState; sequence: number; updatedAt: string | null }> {
    const result = await this.pool.query<{ state: MarketState; last_sequence: string; updated_at: Date }>(
      "SELECT state,last_sequence,updated_at FROM realtime_state WHERE server_id=$1",
      [serverId],
    );
    const row = result.rows[0];
    return row
      ? { state: row.state, sequence: Number(row.last_sequence), updatedAt: row.updated_at.toISOString() }
      : { state: emptyState(), sequence: 0, updatedAt: null };
  }

  async history(serverId: string, stockId: string, since: Date, limit: number): Promise<Array<{ price: number; recordedAt: string }>> {
    const result = await this.pool.query<{ price: string; recorded_at: Date }>(
      `SELECT price,recorded_at FROM (
         SELECT price,recorded_at FROM realtime_prices
         WHERE server_id=$1 AND stock_id=$2 AND recorded_at >= $3
         ORDER BY recorded_at DESC LIMIT $4
       ) points ORDER BY recorded_at`,
      [serverId, stockId, since, limit],
    );
    return result.rows.map((row) => ({ price: Number(row.price), recordedAt: row.recorded_at.toISOString() }));
  }

  async servers(): Promise<Array<{ serverId: string; updatedAt: string }>> {
    const result = await this.pool.query<{ server_id: string; updated_at: Date }>(
      "SELECT server_id,updated_at FROM realtime_state ORDER BY server_id",
    );
    return result.rows.map((row) => ({ serverId: row.server_id, updatedAt: row.updated_at.toISOString() }));
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  private async getSnapshotWithClient(client: pg.PoolClient, serverId: string): Promise<{ state: MarketState; sequence: number }> {
    const result = await client.query<{ state: MarketState; last_sequence: string }>(
      "SELECT state,last_sequence FROM realtime_state WHERE server_id=$1",
      [serverId],
    );
    const row = result.rows[0];
    return row ? { state: row.state, sequence: Number(row.last_sequence) } : { state: emptyState(), sequence: 0 };
  }
}

export function emptyState(): MarketState {
  return { currency: "MONA", marketOpen: false, season: null, stocks: [], activeEvents: [] };
}

export function reduceState(previous: MarketState, event: IngestEvent): MarketState {
  if (event.type === "market.snapshot") return normalizeSnapshot(event.data);
  const state: MarketState = structuredClone(previous);
  if (event.type === "stock.price.changed") {
    const stock = event.data as StockState;
    state.stocks = [...state.stocks.filter((item) => item.id !== stock.id), stock]
      .sort((left, right) => left.id.localeCompare(right.id));
  } else if (event.type === "season.started" || event.type === "season.ended") {
    state.season = event.data as SeasonState;
    state.marketOpen = state.season.status === "OPEN";
  } else if (event.type === "market.event.started") {
    const news = event.data as MarketNews;
    state.activeEvents = [...state.activeEvents.filter((item) => item.instanceId !== news.instanceId), news];
  } else if (event.type === "market.event.ended") {
    const news = event.data as MarketNews;
    state.activeEvents = state.activeEvents.filter((item) => item.instanceId !== news.instanceId);
  }
  return state;
}

function normalizeSnapshot(value: unknown): MarketState {
  const candidate = value as Partial<MarketState> | null;
  return {
    currency: typeof candidate?.currency === "string" ? candidate.currency : "MONA",
    marketOpen: candidate?.marketOpen === true,
    season: candidate?.season ?? null,
    stocks: Array.isArray(candidate?.stocks) ? candidate.stocks : [],
    activeEvents: Array.isArray(candidate?.activeEvents) ? candidate.activeEvents : [],
  };
}
