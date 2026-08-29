package jp.monakaserver.monakabu.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import jp.monakaserver.monakabu.model.StockDefinition;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.Trend;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class PriceEngineTest {
    @Test
    void clampsEveryUpdateAndStockBounds() {
        StockSnapshot snapshot = snapshot(1.5);
        PriceEngine engine = new PriceEngine(new PriceEngine.Settings(.2, 1, .5, -.5, .01));
        for (int i = 0; i < 1000; i++) {
            BigDecimal next = engine.next(snapshot, Duration.ofMinutes(5), 2, new Random(i));
            assertThat(next).isBetween(BigDecimal.valueOf(900), BigDecimal.valueOf(1100));
            assertThat(next).isBetween(BigDecimal.valueOf(800), BigDecimal.valueOf(1200));
        }
    }

    @Test
    void volatilityMultiplierMakesNormalMovementLarger() {
        StockSnapshot snapshot = snapshot(.1);
        PriceEngine normal = new PriceEngine(new PriceEngine.Settings(.2, 1, 0, 0, 0));
        PriceEngine stronger = new PriceEngine(new PriceEngine.Settings(.2, 1.5, 0, 0, 0));

        BigDecimal normalPrice = normal.next(snapshot, Duration.ofMinutes(5), 1, new Random(42));
        BigDecimal strongerPrice = stronger.next(snapshot, Duration.ofMinutes(5), 1, new Random(42));

        assertThat(strongerPrice.subtract(BigDecimal.valueOf(1000)).abs())
                .isGreaterThan(normalPrice.subtract(BigDecimal.valueOf(1000)).abs());
    }

    private StockSnapshot snapshot(double volatility) {
        StockDefinition definition = new StockDefinition(
                "test", "Test", "TST", BigDecimal.valueOf(1000), BigDecimal.valueOf(900),
                BigDecimal.valueOf(1100), volatility, 0, Material.PAPER, 0);
        return new StockSnapshot(
                definition, BigDecimal.valueOf(1000), BigDecimal.valueOf(1000),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), Trend.NORMAL,
                null, false, Instant.now());
    }
}
