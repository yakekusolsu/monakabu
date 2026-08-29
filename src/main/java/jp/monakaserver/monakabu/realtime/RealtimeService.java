package jp.monakaserver.monakabu.realtime;

import jp.monakaserver.monakabu.api.event.MarketEventEndEvent;
import jp.monakaserver.monakabu.api.event.MarketEventStartEvent;
import jp.monakaserver.monakabu.api.event.SeasonEndEvent;
import jp.monakaserver.monakabu.api.event.SeasonStartEvent;
import jp.monakaserver.monakabu.api.event.StockPriceChangeEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.RealtimeOutboxRepository;
import jp.monakaserver.monakabu.market.MarketEventService;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.model.ActiveMarketEvent;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.util.DurationParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Publishes committed public market state through a durable database outbox. */
public final class RealtimeService implements Listener, AutoCloseable {
    private static final int SCHEMA_VERSION = 1;

    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final DatabaseManager database;
    private final RealtimeOutboxRepository outbox;
    private final StockRegistry stocks;
    private final MarketEventService marketEvents;
    private final SeasonService seasons;
    private final HttpClient client;
    private final AtomicBoolean dispatching = new AtomicBoolean();

    private volatile boolean enabled;
    private volatile URI endpoint;
    private volatile String serverId;
    private volatile String secret;
    private volatile Duration requestTimeout = Duration.ofSeconds(8);
    private volatile Duration initialBackoff = Duration.ofSeconds(5);
    private volatile Duration maximumBackoff = Duration.ofMinutes(5);
    private volatile int maxAttempts;
    private volatile int batchSize = 25;
    private BukkitTask dispatchTask;
    private BukkitTask snapshotTask;
    private BukkitTask cleanupTask;

    public RealtimeService(JavaPlugin plugin, ConfigManager configs, DatabaseManager database,
                           RealtimeOutboxRepository outbox, StockRegistry stocks,
                           MarketEventService marketEvents, SeasonService seasons) {
        this.plugin = plugin;
        this.configs = configs;
        this.database = database;
        this.outbox = outbox;
        this.stocks = stocks;
        this.marketEvents = marketEvents;
        this.seasons = seasons;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void start() {
        stopTasks();
        enabled = configs.config().getBoolean("realtime.enabled", false);
        if (!enabled) return;

        try {
            endpoint = URI.create(configs.config().getString("realtime.endpoint", ""));
            serverId = configs.config().getString("realtime.server-id", "monaka-main");
            String environmentName = configs.config().getString("realtime.secret-environment-variable", "MONAKABU_REALTIME_SECRET");
            String environmentSecret = environmentName == null ? null : System.getenv(environmentName);
            secret = environmentSecret == null || environmentSecret.isBlank()
                    ? configs.config().getString("realtime.secret", "") : environmentSecret;
            boolean secure = "https".equalsIgnoreCase(endpoint.getScheme());
            boolean local = endpoint.getHost() != null && (endpoint.getHost().equals("localhost") || endpoint.getHost().equals("127.0.0.1"));
            if ((!secure && !local && !configs.config().getBoolean("realtime.allow-insecure-http", false))
                    || serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,64}")
                    || endpoint.getHost() == null || secret == null || secret.length() < 32) {
                enabled = false;
                plugin.getLogger().severe("Realtime disabled: HTTPS endpoint and a secret of at least 32 characters are required");
                return;
            }
            requestTimeout = DurationParser.parse(configs.config().getString("realtime.request-timeout", "8s"));
            initialBackoff = DurationParser.parse(configs.config().getString("realtime.retry.initial-backoff", "5s"));
            maximumBackoff = DurationParser.parse(configs.config().getString("realtime.retry.maximum-backoff", "5m"));
            maxAttempts = Math.max(0, configs.config().getInt("realtime.retry.max-attempts", 0));
            batchSize = Math.max(1, Math.min(100, configs.config().getInt("realtime.batch-size", 25)));

            Duration dispatchInterval = DurationParser.parse(configs.config().getString("realtime.dispatch-interval", "1s"));
            Duration snapshotInterval = DurationParser.parse(configs.config().getString("realtime.snapshot-interval", "60s"));
            long dispatchTicks = Math.max(20, dispatchInterval.toSeconds() * 20);
            long snapshotTicks = Math.max(20, snapshotInterval.toSeconds() * 20);
            dispatchTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::dispatch, 20, dispatchTicks);
            snapshotTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::publishSnapshot, 40, snapshotTicks);
            cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanup, 20L * 3600, 20L * 86400);
            publishSnapshot();
            plugin.getLogger().info("Realtime publishing enabled for " + serverId);
        } catch (RuntimeException error) {
            enabled = false;
            plugin.getLogger().log(Level.SEVERE, "Realtime configuration is invalid", error);
        }
    }

    public void reload() {
        start();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPriceChange(StockPriceChangeEvent event) {
        stocks.find(event.getStockId()).ifPresent(stock -> publish("stock.price.changed", stockData(stock)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeasonStart(SeasonStartEvent event) {
        publish("season.started", seasonData(event.getSeason()));
        publishSnapshot();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeasonEnd(SeasonEndEvent event) {
        publish("season.ended", seasonData(event.getSeason()));
        publishSnapshot();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMarketEventStart(MarketEventStartEvent event) {
        publish("market.event.started", marketEventData(event.getMarketEvent()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMarketEventEnd(MarketEventEndEvent event) {
        publish("market.event.ended", marketEventData(event.getMarketEvent()));
    }

    public void publishSnapshot() {
        if (!enabled) return;
        Map<String, Object> data = new LinkedHashMap<>();
        Season season = seasons.current();
        data.put("currency", configs.config().getString("currency.display-name", "MONA"));
        data.put("marketOpen", seasons.isOpen());
        data.put("season", season == null ? null : seasonData(season));
        List<Map<String, Object>> stockList = new ArrayList<>();
        for (StockSnapshot stock : stocks.all()) stockList.add(stockData(stock));
        data.put("stocks", stockList);
        List<Map<String, Object>> activeEvents = new ArrayList<>();
        for (ActiveMarketEvent event : marketEvents.activeEvents()) activeEvents.add(marketEventData(event));
        data.put("activeEvents", activeEvents);
        publish("market.snapshot", data);
    }

    private void publish(String type, Map<String, Object> data) {
        if (!enabled) return;
        String eventId = "RT-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("eventId", eventId);
        envelope.put("serverId", serverId);
        envelope.put("type", type);
        envelope.put("timestamp", Instant.ofEpochMilli(now).toString());
        envelope.put("pluginVersion", plugin.getPluginMeta().getVersion());
        envelope.put("data", data);
        String payload = JsonEncoder.encode(envelope);
        database.transaction(connection -> {
            outbox.enqueue(connection, eventId, type, payload, now);
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Realtime event could not be written to outbox: " + type, error);
            return null;
        });
    }

    private Map<String, Object> stockData(StockSnapshot stock) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", stock.definition().id());
        data.put("symbol", stock.definition().symbol());
        data.put("displayName", stock.definition().displayName());
        data.put("price", stock.price());
        data.put("previousPrice", stock.previousPrice());
        data.put("changePercent", stock.changePercent());
        data.put("dailyHigh", stock.dailyHigh());
        data.put("dailyLow", stock.dailyLow());
        data.put("trend", stock.trend());
        data.put("halted", stock.halted(Instant.now()));
        data.put("haltedUntil", stock.haltedUntil() == null ? null : stock.haltedUntil().toString());
        data.put("bankrupt", stock.bankrupt());
        data.put("updatedAt", stock.updatedAt().toString());
        return data;
    }

    private Map<String, Object> seasonData(Season season) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", season.id());
        data.put("number", season.number());
        data.put("startsAt", season.startsAt().toString());
        data.put("endsAt", season.endsAt().toString());
        data.put("status", season.status());
        data.put("settledAt", season.settledAt() == null ? null : season.settledAt().toString());
        return data;
    }

    private Map<String, Object> marketEventData(ActiveMarketEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", event.instanceId());
        data.put("eventId", event.definition().id());
        data.put("stockId", event.definition().stockId());
        data.put("name", event.definition().name());
        data.put("message", event.definition().message());
        data.put("modifier", event.definition().modifier());
        data.put("startedAt", event.startedAt().toString());
        data.put("endsAt", event.endsAt().toString());
        return data;
    }

    private void dispatch() {
        if (!enabled || !dispatching.compareAndSet(false, true)) return;
        database.read(connection -> outbox.ready(connection, System.currentTimeMillis(), batchSize))
                .thenCompose(this::sendSequentially)
                .whenComplete((ignored, error) -> {
                    dispatching.set(false);
                    if (error != null) plugin.getLogger().log(Level.FINE, "Realtime dispatch pass failed", error);
                });
    }

    private CompletableFuture<Void> sendSequentially(List<RealtimeOutboxRepository.OutboxEvent> events) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (RealtimeOutboxRepository.OutboxEvent event : events) {
            chain = chain.thenCompose(ignored -> send(event));
        }
        return chain;
    }

    private CompletableFuture<Void> send(RealtimeOutboxRepository.OutboxEvent event) {
        long timestamp = Instant.now().getEpochSecond();
        String signature;
        try {
            signature = sign(timestamp + "." + event.payload());
        } catch (GeneralSecurityException error) {
            return CompletableFuture.failedFuture(error);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "MonaKabu/" + plugin.getPluginMeta().getVersion())
                .header("X-MonaKabu-Server", serverId)
                .header("X-MonaKabu-Timestamp", Long.toString(timestamp))
                .header("X-MonaKabu-Signature", "sha256=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(event.payload(), StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return database.<Void>transaction(connection -> {
                            outbox.markDelivered(connection, event.eventId(), System.currentTimeMillis());
                            return null;
                        });
                    }
                    return CompletableFuture.failedFuture(new IllegalStateException("HTTP " + response.statusCode()));
                }).exceptionallyCompose(error -> markFailure(event, error));
    }

    private CompletableFuture<Void> markFailure(RealtimeOutboxRepository.OutboxEvent event, Throwable error) {
        int attempts = event.attempts() + 1;
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        long initialMillis = Math.max(1, initialBackoff.toMillis());
        long delay = multiplier > Long.MAX_VALUE / initialMillis
                ? maximumBackoff.toMillis() : Math.min(maximumBackoff.toMillis(), initialMillis * multiplier);
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, delay / 5 + 1));
        boolean dead = maxAttempts > 0 && attempts >= maxAttempts;
        Throwable root = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return database.transaction(connection -> {
            outbox.markFailed(connection, event.eventId(), attempts, System.currentTimeMillis() + delay + jitter,
                    root.getMessage(), dead);
            return null;
        }).thenRun(() -> {
            if (dead) plugin.getLogger().severe("Realtime event moved to dead letter state: " + event.eventId());
        });
    }

    private String sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private void cleanup() {
        long retentionDays = Math.max(1, configs.config().getLong("realtime.outbox-retention-days", 7));
        long before = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
        database.transaction(connection -> outbox.prune(connection, before));
    }

    private void stopTasks() {
        if (dispatchTask != null) dispatchTask.cancel();
        if (snapshotTask != null) snapshotTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        dispatchTask = null;
        snapshotTask = null;
        cleanupTask = null;
    }

    @Override
    public void close() {
        enabled = false;
        stopTasks();
    }
}
