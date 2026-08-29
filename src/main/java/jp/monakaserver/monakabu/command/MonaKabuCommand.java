package jp.monakaserver.monakabu.command;

import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.StatsRepository;
import jp.monakaserver.monakabu.gui.GuiService;
import jp.monakaserver.monakabu.hologram.HologramService;
import jp.monakaserver.monakabu.market.MarketEventService;
import jp.monakaserver.monakabu.market.MarketService;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.PortfolioPosition;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.trading.TradingService;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.MainThread;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class MonaKabuCommand implements CommandExecutor,TabCompleter {
    private final JavaPlugin plugin;private final ConfigManager configs;private final StockRegistry stocks;private final MarketService market;private final MarketEventService events;
    private final SeasonService seasons;private final TradingService trading;private final GuiService gui;private final HologramService holograms;private final DatabaseManager database;private final StatsRepository stats;private final MessageService messages;private final Runnable reloadHook;
    public MonaKabuCommand(JavaPlugin plugin,ConfigManager configs,StockRegistry stocks,MarketService market,MarketEventService events,SeasonService seasons,TradingService trading,GuiService gui,HologramService holograms,DatabaseManager database,StatsRepository stats,MessageService messages,Runnable reloadHook){this.plugin=plugin;this.configs=configs;this.stocks=stocks;this.market=market;this.events=events;this.seasons=seasons;this.trading=trading;this.gui=gui;this.holograms=holograms;this.database=database;this.stats=stats;this.messages=messages;this.reloadHook=reloadHook;}

    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,String[] args){
        if(args.length==0){if(!(sender instanceof Player player)){messages.send(sender,"player-only");return true;}if(!sender.hasPermission("monakabu.use")){messages.send(sender,"no-permission");return true;}gui.openMain(player);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);try{return switch(sub){
            case "admin"->{if(admin(sender,"monakabu.admin")&&sender instanceof Player player)gui.openAdmin(player);else if(!(sender instanceof Player))messages.send(sender,"player-only");yield true;}
            case "reload"->{if(admin(sender,"monakabu.admin.reload")){reloadHook.run();messages.send(sender,"reload-success");}yield true;}
            case "price"->{if(admin(sender,"monakabu.admin.price"))price(sender,args);yield true;}
            case "event"->{if(admin(sender,"monakabu.admin.event"))event(sender,args);yield true;}
            case "halt"->{if(admin(sender,"monakabu.admin.halt"))halt(sender,args);yield true;}
            case "resume"->{if(admin(sender,"monakabu.admin.halt"))resume(sender,args);yield true;}
            case "season"->{if(args.length>=2&&(args[1].equalsIgnoreCase("end")||args[1].equalsIgnoreCase("start"))&&!admin(sender,"monakabu.admin.season"))yield true;season(sender,args);yield true;}
            case "portfolio"->{if(sender instanceof Player p&&args.length==1){gui.openPortfolio(p);}else if(admin(sender,"monakabu.admin.portfolio"))portfolio(sender,args);yield true;}
            case "stats"->{stats(sender,args);yield true;}
            case "economy"->{if(admin(sender,"monakabu.admin"))economy(sender);yield true;}
            case "setholo"->{if(admin(sender,"monakabu.admin.hologram"))setHologram(sender,args);yield true;}
            case "removeholo"->{if(admin(sender,"monakabu.admin.hologram"))removeHologram(sender,args);yield true;}
            case "history"->{if(sender instanceof Player p)gui.openHistory(p);else messages.send(sender,"player-only");yield true;}
            case "ranking"->{if(sender instanceof Player p)gui.openRanking(p);else messages.send(sender,"player-only");yield true;}
            default->{help(sender);yield true;}
        };}catch(IllegalArgumentException error){sender.sendMessage(messages.raw("<red>入力エラー: "+error.getMessage()));return true;}catch(Throwable error){plugin.getLogger().log(Level.SEVERE,"Command failed",error);messages.send(sender,"error");return true;}}

    private boolean admin(CommandSender sender,String permission){if(!sender.hasPermission(permission)){messages.send(sender,"no-permission");return false;}return true;}
    private void price(CommandSender sender,String[] args){if(args.length<3)throw new IllegalArgumentException("/monakabu price <stock> <price>");String id=args[1];BigDecimal value=new BigDecimal(args[2]);market.forcePrice(id,value).thenAccept(stock->MainThread.run(plugin,()->messages.send(sender,"price-set",Map.of("stock",id,"price",Money.format(stock.price()))))).exceptionally(e->{asyncError(sender,e);return null;});}
    private void event(CommandSender sender,String[] args){if(args.length<3)throw new IllegalArgumentException("/monakabu event <stock> <event>");if(!events.startForStock(args[1],args[2]))throw new IllegalArgumentException("銘柄またはイベントが見つかりません");}
    private void halt(CommandSender sender,String[] args){if(args.length<2)throw new IllegalArgumentException("/monakabu halt <stock> [duration]");Duration duration=args.length>=3?DurationParser.parse(args[2]):Duration.ofDays(3650);market.halt(args[1],duration).thenRun(()->MainThread.run(plugin,()->messages.send(sender,"halted",Map.of("stock",args[1]))));}
    private void resume(CommandSender sender,String[] args){if(args.length<2)throw new IllegalArgumentException("/monakabu resume <stock>");market.resume(args[1]).thenRun(()->MainThread.run(plugin,()->messages.send(sender,"resumed",Map.of("stock",args[1]))));}
    private void season(CommandSender sender,String[] args){Season season=seasons.current();if(args.length<2||args[1].equalsIgnoreCase("info")){if(season==null){sender.sendMessage("No season");return;}sender.sendMessage(messages.raw("<gold>Season "+season.number()+" <gray>| 状態: <white>"+season.status()+" <gray>| 残り: <white>"+DurationParser.formatJapanese(seasons.remaining())));return;}if(args[1].equalsIgnoreCase("end")){seasons.forceEnd();sender.sendMessage(messages.raw("<yellow>シーズン終了処理を開始しました。"));}else if(args[1].equalsIgnoreCase("start")){seasons.forceStart().thenAccept(s->MainThread.run(plugin,()->sender.sendMessage(messages.raw("<green>Season "+s.number()+" を開始しました。"))));}else throw new IllegalArgumentException("/monakabu season <info|start|end>");}
    private void portfolio(CommandSender sender,String[] args){if(args.length<2)throw new IllegalArgumentException("/monakabu portfolio <player>");OfflinePlayer target=findPlayer(args[1]);if(target==null)throw new IllegalArgumentException("プレイヤーが見つかりません");trading.portfolio(target.getUniqueId()).thenAccept(positions->MainThread.run(plugin,()->{sender.sendMessage(messages.raw("<gold>"+(target.getName()==null?target.getUniqueId():target.getName())+" の保有株"));for(PortfolioPosition p:positions)sender.sendMessage(messages.raw("<gray>・<white>"+p.stockId()+" <gold>"+p.shares()+"株 <gray>平均 "+Money.format(p.averageCost())+" MONA"));}));}
    private void stats(CommandSender sender,String[] args){UUID uuid;if(args.length>=2){if(!sender.hasPermission("monakabu.admin.portfolio")){messages.send(sender,"no-permission");return;}OfflinePlayer target=findPlayer(args[1]);if(target==null)throw new IllegalArgumentException("プレイヤーが見つかりません");uuid=target.getUniqueId();}else if(sender instanceof Player player)uuid=player.getUniqueId();else throw new IllegalArgumentException("/monakabu stats <player>");database.read(c->stats.playerStats(c,uuid)).thenAccept(s->MainThread.run(plugin,()->sender.sendMessage(messages.raw("<gold>取引統計<newline><gray>実現損益: <white>"+Money.format(s.realizedProfit())+" MONA<newline><gray>購入総額: <white>"+Money.format(s.totalBought())+" MONA<newline><gray>売却総額: <white>"+Money.format(s.totalSold())+" MONA<newline><gray>取引回数: <white>"+s.trades()))));}
    private void economy(CommandSender sender){Season season=seasons.current();if(season==null)return;database.read(c->stats.economy(c,season.id())).thenAccept(s->MainThread.run(plugin,()->sender.sendMessage(messages.raw("<gold>Season "+season.number()+" 経済統計<newline><gray>株購入総額: <white>"+Money.format(s.bought())+" MONA<newline><gray>株売却総額: <white>"+Money.format(s.sold())+" MONA<newline><gray>手数料回収: <white>"+Money.format(s.fees())+" MONA<newline><gray>実現利益: <green>+"+Money.format(s.realizedProfit())+" MONA<newline><gray>実現損失: <red>-"+Money.format(s.realizedLoss())+" MONA<newline><gray>取引件数: <white>"+s.trades()))));}
    private void setHologram(CommandSender sender,String[] args){if(!(sender instanceof Player player)){messages.send(sender,"player-only");return;}String stock=args.length>=2?args[1]:"all";holograms.create(player,stock).thenAccept(result->MainThread.run(plugin,()->messages.send(sender,result.success()?"hologram-created":"hologram-failed",Map.of("message",result.message())))).exceptionally(error->{asyncError(sender,error);return null;});}
    private void removeHologram(CommandSender sender,String[] args){if(!(sender instanceof Player player)){messages.send(sender,"player-only");return;}var future=args.length>=2&&args[1].equalsIgnoreCase("all")?holograms.removeAll():holograms.removeNearest(player);future.thenAccept(result->MainThread.run(plugin,()->messages.send(sender,result.success()?"hologram-removed":"hologram-failed",Map.of("message",result.message())))).exceptionally(error->{asyncError(sender,error);return null;});}
    private OfflinePlayer findPlayer(String name){Player online=Bukkit.getPlayerExact(name);if(online!=null)return online;return Bukkit.getOfflinePlayerIfCached(name);}
    private void help(CommandSender sender){sender.sendMessage(messages.raw("<gold><bold>MonaKabu</bold></gold><newline><yellow>/kabu</yellow> GUIを開く<newline><yellow>/monakabu portfolio</yellow> 保有株<newline><yellow>/monakabu stats</yellow> 統計<newline><yellow>/monakabu ranking</yellow> ランキング<newline><yellow>/monakabu setholo [stock|all]</yellow> チャート設置"));}
    private void asyncError(CommandSender sender,Throwable error){plugin.getLogger().log(Level.SEVERE,"Async command failed",error);MainThread.run(plugin,()->messages.send(sender,"error"));}

    @Override public List<String> onTabComplete(@NotNull CommandSender sender,@NotNull Command command,@NotNull String alias,String[] args){List<String> values=new ArrayList<>();if(args.length==1)values.addAll(List.of("portfolio","stats","ranking","history","season","admin","reload","price","event","halt","resume","economy","setholo","removeholo"));else if(args.length==2&&List.of("price","event","halt","resume","setholo").contains(args[0].toLowerCase(Locale.ROOT))){if(args[0].equalsIgnoreCase("setholo"))values.add("all");stocks.all().forEach(s->values.add(s.definition().id()));}else if(args.length==2&&args[0].equalsIgnoreCase("removeholo"))values.addAll(List.of("nearest","all"));else if(args.length==2&&args[0].equalsIgnoreCase("season"))values.addAll(List.of("info","start","end"));String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();}
}
