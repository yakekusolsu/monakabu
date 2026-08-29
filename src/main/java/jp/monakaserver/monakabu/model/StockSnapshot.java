package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;
import java.time.Instant;

public record StockSnapshot(
        StockDefinition definition,
        BigDecimal price,
        BigDecimal previousPrice,
        BigDecimal dailyHigh,
        BigDecimal dailyLow,
        Trend trend,
        Instant haltedUntil,
        boolean bankrupt,
        Instant updatedAt
) {
    public double changePercent() {
        if (previousPrice.signum() == 0) return 0;
        return price.subtract(previousPrice).divide(previousPrice, 8, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
    }

    public boolean halted(Instant now) {
        return bankrupt || (haltedUntil != null && haltedUntil.isAfter(now));
    }
}
