package jp.monakaserver.monakabu.api;

import jp.monakaserver.monakabu.model.PortfolioPosition;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.model.StockSnapshot;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MonaKabuAPI {
    Optional<StockSnapshot> getStock(String stockId);
    Collection<StockSnapshot> getStocks();
    BigDecimal getStockPrice(String stockId);
    CompletableFuture<List<PortfolioPosition>> getPortfolio(UUID playerId);
    Season getCurrentSeason();
    boolean isMarketOpen();
}
