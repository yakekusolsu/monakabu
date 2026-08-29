package jp.monakaserver.monakabu.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

public record DailyReportSchedule(LocalTime reportTime, ZoneId zone) {
    public Optional<LocalDate> dueDate(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        return local.toLocalTime().isBefore(reportTime)
                ? Optional.empty() : Optional.of(local.toLocalDate());
    }
}
