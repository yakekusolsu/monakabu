package jp.monakaserver.monakabu.api.event;

import java.math.BigDecimal; import java.util.UUID;
import org.bukkit.event.Event; import org.bukkit.event.HandlerList; import org.jetbrains.annotations.NotNull;
public final class PortfolioSettlementEvent extends Event { private static final HandlerList HANDLERS=new HandlerList(); private final UUID playerId; private final long seasonId; private final BigDecimal amount;
    public PortfolioSettlementEvent(UUID playerId,long seasonId,BigDecimal amount){this.playerId=playerId;this.seasonId=seasonId;this.amount=amount;}
    public UUID getPlayerId(){return playerId;} public long getSeasonId(){return seasonId;} public BigDecimal getAmount(){return amount;}
    @Override public @NotNull HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;} }
