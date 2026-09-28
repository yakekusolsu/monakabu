package jp.monakaserver.monakabu.database.repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PaymentRepository {
    public enum State { PENDING, PROCESSING, PAID, REVIEW_REQUIRED }
    public record Payment(String id, UUID uuid, BigDecimal amount, String reason, String referenceId, long seasonId) {}
    public record Charge(String id,UUID uuid,BigDecimal remaining){}
    public Charge nextCharge(Connection c,UUID uuid)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT charge_id,remaining FROM pending_charges WHERE uuid=? AND paid_at IS NULL AND remaining>0 ORDER BY created_at LIMIT 1")){s.setString(1,uuid.toString());try(ResultSet r=s.executeQuery()){return r.next()?new Charge(r.getString(1),uuid,r.getBigDecimal(2)):null;}}}
    public void applyCharge(Connection c,String id,BigDecimal amount)throws SQLException{try(PreparedStatement s=c.prepareStatement("UPDATE pending_charges SET remaining=CASE WHEN remaining>? THEN remaining-? ELSE 0 END,paid_at=CASE WHEN remaining<=? THEN ? ELSE paid_at END WHERE charge_id=? AND paid_at IS NULL")){s.setBigDecimal(1,amount);s.setBigDecimal(2,amount);s.setBigDecimal(3,amount);s.setLong(4,Instant.now().toEpochMilli());s.setString(5,id);s.executeUpdate();}}

    public List<Payment> claimForPlayer(Connection connection, UUID uuid, int limit) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT payment_id FROM pending_payments WHERE uuid=? AND state=? ORDER BY created_at LIMIT ?")) {
            statement.setString(1, uuid.toString()); statement.setString(2, State.PENDING.name()); statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
        }
        List<Payment> claimed = new ArrayList<>();
        for (String id : ids) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE pending_payments SET state=?,claimed_at=? WHERE payment_id=? AND state=?")) {
                update.setString(1, State.PROCESSING.name()); update.setLong(2, Instant.now().toEpochMilli());
                update.setString(3, id); update.setString(4, State.PENDING.name());
                if (update.executeUpdate() != 1) continue;
            }
            try (PreparedStatement select = connection.prepareStatement("SELECT * FROM pending_payments WHERE payment_id=?")) {
                select.setString(1, id);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) claimed.add(new Payment(id, UUID.fromString(rs.getString("uuid")), rs.getBigDecimal("amount"), rs.getString("reason"), rs.getString("reference_id"), rs.getLong("season_id")));
                }
            }
        }
        return claimed;
    }

    public void paid(Connection connection, String paymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE pending_payments SET state=?,paid_at=?,failure=NULL WHERE payment_id=? AND state=?")) {
            statement.setString(1, State.PAID.name()); statement.setLong(2, Instant.now().toEpochMilli());
            statement.setString(3, paymentId); statement.setString(4, State.PROCESSING.name()); statement.executeUpdate();
        }
    }

    public void release(Connection connection, String paymentId, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE pending_payments SET state=?,claimed_at=NULL,failure=? WHERE payment_id=? AND state=?")) {
            statement.setString(1, State.PENDING.name()); statement.setString(2, failure); statement.setString(3, paymentId);
            statement.setString(4, State.PROCESSING.name()); statement.executeUpdate();
        }
    }

    public void reviewRequired(Connection connection, String paymentId, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE pending_payments SET state=?,failure=? WHERE payment_id=? AND state=?")) {
            statement.setString(1, State.REVIEW_REQUIRED.name()); statement.setString(2, failure); statement.setString(3, paymentId);
            statement.setString(4, State.PROCESSING.name()); statement.executeUpdate();
        }
    }

    public long pendingCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pending_payments WHERE state IN (?,?)")) {
            statement.setString(1, State.PENDING.name()); statement.setString(2, State.REVIEW_REQUIRED.name());
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }
}
