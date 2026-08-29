import http from "node:http";
import cors from "cors";
import express from "express";
import helmet from "helmet";
import { WebSocket, WebSocketServer } from "ws";
import { verifySignature, timestampIsFresh } from "./security.js";
import { Store } from "./store.js";
import type { IngestEvent } from "./types.js";

const port = Number(process.env.PORT ?? 10000);
const databaseUrl = required("DATABASE_URL");
const sharedSecret = required("MONAKABU_SHARED_SECRET");
if (sharedSecret.length < 32) throw new Error("MONAKABU_SHARED_SECRET must contain at least 32 characters");
const expectedServerId = process.env.MONAKABU_SERVER_ID?.trim() || null;
const maxSignatureAge = Number(process.env.SIGNATURE_MAX_AGE_SECONDS ?? 300);
const allowedOrigins = (process.env.ALLOWED_ORIGINS ?? "http://localhost:5173")
  .split(",").map((origin) => origin.trim()).filter(Boolean);

const store = new Store(databaseUrl, process.env.DATABASE_SSL === "true");
await store.migrate();

const app = express();
app.set("trust proxy", 1);
app.disable("x-powered-by");
app.use(helmet({ crossOriginResourcePolicy: { policy: "cross-origin" } }));
app.use(cors({
  origin: (origin, callback) => callback(null, originAllowed(origin)),
  methods: ["GET"],
  maxAge: 86400,
}));

app.get("/", (_request, response) => {
  response.json({ service: "MonaKabu Realtime API", status: "ok", schemaVersion: 1 });
});

app.get("/health", (_request, response) => {
  response.json({ status: "ok", timestamp: new Date().toISOString() });
});

app.post("/v1/ingest", express.raw({ type: "application/json", limit: "512kb" }), async (request, response) => {
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
  return origin === undefined || allowedOrigins.includes(origin);
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
  webSockets.close();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await store.close();
  process.exit(0);
}

process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));
