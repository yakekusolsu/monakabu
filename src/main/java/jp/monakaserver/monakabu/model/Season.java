package jp.monakaserver.monakabu.model;

import java.time.Instant;

public record Season(long id, int number, Instant startsAt, Instant endsAt, MarketStatus status, Instant settledAt) {
    public boolean overdue(Instant now) {
        return !status.equals(MarketStatus.CLOSED) && !status.equals(MarketStatus.OPENING) && !now.isBefore(endsAt);
    }
}
