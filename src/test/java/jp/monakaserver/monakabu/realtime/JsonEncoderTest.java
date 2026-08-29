package jp.monakaserver.monakabu.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonEncoderTest {
    @Test void encodesJapaneseTextAndEscapesControlCharacters() {
        String json = JsonEncoder.encode(Map.of(
                "name", "モナカ\n鉱業",
                "price", new BigDecimal("1250.50"),
                "values", List.of(true, false)
        ));
        assertThat(json).contains("\"name\":\"モナカ\\n鉱業\"");
        assertThat(json).contains("\"price\":1250.50");
        assertThat(json).contains("\"values\":[true,false]");
    }
}
