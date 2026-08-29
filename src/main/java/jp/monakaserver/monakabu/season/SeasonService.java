package jp.monakaserver.monakabu.season;

import jp.monakaserver.monakabu.api.event.SeasonStartEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.SeasonRepository;
import jp.monakaserver.monakabu.market.MarketService;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.MarketStatus;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.MainThread;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SeasonService {
    private final JavaPlugin plugin;private final ConfigManager configs;private final DatabaseManager database;private final SeasonRepository repository;private final MessageService messages;
    private volatile Season current;private volatile SeasonSchedule schedule;private SettlementService settlement;private MarketService market;private BukkitTask task;private final AtomicBoolean auditing=new AtomicBoolean();

    public SeasonService(JavaPlugin plugin,ConfigManager configs,DatabaseManager database,SeasonRepository repository,MessageService messages){this.plugin=plugin;this.configs=configs;this.database=database;this.repository=repository;this.messages=messages;reloadSchedule();}

    public void wire(SettlementService settlement,MarketService market){this.settlement=settlement;this.market=market;settlement.listeners(this::setCurrent,this::afterSettlement);}

    public void reloadSchedule(){ZoneId zone=ZoneId.of(configs.config().getString("timezone","Asia/Tokyo"));LocalDate anchor=LocalDate.parse(configs.config().getString("season.anchor-date","2026-08-23"));LocalTime time=LocalTime.parse(configs.config().getString("season.settlement-time","21:00"));int days=configs.config().getInt("season.duration-days",14);DayOfWeek end=DayOfWeek.valueOf(configs.config().getString("season.end-day","SUNDAY").toUpperCase(java.util.Locale.ROOT));schedule=new SeasonSchedule(anchor,time,zone,days,end);}

    public void initialize(){
        current=database.transaction(c->{var latest=repository.findLatest(c);if(latest.isPresent())return latest.get();SeasonSchedule.Window window=schedule.windowAt(Instant.now());MarketStatus state=Instant.now().isBefore(window.startsAt())?MarketStatus.OPENING:MarketStatus.OPEN;return repository.create(c,window.startsAt(),window.endsAt(),state);}).join();
        if(current.status()==MarketStatus.CLOSED&&configs.config().getBoolean("season.auto-start-next-season",true))startNext(current).join();
        audit();long period=Math.max(20,configs.config().getLong("season.check-interval-seconds",20)*20);task=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,this::audit,period,period);
    }

    public void shutdown(){if(task!=null)task.cancel();}

    public void audit(){if(!configs.config().getBoolean("season.enabled",true)||!auditing.compareAndSet(false,true))return;try{
        Season season=current;if(season==null)return;Instant now=Instant.now();
        if(season.status()==MarketStatus.OPENING&&!now.isBefore(season.startsAt()))openScheduled(season);
        else if((season.status()==MarketStatus.OPEN||season.status()==MarketStatus.CLOSING||season.status()==MarketStatus.SETTLEMENT)&&!now.isBefore(season.endsAt()))settlement.settle(season);
        else if(season.status()==MarketStatus.OPEN)sendDueNotifications(season,now);
    }finally{auditing.set(false);}}

    private void openScheduled(Season season){database.transaction(c->repository.transition(c,season.id(),MarketStatus.OPENING,MarketStatus.OPEN)).thenAccept(changed->{if(changed){Season open=new Season(season.id(),season.number(),season.startsAt(),season.endsAt(),MarketStatus.OPEN,null);setCurrent(open);announceStart(open);}}).exceptionally(e->{plugin.getLogger().log(Level.SEVERE,"Season opening failed",e);return null;});}

    private void sendDueNotifications(Season season,Instant now){
        Duration remaining=Duration.between(now,season.endsAt());if(remaining.isNegative())return;List<Duration> thresholds=new ArrayList<>();
        for(String raw:configs.config().getStringList("season.notifications"))try{thresholds.add(DurationParser.parse(raw));}catch(IllegalArgumentException e){plugin.getLogger().warning("Invalid season notification: "+raw);}
        thresholds.sort(Comparator.reverseOrder());long check=configs.config().getLong("season.check-interval-seconds",20);
        for(Duration threshold:thresholds)if(remaining.compareTo(threshold)<=0&&remaining.plusSeconds(check+2).compareTo(threshold)>=0){database.transaction(c->repository.markNotification(c,season.id(),threshold.toSeconds(),now)).thenAccept(marked->{if(marked)MainThread.run(plugin,()->Bukkit.broadcast(messages.component("notification",Map.of("remaining",DurationParser.formatJapanese(remaining)))));});}
    }

    public CompletableFuture<Void> forceEnd(){Season season=current;if(season==null||season.status()==MarketStatus.CLOSED)return CompletableFuture.failedFuture(new IllegalStateException("NO_OPEN_SEASON"));return settlement.settle(season);}

    public CompletableFuture<Season> forceStart(){Season season=current;if(season!=null&&season.status()!=MarketStatus.CLOSED)return CompletableFuture.failedFuture(new IllegalStateException("SEASON_ALREADY_ACTIVE"));return startNext(season);}

    private void afterSettlement(Season closed){if(configs.config().getBoolean("season.auto-start-next-season",true))startNext(closed).exceptionally(error->{plugin.getLogger().log(Level.SEVERE,"Next season could not start; startup audit will retry",error);return null;});}

    private CompletableFuture<Season> startNext(Season closed){
        Instant now=Instant.now();SeasonSchedule.Window window=closed==null?schedule.windowAt(now):new SeasonSchedule.Window(closed.endsAt(),closed.endsAt().plus(Duration.ofDays(configs.config().getInt("season.duration-days",14))),closed.number());
        MarketStatus state=now.isBefore(window.startsAt())?MarketStatus.OPENING:MarketStatus.OPEN;
        return database.transaction(c->{var latest=repository.findLatest(c);if(latest.isPresent()&&latest.get().status()!=MarketStatus.CLOSED)return latest.get();return repository.create(c,window.startsAt(),window.endsAt(),state);}).thenCompose(next->{setCurrent(next);return market.resetForSeason(next.id()).thenApply(ignored->next);}).thenApply(next->{if(next.status()==MarketStatus.OPEN)announceStart(next);return next;});
    }

    private void announceStart(Season season){MainThread.run(plugin,()->{Bukkit.getPluginManager().callEvent(new SeasonStartEvent(season));Bukkit.broadcast(messages.component("season-started",Map.of("season",Integer.toString(season.number()))));});}
    private void setCurrent(Season season){current=season;}
    public Season current(){return current;}
    public boolean isOpen(){return current!=null&&current.status()==MarketStatus.OPEN;}
    public Duration remaining(){return current==null?Duration.ZERO:Duration.between(Instant.now(),current.endsAt()).isNegative()?Duration.ZERO:Duration.between(Instant.now(),current.endsAt());}
}
