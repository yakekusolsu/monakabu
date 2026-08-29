package jp.monakaserver.monakabu.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public final class Money {
    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.##"));

    private Money() {}

    public static BigDecimal of(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite money");
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal base, double percent) {
        return normalize(base.multiply(BigDecimal.valueOf(percent)).movePointLeft(2));
    }

    public static String format(BigDecimal value) {
        return FORMAT.get().format(value);
    }
}
