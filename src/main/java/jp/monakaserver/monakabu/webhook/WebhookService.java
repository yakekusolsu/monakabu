package jp.monakaserver.monakabu.webhook;

import jp.monakaserver.monakabu.api.event.MarketEventStartEvent;
import jp.monakaserver.monakabu.api.event.SeasonEndEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.gui.ChartRenderer;
import jp.monakaserver.monakabu.market.MarketService;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.model.DailyMarketReport;
import jp.monakaserver.monakabu.model.DailyStockRange;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class WebhookService implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final ConfigManager configs;private final HttpClient client;private final StockRegistry stocks;private final MarketService market;private final SeasonService seasons;private final AtomicBoolean chartRunning=new AtomicBoolean();private BukkitTask chartTask;
    public WebhookService(JavaPlugin plugin,ConfigManager configs,StockRegistry stocks,MarketService market,SeasonService seasons){this.plugin=plugin;this.configs=configs;this.stocks=stocks;this.market=market;this.seasons=seasons;this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();reload();}
    @EventHandler public void event(MarketEventStartEvent event){send("📈 **MonaKabu 市場速報**\n"+event.getMarketEvent().definition().name()+"\n対象: "+String.join(", ",event.getMarketEvent().definition().stockIds()));}
    @EventHandler public void season(SeasonEndEvent event){send("📊 **MonaKabu Season "+event.getSeason().number()+" 終了**\nすべての保有株を最終価格でMONA決済しました。");}

    public void reload(){if(chartTask!=null)chartTask.cancel();chartTask=null;if(!configs.config().getBoolean("webhook.enabled",false)||!configs.config().getBoolean("webhook.chart-enabled",true))return;Duration interval=DurationParser.parse(configs.config().getString("webhook.chart-interval","5m"));long ticks=Math.max(20,interval.toSeconds()*20);chartTask=Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,this::sendMarketChart,ticks,ticks);}

    public void sendMarketChart(){if(configs.config().getBoolean("webhook.chart-only-when-open",true)&&!seasons.isOpen())return;if(!chartRunning.compareAndSet(false,true))return;Duration period=DurationParser.parse(configs.config().getString("webhook.chart-period","24h"));int points=Math.max(8,Math.min(64,configs.config().getInt("webhook.chart-points",32)));Map<String,CompletableFuture<List<BigDecimal>>> futures=new HashMap<>();for(StockSnapshot stock:stocks.all())futures.put(stock.definition().id(),market.history(stock.definition().id(),period,Math.max(points,points*4)));CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenCompose(ignored->{StringBuilder content=new StringBuilder("📊 **MonaKabu リアルタイムチャート**\n");if(seasons.current()!=null)content.append("Season ").append(seasons.current().number()).append(" | ").append(seasons.current().status()).append('\n');content.append(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.of(configs.config().getString("timezone","Asia/Tokyo"))).format(java.time.Instant.now())).append("\n```\n");for(StockSnapshot stock:stocks.all()){content.append(stock.definition().symbol()).append("  ").append(Money.format(stock.price())).append(" MONA  ").append(String.format(java.util.Locale.ROOT,"%+.2f%%",stock.changePercent())).append('\n').append(ChartRenderer.render(futures.get(stock.definition().id()).getNow(List.of()),points)).append('\n');}content.append("```");return send(content.toString());}).whenComplete((result,error)->{chartRunning.set(false);if(error!=null)plugin.getLogger().log(Level.WARNING,"Webhook chart generation failed",error);});}

    public CompletableFuture<Boolean> sendDailyReport(DailyMarketReport report){
        StringBuilder content=new StringBuilder("📊 **MonaKabu 21:00 日次相場**\n").append(report.reportDate()).append(" 本日の値幅\n```\n");
        int included=0;
        for(DailyStockRange stock:report.stocks()){
            String line=stock.symbol()+"  "+Money.format(stock.currentPrice())+" MONA | 高 "+Money.format(stock.dailyHigh())+" | 安 "+Money.format(stock.dailyLow())+" | 幅 "+Money.format(stock.range())+" ("+String.format(java.util.Locale.ROOT,"%.2f%%",stock.rangePercent())+")\n";
            if(content.length()+line.length()+40>1950)break;
            content.append(line);included++;
        }
        if(included<report.stocks().size())content.append("…ほか ").append(report.stocks().size()-included).append(" 銘柄\n");
        content.append("```");return send(content.toString());
    }

    public CompletableFuture<Boolean> send(String content){if(!configs.config().getBoolean("webhook.enabled",false))return CompletableFuture.completedFuture(false);String url=configs.config().getString("webhook.url","");if(url==null||url.isBlank())return CompletableFuture.completedFuture(false);String username=configs.config().getString("webhook.username","MonaKabu");String json="{\"username\":\""+escape(username)+"\",\"content\":\""+escape(content)+"\"}";try{HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(configs.config().getLong("webhook.timeout-seconds",5))).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();return client.sendAsync(request,HttpResponse.BodyHandlers.discarding()).thenApply(response->{boolean success=response.statusCode()>=200&&response.statusCode()<300;if(!success)plugin.getLogger().warning("Discord webhook returned HTTP "+response.statusCode());return success;}).exceptionally(error->{plugin.getLogger().log(Level.WARNING,"Discord webhook failed",error);return false;});}catch(RuntimeException error){plugin.getLogger().log(Level.WARNING,"Invalid Discord webhook configuration",error);return CompletableFuture.completedFuture(false);}}
    private String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");}
    @Override public void close(){if(chartTask!=null)chartTask.cancel();chartTask=null;}
}
