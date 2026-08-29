package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.model.MarketStatus;
import jp.monakaserver.monakabu.model.Season;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public final class SeasonRepository {
    public Optional<Season> findLatest(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM seasons ORDER BY season_number DESC LIMIT 1");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    public Optional<Season> findById(Connection connection, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM seasons WHERE season_id=?")) {
            statement.setLong(1, seasonId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public Season create(Connection connection, Instant startsAt, Instant endsAt) throws SQLException {
        return create(connection, startsAt, endsAt, MarketStatus.OPEN);
    }

    public Season create(Connection connection, Instant startsAt, Instant endsAt, MarketStatus initialStatus) throws SQLException {
        int number = 1;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(season_number),0)+1 FROM seasons");
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) number = rs.getInt(1);
        }
        long id = number;
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO seasons(season_id,season_number,starts_at,ends_at,status,created_at) VALUES(?,?,?,?,?,?)")) {
            statement.setLong(1, id);
            statement.setInt(2, number);
            statement.setLong(3, startsAt.toEpochMilli());
            statement.setLong(4, endsAt.toEpochMilli());
            statement.setString(5, initialStatus.name());
            statement.setLong(6, now);
            statement.executeUpdate();
        }
        return new Season(id, number, startsAt, endsAt, initialStatus, null);
    }

    public boolean transition(Connection connection, long seasonId, MarketStatus expected, MarketStatus next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE seasons SET status=? WHERE season_id=? AND status=?")) {
            statement.setString(1, next.name());
            statement.setLong(2, seasonId);
            statement.setString(3, expected.name());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean claimSettlement(Connection connection, long seasonId, String key, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE seasons SET status=?, settlement_key=?, settlement_started_at=? WHERE season_id=? AND status IN (?,?)")) {
            statement.setString(1, MarketStatus.SETTLEMENT.name());
            statement.setString(2, key);
            statement.setLong(3, now.toEpochMilli());
            statement.setLong(4, seasonId);
            statement.setString(5, MarketStatus.OPEN.name());
            statement.setString(6, MarketStatus.CLOSING.name());
            if (statement.executeUpdate() == 1) return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT settlement_key,status FROM seasons WHERE season_id=?")) {
            statement.setLong(1, seasonId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && MarketStatus.SETTLEMENT.name().equals(rs.getString("status")) && key.equals(rs.getString("settlement_key"));
            }
        }
    }

    public boolean finishSettlement(Connection connection, long seasonId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE seasons SET status=?, settled_at=? WHERE season_id=? AND status=?")) {
            statement.setString(1, MarketStatus.CLOSED.name());
            statement.setLong(2, now.toEpochMilli());
            statement.setLong(3, seasonId);
            statement.setString(4, MarketStatus.SETTLEMENT.name());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean markNotification(Connection connection, long seasonId, long thresholdSeconds, Instant now) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM season_notifications WHERE season_id=? AND threshold_seconds=?")) {
            exists.setLong(1, seasonId);
            exists.setLong(2, thresholdSeconds);
            try (ResultSet rs = exists.executeQuery()) { if (rs.next()) return false; }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO season_notifications(season_id,threshold_seconds,sent_at) VALUES(?,?,?)")) {
            statement.setLong(1, seasonId);
            statement.setLong(2, thresholdSeconds);
            statement.setLong(3, now.toEpochMilli());
            statement.executeUpdate();
            return true;
        } catch (SQLException duplicate) {
            return false;
        }
    }

    private Season map(ResultSet rs) throws SQLException {
        long settled = rs.getLong("settled_at");
        return new Season(rs.getLong("season_id"), rs.getInt("season_number"),
                Instant.ofEpochMilli(rs.getLong("starts_at")), Instant.ofEpochMilli(rs.getLong("ends_at")),
                MarketStatus.valueOf(rs.getString("status")), rs.wasNull() ? null : Instant.ofEpochMilli(settled));
    }
}
