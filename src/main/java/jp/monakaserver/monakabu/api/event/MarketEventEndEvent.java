package jp.monakaserver.monakabu.api.event;

import jp.monakaserver.monakabu.model.ActiveMarketEvent;
import org.bukkit.event.Event; import org.bukkit.event.HandlerList; import org.jetbrains.annotations.NotNull;
public final class MarketEventEndEvent extends Event { private static final HandlerList HANDLERS=new HandlerList(); private final ActiveMarketEvent marketEvent;
    public MarketEventEndEvent(ActiveMarketEvent marketEvent){this.marketEvent=marketEvent;} public ActiveMarketEvent getMarketEvent(){return marketEvent;}
    @Override public @NotNull HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;} }
