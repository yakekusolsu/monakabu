package jp.monakaserver.monakabu.hologram;

import jp.monakaserver.monakabu.api.event.SeasonStartEvent;
import jp.monakaserver.monakabu.api.event.StockPriceChangeEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.HologramRepository;
import jp.monakaserver.monakabu.database.repository.HologramRepository.HologramRecord;
import jp.monakaserver.monakabu.gui.ChartRenderer;
import jp.monakaserver.monakabu.market.MarketService;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.MainThread;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class HologramService implements Listener,AutoCloseable {
    public record OperationResult(boolean success,String message){}
    private static final String ALL="*";
    private final JavaPlugin plugin;private final ConfigManager configs;private final DatabaseManager database;private final HologramRepository repository;
    private final StockRegistry stocks;private final MarketService market;private final SeasonService seasons;private final MessageService messages;private final NamespacedKey idKey;
    private final Map<String,HologramRecord> records=new ConcurrentHashMap<>();private final Map<String,TextDisplay> displays=new ConcurrentHashMap<>();private final AtomicBoolean refreshQueued=new AtomicBoolean();

    public HologramService(JavaPlugin plugin,ConfigManager configs,DatabaseManager database,HologramRepository repository,StockRegistry stocks,MarketService market,SeasonService seasons,MessageService messages){
        this.plugin=plugin;this.configs=configs;this.database=database;this.repository=repository;this.stocks=stocks;this.market=market;this.seasons=seasons;this.messages=messages;this.idKey=new NamespacedKey(plugin,"hologram_id");
    }

    public void restore(){database.read(repository::findAll).thenAccept(loaded->MainThread.run(plugin,()->{records.clear();for(HologramRecord record:loaded){records.put(record.id(),record);spawn(record);}refreshAll();})).exceptionally(error->{plugin.getLogger().log(Level.SEVERE,"Holograms could not be restored",error);return null;});}

    public CompletableFuture<OperationResult> create(Player player,String requestedStock){
        String stockId=requestedStock==null||requestedStock.equalsIgnoreCase("all")?ALL:requestedStock.toLowerCase(java.util.Locale.ROOT);
        if(!stockId.equals(ALL)&&stocks.find(stockId).isEmpty())return CompletableFuture.completedFuture(new OperationResult(false,"銘柄が見つかりません: "+stockId));
        Location source=player.getLocation();Vector forward=source.getDirection().setY(0);if(forward.lengthSquared()<0.0001)forward=new Vector(0,0,1);else forward.normalize();Location location=source.clone().add(forward.multiply(2)).add(0,1.8,0);UUID creator=player.getUniqueId();
        HologramRecord record=new HologramRecord("HOLO-"+UUID.randomUUID(),location.getWorld().getUID(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch(),stockId,creator,Instant.now());int max=Math.max(1,configs.config().getInt("hologram.max-count",20));
        return database.transaction(connection->{if(repository.count(connection)>=max)return false;repository.insert(connection,record);return true;}).thenApply(created->{if(!created)return new OperationResult(false,"ホログラム上限 "+max+" 個に達しています。");MainThread.run(plugin,()->{records.put(record.id(),record);spawn(record);refreshAll();});return new OperationResult(true,stockId.equals(ALL)?"全企業チャートを設置しました。":stockId+" のチャートを設置しました。");});
    }

    public CompletableFuture<OperationResult> removeNearest(Player player){Location location=player.getLocation();HologramRecord nearest=records.values().stream().filter(record->record.worldId().equals(location.getWorld().getUID())).min(java.util.Comparator.comparingDouble(record->distanceSquared(record,location))).orElse(null);if(nearest==null||distanceSquared(nearest,location)>100)return CompletableFuture.completedFuture(new OperationResult(false,"10ブロック以内にMonaKabuホログラムがありません。"));return database.transaction(connection->repository.delete(connection,nearest.id())).thenApply(deleted->{if(deleted)MainThread.run(plugin,()->removeEntity(nearest.id()));return new OperationResult(deleted,deleted?"最寄りのホログラムを削除しました。":"削除できませんでした。");});}
    public CompletableFuture<OperationResult> removeAll(){return database.transaction(connection->repository.deleteAll(connection)).thenApply(count->{MainThread.run(plugin,()->{new ArrayList<>(displays.keySet()).forEach(this::removeEntity);records.clear();});return new OperationResult(true,count+" 個のホログラムを削除しました。");});}

    @EventHandler public void price(StockPriceChangeEvent event){queueRefresh();}
    @EventHandler public void season(SeasonStartEvent event){queueRefresh();}
    @EventHandler public void world(WorldLoadEvent event){for(HologramRecord record:records.values()){TextDisplay display=displays.get(record.id());if(record.worldId().equals(event.getWorld().getUID())&&(display==null||!display.isValid()))spawn(record);}queueRefresh();}
    private void queueRefresh(){if(refreshQueued.compareAndSet(false,true))Bukkit.getScheduler().runTaskLater(plugin,()->{refreshQueued.set(false);refreshAll();},2L);}

    public void refreshAll(){if(records.isEmpty())return;Duration period=DurationParser.parse(configs.config().getString("hologram.chart-period","24h"));int points=Math.max(8,Math.min(64,configs.config().getInt("hologram.chart-points",32)));Map<String,CompletableFuture<List<BigDecimal>>> futures=new HashMap<>();for(StockSnapshot stock:stocks.all())futures.put(stock.definition().id(),market.history(stock.definition().id(),period,Math.max(points,points*4)));CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenRun(()->{Map<String,List<BigDecimal>> histories=new HashMap<>();futures.forEach((id,future)->histories.put(id,future.getNow(List.of())));MainThread.run(plugin,()->displays.forEach((id,display)->{HologramRecord record=records.get(id);if(record!=null&&display.isValid())display.text(render(record,histories,points));}));}).exceptionally(error->{plugin.getLogger().log(Level.WARNING,"Hologram chart refresh failed",error);return null;});}

    private Component render(HologramRecord record,Map<String,List<BigDecimal>> histories,int points){StringBuilder text=new StringBuilder("<gradient:#f6c453:#ff8a3d><bold>MonaKabu Market</bold></gradient>");Season season=seasons.current();if(season!=null)text.append("<newline><gray>Season ").append(season.number()).append(" | ").append(season.status()).append("</gray>");for(StockSnapshot stock:stocks.all()){if(!record.stockId().equals(ALL)&&!record.stockId().equals(stock.definition().id()))continue;double change=stock.changePercent();String changeText=(change>=0?"<green>▲ +":"<red>▼ ")+String.format(java.util.Locale.ROOT,"%.2f%%",change)+(change>=0?"</green>":"</red>");text.append("<newline><newline>").append(stock.definition().displayName()).append(" <dark_gray>[").append(stock.definition().symbol()).append("]</dark_gray>").append("<newline><white>").append(Money.format(stock.price())).append(" MONA</white> ").append(changeText).append("<newline><aqua>").append(ChartRenderer.render(histories.getOrDefault(stock.definition().id(),List.of()),points)).append("</aqua>");}return messages.raw(text.toString());}

    private void spawn(HologramRecord record){World world=Bukkit.getWorld(record.worldId());if(world==null)return;removeDisplayOnly(record.id());Location location=new Location(world,record.x(),record.y(),record.z(),record.yaw(),record.pitch());TextDisplay display=world.spawn(location,TextDisplay.class,entity->{entity.setPersistent(false);entity.setBillboard(Display.Billboard.CENTER);entity.setAlignment(TextDisplay.TextAlignment.CENTER);entity.setShadowed(true);entity.setSeeThrough(false);entity.setDefaultBackground(false);entity.setBackgroundColor(Color.fromARGB(150,0,0,0));entity.setLineWidth(Math.max(120,configs.config().getInt("hologram.line-width",240)));entity.setViewRange((float)Math.max(8,configs.config().getDouble("hologram.view-range",64)));entity.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,record.id());entity.text(messages.raw("<yellow>チャートを読み込み中…"));});displays.put(record.id(),display);}
    private void removeEntity(String id){records.remove(id);removeDisplayOnly(id);}
    private void removeDisplayOnly(String id){TextDisplay old=displays.remove(id);if(old!=null&&old.isValid())old.remove();}
    private double distanceSquared(HologramRecord record,Location location){double dx=record.x()-location.getX(),dy=record.y()-location.getY(),dz=record.z()-location.getZ();return dx*dx+dy*dy+dz*dz;}
    @Override public void close(){MainThread.run(plugin,()->new ArrayList<>(displays.keySet()).forEach(this::removeDisplayOnly));}
}
