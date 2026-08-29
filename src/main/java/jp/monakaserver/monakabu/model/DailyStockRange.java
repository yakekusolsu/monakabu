package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;

public record DailyStockRange(
        String stockId,
        String symbol,
        String displayName,
        BigDecimal currentPrice,
        BigDecimal dailyHigh,
        BigDecimal dailyLow,
        BigDecimal range,
        double rangePercent
) {}
