package jp.monakaserver.monakabu.listener;

import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.PlayerRepository;
import jp.monakaserver.monakabu.economy.PaymentService;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerListener implements Listener {
    private final JavaPlugin plugin;private final DatabaseManager database;private final PlayerRepository players;private final PaymentService payments;
    public PlayerListener(JavaPlugin plugin,DatabaseManager database,PlayerRepository players,PaymentService payments){this.plugin=plugin;this.database=database;this.players=players;this.payments=payments;}
    @EventHandler public void join(PlayerJoinEvent event){var uuid=event.getPlayer().getUniqueId();var name=event.getPlayer().getName();database.transaction(c->{players.upsert(c,uuid,name);return null;}).thenRun(()->payments.payAndNotify(event.getPlayer())).exceptionally(error->{plugin.getLogger().log(Level.SEVERE,"Player data initialization failed",error);return null;});}
}
