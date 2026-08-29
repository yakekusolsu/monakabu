package jp.monakaserver.monakabu.trading;

import jp.monakaserver.monakabu.api.event.StockBuyEvent;
import jp.monakaserver.monakabu.api.event.StockSellEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.PlayerRepository;
import jp.monakaserver.monakabu.database.repository.TradingRepository;
import jp.monakaserver.monakabu.economy.EconomyService;
import jp.monakaserver.monakabu.economy.PaymentService;
import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.model.PortfolioPosition;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.TradeResult;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.util.MainThread;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

public final class TradingService {
    private final JavaPlugin plugin;private final ConfigManager configs;private final DatabaseManager database;private final TradingRepository repository;private final PlayerRepository players;
    private final EconomyService economy;private final PaymentService payments;private final StockRegistry stocks;private final SeasonService seasons;private final ZoneId zone;
    private final Set<UUID> busy=ConcurrentHashMap.newKeySet();

    public TradingService(JavaPlugin plugin,ConfigManager configs,DatabaseManager database,TradingRepository repository,PlayerRepository players,
                          EconomyService economy,PaymentService payments,StockRegistry stocks,SeasonService seasons){
        this.plugin=plugin;this.configs=configs;this.database=database;this.repository=repository;this.players=players;this.economy=economy;this.payments=payments;this.stocks=stocks;this.seasons=seasons;this.zone=ZoneId.of(configs.config().getString("timezone","Asia/Tokyo"));
    }

    public CompletableFuture<TradeResult> buy(Player player,String stockId,long requestedShares){
        if(!player.hasPermission("monakabu.buy")||!player.hasPermission("monakabu.trade"))return CompletableFuture.completedFuture(TradeResult.failure("NO_PERMISSION"));
        if(!busy.add(player.getUniqueId()))return CompletableFuture.completedFuture(TradeResult.failure("BUSY"));
        CompletableFuture<TradeResult> future;
        try{
            Season season=requireOpenSeason();StockSnapshot stock=requireTradable(stockId);double feePercent=Math.max(0,configs.config().getDouble("fees.buy.percent",1));double balance=economy.balance(player);UUID playerId=player.getUniqueId();String playerName=player.getName();
            long maxShares=permissionLong(player,"monakabu.limit.shares.",configs.config().getLong("limits.max-shares-per-stock",1000));
            BigDecimal maxInvestment=permissionMoney(player,"monakabu.limit.investment.",BigDecimal.valueOf(configs.config().getDouble("limits.max-total-investment",10_000_000)));
            future=database.transaction(c->{players.upsert(c,playerId,playerName);PortfolioPosition position=repository.position(c,playerId,stockId,season.id()).orElse(new PortfolioPosition(playerId,stockId,season.id(),0,Money.ZERO));
                long shares=requestedShares<0?maximumBuy(balance,stock.price(),feePercent,maxShares-position.shares()):requestedShares;
                if(shares<=0)throw new IllegalStateException("INVALID_AMOUNT");BigDecimal gross=Money.normalize(stock.price().multiply(BigDecimal.valueOf(shares)));BigDecimal fee=Money.percent(gross,feePercent);BigDecimal total=Money.normalize(gross.add(fee));
                String txId=TradeIds.random("BUY",zone);return repository.prepareBuy(c,txId,playerId,stockId,season.id(),shares,stock.price(),gross,fee,total,maxShares,maxInvestment);
            }).thenCompose(plan->MainThread.call(plugin,()->{
                StockBuyEvent event=new StockBuyEvent(player,stockId,plan.shares(),plan.price(),plan.net(),plan.transactionId());Bukkit.getPluginManager().callEvent(event);if(event.isCancelled())return new WithdrawAttempt(false,"CANCELLED");
                EconomyResponse response=economy.withdraw(player,plan.net());return new WithdrawAttempt(response.transactionSuccess(),response.errorMessage);
            }).thenCompose(attempt->{
                if(!attempt.success())return database.transaction(c->{repository.failBuy(c,plan.transactionId(),attempt.error());return TradeResult.failure(attempt.error().equals("CANCELLED")?"CANCELLED":"NOT_ENOUGH_MONEY");});
                return database.transaction(c->{repository.markBuyEconomyApplied(c,plan.transactionId());long resulting=repository.completeBuy(c,plan);return new TradeResult(true,"",plan.transactionId(),plan.stockId(),plan.shares(),plan.gross(),plan.fee(),Money.ZERO,plan.net(),resulting);})
                        .exceptionallyCompose(error->recoverFailedBuy(plan,error));
            }));
        }catch(Throwable error){future=CompletableFuture.completedFuture(TradeResult.failure(reason(error)));}
        return future.exceptionally(error->TradeResult.failure(reason(error))).whenComplete((result,error)->{busy.remove(player.getUniqueId());if(error!=null)plugin.getLogger().log(Level.SEVERE,"Buy failed",error);});
    }

    private CompletableFuture<TradeResult> recoverFailedBuy(TradingRepository.PreparedBuy plan,Throwable error){
        return database.read(c->repository.isCompleted(c,plan.transactionId())).thenCompose(completed->{
            if(completed)return database.read(c->{long shares=repository.position(c,plan.uuid(),plan.stockId(),plan.seasonId()).map(PortfolioPosition::shares).orElse(0L);return new TradeResult(true,"",plan.transactionId(),plan.stockId(),plan.shares(),plan.gross(),plan.fee(),Money.ZERO,plan.net(),shares);});
            return database.transaction(c->{repository.failBuyWithRefund(c,plan,"portfolio-commit-failed");return TradeResult.failure("REFUND_PENDING");}).whenComplete((r,e)->payments.payPending(plan.uuid()));
        }).exceptionally(readError->{plugin.getLogger().log(Level.SEVERE,"Ambiguous BUY kept for manual review: "+plan.transactionId(),readError);return TradeResult.failure("REVIEW_REQUIRED");});
    }

    public CompletableFuture<TradeResult> sell(Player player,String stockId,long requestedShares){
        if(!player.hasPermission("monakabu.sell")||!player.hasPermission("monakabu.trade"))return CompletableFuture.completedFuture(TradeResult.failure("NO_PERMISSION"));
        if(!busy.add(player.getUniqueId()))return CompletableFuture.completedFuture(TradeResult.failure("BUSY"));
        CompletableFuture<TradeResult> future;
        try{
            Season season=requireOpenSeason();StockSnapshot stock=requireTradable(stockId);double feePercent=configs.config().getDouble("fees.sell.percent",2);double taxPercent=configs.config().getDouble("capital-gains-tax.percent",10);boolean taxEnabled=configs.config().getBoolean("capital-gains-tax.enabled",true);UUID playerId=player.getUniqueId();String playerName=player.getName();
            future=database.transaction(c->{players.upsert(c,playerId,playerName);PortfolioPosition position=repository.position(c,playerId,stockId,season.id()).orElseThrow(()->new IllegalStateException("NOT_ENOUGH_SHARES"));
                long shares=requestedShares<0?position.shares():requestedShares;if(shares<=0||shares>position.shares())throw new IllegalStateException("NOT_ENOUGH_SHARES");
                BigDecimal gross=Money.normalize(stock.price().multiply(BigDecimal.valueOf(shares)));BigDecimal fee=Money.percent(gross,feePercent);BigDecimal basis=Money.normalize(position.averageCost().multiply(BigDecimal.valueOf(shares)));BigDecimal taxable=gross.subtract(fee).subtract(basis).max(Money.ZERO);BigDecimal tax=taxEnabled?Money.percent(taxable,taxPercent):Money.ZERO;BigDecimal net=Money.normalize(gross.subtract(fee).subtract(tax).max(Money.ZERO));
                String txId=TradeIds.random("SELL",zone);return new SellPlan(txId,shares,gross,fee,tax,net,position);
            }).thenCompose(plan->MainThread.call(plugin,()->{StockSellEvent event=new StockSellEvent(player,stockId,plan.shares(),stock.price(),plan.net(),plan.txId());Bukkit.getPluginManager().callEvent(event);return !event.isCancelled();}).thenCompose(allowed->{
                if(!allowed)return CompletableFuture.completedFuture(TradeResult.failure("CANCELLED"));
                return database.transaction(c->{TradingRepository.SellCommit commit=repository.commitSell(c,plan.txId(),playerId,stockId,season.id(),plan.shares(),stock.price(),plan.gross(),plan.fee(),plan.tax(),plan.net());return new TradeResult(true,"",plan.txId(),stockId,plan.shares(),plan.gross(),plan.fee(),plan.tax(),plan.net(),commit.resultingShares());}).whenComplete((result,error)->{if(error==null)payments.payAndNotify(player);});
            })).exceptionally(error->TradeResult.failure(reason(error)));
        }catch(Throwable error){future=CompletableFuture.completedFuture(TradeResult.failure(reason(error)));}
        return future.whenComplete((result,error)->busy.remove(player.getUniqueId()));
    }

    public CompletableFuture<java.util.List<PortfolioPosition>> portfolio(UUID uuid){Season season=seasons.current();if(season==null)return CompletableFuture.completedFuture(java.util.List.of());return database.read(c->repository.portfolio(c,uuid,season.id()));}
    public CompletableFuture<BigDecimal> portfolioValue(UUID uuid){Season season=seasons.current();if(season==null)return CompletableFuture.completedFuture(Money.ZERO);return database.read(c->repository.portfolioValue(c,uuid,season.id()));}

    private Season requireOpenSeason(){Season season=seasons.current();if(season==null||!seasons.isOpen())throw new IllegalStateException("MARKET_CLOSED");return season;}
    private StockSnapshot requireTradable(String id){StockSnapshot stock=stocks.find(id).orElseThrow(()->new IllegalArgumentException("UNKNOWN_STOCK"));if(stock.halted(Instant.now()))throw new IllegalStateException("STOCK_HALTED");return stock;}
    static long maximumBuy(double balance,BigDecimal price,double feePercent,long remaining){
        if(!Double.isFinite(balance)||balance<=0||price.signum()<=0||remaining<=0)return 0;
        BigDecimal available=BigDecimal.valueOf(balance);long low=0,high=remaining;
        while(low<high){long distance=high-low;long middle=low+distance/2+distance%2;if(totalBuyCost(price,middle,feePercent).compareTo(available)<=0)low=middle;else high=middle-1;}
        return low;
    }
    static BigDecimal totalBuyCost(BigDecimal price,long shares,double feePercent){BigDecimal gross=Money.normalize(price.multiply(BigDecimal.valueOf(shares)));return Money.normalize(gross.add(Money.percent(gross,Math.max(0,feePercent))));}
    private long permissionLong(Player player,String prefix,long fallback){long best=fallback;for(PermissionAttachmentInfo info:player.getEffectivePermissions())if(info.getValue()&&info.getPermission().startsWith(prefix))try{best=Math.max(best,Long.parseLong(info.getPermission().substring(prefix.length())));}catch(NumberFormatException ignored){}return best;}
    private BigDecimal permissionMoney(Player player,String prefix,BigDecimal fallback){BigDecimal best=fallback;for(PermissionAttachmentInfo info:player.getEffectivePermissions())if(info.getValue()&&info.getPermission().startsWith(prefix))try{best=best.max(new BigDecimal(info.getPermission().substring(prefix.length())));}catch(NumberFormatException ignored){}return best;}
    private String reason(Throwable throwable){Throwable root=throwable;while(root.getCause()!=null)root=root.getCause();return root.getMessage()==null?"ERROR":root.getMessage();}
    private record WithdrawAttempt(boolean success,String error){}
    private record SellPlan(String txId,long shares,BigDecimal gross,BigDecimal fee,BigDecimal tax,BigDecimal net,PortfolioPosition position){}
}
