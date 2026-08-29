package jp.monakaserver.monakabu.gui;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class GuiHolder implements InventoryHolder {
    public enum Menu { MAIN, STOCKS, TRADE, PORTFOLIO, RANKING, NEWS, HISTORY, ADMIN, CONFIRM_SEASON_END }
    public enum Action { STOCKS, PORTFOLIO, RANKING, NEWS, HISTORY, CLOSE, STOCK, BUY, SELL, BACK, PAGE, ADMIN_END, ADMIN_ECONOMY, ADMIN_PRICES, ADMIN_COMPANIES, ADMIN_EVENTS, ADMIN_MARKET, ADMIN_PORTFOLIO, ADMIN_RANKING, ADMIN_HISTORY, CONFIRM_END }
    public record ClickAction(Action action,String value,long amount){}
    private final Menu menu;private final String context;private final int page;private final Map<Integer,ClickAction> actions=new HashMap<>();private Inventory inventory;
    public GuiHolder(Menu menu,String context,int page){this.menu=menu;this.context=context;this.page=page;}
    void inventory(Inventory inventory){this.inventory=inventory;}public Menu menu(){return menu;}public String context(){return context;}public int page(){return page;}
    public void action(int slot,ClickAction action){actions.put(slot,action);}public ClickAction action(int slot){return actions.get(slot);}
    @Override public @NotNull Inventory getInventory(){if(inventory==null)throw new IllegalStateException("Inventory not initialized");return inventory;}
}
