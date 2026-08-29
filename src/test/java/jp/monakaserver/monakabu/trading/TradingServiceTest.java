package jp.monakaserver.monakabu.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TradingServiceTest {
    @Test
    void maximumBuyUsesFeeOnTheCompleteOrder() {
        long shares = TradingService.maximumBuy(490, new BigDecimal("0.49"), 1, 1_000);

        assertThat(shares).isEqualTo(990);
        assertThat(TradingService.totalBuyCost(new BigDecimal("0.49"), shares, 1))
                .isEqualByComparingTo("489.95");
        assertThat(TradingService.totalBuyCost(new BigDecimal("0.49"), shares + 1, 1))
                .isGreaterThan(new BigDecimal("490.00"));
    }

    @Test
    void nonPositiveBalanceCannotBuyShares() {
        assertThat(TradingService.maximumBuy(0, new BigDecimal("100"), 1, 1_000)).isZero();
        assertThat(TradingService.maximumBuy(-25, new BigDecimal("100"), 1, 1_000)).isZero();
    }
}
