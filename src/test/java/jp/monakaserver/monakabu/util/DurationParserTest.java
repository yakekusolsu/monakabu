package jp.monakaserver.monakabu.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationParserTest {
    @Test void parsesCompoundDurations(){assertThat(DurationParser.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));assertThat(DurationParser.parse("14d")).isEqualTo(Duration.ofDays(14));}
    @Test void rejectsInvalidValues(){assertThatThrownBy(()->DurationParser.parse("five minutes")).isInstanceOf(IllegalArgumentException.class);}
}
