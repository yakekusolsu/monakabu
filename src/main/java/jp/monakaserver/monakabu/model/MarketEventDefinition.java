package jp.monakaserver.monakabu.model;

import java.time.Duration;

public record MarketEventDefinition(String id, String stockId, String name, double modifier, Duration duration, int weight, String message) {
    public MarketEventDefinition {
        if (modifier <= 0 || duration.isNegative() || duration.isZero() || weight <= 0) {
            throw new IllegalArgumentException("Invalid market event: " + id);
        }
    }
}
