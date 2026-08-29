package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;
import java.util.UUID;

public record PortfolioPosition(UUID playerId, String stockId, long seasonId, long shares, BigDecimal averageCost) {
    public BigDecimal costBasis() {
        return averageCost.multiply(BigDecimal.valueOf(shares));
    }
}
