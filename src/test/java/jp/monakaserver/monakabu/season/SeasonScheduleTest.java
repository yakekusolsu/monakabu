package jp.monakaserver.monakabu.season;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SeasonScheduleTest {
    private final ZoneId tokyo=ZoneId.of("Asia/Tokyo");
    @Test void computesBiweeklySundaySettlement(){SeasonSchedule schedule=new SeasonSchedule(LocalDate.of(2026,8,23),LocalTime.of(21,0),tokyo,14,DayOfWeek.SUNDAY);SeasonSchedule.Window window=schedule.windowAt(Instant.parse("2026-08-25T00:00:00Z"));assertThat(window.startsAt()).isEqualTo(Instant.parse("2026-08-23T12:00:00Z"));assertThat(window.endsAt()).isEqualTo(Instant.parse("2026-09-06T12:00:00Z"));}
    @Test void advancesAtExactBoundary(){SeasonSchedule schedule=new SeasonSchedule(LocalDate.of(2026,8,23),LocalTime.of(21,0),tokyo,14,DayOfWeek.SUNDAY);SeasonSchedule.Window window=schedule.windowAt(Instant.parse("2026-09-06T12:00:00Z"));assertThat(window.startsAt()).isEqualTo(Instant.parse("2026-09-06T12:00:00Z"));assertThat(window.endsAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));}
}
