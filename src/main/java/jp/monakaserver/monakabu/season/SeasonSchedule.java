package jp.monakaserver.monakabu.season;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class SeasonSchedule {
    public record Window(Instant startsAt, Instant endsAt, long ordinal) {}

    private final ZonedDateTime anchor;
    private final int durationDays;
    private final DayOfWeek endDay;

    public SeasonSchedule(LocalDate anchorDate, LocalTime settlementTime, ZoneId zone, int durationDays, DayOfWeek endDay) {
        if (durationDays <= 0) throw new IllegalArgumentException("duration-days must be positive");
        this.anchor = ZonedDateTime.of(anchorDate, settlementTime, zone);
        this.durationDays = durationDays;
        this.endDay = endDay;
        LocalDate expectedEnd = anchorDate.plusDays(durationDays);
        if (expectedEnd.getDayOfWeek() != endDay) {
            throw new IllegalArgumentException("anchor-date + duration-days must end on " + endDay + " (actual: " + expectedEnd.getDayOfWeek() + ")");
        }
    }

    public Window windowAt(Instant instant) {
        ZonedDateTime now = instant.atZone(anchor.getZone());
        long ordinal;
        if (now.isBefore(anchor)) {
            ordinal = 0;
        } else {
            long elapsedDays = Duration.between(anchor, now).toDays();
            ordinal = Math.floorDiv(elapsedDays, durationDays);
            ZonedDateTime tentativeEnd = anchor.plusDays((ordinal + 1) * (long) durationDays);
            if (!now.isBefore(tentativeEnd)) ordinal++;
        }
        ZonedDateTime start = anchor.plusDays(ordinal * (long) durationDays);
        ZonedDateTime end = start.plusDays(durationDays);
        return new Window(start.toInstant(), end.toInstant(), ordinal);
    }

    public Window next(Window current) {
        Instant start = current.endsAt();
        ZonedDateTime end = start.atZone(anchor.getZone()).plusDays(durationDays);
        return new Window(start, end.toInstant(), current.ordinal() + 1);
    }

    public DayOfWeek endDay() { return endDay; }
}
