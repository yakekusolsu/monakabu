package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;

public record TradeResult(boolean success, String reason, String transactionId, String stockId, long shares,
                          BigDecimal gross, BigDecimal fee, BigDecimal tax, BigDecimal net, long resultingShares) {
    public static TradeResult failure(String reason) {
        return new TradeResult(false, reason, "", "", 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }
}
