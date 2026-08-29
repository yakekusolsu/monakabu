package jp.monakaserver.monakabu.placeholder;

import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.SettlementRepository;
import jp.monakaserver.monakabu.database.repository.StatsRepository;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.trading.TradingService;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PlaceholderService extends PlaceholderExpansion {
    private final JavaPlugin plugin;private final StockRegistry stocks;private final SeasonService seasons;private final TradingService trading;private final DatabaseManager database;private final StatsRepository stats;private final SettlementRepository rankings;
    private final Map<UUID,StatsRepository.PlayerStats> playerCache=new ConcurrentHashMap<>();private final Map<UUID,BigDecimal> valueCache=new ConcurrentHashMap<>();private final Map<UUID,Integer> rankCache=new ConcurrentHashMap<>();private final java.util.Set<UUID> refreshing=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Long> lastRefresh=new ConcurrentHashMap<>();
    public PlaceholderService(JavaPlugin plugin,StockRegistry stocks,SeasonService seasons,TradingService trading,DatabaseManager database,StatsRepository stats,SettlementRepository rankings){this.plugin=plugin;this.stocks=stocks;this.seasons=seasons;this.trading=trading;this.database=database;this.stats=stats;this.rankings=rankings;}
    @Override public @NotNull String getIdentifier(){return "monakabu";}@Override public @NotNull String getAuthor(){return "MONAKA SERVER";}@Override public @NotNull String getVersion(){return plugin.getPluginMeta().getVersion();}@Override public boolean persist(){return true;}
    @Override public @Nullable String onRequest(OfflinePlayer player,@NotNull String params){
        String key=params.toLowerCase(java.util.Locale.ROOT);Season season=seasons.current();
        if(key.equals("season"))return season==null?"-":Integer.toString(season.number());if(key.equals("season_remaining"))return DurationParser.formatJapanese(seasons.remaining());
        if(key.startsWith("stock_")&&key.endsWith("_price")){String id=key.substring(6,key.length()-6);return stocks.find(id).map(s->Money.format(s.price())).orElse("0");}
        if(key.startsWith("stock_")&&key.endsWith("_change")){String id=key.substring(6,key.length()-7);return stocks.find(id).map(s->String.format(java.util.Locale.ROOT,"%+.2f%%",s.changePercent())).orElse("0.00%");}
        if(player==null)return "0";refresh(player.getUniqueId());StatsRepository.PlayerStats ps=playerCache.getOrDefault(player.getUniqueId(),StatsRepository.PlayerStats.empty());
        return switch(key){case "total_profit"->Money.format(ps.totalProfit().subtract(ps.totalLoss()));case "realized_profit"->Money.format(ps.realizedProfit());case "portfolio_value"->Money.format(valueCache.getOrDefault(player.getUniqueId(),Money.ZERO));case "ranking"->Integer.toString(rankCache.getOrDefault(player.getUniqueId(),0));default->null;};
    }
    private void refresh(UUID uuid){long now=System.currentTimeMillis();if(now-lastRefresh.getOrDefault(uuid,0L)<30_000||!refreshing.add(uuid))return;Season season=seasons.current();database.read(c->stats.playerStats(c,uuid)).thenCombine(trading.portfolioValue(uuid),(s,v)->{playerCache.put(uuid,s);valueCache.put(uuid,v);return null;}).thenCompose(ignored->{if(season==null)return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.<jp.monakaserver.monakabu.model.RankingEntry>of());return database.read(c->rankings.liveRanking(c,season.id(),100));}).thenAccept(entries->{entries.forEach(e->rankCache.put(e.playerId(),e.rank()));lastRefresh.put(uuid,System.currentTimeMillis());}).whenComplete((v,e)->refreshing.remove(uuid));}
}
