import http from "node:http";
import { createHash, randomBytes } from "node:crypto";
import cors from "cors";
import express from "express";
import helmet from "helmet";
import { WebSocket, WebSocketServer } from "ws";
import { verifySignature, timestampIsFresh } from "./security.js";
import { Store } from "./store.js";
import type { IngestEvent, WebAccountSnapshot, WebIdentity } from "./types.js";

const port = Number(process.env.PORT ?? 10000);
const databaseUrl = required("DATABASE_URL");
const sharedSecret = required("MONAKABU_SHARED_SECRET");
if (sharedSecret.length < 32) throw new Error("MONAKABU_SHARED_SECRET must contain at least 32 characters");
const expectedServerId = process.env.MONAKABU_SERVER_ID?.trim() || null;
const maxSignatureAge = Number(process.env.SIGNATURE_MAX_AGE_SECONDS ?? 300);
const sessionDays = Math.max(1, Math.min(90, Number(process.env.WEB_SESSION_DAYS ?? 14)));
const maxWebShares = Math.max(1, Number(process.env.WEB_MAX_SHARES_PER_ORDER ?? 1000));
const allowedOrigins = new Set([
  "https://monakabu-realtime-dashboard.vercel.app",
  ...(process.env.ALLOWED_ORIGINS ?? "http://localhost:5173")
    .split(",").map((origin) => origin.trim()).filter(Boolean),
]);

const store = new Store(databaseUrl, process.env.DATABASE_SSL === "true");
await store.migrate();
await store.pruneWebData();

const app = express();
app.set("trust proxy", 1);
app.disable("x-powered-by");
app.use(helmet({ crossOriginResourcePolicy: { policy: "cross-origin" } }));
app.use(cors({
  origin: (origin, callback) => callback(null, originAllowed(origin)),
  methods: ["GET", "POST"],
  allowedHeaders: ["Authorization", "Content-Type"],
  maxAge: 86400,
}));

app.get("/", (_request, response) => {
  response.json({ service: "MonaKabu Realtime API", status: "ok", schemaVersion: 1 });
});

app.get("/health", (_request, response) => {
  response.json({ status: "ok", timestamp: new Date().toISOString() });
});

app.post("/v1/ingest", express.raw({ type: "application/json", limit: "2mb" }), async (request, response) => {
  try {
    if (!Buffer.isBuffer(request.body)) return void response.status(415).json({ error: "application/json required" });
    const timestamp = header(request.headers["x-monakabu-timestamp"]);
    const signature = header(request.headers["x-monakabu-signature"]);
    const headerServerId = header(request.headers["x-monakabu-server"]);
    if (!timestampIsFresh(timestamp, maxSignatureAge)) return void response.status(401).json({ error: "stale timestamp" });
    if (!verifySignature(sharedSecret, timestamp, request.body, signature)) return void response.status(401).json({ error: "invalid signature" });

    const event = JSON.parse(request.body.toString("utf8")) as IngestEvent;
    const validationError = validateEvent(event, headerServerId);
    if (validationError) return void response.status(400).json({ error: validationError });
    if (expectedServerId && event.serverId !== expectedServerId) return void response.status(403).json({ error: "server not allowed" });

    const stored = await store.ingest(event);
    if (!stored.duplicate) broadcast(event.serverId, { kind: "event", sequence: stored.sequence, event });
    response.status(stored.duplicate ? 200 : 202).json({ accepted: true, duplicate: stored.duplicate, sequence: stored.sequence });
  } catch (error) {
    console.error("ingest failed", error);
    response.status(500).json({ error: "ingest failed" });
  }
});

app.post("/v1/plugin/link-code", express.raw({ type: "application/json", limit: "64kb" }), async (request, response) => {
  try {
    const body = signedPluginBody(request, response);
    if (!body) return;
    const value = JSON.parse(body.toString("utf8")) as Record<string, unknown>;
    const serverId = stringValue(value.serverId);
    const code = stringValue(value.code).toUpperCase();
    const playerUuid = stringValue(value.playerUuid);
    const playerName = stringValue(value.playerName);
    const canBuy = value.canBuy === true;
    const canSell = value.canSell === true;
    const expiresAt = new Date(stringValue(value.expiresAt));
    const account = validAccount(value.account);
    if (!validServerId(serverId) || (expectedServerId && serverId !== expectedServerId)
      || !/^[A-HJ-NP-Z2-9]{8}$/.test(code) || !uuidPattern.test(playerUuid)
      || playerName.length < 1 || playerName.length > 64 || (!canBuy && !canSell) || !Number.isFinite(expiresAt.getTime())
      || expiresAt.getTime() <= Date.now() || expiresAt.getTime() > Date.now() + 15 * 60_000 || !account) {
      return void response.status(400).json({ error: "invalid link code payload" });
    }
    await store.registerLinkCode({ serverId, codeHash: hash(`${serverId}:${code}`), playerUuid, playerName, canBuy, canSell, expiresAt, account });
    response.status(202).json({ accepted: true });
  } catch (error) {
    console.error("link code registration failed", error);
    response.status(500).json({ error: "link code registration failed" });
  }
});

app.post("/v1/plugin/orders/claim", express.raw({ type: "application/json", limit: "16kb" }), async (request, response) => {
  try {
    const body = signedPluginBody(request, response);
    if (!body) return;
    const value = JSON.parse(body.toString("utf8")) as Record<string, unknown>;
    const serverId = stringValue(value.serverId);
    if (!validServerId(serverId) || (expectedServerId && serverId !== expectedServerId)) return void response.status(400).json({ error: "invalid serverId" });
    response.json({ orders: await store.claimWebOrders(serverId, 10) });
  } catch (error) {
    console.error("order claim failed", error);
    response.status(500).json({ error: "order claim failed" });
  }
});

app.post("/v1/plugin/unlink", express.raw({ type: "application/json", limit: "16kb" }), async (request, response) => {
  try {
    const body = signedPluginBody(request, response);
    if (!body) return;
    const value = JSON.parse(body.toString("utf8")) as Record<string, unknown>;
    const serverId = stringValue(value.serverId);
    const playerUuid = stringValue(value.playerUuid);
    if (!validServerId(serverId) || (expectedServerId && serverId !== expectedServerId) || !uuidPattern.test(playerUuid)) {
      return void response.status(400).json({ error: "invalid unlink payload" });
    }
    await store.revokePlayerSessions(serverId, playerUuid);
    response.json({ revoked: true });
  } catch (error) {
    console.error("unlink failed", error);
    response.status(500).json({ error: "unlink failed" });
  }
});

app.post("/v1/plugin/orders/result", express.raw({ type: "application/json", limit: "64kb" }), async (request, response) => {
  try {
    const body = signedPluginBody(request, response);
    if (!body) return;
    const value = JSON.parse(body.toString("utf8")) as Record<string, unknown>;
    const serverId = stringValue(value.serverId);
    const orderId = stringValue(value.orderId);
    const claimToken = stringValue(value.claimToken);
    const account = validAccount(value.account);
    if (!validServerId(serverId) || (expectedServerId && serverId !== expectedServerId)
      || !uuidPattern.test(orderId) || !/^[a-f0-9]{48}$/.test(claimToken) || typeof value.success !== "boolean" || !account) {
      return void response.status(400).json({ error: "invalid order result" });
    }
    const accepted = await store.completeWebOrder({ serverId, orderId, claimToken, success: value.success, result: value.result ?? {}, account });
    response.status(accepted ? 200 : 409).json({ accepted });
  } catch (error) {
    console.error("order result failed", error);
    response.status(500).json({ error: "order result failed" });
  }
});

app.post("/v1/auth/exchange", express.json({ limit: "8kb" }), async (request, response) => {
  try {
    if (!allowAttempt(`exchange:${request.ip}`, 10, 10 * 60_000)) return void response.status(429).json({ error: "too many attempts" });
    const serverId = stringValue(request.body?.serverId);
    const code = stringValue(request.body?.code).toUpperCase().replace(/\s/g, "");
    if (!validServerId(serverId) || !/^[A-HJ-NP-Z2-9]{8}$/.test(code)) return void response.status(400).json({ error: "invalid code" });
    const token = randomBytes(32).toString("base64url");
    const expiresAt = new Date(Date.now() + sessionDays * 86_400_000);
    const identity = await store.exchangeLinkCode(serverId, hash(`${serverId}:${code}`), hash(token), expiresAt);
    if (!identity) return void response.status(401).json({ error: "code expired or already used" });
    response.set("Cache-Control", "no-store").json({ token, identity });
  } catch (error) {
    console.error("code exchange failed", error);
    response.status(500).json({ error: "code exchange failed" });
  }
});

app.get("/v1/account", async (request, response) => {
  try {
    const identity = await authenticated(request);
    if (!identity) return void response.status(401).json({ error: "authentication required" });
    response.set("Cache-Control", "no-store").json(await store.webAccount(identity));
  } catch (error) {
    console.error("account lookup failed", error);
    response.status(500).json({ error: "account lookup failed" });
  }
});

app.post("/v1/orders", express.json({ limit: "8kb" }), async (request, response) => {
  try {
    const identity = await authenticated(request);
    if (!identity) return void response.status(401).json({ error: "authentication required" });
    if (!allowAttempt(`orders:${identity.serverId}:${identity.playerUuid}`, 20, 60_000)) return void response.status(429).json({ error: "too many orders" });
    const type = stringValue(request.body?.type).toUpperCase();
    const stockId = stringValue(request.body?.stockId);
    const shares = Number(request.body?.shares);
    const orderId = stringValue(request.body?.requestId);
    if ((type !== "BUY" && type !== "SELL") || !/^[a-z0-9_-]{1,64}$/.test(stockId)
      || !Number.isSafeInteger(shares) || shares < 1 || shares > maxWebShares || !uuidPattern.test(orderId)) {
      return void response.status(400).json({ error: "invalid order" });
    }
    if ((type === "BUY" && !identity.canBuy) || (type === "SELL" && !identity.canSell)) return void response.status(403).json({ error: "trade permission denied" });
    const market = await store.snapshot(identity.serverId);
    if (!market.state.marketOpen || !market.state.stocks.some((stock) => stock.id === stockId && !stock.halted && !stock.bankrupt)) {
      return void response.status(409).json({ error: "market or stock is not tradable" });
    }
    try {
      await store.createWebOrder({ orderId, identity, type, stockId, shares });
    } catch (error) {
      if (!isUniqueViolation(error)) throw error;
    }
    response.status(202).json({ accepted: true, orderId });
  } catch (error) {
    console.error("order creation failed", error);
    response.status(500).json({ error: "order creation failed" });
  }
});

app.post("/v1/account/refresh", express.json({ limit: "4kb" }), async (request, response) => {
  try {
    const identity = await authenticated(request);
    if (!identity) return void response.status(401).json({ error: "authentication required" });
    if (!allowAttempt(`refresh:${identity.serverId}:${identity.playerUuid}`, 10, 60_000)) return void response.status(429).json({ error: "too many refreshes" });
    const orderId = stringValue(request.body?.requestId);
    if (!uuidPattern.test(orderId)) return void response.status(400).json({ error: "invalid requestId" });
    try {
      await store.createWebOrder({ orderId, identity, type: "REFRESH", stockId: null, shares: 0 });
    } catch (error) {
      if (!isUniqueViolation(error)) throw error;
    }
    response.status(202).json({ accepted: true, orderId });
  } catch (error) {
    console.error("account refresh failed", error);
    response.status(500).json({ error: "account refresh failed" });
  }
});

app.post("/v1/auth/logout", async (request, response) => {
  const token = bearer(request.headers.authorization);
  if (token) await store.revokeSession(hash(token));
  response.status(204).end();
});

app.get("/v1/snapshot", async (request, response) => {
  try {
    const serverId = query(request.query.serverId, expectedServerId ?? "monaka-main");
    response.set("Cache-Control", "no-store").json(await store.snapshot(serverId));
  } catch (error) {
    console.error("snapshot failed", error);
    response.status(500).json({ error: "snapshot failed" });
  }
});

app.get("/v1/history", async (request, response) => {
  try {
    const serverId = query(request.query.serverId, expectedServerId ?? "monaka-main");
    const stockId = query(request.query.stockId, "");
    if (!/^[a-z0-9_-]{1,64}$/.test(stockId)) return void response.status(400).json({ error: "invalid stockId" });
    const period = query(request.query.period, "24h");
    const duration = periods[period];
    if (!duration) return void response.status(400).json({ error: "period must be 1h, 6h, 24h, or 7d" });
    const since = new Date(Date.now() - duration);
    const points = await store.history(serverId, stockId, since, 2048);
    response.set("Cache-Control", "public, max-age=15").json({ serverId, stockId, period, points });
  } catch (error) {
    console.error("history failed", error);
    response.status(500).json({ error: "history failed" });
  }
});

app.get("/v1/monaprice", async (request, response) => {
  try {
    const serverId = query(request.query.serverId, expectedServerId ?? "monaka-main");
    const snapshot = await store.snapshot(serverId);
    response.set("Cache-Control", "no-store").json({
      serverId,
      monaPrice: snapshot.state.monaPrice,
      sequence: snapshot.sequence,
      updatedAt: snapshot.updatedAt,
    });
  } catch (error) {
    console.error("MonaPrice snapshot failed", error);
    response.status(500).json({ error: "MonaPrice snapshot failed" });
  }
});

app.get("/v1/monaprice/history", async (request, response) => {
  try {
    const serverId = query(request.query.serverId, expectedServerId ?? "monaka-main");
    const materialId = query(request.query.itemId, "").toUpperCase();
    if (!/^[A-Z0-9_]{1,96}$/.test(materialId)) return void response.status(400).json({ error: "invalid itemId" });
    const period = query(request.query.period, "24h");
    const duration = periods[period];
    if (!duration) return void response.status(400).json({ error: "period must be 1h, 6h, 24h, or 7d" });
    const points = await store.monaPriceHistory(serverId, materialId, new Date(Date.now() - duration), 2048);
    response.set("Cache-Control", "public, max-age=15").json({ serverId, itemId: materialId, period, points });
  } catch (error) {
    console.error("MonaPrice history failed", error);
    response.status(500).json({ error: "MonaPrice history failed" });
  }
});

app.get("/v1/servers", async (_request, response) => {
  try {
    response.set("Cache-Control", "public, max-age=30").json({ servers: await store.servers() });
  } catch (error) {
    console.error("servers failed", error);
    response.status(500).json({ error: "servers failed" });
  }
});

const server = http.createServer(app);
const webSockets = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

interface LiveSocket extends WebSocket {
  serverId?: string;
  alive?: boolean;
}

server.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  if (url.pathname !== "/v1/stream" || !originAllowed(request.headers.origin)) {
    socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
    socket.destroy();
    return;
  }
  const serverId = url.searchParams.get("serverId") ?? expectedServerId ?? "monaka-main";
  if (!validServerId(serverId)) {
    socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
    socket.destroy();
    return;
  }
  webSockets.handleUpgrade(request, socket, head, (client) => {
    const live = client as LiveSocket;
    live.serverId = serverId;
    live.alive = true;
    webSockets.emit("connection", live, request);
  });
});

webSockets.on("connection", async (socket: LiveSocket) => {
  socket.on("pong", () => { socket.alive = true; });
  try {
    const snapshot = await store.snapshot(socket.serverId!);
    socket.send(JSON.stringify({ kind: "snapshot", ...snapshot }));
  } catch (error) {
    console.error("websocket snapshot failed", error);
    socket.close(1011, "snapshot unavailable");
  }
});

const heartbeat = setInterval(() => {
  for (const client of webSockets.clients as Set<LiveSocket>) {
    if (client.alive === false) {
      client.terminate();
      continue;
    }
    client.alive = false;
    client.ping();
  }
}, 30_000);
const webDataCleanup = setInterval(() => void store.pruneWebData().catch((error) => console.error("web data cleanup failed", error)), 24 * 60 * 60_000);

function broadcast(serverId: string, message: unknown): void {
  const payload = JSON.stringify(message);
  for (const client of webSockets.clients as Set<LiveSocket>) {
    if (client.readyState === WebSocket.OPEN && client.serverId === serverId) client.send(payload);
  }
}

function validateEvent(value: IngestEvent, headerServerId: string): string | null {
  if (!value || typeof value !== "object") return "invalid event";
  if (value.schemaVersion !== 1) return "unsupported schemaVersion";
  if (!/^RT-[0-9a-f-]{36}$/i.test(value.eventId)) return "invalid eventId";
  if (!validServerId(value.serverId) || value.serverId !== headerServerId) return "invalid serverId";
  if (!/^[a-z]+(?:\.[a-z]+){1,3}$/.test(value.type)) return "invalid type";
  if (typeof value.pluginVersion !== "string" || value.pluginVersion.length > 32) return "invalid pluginVersion";
  if (!Number.isFinite(Date.parse(value.timestamp))) return "invalid timestamp";
  if (typeof value.data !== "object" || value.data === null) return "invalid data";
  return null;
}

function validServerId(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,64}$/.test(value);
}

function originAllowed(origin?: string): boolean {
  return origin === undefined || allowedOrigins.has(origin);
}

function header(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] ?? "" : value ?? "";
}

function query(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function signedPluginBody(request: express.Request, response: express.Response): Buffer | null {
  if (!Buffer.isBuffer(request.body)) {
    response.status(415).json({ error: "application/json required" });
    return null;
  }
  const timestamp = header(request.headers["x-monakabu-timestamp"]);
  const signature = header(request.headers["x-monakabu-signature"]);
  const headerServerId = header(request.headers["x-monakabu-server"]);
  if (!timestampIsFresh(timestamp, maxSignatureAge) || !verifySignature(sharedSecret, timestamp, request.body, signature)) {
    response.status(401).json({ error: "invalid plugin signature" });
    return null;
  }
  try {
    const bodyServerId = stringValue((JSON.parse(request.body.toString("utf8")) as Record<string, unknown>).serverId);
    if (!validServerId(headerServerId) || bodyServerId !== headerServerId) {
      response.status(403).json({ error: "server mismatch" });
      return null;
    }
  } catch {
    response.status(400).json({ error: "invalid json" });
    return null;
  }
  return request.body;
}

async function authenticated(request: express.Request): Promise<WebIdentity | null> {
  const token = bearer(request.headers.authorization);
  return token ? store.authenticate(hash(token)) : null;
}

function bearer(value: string | undefined): string | null {
  const match = /^Bearer ([A-Za-z0-9_-]{40,100})$/.exec(value ?? "");
  return match?.[1] ?? null;
}

function validAccount(value: unknown): WebAccountSnapshot | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as Partial<WebAccountSnapshot>;
  if (!Number.isFinite(candidate.balance) || !Array.isArray(candidate.portfolio) || !Number.isFinite(Date.parse(candidate.capturedAt ?? ""))) return null;
  const portfolio = candidate.portfolio.filter((position) => position && typeof position === "object").map((position) => ({
    stockId: stringValue(position.stockId), shares: Number(position.shares), averageCost: Number(position.averageCost),
  }));
  if (portfolio.length !== candidate.portfolio.length || portfolio.some((position) => !/^[a-z0-9_-]{1,64}$/.test(position.stockId)
    || !Number.isSafeInteger(position.shares) || position.shares < 0 || !Number.isFinite(position.averageCost) || position.averageCost < 0)) return null;
  return { balance: Number(candidate.balance), portfolio, capturedAt: candidate.capturedAt! };
}

function hash(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function stringValue(value: unknown): string { return typeof value === "string" ? value.trim() : ""; }
function isUniqueViolation(error: unknown): boolean { return Boolean(error && typeof error === "object" && "code" in error && error.code === "23505"); }

const attempts = new Map<string, { count: number; resetsAt: number }>();
function allowAttempt(key: string, limit: number, windowMs: number): boolean {
  const now = Date.now();
  if (attempts.size > 10_000) {
    for (const [candidate, value] of attempts) if (value.resetsAt <= now) attempts.delete(candidate);
    if (attempts.size > 10_000 && !attempts.has(key)) return false;
  }
  const current = attempts.get(key);
  if (!current || current.resetsAt <= now) { attempts.set(key, { count: 1, resetsAt: now + windowMs }); return true; }
  if (current.count >= limit) return false;
  current.count++; return true;
}

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const periods: Record<string, number> = {
  "1h": 60 * 60 * 1000,
  "6h": 6 * 60 * 60 * 1000,
  "24h": 24 * 60 * 60 * 1000,
  "7d": 7 * 24 * 60 * 60 * 1000,
};

server.listen(port, "0.0.0.0", () => console.log(`MonaKabu Realtime API listening on ${port}`));

async function shutdown(signal: string): Promise<void> {
  console.log(`${signal}: shutting down`);
  clearInterval(heartbeat);
  clearInterval(webDataCleanup);
  webSockets.close();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await store.close();
  process.exit(0);
}

process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));
