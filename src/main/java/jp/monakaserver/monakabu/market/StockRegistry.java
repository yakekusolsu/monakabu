package jp.monakaserver.monakabu.market;

import jp.monakaserver.monakabu.model.StockDefinition;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.Trend;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public final class StockRegistry {
    private final Map<String, MutableStock> stocks = new ConcurrentHashMap<>();
    private final ZoneId zoneId;

    public StockRegistry(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    public void load(ConfigurationSection root) {
        if (root == null) throw new IllegalArgumentException("stocks section is missing");
        Map<String, MutableStock> replacement = new ConcurrentHashMap<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            Material material = Material.matchMaterial(section.getString("icon.material", "PAPER"));
            StockDefinition definition = new StockDefinition(
                    id,
                    section.getString("display-name", id),
                    section.getString("symbol", id.toUpperCase()),
                    Money.of(section.getDouble("initial-price", 1000)),
                    Money.of(section.getDouble("min-price", 1)),
                    Money.of(section.getDouble("max-price", 1_000_000)),
                    section.getDouble("volatility", 0.08),
                    section.getDouble("drift", 0),
                    material == null ? Material.PAPER : material,
                    Math.max(0, section.getInt("icon.custom-model-data", 0))
            );
            MutableStock old = stocks.get(id);
            replacement.put(id, old == null ? new MutableStock(definition) : old.withDefinition(definition));
        }
        if (replacement.isEmpty()) throw new IllegalArgumentException("At least one stock is required");
        stocks.clear();
        stocks.putAll(replacement);
    }

    public Optional<StockSnapshot> find(String id) {
        MutableStock stock = stocks.get(id.toLowerCase(java.util.Locale.ROOT));
        return stock == null ? Optional.empty() : Optional.of(stock.snapshot());
    }

    public Collection<StockSnapshot> all() {
        ArrayList<StockSnapshot> result = new ArrayList<>();
        for (MutableStock stock : stocks.values()) result.add(stock.snapshot());
        result.sort(Comparator.comparing(s -> s.definition().id()));
        return ListCopy.copy(result);
    }

    public void restore(String id, BigDecimal price, BigDecimal previous, BigDecimal high, BigDecimal low,
                        Trend trend, Instant haltedUntil, boolean bankrupt, Instant updatedAt) {
        MutableStock stock = stocks.get(id);
        if (stock != null) stock.restore(price, previous, high, low, trend, haltedUntil, bankrupt, updatedAt);
    }

    public StockSnapshot updatePrice(String id, BigDecimal newPrice, Instant at) {
        MutableStock stock = require(id);
        return stock.update(newPrice, at, zoneId);
    }

    public void setTrend(String id, Trend trend) { require(id).setTrend(trend); }
    public void halt(String id, Instant until) { require(id).halt(until); }
    public void resume(String id) { require(id).halt(null); }
    public void bankrupt(String id, Instant at) { require(id).bankrupt(at); }
    public void resetForSeason(Instant at) { for (MutableStock stock : stocks.values()) stock.reset(at); }

    private MutableStock require(String id) {
        MutableStock stock = stocks.get(id);
        if (stock == null) throw new IllegalArgumentException("Unknown stock: " + id);
        return stock;
    }

    private static final class ListCopy {
        static <T> Collection<T> copy(Collection<T> source) { return java.util.List.copyOf(source); }
    }

    private static final class MutableStock {
        private StockDefinition definition;
        private BigDecimal price;
        private BigDecimal previous;
        private BigDecimal dailyHigh;
        private BigDecimal dailyLow;
        private Trend trend = Trend.NORMAL;
        private Instant haltedUntil;
        private boolean bankrupt;
        private Instant updatedAt = Instant.EPOCH;
        private LocalDate tradingDay;

        MutableStock(StockDefinition definition) {
            this.definition = definition;
            this.price = definition.initialPrice();
            this.previous = definition.initialPrice();
            this.dailyHigh = definition.initialPrice();
            this.dailyLow = definition.initialPrice();
        }

        synchronized MutableStock withDefinition(StockDefinition replacement) {
            this.definition = replacement;
            price = clamp(price);
            previous = clamp(previous);
            dailyHigh = clamp(dailyHigh);
            dailyLow = clamp(dailyLow);
            return this;
        }

        synchronized StockSnapshot snapshot() {
            return new StockSnapshot(definition, price, previous, dailyHigh, dailyLow, trend, haltedUntil, bankrupt, updatedAt);
        }

        synchronized void restore(BigDecimal price, BigDecimal previous, BigDecimal high, BigDecimal low, Trend trend,
                                  Instant haltedUntil, boolean bankrupt, Instant updatedAt) {
            this.price = bankrupt ? Money.ZERO : clamp(price);
            this.previous = clamp(previous);
            this.dailyHigh = clamp(high);
            this.dailyLow = bankrupt ? Money.ZERO : clamp(low);
            this.trend = trend;
            this.haltedUntil = haltedUntil;
            this.bankrupt = bankrupt;
            this.updatedAt = updatedAt;
        }

        synchronized StockSnapshot update(BigDecimal value, Instant at, ZoneId zone) {
            if (bankrupt) return snapshot();
            LocalDate date = at.atZone(zone).toLocalDate();
            BigDecimal next = clamp(value);
            previous = price;
            price = next;
            if (!date.equals(tradingDay)) {
                tradingDay = date;
                dailyHigh = next;
                dailyLow = next;
            } else {
                dailyHigh = dailyHigh.max(next);
                dailyLow = dailyLow.min(next);
            }
            updatedAt = at;
            return snapshot();
        }

        synchronized void setTrend(Trend value) { trend = value; }
        synchronized void halt(Instant value) { haltedUntil = value; }
        synchronized void bankrupt(Instant at) {
            previous = price;
            price = Money.ZERO;
            dailyLow = Money.ZERO;
            bankrupt = true;
            haltedUntil = Instant.MAX;
            updatedAt = at;
        }

        synchronized void reset(Instant at) {
            price = definition.initialPrice(); previous = definition.initialPrice();
            dailyHigh = price; dailyLow = price; trend = Trend.NORMAL; haltedUntil = null;
            bankrupt = false; updatedAt = at; tradingDay = null;
        }

        private BigDecimal clamp(BigDecimal value) {
            return Money.normalize(value.max(definition.minPrice()).min(definition.maxPrice()));
        }
    }
}
