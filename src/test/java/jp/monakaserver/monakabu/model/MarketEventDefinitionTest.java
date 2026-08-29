package jp.monakaserver.monakabu.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketEventDefinitionTest {
    @Test
    @SuppressWarnings("deprecation")
    void supportsMultipleStocksAndRemovesDuplicateTargets() {
        MarketEventDefinition definition = new MarketEventDefinition(
                "resource_boom", List.of("mona_mining", "hinode_energy", "mona_mining"),
                "資源価格高騰", 1.32, Duration.ofMinutes(45), 5, "速報");

        assertThat(definition.stockIds()).containsExactly("mona_mining", "hinode_energy");
        assertThat(definition.affectsStock("mona_mining")).isTrue();
        assertThat(definition.affectsStock("mona_fishing")).isFalse();
        assertThat(definition.stockId()).isEqualTo("mona_mining");
    }

    @Test
    void keepsTheLegacySingleStockConstructor() {
        MarketEventDefinition definition = new MarketEventDefinition(
                "mining_boom", "mona_mining", "巨大鉱脈発見",
                1.2, Duration.ofMinutes(30), 10, "速報");

        assertThat(definition.stockIds()).containsExactly("mona_mining");
    }

    @Test
    void rejectsAnEventWithoutTargets() {
        assertThatThrownBy(() -> new MarketEventDefinition(
                "invalid", List.of("", "  "), "Invalid", 1.1, Duration.ofMinutes(30), 1, "Invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
