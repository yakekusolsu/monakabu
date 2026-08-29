package jp.monakaserver.monakabu.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DailyReportScheduleTest {
    private final DailyReportSchedule schedule = new DailyReportSchedule(
            LocalTime.of(21, 0), ZoneId.of("Asia/Tokyo"));

    @Test
    void isNotDueBeforeConfiguredLocalTime() {
        assertThat(schedule.dueDate(Instant.parse("2026-08-29T11:59:59Z"))).isEmpty();
    }

    @Test
    void isDueFromConfiguredLocalTime() {
        assertThat(schedule.dueDate(Instant.parse("2026-08-29T12:00:00Z")))
                .contains(LocalDate.of(2026, 8, 29));
    }
}
