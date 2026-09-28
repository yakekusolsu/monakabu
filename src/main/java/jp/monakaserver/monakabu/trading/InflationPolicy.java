package jp.monakaserver.monakabu.trading;

import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Config-backed calculations for the v2 anti-inflation rules. */
public final class InflationPolicy {
    public record AssetTier(String id, BigDecimal minimumAssets, long orderLimit, double additionalFeePercent) {}
    public record CircuitWindow(Duration window, double changePercent, Duration cooldown) {}

    private final ConfigManager configs;

    public InflationPolicy(ConfigManager configs) {
        this.configs = configs;
    }

    public long baseOrderLimit() { return positiveLong("trading-rules.order-limit", 100); }
    public long maxSharesPerStock() { return positiveLong("trading-rules.max-shares-per-stock", 1_000); }
    public Duration minimumHold() { return duration("trading-rules.minimum-hold", "5m"); }
    public double buyFee() { return percent("trading-rules.fees.buy-percent", 2); }
    public double sellFee() { return percent("trading-rules.fees.sell-percent", 3); }
    public double dailyPriceLimit() { return percent("trading-rules.price-limit.24h-percent", 25); }

    public AssetTier tier(BigDecimal totalAssets) {
        List<AssetTier> tiers = new ArrayList<>();
        ConfigurationSection root = configs.config().getConfigurationSection("trading-rules.asset-tiers");
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            tiers.add(new AssetTier(id, money(section.getDouble("minimum-assets", 0)),
                    Math.max(1, section.getLong("order-limit", baseOrderLimit())),
                    clampPercent(section.getDouble("additional-fee-percent", 0))));
        }
        if (tiers.isEmpty()) {
            tiers.add(new AssetTier("tier1", Money.ZERO, 100, 0));
            tiers.add(new AssetTier("tier2", money(1_000_001), 75, 1));
            tiers.add(new AssetTier("tier3", money(5_000_001), 50, 2));
            tiers.add(new AssetTier("tier4", money(10_000_001), 25, 4));
        }
        tiers.sort(Comparator.comparing(AssetTier::minimumAssets));
        AssetTier selected = tiers.getFirst();
        for (AssetTier candidate : tiers) if (totalAssets.compareTo(candidate.minimumAssets()) >= 0) selected = candidate;
        return selected;
    }

    public double slippage(long shares) { return bracketPercent("trading-rules.slippage", shares, new long[]{10,25,50,75,100}, new double[]{0,.5,1,2,3}); }
    public double largeSellFee(long shares) { return bracketPercent("trading-rules.large-sell-fee", shares, new long[]{25,50,75,100}, new double[]{0,1,3,5}); }

    public double shortTermTax(Duration held) {
        if (held.compareTo(Duration.ofMinutes(30)) < 0) return percent("trading-rules.short-term-tax.under-30m", 20);
        if (held.compareTo(Duration.ofHours(1)) < 0) return percent("trading-rules.short-term-tax.under-1h", 15);
        if (held.compareTo(Duration.ofHours(6)) < 0) return percent("trading-rules.short-term-tax.under-6h", 10);
        return 0;
    }

    public BigDecimal progressiveTax(BigDecimal priorNetProfit, BigDecimal currentProfit) {
        if (currentProfit.signum() <= 0) return Money.ZERO;
        BigDecimal before = priorNetProfit.max(Money.ZERO);
        BigDecimal after = priorNetProfit.add(currentProfit).max(Money.ZERO);
        if (after.compareTo(before) <= 0) return Money.ZERO;
        return Money.normalize(progressiveTaxTotal(after).subtract(progressiveTaxTotal(before)).max(Money.ZERO));
    }

    public BigDecimal carryoverWithholding(BigDecimal seasonProfit) {
        if (seasonProfit.signum() <= 0) return Money.ZERO;
        BigDecimal carried = Money.ZERO;
        BigDecimal previous = Money.ZERO;
        long[] upper = {500_000, 2_000_000, 5_000_000};
        double[] rates = {
                percent("trading-rules.season-carryover.up-to-500000", 100),
                percent("trading-rules.season-carryover.up-to-2000000", 80),
                percent("trading-rules.season-carryover.up-to-5000000", 60)};
        for (int i=0;i<upper.length;i++) {
            BigDecimal cap=money(upper[i]); BigDecimal portion=seasonProfit.min(cap).subtract(previous).max(Money.ZERO);
            carried=carried.add(Money.percent(portion,rates[i])); previous=cap;
        }
        BigDecimal excess=seasonProfit.subtract(previous).max(Money.ZERO);
        carried=carried.add(Money.percent(excess,percent("trading-rules.season-carryover.above-5000000",40)));
        return Money.normalize(seasonProfit.subtract(carried).max(Money.ZERO));
    }

    public List<CircuitWindow> circuitWindows() {
        return List.of(
                new CircuitWindow(duration("trading-rules.circuit-breaker.window-10m.window","10m"),percent("trading-rules.circuit-breaker.window-10m.change-percent",10),duration("trading-rules.circuit-breaker.window-10m.cooldown","5m")),
                new CircuitWindow(duration("trading-rules.circuit-breaker.window-30m.window","30m"),percent("trading-rules.circuit-breaker.window-30m.change-percent",15),duration("trading-rules.circuit-breaker.window-30m.cooldown","15m")),
                new CircuitWindow(duration("trading-rules.circuit-breaker.window-1h.window","1h"),percent("trading-rules.circuit-breaker.window-1h.change-percent",20),duration("trading-rules.circuit-breaker.window-1h.cooldown","30m")));
    }

    private BigDecimal progressiveTaxTotal(BigDecimal profit) {
        long[] upper={100_000,500_000,1_000_000,5_000_000};
        double[] rates={percent("trading-rules.progressive-tax.up-to-100000",5),percent("trading-rules.progressive-tax.up-to-500000",10),percent("trading-rules.progressive-tax.up-to-1000000",20),percent("trading-rules.progressive-tax.up-to-5000000",30)};
        BigDecimal tax=Money.ZERO,previous=Money.ZERO;
        for(int i=0;i<upper.length;i++){BigDecimal cap=money(upper[i]);BigDecimal portion=profit.min(cap).subtract(previous).max(Money.ZERO);tax=tax.add(Money.percent(portion,rates[i]));previous=cap;}
        return Money.normalize(tax.add(Money.percent(profit.subtract(previous).max(Money.ZERO),percent("trading-rules.progressive-tax.above-5000000",40))));
    }

    private double bracketPercent(String path,long value,long[] defaults,double[] defaultRates){
        ConfigurationSection root=configs.config().getConfigurationSection(path);double selected=0;
        for(int i=0;i<defaults.length;i++){double rate=root==null?defaultRates[i]:root.getDouble("up-to-"+defaults[i],defaultRates[i]);if(value<=defaults[i])return clampPercent(rate);selected=rate;}
        return clampPercent(selected);
    }
    private long positiveLong(String path,long fallback){return Math.max(1,configs.config().getLong(path,fallback));}
    private double percent(String path,double fallback){return clampPercent(configs.config().getDouble(path,fallback));}
    private Duration duration(String path,String fallback){return DurationParser.parse(configs.config().getString(path,fallback));}
    private static double clampPercent(double value){return Math.max(0,Math.min(100,value));}
    private static BigDecimal money(double value){return Money.of(value);}
}
