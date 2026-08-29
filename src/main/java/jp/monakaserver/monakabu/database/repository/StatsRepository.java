package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StatsRepository {
    public record PlayerStats(BigDecimal totalBought,BigDecimal totalSold,BigDecimal totalProfit,BigDecimal totalLoss,BigDecimal realizedProfit,
                              BigDecimal maxProfit,BigDecimal maxLoss,long trades,long buys,long sells,int seasons,BigDecimal bestSeasonProfit) {
        public static PlayerStats empty(){return new PlayerStats(Money.ZERO,Money.ZERO,Money.ZERO,Money.ZERO,Money.ZERO,Money.ZERO,Money.ZERO,0,0,0,0,Money.ZERO);}
    }
    public record TransactionView(String id,String stockId,String type,long shares,BigDecimal price,BigDecimal net,Instant occurredAt,String status){}
    public record EconomyStats(BigDecimal bought,BigDecimal sold,BigDecimal fees,BigDecimal realizedProfit,BigDecimal realizedLoss,long trades){}
    public record SeasonHistory(int number,String winner,BigDecimal profit,Instant endedAt){}

    public PlayerStats playerStats(Connection connection,UUID uuid)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM players WHERE uuid=?")){statement.setString(1,uuid.toString());try(ResultSet rs=statement.executeQuery()){if(!rs.next())return PlayerStats.empty();return new PlayerStats(rs.getBigDecimal("total_bought"),rs.getBigDecimal("total_sold"),rs.getBigDecimal("total_profit"),rs.getBigDecimal("total_loss"),rs.getBigDecimal("realized_profit"),rs.getBigDecimal("max_profit"),rs.getBigDecimal("max_loss"),rs.getLong("trades"),rs.getLong("buys"),rs.getLong("sells"),rs.getInt("seasons"),rs.getBigDecimal("best_season_profit"));}}
    }

    public List<TransactionView> recentTransactions(Connection connection,UUID uuid,int limit)throws SQLException{
        List<TransactionView> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT transaction_id,stock_id,type,shares,price,net,occurred_at,status FROM transactions WHERE uuid=? ORDER BY occurred_at DESC LIMIT ?")){statement.setString(1,uuid.toString());statement.setInt(2,limit);try(ResultSet rs=statement.executeQuery()){while(rs.next())result.add(new TransactionView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getBigDecimal(5),rs.getBigDecimal(6),Instant.ofEpochMilli(rs.getLong(7)),rs.getString(8)));}}return result;
    }

    public EconomyStats economy(Connection connection,long seasonId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("SELECT COALESCE(SUM(CASE WHEN type='BUY' THEN gross ELSE 0 END),0),COALESCE(SUM(CASE WHEN type IN ('SELL','SETTLEMENT') THEN gross ELSE 0 END),0),COALESCE(SUM(fee),0),COALESCE(SUM(CASE WHEN metadata LIKE 'realized=%' AND CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2))>0 THEN CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2)) ELSE 0 END),0),COALESCE(SUM(CASE WHEN metadata LIKE 'realized=%' AND CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2))<0 THEN -CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2)) ELSE 0 END),0),COUNT(*) FROM transactions WHERE season_id=? AND status='COMPLETED'")){statement.setLong(1,seasonId);try(ResultSet rs=statement.executeQuery()){if(rs.next())return new EconomyStats(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getLong(6));}}return new EconomyStats(Money.ZERO,Money.ZERO,Money.ZERO,Money.ZERO,Money.ZERO,0);
    }

    public List<SeasonHistory> seasonHistory(Connection connection,int limit)throws SQLException{
        List<SeasonHistory> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT s.season_number,p.last_name,r.realized_profit,s.settled_at FROM seasons s LEFT JOIN season_results r ON r.season_id=s.season_id AND r.rank_profit=1 LEFT JOIN players p ON p.uuid=r.uuid WHERE s.status='CLOSED' ORDER BY s.season_number DESC LIMIT ?")){statement.setInt(1,limit);try(ResultSet rs=statement.executeQuery()){while(rs.next()){long ended=rs.getLong(4);result.add(new SeasonHistory(rs.getInt(1),rs.getString(2)==null?"-":rs.getString(2),rs.getBigDecimal(3)==null?Money.ZERO:rs.getBigDecimal(3),Instant.ofEpochMilli(ended)));}}}return result;
    }
}
