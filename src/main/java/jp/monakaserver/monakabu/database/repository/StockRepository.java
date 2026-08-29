package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.market.StockRegistry;
import jp.monakaserver.monakabu.model.StockSnapshot;
import jp.monakaserver.monakabu.model.Trend;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;

public final class StockRepository {
    public void synchronizeDefinitions(Connection connection, Collection<StockSnapshot> stocks) throws SQLException {
        String update = "UPDATE stocks SET symbol=? WHERE stock_id=?";
        String insert = "INSERT INTO stocks(stock_id,symbol,current_price,previous_price,daily_high,daily_low,trend,halted_until,bankrupt,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)";
        for (StockSnapshot stock : stocks) {
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, stock.definition().symbol());
                statement.setString(2, stock.definition().id());
                if (statement.executeUpdate() > 0) continue;
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, stock.definition().id());
                statement.setString(2, stock.definition().symbol());
                statement.setBigDecimal(3, stock.price());
                statement.setBigDecimal(4, stock.previousPrice());
                statement.setBigDecimal(5, stock.dailyHigh());
                statement.setBigDecimal(6, stock.dailyLow());
                statement.setString(7, stock.trend().name());
                statement.setObject(8, null);
                statement.setInt(9, 0);
                statement.setLong(10, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
        }
    }

    public void restore(Connection connection, StockRegistry registry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM stocks"); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                long halted = rs.getLong("halted_until");
                registry.restore(rs.getString("stock_id"), rs.getBigDecimal("current_price"), rs.getBigDecimal("previous_price"),
                        rs.getBigDecimal("daily_high"), rs.getBigDecimal("daily_low"), Trend.valueOf(rs.getString("trend")),
                        rs.wasNull() ? null : Instant.ofEpochMilli(halted), rs.getInt("bankrupt") != 0,
                        Instant.ofEpochMilli(rs.getLong("updated_at")));
            }
        }
    }

    public void savePrice(Connection connection, StockSnapshot stock, long seasonId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE stocks SET current_price=?, previous_price=?, daily_high=?, daily_low=?, trend=?, halted_until=?, bankrupt=?, updated_at=? WHERE stock_id=?")) {
            update.setBigDecimal(1, stock.price());
            update.setBigDecimal(2, stock.previousPrice());
            update.setBigDecimal(3, stock.dailyHigh());
            update.setBigDecimal(4, stock.dailyLow());
            update.setString(5, stock.trend().name());
            if (stock.haltedUntil() == null) update.setObject(6, null); else update.setLong(6, stock.haltedUntil().toEpochMilli());
            update.setInt(7, stock.bankrupt() ? 1 : 0);
            update.setLong(8, stock.updatedAt().toEpochMilli());
            update.setString(9, stock.definition().id());
            update.executeUpdate();
        }
        try (PreparedStatement history = connection.prepareStatement("INSERT INTO stock_prices(stock_id,price,recorded_at,season_id) VALUES(?,?,?,?)")) {
            history.setString(1, stock.definition().id());
            history.setBigDecimal(2, stock.price());
            history.setLong(3, stock.updatedAt().toEpochMilli());
            history.setLong(4, seasonId);
            history.executeUpdate();
        }
    }

    public void updateState(Connection connection, StockSnapshot stock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE stocks SET trend=?, halted_until=?, bankrupt=?, current_price=?, previous_price=?, updated_at=? WHERE stock_id=?")) {
            statement.setString(1, stock.trend().name());
            if (stock.haltedUntil() == null) statement.setObject(2, null); else statement.setLong(2, stock.haltedUntil().toEpochMilli());
            statement.setInt(3, stock.bankrupt() ? 1 : 0);
            statement.setBigDecimal(4, stock.price());
            statement.setBigDecimal(5, stock.previousPrice());
            statement.setLong(6, stock.updatedAt().toEpochMilli());
            statement.setString(7, stock.definition().id());
            statement.executeUpdate();
        }
    }

    public java.util.List<BigDecimal> history(Connection connection, String stockId, long since, int limit) throws SQLException {
        java.util.ArrayList<BigDecimal> prices = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT price FROM stock_prices WHERE stock_id=? AND recorded_at>=? ORDER BY recorded_at DESC LIMIT ?")) {
            statement.setString(1, stockId);
            statement.setLong(2, since);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) prices.add(rs.getBigDecimal(1)); }
        }
        java.util.Collections.reverse(prices);
        return prices;
    }

    public int pruneHistory(Connection connection, long before) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM stock_prices WHERE recorded_at<?")) {
            statement.setLong(1, before);
            return statement.executeUpdate();
        }
    }
}
