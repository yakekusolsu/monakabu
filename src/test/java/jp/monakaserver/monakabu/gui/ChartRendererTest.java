package jp.monakaserver.monakabu.gui;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartRendererTest {
    @Test void rendersCompactTrend(){String chart=ChartRenderer.render(List.of(BigDecimal.ONE,BigDecimal.TWO,BigDecimal.TEN),8);assertThat(chart).hasSize(3).startsWith("▁").endsWith("█");}
}
