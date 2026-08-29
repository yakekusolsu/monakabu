package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;
import java.util.UUID;

public record RankingEntry(UUID playerId, String playerName, BigDecimal realizedProfit, long trades, int rank) {}
