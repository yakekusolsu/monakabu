package jp.monakaserver.monakabu.api.event;

import java.math.BigDecimal;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class StockSellEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player; private final String stockId; private final long shares; private final BigDecimal price; private final BigDecimal net; private final String transactionId;
    private boolean cancelled;
    public StockSellEvent(Player player,String stockId,long shares,BigDecimal price,BigDecimal net,String transactionId){this.player=player;this.stockId=stockId;this.shares=shares;this.price=price;this.net=net;this.transactionId=transactionId;}
    public Player getPlayer(){return player;} public String getStockId(){return stockId;} public long getShares(){return shares;}
    public BigDecimal getPrice(){return price;} public BigDecimal getNet(){return net;} public String getTransactionId(){return transactionId;}
    @Override public boolean isCancelled(){return cancelled;} @Override public void setCancelled(boolean cancelled){this.cancelled=cancelled;}
    @Override public @NotNull HandlerList getHandlers(){return HANDLERS;} public static HandlerList getHandlerList(){return HANDLERS;}
}
