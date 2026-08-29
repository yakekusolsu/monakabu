package jp.monakaserver.monakabu.market;

import jp.monakaserver.monakabu.api.event.StockPriceChangeEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.StockRepository;
import jp.monakaserver.monakabu.model.MarketStatus;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.Trend;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.MainThread;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MarketService {
    private final JavaPlugin plugin; private final ConfigManager configs; private final DatabaseManager database; private final StockRepository repository;
    private final StockRegistry registry; private final MarketEventService events; private final Supplier<Season> seasonSupplier;
    private final AtomicBoolean updating = new AtomicBoolean();
    private volatile PriceEngine engine; private volatile Duration updateInterval; private volatile Instant nextTrendChange=Instant.EPOCH;
    private BukkitTask priceTask; private BukkitTask eventTask;private BukkitTask cleanupTask;

    public MarketService(JavaPlugin plugin, ConfigManager configs, DatabaseManager database, StockRepository repository,
                         StockRegistry registry, MarketEventService events, Supplier<Season> seasonSupplier) {
        this.plugin=plugin;this.configs=configs;this.database=database;this.repository=repository;this.registry=registry;this.events=events;this.seasonSupplier=seasonSupplier;
        reloadSettings();
    }

    public void reloadSettings() {
        updateInterval=DurationParser.parse(configs.config().getString("market.price-update-interval","5m"));
        engine=new PriceEngine(new PriceEngine.Settings(configs.config().getDouble("market.max-change-per-update",.2),
                configs.config().getDouble("market.volatility-multiplier",1.5),
                configs.config().getDouble("market.bull-bias",.03),configs.config().getDouble("market.bear-bias",-.03),
                configs.config().getDouble("market.mean-reversion",.015)));
    }

    public void start() {
        stop(); long ticks=Math.max(20,updateInterval.toSeconds()*20);
        priceTask=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,this::updatePrices,ticks,ticks);
        Duration eventInterval=DurationParser.parse(configs.config().getString("events.check-interval","30m"));
        eventTask=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,events::randomCheck,eventInterval.toSeconds()*20,eventInterval.toSeconds()*20);
        cleanupTask=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,this::pruneHistory,20L*3600,20L*86400);
    }

    public void stop() { if(priceTask!=null)priceTask.cancel();if(eventTask!=null)eventTask.cancel();if(cleanupTask!=null)cleanupTask.cancel();priceTask=null;eventTask=null;cleanupTask=null; }

    public void updatePrices() {
        Season season=seasonSupplier.get(); Instant now=Instant.now();
        if(season==null||season.status()!=MarketStatus.OPEN||!updating.compareAndSet(false,true))return;
        events.expire(now);
        if(!now.isBefore(nextTrendChange)) changeTrends(now);
        Map<String,StockSnapshot> old=new HashMap<>(); ArrayList<StockSnapshot> changed=new ArrayList<>();
        try {
            for(StockSnapshot snapshot:registry.all()){
                old.put(snapshot.definition().id(),snapshot); if(snapshot.bankrupt())continue;
                BigDecimal next=engine.next(snapshot,updateInterval,events.factorFor(snapshot.definition().id(),updateInterval,now),ThreadLocalRandom.current());
                StockSnapshot updated=registry.updatePrice(snapshot.definition().id(),next,now);
                double delta=Math.abs(updated.changePercent());
                if(configs.config().getBoolean("circuit-breaker.enabled",true)&&delta>=configs.config().getDouble("circuit-breaker.change-percent",30)){
                    registry.halt(snapshot.definition().id(),now.plus(DurationParser.parse(configs.config().getString("circuit-breaker.cooldown","10m"))));
                    updated=registry.find(snapshot.definition().id()).orElseThrow();
                }
                if(configs.config().getBoolean("bankruptcy.enabled",false)&&updated.price().doubleValue()<=configs.config().getDouble("bankruptcy.threshold",25)
                        &&ThreadLocalRandom.current().nextDouble()<configs.config().getDouble("bankruptcy.chance-per-update",.005)){
                    registry.bankrupt(snapshot.definition().id(),now);updated=registry.find(snapshot.definition().id()).orElseThrow();
                }
                changed.add(updated);
            }
            database.transaction(c->{requireOpen(c,season.id());for(StockSnapshot snapshot:changed)repository.savePrice(c,snapshot,season.id());return null;}).thenRun(()-> MainThread.run(plugin,()->{
                for(StockSnapshot snapshot:changed){StockSnapshot before=old.get(snapshot.definition().id());Bukkit.getPluginManager().callEvent(new StockPriceChangeEvent(snapshot.definition().id(),before.price(),snapshot.price()));}
            })).exceptionally(error->{old.values().forEach(s->registry.restore(s.definition().id(),s.price(),s.previousPrice(),s.dailyHigh(),s.dailyLow(),s.trend(),s.haltedUntil(),s.bankrupt(),s.updatedAt()));plugin.getLogger().log(Level.SEVERE,"Price update rollback",error);return null;})
                    .whenComplete((ignored,error)->updating.set(false));
        }catch(Throwable error){old.values().forEach(s->registry.restore(s.definition().id(),s.price(),s.previousPrice(),s.dailyHigh(),s.dailyLow(),s.trend(),s.haltedUntil(),s.bankrupt(),s.updatedAt()));updating.set(false);plugin.getLogger().log(Level.SEVERE,"Price update failed",error);}
    }

    private void changeTrends(Instant now){
        int bull=configs.config().getInt("market.trend-bull-weight",20),normal=configs.config().getInt("market.trend-normal-weight",60),bear=configs.config().getInt("market.trend-bear-weight",20),total=Math.max(1,bull+normal+bear);
        for(StockSnapshot stock:registry.all()){int roll=ThreadLocalRandom.current().nextInt(total);registry.setTrend(stock.definition().id(),roll<bull?Trend.BULL:roll<bull+normal?Trend.NORMAL:Trend.BEAR);}
        nextTrendChange=now.plus(DurationParser.parse(configs.config().getString("market.trend-change-interval","6h")));
    }

    public java.util.concurrent.CompletableFuture<StockSnapshot> forcePrice(String stockId,BigDecimal price){
        Season season=seasonSupplier.get();if(season==null)return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NO_SEASON"));
        StockSnapshot before=registry.find(stockId).orElseThrow(()->new IllegalArgumentException("Unknown stock"));StockSnapshot after=registry.updatePrice(stockId,price,Instant.now());
        return database.transaction(c->{requireOpen(c,season.id());repository.savePrice(c,after,season.id());return after;}).thenApply(saved->{MainThread.run(plugin,()->Bukkit.getPluginManager().callEvent(new StockPriceChangeEvent(stockId,before.price(),saved.price())));return saved;}).whenComplete((ok,error)->{if(error!=null)registry.restore(stockId,before.price(),before.previousPrice(),before.dailyHigh(),before.dailyLow(),before.trend(),before.haltedUntil(),before.bankrupt(),before.updatedAt());});
    }

    public java.util.concurrent.CompletableFuture<Void> halt(String id,Duration duration){registry.halt(id,Instant.now().plus(duration));StockSnapshot s=registry.find(id).orElseThrow();return database.transaction(c->{repository.updateState(c,s);return null;});}
    public java.util.concurrent.CompletableFuture<Void> resume(String id){registry.resume(id);StockSnapshot s=registry.find(id).orElseThrow();return database.transaction(c->{repository.updateState(c,s);return null;});}

    public java.util.concurrent.CompletableFuture<Void> resetForSeason(long seasonId){registry.resetForSeason(Instant.now());return database.transaction(c->{for(StockSnapshot s:registry.all())repository.savePrice(c,s,seasonId);return null;});}
    public java.util.concurrent.CompletableFuture<java.util.List<BigDecimal>> history(String stockId,Duration period,int limit){long since=Instant.now().minus(period).toEpochMilli();return database.read(c->repository.history(c,stockId,since,limit));}
    public void pruneHistory(){long before=Instant.now().minus(configs.config().getLong("market.history-retention-detailed-days",30),ChronoUnit.DAYS).toEpochMilli();database.transaction(c->repository.pruneHistory(c,before));}
    private void requireOpen(java.sql.Connection connection,long seasonId)throws java.sql.SQLException{try(var statement=connection.prepareStatement("SELECT status FROM seasons WHERE season_id=?")){statement.setLong(1,seasonId);try(var rs=statement.executeQuery()){if(!rs.next()||!"OPEN".equals(rs.getString(1)))throw new IllegalStateException("MARKET_CLOSED_DURING_PRICE_UPDATE");}}}
}
