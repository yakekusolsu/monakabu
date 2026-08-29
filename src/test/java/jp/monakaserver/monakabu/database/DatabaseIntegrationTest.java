package jp.monakaserver.monakabu.database;

import static org.assertj.core.api.Assertions.assertThat;
import jp.monakaserver.monakabu.database.repository.PlayerRepository;
import jp.monakaserver.monakabu.database.repository.SettlementRepository;
import jp.monakaserver.monakabu.database.repository.TradingRepository;
import jp.monakaserver.monakabu.database.repository.RewardRepository;
import jp.monakaserver.monakabu.database.repository.HologramRepository;
import jp.monakaserver.monakabu.database.repository.RealtimeOutboxRepository;
import jp.monakaserver.monakabu.model.PortfolioPosition;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseIntegrationTest {
    private Connection connection;private UUID player;
    @BeforeEach void setup()throws Exception{connection=DriverManager.getConnection("jdbc:sqlite::memory:");try(Statement statement=connection.createStatement()){statement.execute("PRAGMA foreign_keys=ON");for(String sql:DatabaseManager.schema(DatabaseManager.Dialect.SQLITE))statement.execute(sql);}player=UUID.randomUUID();new PlayerRepository().upsert(connection,player,"Tester");try(PreparedStatement stock=connection.prepareStatement("INSERT INTO stocks(stock_id,symbol,current_price,previous_price,daily_high,daily_low,trend,bankrupt,updated_at) VALUES('mona','MON',1500,1500,1500,1500,'NORMAL',0,?)")){stock.setLong(1,Instant.now().toEpochMilli());stock.executeUpdate();}try(PreparedStatement season=connection.prepareStatement("INSERT INTO seasons(season_id,season_number,starts_at,ends_at,status,created_at) VALUES(1,1,?,?, 'OPEN',?)")){season.setLong(1,Instant.now().minusSeconds(60).toEpochMilli());season.setLong(2,Instant.now().plusSeconds(3600).toEpochMilli());season.setLong(3,Instant.now().toEpochMilli());season.executeUpdate();}}
    @Test void buySellAndSettlementAreJournaledExactlyOnce()throws Exception{TradingRepository trades=new TradingRepository();TradingRepository.PreparedBuy buy=trades.prepareBuy(connection,"BUY-TEST",player,"mona",1,10,BigDecimal.valueOf(1000),BigDecimal.valueOf(10000),BigDecimal.valueOf(100),BigDecimal.valueOf(10100),1000,BigDecimal.valueOf(1_000_000));trades.markBuyEconomyApplied(connection,buy.transactionId());assertThat(trades.completeBuy(connection,buy)).isEqualTo(10);assertThat(trades.completeBuy(connection,buy)).isEqualTo(10);TradingRepository.SellCommit sale=trades.commitSell(connection,"SELL-TEST",player,"mona",1,2,BigDecimal.valueOf(1500),BigDecimal.valueOf(3000),BigDecimal.valueOf(60),BigDecimal.valueOf(94),BigDecimal.valueOf(2846));assertThat(sale.resultingShares()).isEqualTo(8);try(PreparedStatement close=connection.prepareStatement("UPDATE seasons SET status='SETTLEMENT' WHERE season_id=1")){close.executeUpdate();}SettlementRepository settlement=new SettlementRepository();var first=settlement.settleBatch(connection,1,1,100,new SettlementRepository.SettlementOptions(0,false,0,false));var second=settlement.settleBatch(connection,1,1,100,new SettlementRepository.SettlementOptions(0,false,0,false));assertThat(first.positions()).isEqualTo(1);assertThat(second.positions()).isZero();assertThat(trades.position(connection,player,"mona",1).map(PortfolioPosition::shares)).contains(0L);assertThat(count("transactions")).isEqualTo(3);assertThat(count("pending_payments")).isEqualTo(2);settlement.buildResults(connection,1);RewardRepository rewards=new RewardRepository();var winners=rewards.claimWinners(connection,1,3);assertThat(winners).hasSize(1);assertThat(rewards.claimWinners(connection,1,3)).isEmpty();rewards.addMoney(connection,1,1,winners.getFirst(),BigDecimal.valueOf(1000));assertThat(count("pending_payments")).isEqualTo(3);}
    @Test void hologramLocationsPersistAcrossRestart()throws Exception{HologramRepository repository=new HologramRepository();UUID world=UUID.randomUUID();var record=new HologramRepository.HologramRecord("HOLO-TEST",world,1.5,64,2.5,90,0,"*",player,Instant.now());repository.insert(connection,record);assertThat(repository.findAll(connection)).singleElement().satisfies(saved->{assertThat(saved.worldId()).isEqualTo(world);assertThat(saved.stockId()).isEqualTo("*");});assertThat(repository.delete(connection,record.id())).isTrue();assertThat(repository.findAll(connection)).isEmpty();}
    @Test void realtimeOutboxRetriesAndCompletesExactlyOnce()throws Exception{RealtimeOutboxRepository repository=new RealtimeOutboxRepository();long now=Instant.now().toEpochMilli();repository.enqueue(connection,"RT-TEST","market.snapshot","{\"large\":\""+"x".repeat(1000)+"\"}",now);assertThat(repository.ready(connection,now,10)).singleElement().satisfies(event->{assertThat(event.eventId()).isEqualTo("RT-TEST");assertThat(event.payload()).hasSizeGreaterThan(1000);});repository.markFailed(connection,"RT-TEST",1,now+1000,"network",false);assertThat(repository.ready(connection,now,10)).isEmpty();assertThat(repository.ready(connection,now+1000,10)).singleElement().extracting(RealtimeOutboxRepository.OutboxEvent::attempts).isEqualTo(1);repository.markDelivered(connection,"RT-TEST",now+1100);repository.markDelivered(connection,"RT-TEST",now+1200);assertThat(repository.ready(connection,now+2000,10)).isEmpty();}
    private long count(String table)throws Exception{try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT COUNT(*) FROM "+table)){return rs.next()?rs.getLong(1):0;}}
}
