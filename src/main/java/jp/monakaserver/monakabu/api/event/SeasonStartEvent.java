package jp.monakaserver.monakabu.api.event;

import jp.monakaserver.monakabu.model.Season;
import org.bukkit.event.Event; import org.bukkit.event.HandlerList; import org.jetbrains.annotations.NotNull;
public final class SeasonStartEvent extends Event { private static final HandlerList HANDLERS=new HandlerList(); private final Season season;
    public SeasonStartEvent(Season season){this.season=season;} public Season getSeason(){return season;}
    @Override public @NotNull HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;} }
