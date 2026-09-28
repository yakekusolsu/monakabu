package jp.monakaserver.monakabu.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TradingServiceTest {
    @Test
    void largeBalanceStillReservesThePurchaseFee() {
        long shares = TradingService.maximumBuy(20_000_000, BigDecimal.valueOf(1500), 1, ShareLimits.effective(0));
        assertThat(shares).isEqualTo(13_201);
        assertThat(TradingService.totalBuyCost(BigDecimal.valueOf(1500), shares, 1)).isLessThanOrEqualTo(new BigDecimal("20000000"));
        assertThat(TradingService.totalBuyCost(BigDecimal.valueOf(1500), shares + 1, 1)).isGreaterThan(new BigDecimal("20000000"));
    }

    @Test
    void unlimitedMaximumBuyStillRespectsBalanceAndFees() {
        long shares = TradingService.maximumBuy(50_000, BigDecimal.ONE, 1, ShareLimits.effective(0));
        assertThat(shares).isEqualTo(49_504);
        assertThat(TradingService.totalBuyCost(BigDecimal.ONE, shares, 1)).isLessThanOrEqualTo(new BigDecimal("50000"));
        assertThat(TradingService.totalBuyCost(BigDecimal.ONE, shares + 1, 1)).isGreaterThan(new BigDecimal("50000"));
    }

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

    @Test
    void sellAllBypassesPerOrderLimitButExactOrdersDoNot() {
        assertThat(TradingService.sellAmount(-1, 1_000, 25)).isEqualTo(1_000);
        assertThat(TradingService.sellAmount(25, 1_000, 25)).isEqualTo(25);
        assertThatThrownBy(() -> TradingService.sellAmount(26, 1_000, 25))
                .isInstanceOf(IllegalStateException.class).hasMessage("ORDER_LIMIT");
    }
}
