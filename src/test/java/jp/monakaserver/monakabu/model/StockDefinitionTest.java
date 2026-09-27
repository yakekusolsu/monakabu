package jp.monakaserver.monakabu.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class StockDefinitionTest {
    @Test
    void rejectsZeroMinimumPrice() {
        assertThatThrownBy(() -> new StockDefinition(
                "test", "Test", "TST", BigDecimal.valueOf(300), BigDecimal.ZERO,
                BigDecimal.valueOf(50_000), 0.65, -0.0005, Material.PAPER, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid prices");
    }
}
