package jp.monakaserver.monakabu.database.repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RewardRepository {
    public record Winner(UUID uuid,String name,int place){}
    public List<Winner> claimWinners(Connection connection,long seasonId,int maxPlace)throws SQLException{
        List<Winner> candidates=new ArrayList<>();List<Winner> claimed=new ArrayList<>();
        try(PreparedStatement select=connection.prepareStatement("SELECT r.uuid,p.last_name,r.rank_profit FROM season_results r JOIN players p ON p.uuid=r.uuid WHERE r.season_id=? AND r.rank_profit<=? AND r.rewards_applied=0 ORDER BY r.rank_profit")){
            select.setLong(1,seasonId);select.setInt(2,maxPlace);try(ResultSet rs=select.executeQuery()){
                while(rs.next())candidates.add(new Winner(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getInt(3)));
            }
        }
        for(Winner winner:candidates)try(PreparedStatement update=connection.prepareStatement("UPDATE season_results SET rewards_applied=1 WHERE season_id=? AND uuid=? AND rewards_applied=0")){update.setLong(1,seasonId);update.setString(2,winner.uuid().toString());if(update.executeUpdate()==1)claimed.add(winner);}
        return claimed;
    }
    public void addMoney(Connection connection,long seasonId,int seasonNumber,Winner winner,BigDecimal amount)throws SQLException{
        if(amount.signum()<=0)return;String reference="REWARD-S"+seasonNumber+"-P"+winner.place();
        TradingRepository.insertPayment(connection,"PAY-"+reference+"-"+winner.uuid(),winner.uuid(),amount,"REWARD",reference,seasonId);
    }
}
