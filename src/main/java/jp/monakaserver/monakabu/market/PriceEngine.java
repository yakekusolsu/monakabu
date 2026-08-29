package jp.monakaserver.monakabu.market;

import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.Trend;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.random.RandomGenerator;

public final class PriceEngine {
    public record Settings(double maxChange, double bullBias, double bearBias, double meanReversion) {
        public Settings {
            if (maxChange <= 0 || maxChange > 1 || meanReversion < 0 || meanReversion > 1) {
                throw new IllegalArgumentException("Invalid price settings");
            }
        }
    }

    private final Settings settings;

    public PriceEngine(Settings settings) {
        this.settings = settings;
    }

    public BigDecimal next(StockSnapshot stock, Duration interval, double eventFactor, RandomGenerator random) {
        if (stock.bankrupt()) return Money.ZERO;
        double current = stock.price().doubleValue();
        double initial = stock.definition().initialPrice().doubleValue();
        double fractionOfDay = Math.max(1.0 / 288.0, interval.toMillis() / 86_400_000.0);
        double trendBiasPerDay = switch (stock.trend()) {
            case BULL -> settings.bullBias();
            case BEAR -> settings.bearBias();
            case NORMAL -> 0;
        };
        double trendBias = trendBiasPerDay * fractionOfDay;
        double shock = random.nextGaussian() * stock.definition().volatility() * Math.sqrt(fractionOfDay);
        double drift = stock.definition().drift() * fractionOfDay;
        double reversion = settings.meanReversion() * Math.log(initial / Math.max(current, 0.01)) * fractionOfDay;
        double eventReturn = Math.log(Math.max(0.01, eventFactor));
        double rawReturn = drift + trendBias + shock + reversion + eventReturn;
        double boundedReturn = Math.max(-settings.maxChange(), Math.min(settings.maxChange(), Math.expm1(rawReturn)));
        double candidate = current * (1.0 + boundedReturn);
        double clamped = Math.max(stock.definition().minPrice().doubleValue(), Math.min(stock.definition().maxPrice().doubleValue(), candidate));
        return Money.of(clamped);
    }
}
