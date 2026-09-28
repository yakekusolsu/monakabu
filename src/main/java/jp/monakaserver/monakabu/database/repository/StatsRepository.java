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
    public record EconomyStats(BigDecimal bought,BigDecimal sold,BigDecimal fees,BigDecimal realizedProfit,BigDecimal realizedLoss,long trades,
                               BigDecimal marketValue,BigDecimal taxes,BigDecimal profit24h,BigDecimal loss24h,double top10Share,String largestHolding,String topPlayer){}
    public record SeasonHistory(int number,String winner,BigDecimal profit,Instant endedAt){}
    public record AssetRow(UUID uuid,BigDecimal stockValue){}
    public List<AssetRow> assetRows(Connection c,long seasonId)throws SQLException{List<AssetRow> rows=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT p.uuid,COALESCE(SUM(po.shares*st.current_price),0) FROM players p LEFT JOIN portfolios po ON po.uuid=p.uuid AND po.season_id=? LEFT JOIN stocks st ON st.stock_id=po.stock_id GROUP BY p.uuid")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){while(r.next())rows.add(new AssetRow(UUID.fromString(r.getString(1)),r.getBigDecimal(2)));}}return rows;}

    public PlayerStats playerStats(Connection connection,UUID uuid)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM players WHERE uuid=?")){statement.setString(1,uuid.toString());try(ResultSet rs=statement.executeQuery()){if(!rs.next())return PlayerStats.empty();return new PlayerStats(rs.getBigDecimal("total_bought"),rs.getBigDecimal("total_sold"),rs.getBigDecimal("total_profit"),rs.getBigDecimal("total_loss"),rs.getBigDecimal("realized_profit"),rs.getBigDecimal("max_profit"),rs.getBigDecimal("max_loss"),rs.getLong("trades"),rs.getLong("buys"),rs.getLong("sells"),rs.getInt("seasons"),rs.getBigDecimal("best_season_profit"));}}
    }

    public List<TransactionView> recentTransactions(Connection connection,UUID uuid,int limit)throws SQLException{
        List<TransactionView> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT transaction_id,stock_id,type,shares,price,net,occurred_at,status FROM transactions WHERE uuid=? ORDER BY occurred_at DESC LIMIT ?")){statement.setString(1,uuid.toString());statement.setInt(2,limit);try(ResultSet rs=statement.executeQuery()){while(rs.next())result.add(new TransactionView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getBigDecimal(5),rs.getBigDecimal(6),Instant.ofEpochMilli(rs.getLong(7)),rs.getString(8)));}}return result;
    }

    public EconomyStats economy(Connection connection,long seasonId)throws SQLException{
        BigDecimal bought=Money.ZERO,sold=Money.ZERO,fees=Money.ZERO,profit=Money.ZERO,loss=Money.ZERO,taxes=Money.ZERO,p24=Money.ZERO,l24=Money.ZERO;long trades=0;
        try(PreparedStatement s=connection.prepareStatement("SELECT COALESCE(SUM(CASE WHEN type='BUY' THEN gross ELSE 0 END),0),COALESCE(SUM(CASE WHEN type IN ('SELL','SETTLEMENT') THEN gross ELSE 0 END),0),COALESCE(SUM(fee),0),COALESCE(SUM(CASE WHEN metadata LIKE 'realized=%' AND CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2))>0 THEN CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2)) ELSE 0 END),0),COALESCE(SUM(CASE WHEN metadata LIKE 'realized=%' AND CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2))<0 THEN -CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2)) ELSE 0 END),0),COUNT(*),COALESCE(SUM(tax),0) FROM transactions WHERE season_id=? AND status='COMPLETED'")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){if(r.next()){bought=r.getBigDecimal(1);sold=r.getBigDecimal(2);fees=r.getBigDecimal(3);profit=r.getBigDecimal(4);loss=r.getBigDecimal(5);trades=r.getLong(6);taxes=r.getBigDecimal(7);}}}
        long since=Instant.now().minusSeconds(86400).toEpochMilli();try(PreparedStatement s=connection.prepareStatement("SELECT COALESCE(SUM(CASE WHEN metadata LIKE 'realized=%' AND CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2))>0 THEN CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2)) ELSE 0 END),0),COALESCE(SUM(CASE WHEN metadata LIKE 'realized=%' AND CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2))<0 THEN -CAST(SUBSTRING(metadata,10) AS DECIMAL(20,2)) ELSE 0 END),0) FROM transactions WHERE season_id=? AND status='COMPLETED' AND occurred_at>=?")){s.setLong(1,seasonId);s.setLong(2,since);try(ResultSet r=s.executeQuery()){if(r.next()){p24=r.getBigDecimal(1);l24=r.getBigDecimal(2);}}}
        try(PreparedStatement s=connection.prepareStatement("SELECT COALESCE(SUM(taxes_paid+carryover_withheld),0) FROM season_player_economy WHERE season_id=?")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){if(r.next())taxes=r.getBigDecimal(1);}}
        BigDecimal market=Money.ZERO;double concentration=0;String holding="-",top="-";try(PreparedStatement s=connection.prepareStatement("SELECT COALESCE(SUM(p.shares*s.current_price),0) FROM portfolios p JOIN stocks s ON s.stock_id=p.stock_id WHERE p.season_id=?")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){if(r.next())market=r.getBigDecimal(1);}}
        if(market.signum()>0)try(PreparedStatement s=connection.prepareStatement("SELECT COALESCE(SUM(v),0) FROM (SELECT SUM(p.shares*s.current_price) v FROM portfolios p JOIN stocks s ON s.stock_id=p.stock_id WHERE p.season_id=? GROUP BY p.uuid ORDER BY v DESC LIMIT 10) x")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){if(r.next())concentration=r.getBigDecimal(1).multiply(BigDecimal.valueOf(100)).divide(market,2,java.math.RoundingMode.HALF_UP).doubleValue();}}
        try(PreparedStatement s=connection.prepareStatement("SELECT p.stock_id,SUM(p.shares) n FROM portfolios p WHERE p.season_id=? GROUP BY p.stock_id ORDER BY n DESC LIMIT 1")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){if(r.next())holding=r.getString(1)+" ("+r.getLong(2)+"株)";}}
        try(PreparedStatement s=connection.prepareStatement("SELECT p.last_name,e.realized_profit-e.realized_loss v FROM season_player_economy e JOIN players p ON p.uuid=e.uuid WHERE e.season_id=? ORDER BY v DESC LIMIT 1")){s.setLong(1,seasonId);try(ResultSet r=s.executeQuery()){if(r.next())top=r.getString(1)+" ("+Money.format(r.getBigDecimal(2))+" MONA)";}}
        return new EconomyStats(bought,sold,fees,profit,loss,trades,market,taxes,p24,l24,concentration,holding,top);
    }

    public List<SeasonHistory> seasonHistory(Connection connection,int limit)throws SQLException{
        List<SeasonHistory> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT s.season_number,p.last_name,r.realized_profit,s.settled_at FROM seasons s LEFT JOIN season_results r ON r.season_id=s.season_id AND r.rank_profit=1 LEFT JOIN players p ON p.uuid=r.uuid WHERE s.status='CLOSED' ORDER BY s.season_number DESC LIMIT ?")){statement.setInt(1,limit);try(ResultSet rs=statement.executeQuery()){while(rs.next()){long ended=rs.getLong(4);result.add(new SeasonHistory(rs.getInt(1),rs.getString(2)==null?"-":rs.getString(2),rs.getBigDecimal(3)==null?Money.ZERO:rs.getBigDecimal(3),Instant.ofEpochMilli(ended)));}}}return result;
    }
}
