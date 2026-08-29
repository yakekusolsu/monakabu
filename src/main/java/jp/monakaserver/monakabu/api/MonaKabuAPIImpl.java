package jp.monakaserver.monakabu.api;

import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.model.PortfolioPosition;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.season.SeasonService;
import jp.monakaserver.monakabu.trading.TradingService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MonaKabuAPIImpl implements MonaKabuAPI {
    private final StockRegistry stocks;private final TradingService trading;private final SeasonService seasons;
    public MonaKabuAPIImpl(StockRegistry stocks,TradingService trading,SeasonService seasons){this.stocks=stocks;this.trading=trading;this.seasons=seasons;}
    @Override public Optional<StockSnapshot> getStock(String stockId){return stocks.find(stockId);}
    @Override public Collection<StockSnapshot> getStocks(){return stocks.all();}
    @Override public BigDecimal getStockPrice(String stockId){return stocks.find(stockId).orElseThrow(()->new IllegalArgumentException("Unknown stock: "+stockId)).price();}
    @Override public CompletableFuture<List<PortfolioPosition>> getPortfolio(UUID playerId){return trading.portfolio(playerId);}
    @Override public Season getCurrentSeason(){return seasons.current();}
    @Override public boolean isMarketOpen(){return seasons.isOpen();}
}
