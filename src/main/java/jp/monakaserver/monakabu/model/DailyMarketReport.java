package jp.monakaserver.monakabu.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyMarketReport(LocalDate reportDate, Instant generatedAt, List<DailyStockRange> stocks) {
    public DailyMarketReport {
        stocks = List.copyOf(stocks);
    }
}
