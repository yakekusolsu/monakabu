package jp.monakaserver.monakabu.database.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class RealtimeOutboxRepository {
    public record OutboxEvent(String eventId, String eventType, String payload, int attempts) {}

    public void enqueue(Connection connection, String eventId, String eventType, String payload, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO realtime_outbox(event_id,event_type,payload,created_at,next_attempt_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, eventId);
            statement.setString(2, eventType);
            statement.setString(3, payload);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    public List<OutboxEvent> ready(Connection connection, long now, int limit) throws SQLException {
        List<OutboxEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id,event_type,payload,attempts FROM realtime_outbox "
                        + "WHERE delivered_at IS NULL AND dead_at IS NULL AND next_attempt_at<=? "
                        + "ORDER BY created_at,event_id LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new OutboxEvent(result.getString(1), result.getString(2), result.getString(3), result.getInt(4)));
                }
            }
        }
        return events;
    }

    public void markDelivered(Connection connection, String eventId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE realtime_outbox SET delivered_at=?,last_error=NULL WHERE event_id=? AND delivered_at IS NULL")) {
            statement.setLong(1, now);
            statement.setString(2, eventId);
            statement.executeUpdate();
        }
    }

    public void markFailed(Connection connection, String eventId, int attempts, long retryAt, String error, boolean dead) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE realtime_outbox SET attempts=?,next_attempt_at=?,last_error=?,dead_at=? WHERE event_id=? AND delivered_at IS NULL")) {
            statement.setInt(1, attempts);
            statement.setLong(2, retryAt);
            statement.setString(3, truncate(error, 240));
            if (dead) statement.setLong(4, System.currentTimeMillis()); else statement.setNull(4, java.sql.Types.BIGINT);
            statement.setString(5, eventId);
            statement.executeUpdate();
        }
    }

    public int prune(Connection connection, long deliveredBefore) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM realtime_outbox WHERE delivered_at IS NOT NULL AND delivered_at<?")) {
            statement.setLong(1, deliveredBefore);
            return statement.executeUpdate();
        }
    }

    private String truncate(String value, int length) {
        if (value == null) return "unknown";
        return value.length() <= length ? value : value.substring(0, length);
    }
}
