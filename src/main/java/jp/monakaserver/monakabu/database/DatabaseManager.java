package jp.monakaserver.monakabu.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class DatabaseManager implements AutoCloseable {
    public enum Dialect { SQLITE, MYSQL }

    private final JavaPlugin plugin;
    private final ExecutorService executor;
    private final HikariDataSource dataSource;
    private final Dialect dialect;

    public DatabaseManager(JavaPlugin plugin, ConfigurationSection config) {
        this.plugin = plugin;
        String type = config.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        dialect = type.equals("sqlite") ? Dialect.SQLITE : Dialect.MYSQL;
        int threads = Math.max(1, plugin.getConfig().getInt("performance.db-threads", 4));
        executor = Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("MonaKabu-DB-", 0).factory());

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MonaKabuPool");
        hikari.setMaximumPoolSize(dialect == Dialect.SQLITE ? 1 : Math.max(2, config.getInt("pool-size", 10)));
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(Math.max(2_000, config.getLong("connection-timeout-ms", 10_000)));
        hikari.setAutoCommit(true);
        if (dialect == Dialect.SQLITE) {
            File file = new File(plugin.getDataFolder(), config.getString("sqlite-file", "monakabu.db"));
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath() + "?foreign_keys=on&journal_mode=WAL&busy_timeout=10000");
            hikari.setConnectionInitSql("PRAGMA foreign_keys=ON");
        } else {
            String scheme = type.equals("mariadb") ? "jdbc:mysql" : "jdbc:mysql";
            hikari.setJdbcUrl(scheme + "://" + config.getString("host", "localhost") + ":" + config.getInt("port", 3306)
                    + "/" + config.getString("name", "monakabu")
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&rewriteBatchedStatements=true");
            hikari.setUsername(config.getString("username", "root"));
            hikari.setPassword(config.getString("password", ""));
        }
        dataSource = new HikariDataSource(hikari);
        migrate();
    }

    public Dialect dialect() { return dialect; }

    public <T> CompletableFuture<T> read(SqlFunction<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return operation.apply(connection);
            } catch (SQLException exception) {
                throw new DatabaseException("Database read failed", exception);
            }
        }, executor);
    }

    public <T> CompletableFuture<T> transaction(SqlFunction<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = operation.apply(connection);
                    connection.commit();
                    return result;
                } catch (Throwable throwable) {
                    try { connection.rollback(); } catch (SQLException rollback) { throwable.addSuppressed(rollback); }
                    if (throwable instanceof SQLException sql) throw sql;
                    if (throwable instanceof RuntimeException runtime) throw runtime;
                    throw new DatabaseException("Database transaction failed", throwable);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException exception) {
                throw new DatabaseException("Database transaction failed", exception);
            }
        }, executor);
    }

    public void executeBlocking(SqlFunction<Connection, ?> operation) {
        try (Connection connection = dataSource.getConnection()) {
            operation.apply(connection);
        } catch (SQLException exception) {
            throw new DatabaseException("Database operation failed", exception);
        }
    }

    private void migrate() {
        executeBlocking(connection -> {
            for (String sql : schema(dialect)) {
                try (Statement statement = connection.createStatement()) {
                    try { statement.execute(sql); }
                    catch (SQLException exception) { if (!sql.startsWith("CREATE INDEX ")) throw exception; }
                }
            }
            return null;
        });
    }

    static List<String> schema(Dialect dialect) {
        String identity = dialect == Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        String text = dialect == Dialect.SQLITE ? "TEXT" : "VARCHAR(255)";
        String longText = dialect == Dialect.SQLITE ? "TEXT" : "LONGTEXT";
        return List.of(
                "CREATE TABLE IF NOT EXISTS schema_meta (version INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) PRIMARY KEY, last_name VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, last_seen BIGINT NOT NULL, total_bought DECIMAL(20,2) NOT NULL DEFAULT 0, total_sold DECIMAL(20,2) NOT NULL DEFAULT 0, total_profit DECIMAL(20,2) NOT NULL DEFAULT 0, total_loss DECIMAL(20,2) NOT NULL DEFAULT 0, realized_profit DECIMAL(20,2) NOT NULL DEFAULT 0, max_profit DECIMAL(20,2) NOT NULL DEFAULT 0, max_loss DECIMAL(20,2) NOT NULL DEFAULT 0, trades BIGINT NOT NULL DEFAULT 0, buys BIGINT NOT NULL DEFAULT 0, sells BIGINT NOT NULL DEFAULT 0, seasons INTEGER NOT NULL DEFAULT 0, best_season_profit DECIMAL(20,2) NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS stocks (stock_id VARCHAR(64) PRIMARY KEY, symbol VARCHAR(24) NOT NULL, current_price DECIMAL(20,2) NOT NULL, previous_price DECIMAL(20,2) NOT NULL, daily_high DECIMAL(20,2) NOT NULL, daily_low DECIMAL(20,2) NOT NULL, trend VARCHAR(16) NOT NULL, halted_until BIGINT NULL, bankrupt INTEGER NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS seasons (season_id BIGINT PRIMARY KEY, season_number INTEGER NOT NULL UNIQUE, starts_at BIGINT NOT NULL, ends_at BIGINT NOT NULL, status VARCHAR(20) NOT NULL, settlement_key VARCHAR(128) UNIQUE, settlement_started_at BIGINT NULL, settled_at BIGINT NULL, created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS stock_prices (id " + identity + ", stock_id VARCHAR(64) NOT NULL, price DECIMAL(20,2) NOT NULL, recorded_at BIGINT NOT NULL, season_id BIGINT NOT NULL, FOREIGN KEY(stock_id) REFERENCES stocks(stock_id), FOREIGN KEY(season_id) REFERENCES seasons(season_id))",
                "CREATE TABLE IF NOT EXISTS portfolios (uuid VARCHAR(36) NOT NULL, stock_id VARCHAR(64) NOT NULL, season_id BIGINT NOT NULL, shares BIGINT NOT NULL, average_cost DECIMAL(20,2) NOT NULL, invested DECIMAL(20,2) NOT NULL DEFAULT 0, realized_profit DECIMAL(20,2) NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL, PRIMARY KEY(uuid, stock_id, season_id), FOREIGN KEY(uuid) REFERENCES players(uuid), FOREIGN KEY(stock_id) REFERENCES stocks(stock_id), FOREIGN KEY(season_id) REFERENCES seasons(season_id))",
                "CREATE TABLE IF NOT EXISTS transactions (transaction_id VARCHAR(96) PRIMARY KEY, uuid VARCHAR(36) NOT NULL, stock_id VARCHAR(64) NOT NULL, type VARCHAR(20) NOT NULL, shares BIGINT NOT NULL, price DECIMAL(20,2) NOT NULL, gross DECIMAL(20,2) NOT NULL, fee DECIMAL(20,2) NOT NULL, tax DECIMAL(20,2) NOT NULL, net DECIMAL(20,2) NOT NULL, occurred_at BIGINT NOT NULL, season_id BIGINT NOT NULL, status VARCHAR(24) NOT NULL, metadata " + text + ", FOREIGN KEY(uuid) REFERENCES players(uuid), FOREIGN KEY(stock_id) REFERENCES stocks(stock_id), FOREIGN KEY(season_id) REFERENCES seasons(season_id))",
                "CREATE TABLE IF NOT EXISTS season_results (season_id BIGINT NOT NULL, uuid VARCHAR(36) NOT NULL, realized_profit DECIMAL(20,2) NOT NULL, asset_change DECIMAL(20,2) NOT NULL, profit_rate DECIMAL(12,6) NOT NULL, trades BIGINT NOT NULL, max_single_profit DECIMAL(20,2) NOT NULL, rank_profit INTEGER NULL, rewards_applied INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(season_id, uuid), FOREIGN KEY(season_id) REFERENCES seasons(season_id), FOREIGN KEY(uuid) REFERENCES players(uuid))",
                "CREATE TABLE IF NOT EXISTS market_events (instance_id VARCHAR(96) PRIMARY KEY, event_id VARCHAR(64) NOT NULL, stock_id VARCHAR(64) NOT NULL, modifier DECIMAL(12,6) NOT NULL, started_at BIGINT NOT NULL, ends_at BIGINT NOT NULL, ended_at BIGINT NULL, season_id BIGINT NOT NULL, FOREIGN KEY(stock_id) REFERENCES stocks(stock_id), FOREIGN KEY(season_id) REFERENCES seasons(season_id))",
                "CREATE TABLE IF NOT EXISTS pending_payments (payment_id VARCHAR(128) PRIMARY KEY, uuid VARCHAR(36) NOT NULL, amount DECIMAL(20,2) NOT NULL, reason VARCHAR(32) NOT NULL, reference_id VARCHAR(128) NOT NULL, season_id BIGINT NOT NULL, state VARCHAR(20) NOT NULL, created_at BIGINT NOT NULL, claimed_at BIGINT NULL, paid_at BIGINT NULL, failure " + text + ", UNIQUE(reason, reference_id, uuid), FOREIGN KEY(uuid) REFERENCES players(uuid), FOREIGN KEY(season_id) REFERENCES seasons(season_id))",
                "CREATE TABLE IF NOT EXISTS season_notifications (season_id BIGINT NOT NULL, threshold_seconds BIGINT NOT NULL, sent_at BIGINT NOT NULL, PRIMARY KEY(season_id, threshold_seconds), FOREIGN KEY(season_id) REFERENCES seasons(season_id))",
                "CREATE TABLE IF NOT EXISTS holograms (hologram_id VARCHAR(96) PRIMARY KEY, world_uuid VARCHAR(36) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, stock_id VARCHAR(64) NOT NULL, created_by VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS realtime_outbox (event_id VARCHAR(96) PRIMARY KEY, event_type VARCHAR(64) NOT NULL, payload " + longText + " NOT NULL, created_at BIGINT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at BIGINT NOT NULL, delivered_at BIGINT NULL, dead_at BIGINT NULL, last_error " + text + " NULL)",
                "CREATE INDEX idx_stock_prices_lookup ON stock_prices(stock_id, season_id, recorded_at)",
                "CREATE INDEX idx_portfolios_season ON portfolios(season_id, shares)",
                "CREATE INDEX idx_transactions_player ON transactions(uuid, occurred_at)",
                "CREATE INDEX idx_transactions_season ON transactions(season_id, type, status)",
                "CREATE INDEX idx_results_ranking ON season_results(season_id, realized_profit)",
                "CREATE INDEX idx_payments_player_state ON pending_payments(uuid, state)",
                "CREATE INDEX idx_events_active ON market_events(season_id, ends_at)",
                "CREATE INDEX idx_holograms_world ON holograms(world_uuid)",
                "CREATE INDEX idx_realtime_outbox_ready ON realtime_outbox(delivered_at, dead_at, next_attempt_at)"
        );
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            dataSource.close();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Database pool close failed", exception);
        }
    }
}
