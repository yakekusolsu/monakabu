package jp.monakaserver.monakabu.model;

import java.time.Instant;

public record ActiveMarketEvent(String instanceId, MarketEventDefinition definition, Instant startedAt, Instant endsAt) {
    public boolean activeAt(Instant now) {
        return now.isBefore(endsAt);
    }
}
