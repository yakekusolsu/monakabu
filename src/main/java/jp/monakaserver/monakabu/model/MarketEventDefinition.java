package jp.monakaserver.monakabu.model;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record MarketEventDefinition(String id, List<String> stockIds, String name, double modifier, Duration duration, int weight, String message) {
    public MarketEventDefinition {
        Objects.requireNonNull(stockIds, "stockIds");
        stockIds = List.copyOf(new LinkedHashSet<>(stockIds.stream()
                .map(String::trim).filter(stockId -> !stockId.isEmpty()).toList()));
        if (stockIds.isEmpty() || modifier <= 0 || duration.isNegative() || duration.isZero() || weight <= 0) {
            throw new IllegalArgumentException("Invalid market event: " + id);
        }
    }

    /** Backward-compatible constructor for integrations using one target stock. */
    public MarketEventDefinition(String id, String stockId, String name, double modifier,
                                 Duration duration, int weight, String message) {
        this(id, List.of(stockId), name, modifier, duration, weight, message);
    }

    /** The first target, retained for source and binary compatibility with the original API. */
    @Deprecated(forRemoval = false)
    public String stockId() {
        return primaryStockId();
    }

    public String primaryStockId() {
        return stockIds.getFirst();
    }

    public boolean affectsStock(String stockId) {
        return stockIds.contains(stockId);
    }
}
