package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;
import org.bukkit.Material;

public record StockDefinition(
        String id,
        String displayName,
        String symbol,
        BigDecimal initialPrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        double volatility,
        double drift,
        Material icon,
        int customModelData
) {
    public StockDefinition {
        if (!id.matches("[a-z0-9_\\-]+")) throw new IllegalArgumentException("Invalid stock id: " + id);
        if (initialPrice.signum() <= 0 || minPrice.signum() < 0 || maxPrice.compareTo(minPrice) <= 0) {
            throw new IllegalArgumentException("Invalid prices for " + id);
        }
        if (volatility < 0 || volatility > 2 || Math.abs(drift) > 1) {
            throw new IllegalArgumentException("Invalid volatility/drift for " + id);
        }
    }
}
