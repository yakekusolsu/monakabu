package jp.monakaserver.monakabu;

import jp.monakaserver.monakabu.api.MonaKabu;
import jp.monakaserver.monakabu.api.MonaKabuAPIImpl;
import jp.monakaserver.monakabu.command.MonaKabuCommand;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.MarketEventRepository;
import jp.monakaserver.monakabu.database.repository.DailyReportRepository;
import jp.monakaserver.monakabu.database.repository.HologramRepository;
import jp.monakaserver.monakabu.database.repository.PaymentRepository;
import jp.monakaserver.monakabu.database.repository.PlayerRepository;
import jp.monakaserver.monakabu.database.repository.RewardRepository;
import jp.monakaserver.monakabu.database.repository.RealtimeOutboxRepository;
import jp.monakaserver.monakabu.database.repository.SeasonRepository;
import jp.monakaserver.monakabu.database.repository.SettlementRepository;
import jp.monakaserver.monakabu.database.repository.StatsRepository;
import jp.monakaserver.monakabu.database.repository.StockRepository;
import jp.monakaserver.monakabu.database.repository.TradingRepository;
import jp.monakaserver.monakabu.economy.EconomyService;
import jp.monakaserver.monakabu.economy.PaymentService;
import jp.monakaserver.monakabu.gui.GuiService;
import jp.monakaserver.monakabu.hologram.HologramService;
import jp.monakaserver.monakabu.listener.PlayerListener;
import jp.monakaserver.monakabu.market.MarketEventService;
import jp.monakaserver.monakabu.market.MarketService;
import jp.monakaserver.monakabu.market.DailyReportService;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.placeholder.PlaceholderService;
import jp.monakaserver.monakabu.realtime.RealtimeService;
import jp.monakaserver.monakabu.realtime.MonaPriceRealtimeBridge;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.season.SettlementService;
import jp.monakaserver.monakabu.season.RewardService;
import jp.monakaserver.monakabu.trading.TradingService;
import jp.monakaserver.monakabu.webhook.WebhookService;
import jp.monakaserver.monakabu.web.WebTradingService;
import java.time.ZoneId;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MonaKabuPlugin extends JavaPlugin {
    private ConfigManager configs;private DatabaseManager database;private StockRegistry stocks;private MarketService market;private MarketEventService marketEvents;
    private SeasonService seasons;private TradingService trading;private WebhookService webhook;private HologramService holograms;private RealtimeService realtime;private MonaPriceRealtimeBridge monaPriceRealtime;private DailyReportService dailyReports;private WebTradingService webTrading;

    @Override public void onEnable(){
        try{
            configs=new ConfigManager(this);configs.load();ZoneId zone=ZoneId.of(configs.config().getString("timezone","Asia/Tokyo"));
            stocks=new StockRegistry(zone);stocks.load(configs.stocks().getConfigurationSection("stocks"));
            database=new DatabaseManager(this,Objects.requireNonNull(configs.config().getConfigurationSection("database"),"database config"));
            StockRepository stockRepository=new StockRepository();SeasonRepository seasonRepository=new SeasonRepository();TradingRepository tradingRepository=new TradingRepository();PlayerRepository playerRepository=new PlayerRepository();
            SettlementRepository settlementRepository=new SettlementRepository();PaymentRepository paymentRepository=new PaymentRepository();MarketEventRepository eventRepository=new MarketEventRepository();DailyReportRepository dailyReportRepository=new DailyReportRepository();StatsRepository statsRepository=new StatsRepository();RewardRepository rewardRepository=new RewardRepository();HologramRepository hologramRepository=new HologramRepository();RealtimeOutboxRepository realtimeOutboxRepository=new RealtimeOutboxRepository();
            database.transaction(c->{stockRepository.synchronizeDefinitions(c,stocks.all());stockRepository.restore(c,stocks);tradingRepository.recoverBuyJournal(c);return null;}).join();
            MessageService messages=new MessageService(configs);EconomyService economy=new EconomyService(this);PaymentService payments=new PaymentService(this,database,paymentRepository,economy,messages);
            seasons=new SeasonService(this,configs,database,seasonRepository,messages);
            marketEvents=new MarketEventService(this,configs,database,eventRepository,messages,seasons::current);marketEvents.reloadDefinitions();
            market=new MarketService(this,configs,database,stockRepository,stocks,marketEvents,seasons::current);
            RewardService rewards=new RewardService(this,configs,database,rewardRepository,payments);SettlementService settlement=new SettlementService(this,configs,database,seasonRepository,settlementRepository,payments,marketEvents,messages,rewards);seasons.wire(settlement,market);seasons.initialize();marketEvents.restore();
            trading=new TradingService(this,configs,database,tradingRepository,playerRepository,economy,payments,stocks,seasons);
            GuiService gui=new GuiService(this,configs,stocks,trading,seasons,market,marketEvents,database,settlementRepository,statsRepository,messages);
            getServer().getPluginManager().registerEvents(gui,this);getServer().getPluginManager().registerEvents(new PlayerListener(this,database,playerRepository,payments),this);
            holograms=new HologramService(this,configs,database,hologramRepository,stocks,market,seasons,messages);getServer().getPluginManager().registerEvents(holograms,this);holograms.restore();
            webhook=new WebhookService(this,configs,stocks,market,seasons);getServer().getPluginManager().registerEvents(webhook,this);
            realtime=new RealtimeService(this,configs,database,realtimeOutboxRepository,stocks,marketEvents,seasons);getServer().getPluginManager().registerEvents(realtime,this);realtime.start();
            monaPriceRealtime=new MonaPriceRealtimeBridge(this,configs,realtime);monaPriceRealtime.start();
            webTrading=new WebTradingService(this,configs,realtime,trading,economy,messages);webTrading.start();
            dailyReports=new DailyReportService(this,configs,database,dailyReportRepository,stocks,messages,webhook,realtime);dailyReports.start();
            MonaKabuCommand command=new MonaKabuCommand(this,configs,stocks,market,marketEvents,seasons,trading,gui,holograms,webTrading,database,statsRepository,messages,this::reloadPlugin);
            PluginCommand registered=Objects.requireNonNull(getCommand("monakabu"));registered.setExecutor(command);registered.setTabCompleter(command);
            if(getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")){new PlaceholderService(this,stocks,seasons,trading,database,statsRepository,settlementRepository).register();getLogger().info("PlaceholderAPI integration enabled");}
            MonaKabu.register(new MonaKabuAPIImpl(stocks,trading,seasons));market.start();getLogger().info("MonaKabu "+getPluginMeta().getVersion()+" enabled. Season "+seasons.current().number()+" / "+seasons.current().status());
        }catch(Throwable error){getLogger().log(Level.SEVERE,"MonaKabu startup failed",error);getServer().getPluginManager().disablePlugin(this);}
    }

    private void reloadPlugin(){
        configs.load();stocks.load(configs.stocks().getConfigurationSection("stocks"));marketEvents.reloadDefinitions();market.stop();market.reloadSettings();seasons.reloadSchedule();market.start();if(webhook!=null)webhook.reload();if(realtime!=null)realtime.reload();if(monaPriceRealtime!=null)monaPriceRealtime.reload();if(webTrading!=null)webTrading.reload();if(dailyReports!=null)dailyReports.reload();if(holograms!=null)holograms.refreshAll();
        StockRepository repository=new StockRepository();database.transaction(c->{repository.synchronizeDefinitions(c,stocks.all());repository.restore(c,stocks);return null;}).exceptionally(error->{getLogger().log(Level.SEVERE,"Stock reload persistence failed",error);return null;});
    }

    @Override public void onDisable(){
        MonaKabu.unregister();if(market!=null)market.stop();if(seasons!=null)seasons.shutdown();if(dailyReports!=null)dailyReports.close();if(webTrading!=null)webTrading.close();if(webhook!=null)webhook.close();if(monaPriceRealtime!=null)monaPriceRealtime.close();if(realtime!=null)realtime.close();if(holograms!=null)holograms.close();if(database!=null)database.close();getLogger().info("MonaKabu disabled safely");
    }
}
