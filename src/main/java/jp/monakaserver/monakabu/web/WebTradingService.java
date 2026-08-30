package jp.monakaserver.monakabu.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.economy.EconomyService;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.PortfolioPosition;
import jp.monakaserver.monakabu.model.TradeResult;
import jp.monakaserver.monakabu.realtime.RealtimeService;
import jp.monakaserver.monakabu.trading.TradingService;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.MainThread;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class WebTradingService implements AutoCloseable {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final RealtimeService realtime;
    private final TradingService trading;
    private final EconomyService economy;
    private final MessageService messages;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Map<UUID, Long> lastLinkRequest = new ConcurrentHashMap<>();
    private BukkitTask pollTask;
    private volatile boolean enabled;

    public WebTradingService(JavaPlugin plugin, ConfigManager configs, RealtimeService realtime,
                             TradingService trading, EconomyService economy, MessageService messages) {
        this.plugin = plugin;
        this.configs = configs;
        this.realtime = realtime;
        this.trading = trading;
        this.economy = economy;
        this.messages = messages;
    }

    public void start() {
        close();
        enabled = configs.config().getBoolean("web-trading.enabled", true)
                && configs.config().getBoolean("realtime.enabled", false);
        if (!enabled) return;
        Duration interval = DurationParser.parse(configs.config().getString("web-trading.order-poll-interval", "2s"));
        long ticks = Math.max(20, interval.toMillis() / 50);
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, ticks, ticks);
        plugin.getLogger().info("Web trading order polling enabled");
    }

    public void reload() {
        start();
    }

    public CompletableFuture<LinkResult> createLink(Player player) {
        if (!enabled) return CompletableFuture.completedFuture(new LinkResult(false, "", null, "WEB_TRADING_DISABLED"));
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        boolean canBuy = player.hasPermission("monakabu.trade") && player.hasPermission("monakabu.buy");
        boolean canSell = player.hasPermission("monakabu.trade") && player.hasPermission("monakabu.sell");
        long now = System.currentTimeMillis();
        Long previous = lastLinkRequest.put(playerId, now);
        if (previous != null && now - previous < 10_000) {
            return CompletableFuture.completedFuture(new LinkResult(false, "", null, "COOLDOWN"));
        }
        String code = generateCode();
        Duration lifetime = DurationParser.parse(configs.config().getString("web-trading.link-code-lifetime", "10m"));
        Instant expiresAt = Instant.now().plus(lifetime.compareTo(Duration.ofMinutes(15)) > 0 ? Duration.ofMinutes(15) : lifetime);
        return account(playerId).thenCompose(account -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", realtime.serverId());
            payload.put("code", code);
            payload.put("playerUuid", playerId.toString());
            payload.put("playerName", playerName);
            payload.put("canBuy", canBuy);
            payload.put("canSell", canSell);
            payload.put("expiresAt", expiresAt.toString());
            payload.put("account", account);
            return realtime.postSigned("/v1/plugin/link-code", payload)
                    .thenApply(ignored -> new LinkResult(true, code, expiresAt, ""));
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Web link code creation failed for " + playerId, error);
            return new LinkResult(false, "", null, "LINK_FAILED");
        });
    }

    public CompletableFuture<Boolean> unlink(Player player) {
        if (!enabled) return CompletableFuture.completedFuture(false);
        return realtime.postSigned("/v1/plugin/unlink", Map.of(
                "serverId", realtime.serverId(), "playerUuid", player.getUniqueId().toString()))
                .thenApply(ignored -> true).exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Web unlink failed for " + player.getUniqueId(), error);
                    return false;
                });
    }

    private void poll() {
        if (!enabled || !polling.compareAndSet(false, true)) return;
        realtime.postSigned("/v1/plugin/orders/claim", Map.of("serverId", realtime.serverId()))
                .thenApply(this::parseOrders)
                .thenCompose(this::processSequentially)
                .whenComplete((ignored, error) -> {
                    polling.set(false);
                    if (error != null) plugin.getLogger().log(Level.FINE, "Web order poll failed", error);
                });
    }

    private List<WebOrder> parseOrders(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        ArrayList<WebOrder> orders = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("orders")) {
            JsonObject order = element.getAsJsonObject();
            orders.add(new WebOrder(UUID.fromString(order.get("orderId").getAsString()),
                    UUID.fromString(order.get("playerUuid").getAsString()), order.get("playerName").getAsString(),
                    OrderType.valueOf(order.get("type").getAsString()),
                    order.has("stockId") && !order.get("stockId").isJsonNull() ? order.get("stockId").getAsString() : null,
                    order.get("shares").getAsLong(), order.get("claimToken").getAsString()));
        }
        return orders;
    }

    private CompletableFuture<Void> processSequentially(List<WebOrder> orders) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (WebOrder order : orders) chain = chain.thenCompose(ignored -> process(order));
        return chain;
    }

    private CompletableFuture<Void> process(WebOrder order) {
        int maximum = Math.max(1, configs.config().getInt("web-trading.max-shares-per-order", 1000));
        CompletableFuture<TradeResult> execution;
        if (order.type() != OrderType.REFRESH && (order.shares() <= 0 || order.shares() > maximum)) {
            execution = CompletableFuture.completedFuture(TradeResult.failure("WEB_ORDER_LIMIT"));
        } else if (order.type() == OrderType.BUY) {
            execution = trading.buyWeb(order.playerId(), order.playerName(), order.stockId(), order.shares(), order.orderId().toString());
        } else if (order.type() == OrderType.SELL) {
            execution = trading.sellWeb(order.playerId(), order.playerName(), order.stockId(), order.shares(), order.orderId().toString());
        } else {
            execution = CompletableFuture.completedFuture(new TradeResult(true, "", "", "", 0,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, 0));
        }
        return execution.thenCompose(result -> account(order.playerId()).thenCompose(account -> {
            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("reason", result.reason());
            resultData.put("transactionId", result.transactionId());
            resultData.put("stockId", result.stockId());
            resultData.put("shares", result.shares());
            resultData.put("gross", result.gross());
            resultData.put("fee", result.fee());
            resultData.put("tax", result.tax());
            resultData.put("net", result.net());
            resultData.put("resultingShares", result.resultingShares());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", realtime.serverId());
            payload.put("orderId", order.orderId().toString());
            payload.put("claimToken", order.claimToken());
            payload.put("success", result.success());
            payload.put("result", resultData);
            payload.put("account", account);
            return realtime.postSigned("/v1/plugin/orders/result", payload).thenAccept(ignored -> notify(order, result));
        }));
    }

    private CompletableFuture<Map<String, Object>> account(UUID playerId) {
        return trading.portfolio(playerId).thenCombine(MainThread.call(plugin, () -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            return economy.balance(player);
        }), (positions, balance) -> accountData(positions, balance));
    }

    private Map<String, Object> accountData(List<PortfolioPosition> positions, double balance) {
        List<Map<String, Object>> portfolio = new ArrayList<>();
        for (PortfolioPosition position : positions) portfolio.add(Map.of(
                "stockId", position.stockId(), "shares", position.shares(), "averageCost", position.averageCost()));
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("balance", Double.isFinite(balance) ? balance : 0);
        account.put("portfolio", portfolio);
        account.put("capturedAt", Instant.now().toString());
        return account;
    }

    private void notify(WebOrder order, TradeResult result) {
        if (order.type() == OrderType.REFRESH) return;
        MainThread.run(plugin, () -> {
            Player player = Bukkit.getPlayer(order.playerId());
            if (player != null) player.sendMessage(messages.raw(result.success()
                    ? "<green>Webからの" + (order.type() == OrderType.BUY ? "購入" : "売却") + "注文が完了しました。</green>"
                    : "<red>Web注文に失敗しました: " + result.reason() + "</red>"));
        });
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        return code.toString();
    }

    @Override
    public void close() {
        enabled = false;
        if (pollTask != null) pollTask.cancel();
        pollTask = null;
    }

    public record LinkResult(boolean success, String code, Instant expiresAt, String error) {}
    private enum OrderType { BUY, SELL, REFRESH }
    private record WebOrder(UUID orderId, UUID playerId, String playerName, OrderType type,
                            String stockId, long shares, String claimToken) {}
}
