package jp.monakaserver.monakabu.season;

import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.RewardRepository;
import jp.monakaserver.monakabu.economy.PaymentService;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.util.MainThread;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardService {
    private final JavaPlugin plugin;private final ConfigManager configs;private final DatabaseManager database;private final RewardRepository repository;private final PaymentService payments;
    public RewardService(JavaPlugin plugin,ConfigManager configs,DatabaseManager database,RewardRepository repository,PaymentService payments){this.plugin=plugin;this.configs=configs;this.database=database;this.repository=repository;this.payments=payments;}
    public CompletableFuture<Void> apply(Season season){if(!configs.config().getBoolean("season-rewards.enabled",false))return CompletableFuture.completedFuture(null);var places=configs.config().getConfigurationSection("season-rewards.places");if(places==null||places.getKeys(false).isEmpty())return CompletableFuture.completedFuture(null);int max=places.getKeys(false).stream().mapToInt(key->{try{return Integer.parseInt(key);}catch(NumberFormatException e){return 0;}}).max().orElse(0);
        return database.transaction(c->{List<RewardAction> actions=new ArrayList<>();for(RewardRepository.Winner winner:repository.claimWinners(c,season.id(),max)){BigDecimal money=BigDecimal.valueOf(places.getDouble(winner.place()+".money",0));repository.addMoney(c,season.id(),season.number(),winner,money);actions.add(new RewardAction(winner,places.getStringList(winner.place()+".commands")));}return actions;}).thenAccept(actions->MainThread.run(plugin,()->{for(RewardAction action:actions){for(String command:action.commands())Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command.replace("%player%",action.winner().name()));Player online=Bukkit.getPlayer(action.winner().uuid());if(online!=null)payments.payAndNotify(online);}})).exceptionally(error->{plugin.getLogger().log(Level.SEVERE,"Season rewards failed; duplicate execution is blocked",error);return null;});
    }
    private record RewardAction(RewardRepository.Winner winner,List<String> commands){}
}
