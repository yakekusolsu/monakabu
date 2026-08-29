package jp.monakaserver.monakabu.api.event;

import java.math.BigDecimal;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class StockPriceChangeEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final String stockId; private final BigDecimal oldPrice; private final BigDecimal newPrice;
    public StockPriceChangeEvent(String stockId,BigDecimal oldPrice,BigDecimal newPrice){this.stockId=stockId;this.oldPrice=oldPrice;this.newPrice=newPrice;}
    public String getStockId(){return stockId;} public BigDecimal getOldPrice(){return oldPrice;} public BigDecimal getNewPrice(){return newPrice;}
    @Override public @NotNull HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;}
}
