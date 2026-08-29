package jp.monakaserver.monakabu.season;

import jp.monakaserver.monakabu.api.event.SeasonEndEvent;
import jp.monakaserver.monakabu.api.event.PortfolioSettlementEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.SeasonRepository;
import jp.monakaserver.monakabu.database.repository.SettlementRepository;
import jp.monakaserver.monakabu.economy.PaymentService;
import jp.monakaserver.monakabu.market.MarketEventService;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.MarketStatus;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.util.MainThread;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SettlementService {
    private final JavaPlugin plugin; private final ConfigManager configs; private final DatabaseManager database;
    private final SeasonRepository seasons; private final SettlementRepository settlements; private final PaymentService payments;
    private final MarketEventService events; private final MessageService messages; private final RewardService rewards; private final AtomicBoolean running=new AtomicBoolean();
    private Consumer<Season> statusConsumer=ignored->{}; private Consumer<Season> completionConsumer=ignored->{};

    public SettlementService(JavaPlugin plugin, ConfigManager configs, DatabaseManager database, SeasonRepository seasons,
                             SettlementRepository settlements, PaymentService payments, MarketEventService events, MessageService messages,RewardService rewards){
        this.plugin=plugin;this.configs=configs;this.database=database;this.seasons=seasons;this.settlements=settlements;this.payments=payments;this.events=events;this.messages=messages;this.rewards=rewards;
    }

    public void listeners(Consumer<Season> statusConsumer,Consumer<Season> completionConsumer){this.statusConsumer=statusConsumer;this.completionConsumer=completionConsumer;}

    public CompletableFuture<Void> settle(Season season){
        if(!running.compareAndSet(false,true))return CompletableFuture.completedFuture(null);
        String key="SEASON-"+season.number()+"-FINAL";Instant now=Instant.now();
        return database.transaction(c->seasons.claimSettlement(c,season.id(),key,now)).thenCompose(claimed->{
            if(!claimed)throw new IllegalStateException("Season settlement could not be claimed");
            Season settling=new Season(season.id(),season.number(),season.startsAt(),season.endsAt(),MarketStatus.SETTLEMENT,null);statusConsumer.accept(settling);
            return settleBatches(settling);
        }).whenComplete((ignored,error)->{running.set(false);if(error!=null)plugin.getLogger().log(Level.SEVERE,"Season settlement failed; it will resume on the next audit",error);});
    }

    private CompletableFuture<Void> settleBatches(Season season){
        int batch=Math.max(1,configs.config().getInt("season.settlement-batch-size",200));
        SettlementRepository.SettlementOptions options=new SettlementRepository.SettlementOptions(
                configs.config().getDouble("fees.sell.percent",2),configs.config().getBoolean("settlement.include-sell-fee",false),
                configs.config().getDouble("capital-gains-tax.percent",10),configs.config().getBoolean("settlement.include-capital-gains-tax",false));
        return database.transaction(c->settlements.settleBatch(c,season.id(),season.number(),batch,options)).thenCompose(result->{
            if(!result.payouts().isEmpty())MainThread.run(plugin,()->result.payouts().forEach(payout->Bukkit.getPluginManager().callEvent(new PortfolioSettlementEvent(payout.playerId(),season.id(),payout.amount()))));
            if(result.positions()>0)return settleBatches(season);
            return finalizeSeason(season);
        });
    }

    private CompletableFuture<Void> finalizeSeason(Season season){
        Instant finished=Instant.now();
        return database.transaction(c->{settlements.buildResults(c,season.id());return null;}).thenCompose(ignored->rewards.apply(season)).thenCompose(ignored->database.transaction(c->{seasons.finishSettlement(c,season.id(),finished);return null;})).thenRun(()->{
            Season closed=new Season(season.id(),season.number(),season.startsAt(),season.endsAt(),MarketStatus.CLOSED,finished);
            statusConsumer.accept(closed);events.endSeason(season.id());
            MainThread.run(plugin,()->{
                Bukkit.getPluginManager().callEvent(new SeasonEndEvent(closed));
                if(configs.config().getBoolean("season.presentation.broadcast",true))Bukkit.broadcast(messages.component("season-ended",Map.of("season",Integer.toString(season.number()))));
                for(Player player:Bukkit.getOnlinePlayers()){
                    if(configs.config().getBoolean("season.presentation.title",true)){String title=configs.config().getString("season.presentation.title-text","<gold>Season <season> 終了").replace("<season>",Integer.toString(season.number()));String subtitle=configs.config().getString("season.presentation.subtitle-text","<yellow>決済しました").replace("<season>",Integer.toString(season.number()));player.showTitle(net.kyori.adventure.title.Title.title(messages.raw(title),messages.raw(subtitle)));}
                    if(configs.config().getBoolean("season.presentation.actionbar",true))player.sendActionBar(messages.raw(configs.config().getString("season.presentation.actionbar-text","<green>次シーズンを準備しています…")));
                    if(configs.config().getBoolean("season.presentation.sound",true)){NamespacedKey key=NamespacedKey.fromString(configs.config().getString("season.presentation.sound-name","minecraft:block.note_block.pling"));Sound sound=key==null?null:Registry.SOUNDS.get(key);if(sound!=null)player.playSound(player.getLocation(),sound,(float)configs.config().getDouble("season.presentation.sound-volume",1),(float)configs.config().getDouble("season.presentation.sound-pitch",1));else plugin.getLogger().warning("Invalid season end sound");}payments.payAndNotify(player);
                }
            });
            completionConsumer.accept(closed);
        });
    }

    public boolean running(){return running.get();}
}
