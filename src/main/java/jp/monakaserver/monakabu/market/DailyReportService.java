package jp.monakaserver.monakabu.market;

import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.DailyReportRepository;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.DailyMarketReport;
import jp.monakaserver.monakabu.model.DailyStockRange;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.realtime.RealtimeService;
import jp.monakaserver.monakabu.util.MainThread;
import jp.monakaserver.monakabu.util.Money;
import jp.monakaserver.monakabu.webhook.WebhookService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DailyReportService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final DatabaseManager database;
    private final DailyReportRepository repository;
    private final StockRegistry stocks;
    private final MessageService messages;
    private final WebhookService webhook;
    private final RealtimeService realtime;
    private final AtomicBoolean auditing = new AtomicBoolean();
    private volatile DailyReportSchedule schedule;
    private BukkitTask task;

    public DailyReportService(JavaPlugin plugin, ConfigManager configs, DatabaseManager database,
                              DailyReportRepository repository, StockRegistry stocks, MessageService messages,
                              WebhookService webhook, RealtimeService realtime) {
        this.plugin = plugin;
        this.configs = configs;
        this.database = database;
        this.repository = repository;
        this.stocks = stocks;
        this.messages = messages;
        this.webhook = webhook;
        this.realtime = realtime;
    }

    public void start() {
        close();
        if (!configs.config().getBoolean("daily-report.enabled", true)) return;
        ZoneId zone = ZoneId.of(configs.config().getString("timezone", "Asia/Tokyo"));
        LocalTime time = LocalTime.parse(configs.config().getString("daily-report.time", "21:15"));
        schedule = new DailyReportSchedule(time, zone);
        long period = Math.max(20, configs.config().getLong("daily-report.check-interval-seconds", 30) * 20);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::audit, 20, period);
    }

    public void reload() {
        start();
    }

    void audit() {
        if (!auditing.compareAndSet(false, true)) return;
        Instant now = Instant.now();
        DailyReportSchedule currentSchedule = schedule;
        if (currentSchedule == null) {
            auditing.set(false);
            return;
        }
        LocalDate reportDate = currentSchedule.dueDate(now).orElse(null);
        if (reportDate == null) {
            auditing.set(false);
            return;
        }
        DailyMarketReport report = buildReport(reportDate, now);
        database.transaction(connection -> repository.claim(connection, database.dialect(), reportDate, now))
                .thenAccept(claimed -> { if (claimed) dispatch(report); })
                .whenComplete((ignored, error) -> {
                    auditing.set(false);
                    if (error != null) plugin.getLogger().log(Level.SEVERE, "Daily market report failed", error);
                });
    }

    DailyMarketReport buildReport(LocalDate reportDate, Instant generatedAt) {
        ArrayList<DailyStockRange> ranges = new ArrayList<>();
        for (StockSnapshot stock : stocks.all()) {
            BigDecimal range = Money.normalize(stock.dailyHigh().subtract(stock.dailyLow()).max(BigDecimal.ZERO));
            double percent = stock.dailyLow().signum() == 0 ? 0
                    : range.divide(stock.dailyLow(), 8, RoundingMode.HALF_UP).doubleValue() * 100;
            ranges.add(new DailyStockRange(stock.definition().id(), stock.definition().symbol(),
                    stock.definition().displayName(), stock.price(), stock.dailyHigh(), stock.dailyLow(), range, percent));
        }
        return new DailyMarketReport(reportDate, generatedAt, ranges);
    }

    private void dispatch(DailyMarketReport report) {
        if (configs.config().getBoolean("daily-report.game-broadcast", true)) {
            MainThread.run(plugin, () -> Bukkit.broadcast(messages.raw(gameMessage(report))));
        }
        if (configs.config().getBoolean("daily-report.webhook", true)) webhook.sendDailyReport(report);
        if (configs.config().getBoolean("daily-report.site", true)) realtime.publishDailyReport(report);
    }

    private String gameMessage(DailyMarketReport report) {
        StringBuilder text = new StringBuilder("<gold><bold>【MonaKabu 21:15 日次相場】</bold></gold>")
                .append("<newline><gray>").append(report.reportDate().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")))
                .append(" 本日の値幅</gray>");
        for (DailyStockRange stock : report.stocks()) {
            text.append("<newline><green>").append(stock.symbol()).append("</green> <white>")
                    .append(Money.format(stock.currentPrice())).append(" MONA</white>")
                    .append(" <gray>高 ").append(Money.format(stock.dailyHigh()))
                    .append(" / 安 ").append(Money.format(stock.dailyLow()))
                    .append(" / 幅 ").append(Money.format(stock.range())).append(" (")
                    .append(String.format(java.util.Locale.ROOT, "%.2f%%", stock.rangePercent())).append(")</gray>");
        }
        return text.toString();
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        schedule = null;
    }
}
