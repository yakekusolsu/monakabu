import pg from "pg";
import { randomBytes } from "node:crypto";
import type { DailyMarketReport, IngestEvent, MarketNews, MarketState, MonaPriceItemState, MonaPriceState, SeasonState, StockState, WebAccountSnapshot, WebIdentity, WebTradeOrder } from "./types.js";

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
      CREATE TABLE IF NOT EXISTS realtime_item_prices (
        id BIGSERIAL PRIMARY KEY,
        server_id VARCHAR(64) NOT NULL,
        material_id VARCHAR(96) NOT NULL,
        price NUMERIC(20,2) NOT NULL,
        recorded_at TIMESTAMPTZ NOT NULL,
        event_id VARCHAR(96) NOT NULL,
        UNIQUE(server_id, event_id, material_id)
      );
      CREATE INDEX IF NOT EXISTS idx_realtime_item_prices_lookup
        ON realtime_item_prices(server_id, material_id, recorded_at DESC);
      CREATE TABLE IF NOT EXISTS web_link_codes (
        code_hash CHAR(64) PRIMARY KEY,
        server_id VARCHAR(64) NOT NULL,
        player_uuid VARCHAR(36) NOT NULL,
        player_name VARCHAR(64) NOT NULL,
        can_buy BOOLEAN NOT NULL,
        can_sell BOOLEAN NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        used_at TIMESTAMPTZ NULL
      );
      CREATE INDEX IF NOT EXISTS idx_web_link_codes_player
        ON web_link_codes(server_id, player_uuid, expires_at DESC);
      CREATE TABLE IF NOT EXISTS web_sessions (
        session_hash CHAR(64) PRIMARY KEY,
        server_id VARCHAR(64) NOT NULL,
        player_uuid VARCHAR(36) NOT NULL,
        player_name VARCHAR(64) NOT NULL,
        can_buy BOOLEAN NOT NULL,
        can_sell BOOLEAN NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        revoked_at TIMESTAMPTZ NULL
      );
      CREATE INDEX IF NOT EXISTS idx_web_sessions_player
        ON web_sessions(server_id, player_uuid, expires_at DESC);
      CREATE TABLE IF NOT EXISTS web_accounts (
        server_id VARCHAR(64) NOT NULL,
        player_uuid VARCHAR(36) NOT NULL,
        balance NUMERIC(20,2) NOT NULL,
        portfolio JSONB NOT NULL,
        captured_at TIMESTAMPTZ NOT NULL,
        PRIMARY KEY(server_id, player_uuid)
      );
      CREATE TABLE IF NOT EXISTS web_trade_orders (
        order_id VARCHAR(36) PRIMARY KEY,
        server_id VARCHAR(64) NOT NULL,
        player_uuid VARCHAR(36) NOT NULL,
        player_name VARCHAR(64) NOT NULL,
        order_type VARCHAR(12) NOT NULL,
        stock_id VARCHAR(64) NULL,
        shares BIGINT NOT NULL,
        status VARCHAR(20) NOT NULL,
        claim_token VARCHAR(64) NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        claimed_at TIMESTAMPTZ NULL,
        completed_at TIMESTAMPTZ NULL,
        result JSONB NULL
      );
      CREATE INDEX IF NOT EXISTS idx_web_trade_orders_claim
        ON web_trade_orders(server_id, status, created_at);
      CREATE INDEX IF NOT EXISTS idx_web_trade_orders_player
        ON web_trade_orders(server_id, player_uuid, created_at DESC);
    `);
  }

  async registerLinkCode(input: { serverId: string; codeHash: string; playerUuid: string; playerName: string; canBuy: boolean; canSell: boolean; expiresAt: Date; account: WebAccountSnapshot }): Promise<void> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query(
        "UPDATE web_link_codes SET used_at=NOW() WHERE server_id=$1 AND player_uuid=$2 AND used_at IS NULL",
        [input.serverId, input.playerUuid],
      );
      await client.query(
        `INSERT INTO web_link_codes(code_hash,server_id,player_uuid,player_name,can_buy,can_sell,expires_at)
         VALUES($1,$2,$3,$4,$5,$6,$7)
         ON CONFLICT(code_hash) DO UPDATE SET player_uuid=EXCLUDED.player_uuid,player_name=EXCLUDED.player_name,
           can_buy=EXCLUDED.can_buy,can_sell=EXCLUDED.can_sell,expires_at=EXCLUDED.expires_at,created_at=NOW(),used_at=NULL`,
        [input.codeHash, input.serverId, input.playerUuid, input.playerName, input.canBuy, input.canSell, input.expiresAt],
      );
      await this.upsertAccount(client, input.serverId, input.playerUuid, input.account);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async exchangeLinkCode(serverId: string, codeHash: string, sessionHash: string, sessionExpiresAt: Date): Promise<WebIdentity | null> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query<{ player_uuid: string; player_name: string; can_buy: boolean; can_sell: boolean }>(
        `SELECT player_uuid,player_name,can_buy,can_sell FROM web_link_codes
         WHERE code_hash=$1 AND server_id=$2 AND used_at IS NULL AND expires_at>NOW() FOR UPDATE`,
        [codeHash, serverId],
      );
      const row = result.rows[0];
      if (!row) {
        await client.query("ROLLBACK");
        return null;
      }
      await client.query("UPDATE web_link_codes SET used_at=NOW() WHERE code_hash=$1", [codeHash]);
      await client.query(
        `INSERT INTO web_sessions(session_hash,server_id,player_uuid,player_name,can_buy,can_sell,expires_at)
         VALUES($1,$2,$3,$4,$5,$6,$7)`,
        [sessionHash, serverId, row.player_uuid, row.player_name, row.can_buy, row.can_sell, sessionExpiresAt],
      );
      await client.query("COMMIT");
      return { serverId, playerUuid: row.player_uuid, playerName: row.player_name, canBuy: row.can_buy, canSell: row.can_sell, expiresAt: sessionExpiresAt.toISOString() };
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async authenticate(sessionHash: string): Promise<WebIdentity | null> {
    const result = await this.pool.query<{ server_id: string; player_uuid: string; player_name: string; can_buy: boolean; can_sell: boolean; expires_at: Date }>(
      `UPDATE web_sessions SET last_seen_at=NOW() WHERE session_hash=$1 AND revoked_at IS NULL AND expires_at>NOW()
       RETURNING server_id,player_uuid,player_name,can_buy,can_sell,expires_at`,
      [sessionHash],
    );
    const row = result.rows[0];
    return row ? { serverId: row.server_id, playerUuid: row.player_uuid, playerName: row.player_name, canBuy: row.can_buy, canSell: row.can_sell, expiresAt: row.expires_at.toISOString() } : null;
  }

  async revokeSession(sessionHash: string): Promise<void> {
    await this.pool.query("UPDATE web_sessions SET revoked_at=NOW() WHERE session_hash=$1", [sessionHash]);
  }

  async revokePlayerSessions(serverId: string, playerUuid: string): Promise<void> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query("UPDATE web_sessions SET revoked_at=NOW() WHERE server_id=$1 AND player_uuid=$2 AND revoked_at IS NULL", [serverId, playerUuid]);
      await client.query("UPDATE web_link_codes SET used_at=NOW() WHERE server_id=$1 AND player_uuid=$2 AND used_at IS NULL", [serverId, playerUuid]);
      await client.query("UPDATE web_trade_orders SET status='CANCELLED',completed_at=NOW(),result='{\"reason\":\"UNLINKED\"}'::jsonb WHERE server_id=$1 AND player_uuid=$2 AND status='PENDING'", [serverId, playerUuid]);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async webAccount(identity: WebIdentity): Promise<{ identity: WebIdentity; account: WebAccountSnapshot | null; orders: unknown[] }> {
    const [accountResult, orderResult] = await Promise.all([
      this.pool.query<{ balance: string; portfolio: WebAccountSnapshot["portfolio"]; captured_at: Date }>(
        "SELECT balance,portfolio,captured_at FROM web_accounts WHERE server_id=$1 AND player_uuid=$2",
        [identity.serverId, identity.playerUuid],
      ),
      this.pool.query(
        `SELECT order_id AS "orderId",order_type AS "type",stock_id AS "stockId",shares,status,created_at AS "createdAt",
                completed_at AS "completedAt",result
         FROM web_trade_orders WHERE server_id=$1 AND player_uuid=$2 ORDER BY created_at DESC LIMIT 30`,
        [identity.serverId, identity.playerUuid],
      ),
    ]);
    const account = accountResult.rows[0];
    return {
      identity,
      account: account ? { balance: Number(account.balance), portfolio: account.portfolio, capturedAt: account.captured_at.toISOString() } : null,
      orders: orderResult.rows.map((row) => normalizeNumbers(row as Record<string, unknown>)),
    };
  }

  async createWebOrder(input: { orderId: string; identity: WebIdentity; type: "BUY" | "SELL" | "REFRESH"; stockId: string | null; shares: number }): Promise<void> {
    await this.pool.query(
      `INSERT INTO web_trade_orders(order_id,server_id,player_uuid,player_name,order_type,stock_id,shares,status)
       VALUES($1,$2,$3,$4,$5,$6,$7,'PENDING')`,
      [input.orderId, input.identity.serverId, input.identity.playerUuid, input.identity.playerName, input.type, input.stockId, input.shares],
    );
  }

  async claimWebOrders(serverId: string, limit: number): Promise<WebTradeOrder[]> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query(
        "UPDATE web_trade_orders SET status='PENDING',claim_token=NULL,claimed_at=NULL WHERE server_id=$1 AND status='CLAIMED' AND claimed_at<NOW()-INTERVAL '2 minutes'",
        [serverId],
      );
      const result = await client.query<{ order_id: string; player_uuid: string; player_name: string; order_type: WebTradeOrder["type"]; stock_id: string | null; shares: string; created_at: Date }>(
        `SELECT order_id,player_uuid,player_name,order_type,stock_id,shares,created_at FROM web_trade_orders
         WHERE server_id=$1 AND status='PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT $2`,
        [serverId, limit],
      );
      const orders: WebTradeOrder[] = [];
      for (const row of result.rows) {
        const claimToken = randomBytes(24).toString("hex");
        await client.query(
          "UPDATE web_trade_orders SET status='CLAIMED',claim_token=$2,claimed_at=NOW() WHERE order_id=$1",
          [row.order_id, claimToken],
        );
        orders.push({
          orderId: row.order_id, serverId, playerUuid: row.player_uuid, playerName: row.player_name,
          type: row.order_type, stockId: row.stock_id, shares: Number(row.shares), claimToken,
          createdAt: row.created_at.toISOString(),
        });
      }
      await client.query("COMMIT");
      return orders;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async completeWebOrder(input: { serverId: string; orderId: string; claimToken: string; success: boolean; result: unknown; account: WebAccountSnapshot }): Promise<boolean> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const order = await client.query<{ player_uuid: string }>(
        `UPDATE web_trade_orders SET status=$4,completed_at=NOW(),result=$5::jsonb
         WHERE order_id=$1 AND server_id=$2 AND claim_token=$3 AND status='CLAIMED'
         RETURNING player_uuid`,
        [input.orderId, input.serverId, input.claimToken, input.success ? "COMPLETED" : "FAILED", JSON.stringify(input.result)],
      );
      const row = order.rows[0];
      if (!row) {
        const completed = await client.query("SELECT 1 FROM web_trade_orders WHERE order_id=$1 AND server_id=$2 AND status IN ('COMPLETED','FAILED')", [input.orderId, input.serverId]);
        await client.query("COMMIT");
        return completed.rowCount === 1;
      }
      await this.upsertAccount(client, input.serverId, row.player_uuid, input.account);
      await client.query("COMMIT");
      return true;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
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
      } else if (event.type === "monaprice.snapshot" && state.monaPrice) {
        await client.query(
          `INSERT INTO realtime_item_prices(server_id,material_id,price,recorded_at,event_id)
           SELECT $1,item.id,item.price,$3,$4
           FROM jsonb_to_recordset($2::jsonb) AS item(id VARCHAR(96),price NUMERIC(20,2))
           WHERE item.id ~ '^[A-Z0-9_]{1,96}$' AND item.price >= 0
           ON CONFLICT(server_id,event_id,material_id) DO NOTHING`,
          [event.serverId, JSON.stringify(state.monaPrice.items), event.timestamp, event.eventId],
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

  async monaPriceHistory(serverId: string, materialId: string, since: Date, limit: number): Promise<Array<{ price: number; recordedAt: string }>> {
    const result = await this.pool.query<{ price: string; recorded_at: Date }>(
      `SELECT price,recorded_at FROM (
         SELECT price,recorded_at FROM realtime_item_prices
         WHERE server_id=$1 AND material_id=$2 AND recorded_at >= $3
         ORDER BY recorded_at DESC LIMIT $4
       ) points ORDER BY recorded_at`,
      [serverId, materialId, since, limit],
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

  async pruneWebData(): Promise<void> {
    await this.pool.query("DELETE FROM web_link_codes WHERE expires_at<NOW()-INTERVAL '1 day'");
    await this.pool.query("DELETE FROM web_sessions WHERE expires_at<NOW()-INTERVAL '7 days' OR revoked_at<NOW()-INTERVAL '7 days'");
    await this.pool.query("DELETE FROM web_trade_orders WHERE completed_at<NOW()-INTERVAL '30 days'");
    await this.pool.query("DELETE FROM realtime_item_prices WHERE recorded_at<NOW()-INTERVAL '30 days'");
  }

  private async getSnapshotWithClient(client: pg.PoolClient, serverId: string): Promise<{ state: MarketState; sequence: number }> {
    const result = await client.query<{ state: MarketState; last_sequence: string }>(
      "SELECT state,last_sequence FROM realtime_state WHERE server_id=$1",
      [serverId],
    );
    const row = result.rows[0];
    return row ? { state: row.state, sequence: Number(row.last_sequence) } : { state: emptyState(), sequence: 0 };
  }

  private async upsertAccount(client: pg.PoolClient, serverId: string, playerUuid: string, account: WebAccountSnapshot): Promise<void> {
    await client.query(
      `INSERT INTO web_accounts(server_id,player_uuid,balance,portfolio,captured_at) VALUES($1,$2,$3,$4::jsonb,$5)
       ON CONFLICT(server_id,player_uuid) DO UPDATE SET balance=EXCLUDED.balance,portfolio=EXCLUDED.portfolio,captured_at=EXCLUDED.captured_at`,
      [serverId, playerUuid, account.balance, JSON.stringify(account.portfolio), account.capturedAt],
    );
  }
}

function normalizeNumbers(value: Record<string, unknown>): Record<string, unknown> {
  return { ...value, shares: Number(value.shares) };
}

export function emptyState(): MarketState {
  return { currency: "MONA", marketOpen: false, season: null, stocks: [], activeEvents: [], dailyReport: null, monaPrice: null };
}

export function reduceState(previous: MarketState, event: IngestEvent): MarketState {
  if (event.type === "market.snapshot") return normalizeSnapshot(event.data, previous.dailyReport ?? null, previous.monaPrice ?? null);
  const state: MarketState = { ...structuredClone(previous), dailyReport: previous.dailyReport ?? null, monaPrice: previous.monaPrice ?? null };
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
  } else if (event.type === "market.daily.report") {
    state.dailyReport = event.data as DailyMarketReport;
  } else if (event.type === "monaprice.snapshot") {
    state.monaPrice = normalizeMonaPrice(event.data);
  }
  return state;
}

function normalizeSnapshot(value: unknown, previousDailyReport: DailyMarketReport | null, previousMonaPrice: MonaPriceState | null): MarketState {
  const candidate = value as Partial<MarketState> | null;
  return {
    currency: typeof candidate?.currency === "string" ? candidate.currency : "MONA",
    marketOpen: candidate?.marketOpen === true,
    season: candidate?.season ?? null,
    stocks: Array.isArray(candidate?.stocks) ? candidate.stocks : [],
    activeEvents: Array.isArray(candidate?.activeEvents) ? candidate.activeEvents : [],
    dailyReport: candidate?.dailyReport ?? previousDailyReport,
    monaPrice: candidate?.monaPrice ? normalizeMonaPrice(candidate.monaPrice) : previousMonaPrice,
  };
}

function normalizeMonaPrice(value: unknown): MonaPriceState | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as Partial<MonaPriceState>;
  const rawItems = Array.isArray(candidate.items) ? candidate.items : [];
  const items: MonaPriceItemState[] = rawItems.flatMap((raw) => {
    if (!raw || typeof raw !== "object") return [];
    const item = raw as Partial<MonaPriceItemState>;
    const numeric = [item.price, item.previousPrice, item.changePercent, item.buyPrice, item.sellPrice,
      item.highPrice, item.lowPrice, item.buyVolume, item.sellVolume];
    if (typeof item.id !== "string" || !/^[A-Z0-9_]{1,96}$/.test(item.id)
      || typeof item.displayName !== "string" || item.displayName.length > 128
      || typeof item.category !== "string" || !/^[a-z0-9_-]{1,64}$/.test(item.category)
      || typeof item.categoryName !== "string" || item.categoryName.length > 64
      || numeric.some((entry) => !Number.isFinite(entry)) || !Number.isFinite(Date.parse(item.updatedAt ?? ""))) return [];
    return [{ ...item } as MonaPriceItemState];
  });
  const index = candidate.index;
  if (!index || !Number.isFinite(index.current) || !Number.isFinite(index.previous)
    || !Number.isFinite(index.changePercent) || !Number.isFinite(Date.parse(index.updatedAt ?? ""))
    || !Number.isFinite(Date.parse(candidate.capturedAt ?? ""))) return null;
  return {
    currency: typeof candidate.currency === "string" && candidate.currency.length <= 16 ? candidate.currency : "MONA",
    index,
    nextUpdateSeconds: Number.isFinite(candidate.nextUpdateSeconds) ? Math.max(0, Number(candidate.nextUpdateSeconds)) : 0,
    capturedAt: candidate.capturedAt!,
    sourceVersion: typeof candidate.sourceVersion === "string" ? candidate.sourceVersion.slice(0, 32) : "unknown",
    items,
  };
}
