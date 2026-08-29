package jp.monakaserver.monakabu.market;

import static org.assertj.core.api.Assertions.assertThat;
import jp.monakaserver.monakabu.model.StockDefinition;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.Trend;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class PriceEngineTest {
    @Test void clampsEveryUpdateAndStockBounds(){StockDefinition definition=new StockDefinition("test","Test","TST",BigDecimal.valueOf(1000),BigDecimal.valueOf(900),BigDecimal.valueOf(1100),1.5,0,Material.PAPER,0);StockSnapshot snapshot=new StockSnapshot(definition,BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),BigDecimal.valueOf(1000),Trend.BULL,null,false,Instant.now());PriceEngine engine=new PriceEngine(new PriceEngine.Settings(.2,.5,-.5,.01));for(int i=0;i<1000;i++){BigDecimal next=engine.next(snapshot,Duration.ofMinutes(5),2,new Random(i));assertThat(next).isBetween(BigDecimal.valueOf(900),BigDecimal.valueOf(1100));assertThat(next).isBetween(BigDecimal.valueOf(800),BigDecimal.valueOf(1200));}}
}
