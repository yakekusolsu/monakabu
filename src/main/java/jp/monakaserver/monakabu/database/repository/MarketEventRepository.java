package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.model.ActiveMarketEvent;
import jp.monakaserver.monakabu.model.MarketEventDefinition;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MarketEventRepository {
    public void start(Connection connection, ActiveMarketEvent event, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO market_events(instance_id,event_id,stock_id,modifier,started_at,ends_at,season_id) VALUES(?,?,?,?,?,?,?)")) {
            statement.setString(1, event.instanceId()); statement.setString(2, event.definition().id());
            statement.setString(3, event.definition().stockId()); statement.setDouble(4, event.definition().modifier());
            statement.setLong(5, event.startedAt().toEpochMilli()); statement.setLong(6, event.endsAt().toEpochMilli());
            statement.setLong(7, seasonId); statement.executeUpdate();
        }
    }

    public List<ActiveMarketEvent> restore(Connection connection, long seasonId, Instant now, Map<String, MarketEventDefinition> definitions) throws SQLException {
        List<ActiveMarketEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT instance_id,event_id,started_at,ends_at FROM market_events WHERE season_id=? AND ended_at IS NULL AND ends_at>?")) {
            statement.setLong(1, seasonId); statement.setLong(2, now.toEpochMilli());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    MarketEventDefinition definition = definitions.get(rs.getString("event_id"));
                    if (definition != null) events.add(new ActiveMarketEvent(rs.getString("instance_id"), definition,
                            Instant.ofEpochMilli(rs.getLong("started_at")), Instant.ofEpochMilli(rs.getLong("ends_at"))));
                }
            }
        }
        return events;
    }

    public int endExpired(Connection connection, long seasonId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE market_events SET ended_at=? WHERE season_id=? AND ended_at IS NULL AND ends_at<=?")) {
            statement.setLong(1, now.toEpochMilli()); statement.setLong(2, seasonId); statement.setLong(3, now.toEpochMilli());
            return statement.executeUpdate();
        }
    }

    public int endAll(Connection connection, long seasonId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE market_events SET ended_at=? WHERE season_id=? AND ended_at IS NULL")) {
            statement.setLong(1, now.toEpochMilli()); statement.setLong(2, seasonId); return statement.executeUpdate();
        }
    }
}
