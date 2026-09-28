package jp.monakaserver.monakabu.model;

import java.math.BigDecimal;
import java.util.UUID;

public record RankingEntry(UUID playerId,String playerName,BigDecimal realizedProfit,long trades,int rank,
                           BigDecimal cash,BigDecimal stockValue,BigDecimal totalAssets,BigDecimal seasonProfit,
                           BigDecimal totalTax,BigDecimal roi){
    public RankingEntry(UUID id,String name,BigDecimal profit,long trades,int rank){this(id,name,profit,trades,rank,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,profit,BigDecimal.ZERO,BigDecimal.ZERO);}
    public RankingEntry withCash(BigDecimal value){return new RankingEntry(playerId,playerName,realizedProfit,trades,rank,value,stockValue,value.add(stockValue),seasonProfit,totalTax,roi);}
}
