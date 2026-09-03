package jp.monakaserver.monakabu.trading;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ShareLimitsTest {
    @Test void zeroDisablesTheConfiguredCap() {
        assertThat(ShareLimits.effective(0)).isEqualTo(ShareLimits.MAX_SAFE_SHARES);
        assertThat(ShareLimits.validOrder(1001, 0)).isTrue();
        assertThat(ShareLimits.validOrder(3_000_000_000L, 0)).isTrue();
        assertThat(ShareLimits.validOrder(ShareLimits.MAX_SAFE_SHARES, 0)).isTrue();
    }

    @Test void numericSafetyAndOptionalLimitsRemainEnforced() {
        assertThat(ShareLimits.validOrder(0, 0)).isFalse();
        assertThat(ShareLimits.validOrder(-1, 0)).isFalse();
        assertThat(ShareLimits.validOrder(Long.MAX_VALUE, 0)).isFalse();
        assertThat(ShareLimits.validOrder(1000, 1000)).isTrue();
        assertThat(ShareLimits.validOrder(1001, 1000)).isFalse();
        assertThat(ShareLimits.remaining(0, ShareLimits.MAX_SAFE_SHARES)).isZero();
        assertThat(ShareLimits.remaining(1000, 1001)).isZero();
        assertThatThrownBy(() -> ShareLimits.effective(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
