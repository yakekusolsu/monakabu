package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.model.PortfolioPosition;
import jp.monakaserver.monakabu.model.TransactionStatus;
import jp.monakaserver.monakabu.model.TransactionType;
import jp.monakaserver.monakabu.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TradingRepository {
    public record PreparedBuy(String transactionId, UUID uuid, String stockId, long seasonId, long shares,
                              BigDecimal price, BigDecimal gross, BigDecimal fee, BigDecimal net) {}
    public record SellCommit(long resultingShares, BigDecimal realizedProfit, String paymentId) {}
    public record StoredTransaction(String transactionId, UUID uuid, String stockId, long seasonId,
                                    TransactionType type, TransactionStatus status, long shares,
                                    BigDecimal price, BigDecimal gross, BigDecimal fee, BigDecimal tax, BigDecimal net) {}

    public Optional<StoredTransaction> transaction(Connection connection, String transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid,stock_id,season_id,type,status,shares,price,gross,fee,tax,net FROM transactions WHERE transaction_id=?")) {
            statement.setString(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new StoredTransaction(transactionId, UUID.fromString(rs.getString("uuid")),
                        rs.getString("stock_id"), rs.getLong("season_id"), TransactionType.valueOf(rs.getString("type")),
                        TransactionStatus.valueOf(rs.getString("status")), rs.getLong("shares"), rs.getBigDecimal("price"),
                        rs.getBigDecimal("gross"), rs.getBigDecimal("fee"), rs.getBigDecimal("tax"), rs.getBigDecimal("net")));
            }
        }
    }

    public Optional<PortfolioPosition> position(Connection connection, UUID uuid, String stockId, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT shares,average_cost FROM portfolios WHERE uuid=? AND stock_id=? AND season_id=?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, stockId);
            statement.setLong(3, seasonId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new PortfolioPosition(uuid, stockId, seasonId, rs.getLong(1), rs.getBigDecimal(2))) : Optional.empty();
            }
        }
    }

    public List<PortfolioPosition> portfolio(Connection connection, UUID uuid, long seasonId) throws SQLException {
        List<PortfolioPosition> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT stock_id,shares,average_cost FROM portfolios WHERE uuid=? AND season_id=? AND shares>0 ORDER BY stock_id")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, seasonId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(new PortfolioPosition(uuid, rs.getString(1), seasonId, rs.getLong(2), rs.getBigDecimal(3)));
            }
        }
        return result;
    }

    public BigDecimal portfolioValue(Connection connection, UUID uuid, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(p.shares*s.current_price),0) FROM portfolios p JOIN stocks s ON s.stock_id=p.stock_id WHERE p.uuid=? AND p.season_id=?")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, seasonId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Money.normalize(rs.getBigDecimal(1)) : Money.ZERO; }
        }
    }

    public PreparedBuy prepareBuy(Connection connection, String txId, UUID uuid, String stockId, long seasonId, long shares,
                                  BigDecimal price, BigDecimal gross, BigDecimal fee, BigDecimal total,
                                  long maxShares, BigDecimal maxInvestment) throws SQLException {
        requireOpenSeason(connection, seasonId);
        PortfolioPosition current = position(connection, uuid, stockId, seasonId)
                .orElse(new PortfolioPosition(uuid, stockId, seasonId, 0, Money.ZERO));
        if (shares <= 0 || current.shares() + shares > maxShares) throw new IllegalStateException("LIMIT_SHARES");
        BigDecimal investedNow = portfolioValue(connection, uuid, seasonId);
        if (investedNow.add(gross).compareTo(maxInvestment) > 0) throw new IllegalStateException("LIMIT_INVESTMENT");
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO transactions(transaction_id,uuid,stock_id,type,shares,price,gross,fee,tax,net,occurred_at,season_id,status,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindTransaction(statement, txId, uuid, stockId, TransactionType.BUY, shares, price, gross, fee, Money.ZERO,
                    total, seasonId, TransactionStatus.PREPARED, "buy-intent");
            statement.executeUpdate();
        }
        return new PreparedBuy(txId, uuid, stockId, seasonId, shares, price, gross, fee, total);
    }

    public long completeBuy(Connection connection, PreparedBuy buy) throws SQLException {
        requireOpenSeason(connection, buy.seasonId());
        TransactionStatus status = transactionStatus(connection, buy.transactionId());
        if (status == TransactionStatus.COMPLETED) return position(connection, buy.uuid(), buy.stockId(), buy.seasonId()).map(PortfolioPosition::shares).orElse(0L);
        if (status != TransactionStatus.PREPARED && status != TransactionStatus.ECONOMY_APPLIED) throw new IllegalStateException("BUY_NOT_PREPARED");
        PortfolioPosition current = position(connection, buy.uuid(), buy.stockId(), buy.seasonId())
                .orElse(new PortfolioPosition(buy.uuid(), buy.stockId(), buy.seasonId(), 0, Money.ZERO));
        long resulting = Math.addExact(current.shares(), buy.shares());
        BigDecimal oldBasis = current.averageCost().multiply(BigDecimal.valueOf(current.shares()));
        BigDecimal newAverage = oldBasis.add(buy.gross()).divide(BigDecimal.valueOf(resulting), 2, RoundingMode.HALF_UP);
        upsertPosition(connection, buy.uuid(), buy.stockId(), buy.seasonId(), resulting, newAverage, buy.gross(), Money.ZERO);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE transactions SET status=? WHERE transaction_id=? AND status IN (?,?)")) {
            statement.setString(1, TransactionStatus.COMPLETED.name());
            statement.setString(2, buy.transactionId());
            statement.setString(3, TransactionStatus.PREPARED.name());
            statement.setString(4, TransactionStatus.ECONOMY_APPLIED.name());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("BUY_ALREADY_COMPLETED");
        }
        updatePlayerStats(connection, buy.uuid(), TransactionType.BUY, buy.gross(), Money.ZERO);
        return resulting;
    }

    public void markBuyEconomyApplied(Connection connection, String txId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE transactions SET status=? WHERE transaction_id=? AND status=?")) {
            statement.setString(1, TransactionStatus.ECONOMY_APPLIED.name());
            statement.setString(2, txId);
            statement.setString(3, TransactionStatus.PREPARED.name());
            statement.executeUpdate();
        }
    }

    public void failBuy(Connection connection, String txId, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE transactions SET status=?,metadata=? WHERE transaction_id=? AND status<>?")) {
            statement.setString(1, TransactionStatus.FAILED.name());
            statement.setString(2, reason);
            statement.setString(3, txId);
            statement.setString(4, TransactionStatus.COMPLETED.name());
            statement.executeUpdate();
        }
    }

    public void failBuyWithRefund(Connection connection, PreparedBuy buy, String reason) throws SQLException {
        failBuy(connection, buy.transactionId(), reason);
        String paymentId = "REFUND-" + buy.transactionId();
        try {
            insertPayment(connection, paymentId, buy.uuid(), buy.net(), "REFUND", buy.transactionId(), buy.seasonId());
        } catch (SQLException duplicate) {
            try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM pending_payments WHERE payment_id=?")) {
                check.setString(1, paymentId);
                try (ResultSet rs = check.executeQuery()) { if (!rs.next()) throw duplicate; }
            }
        }
    }

    public SellCommit commitSell(Connection connection, String txId, UUID uuid, String stockId, long seasonId, long shares,
                                 BigDecimal price, BigDecimal gross, BigDecimal fee, BigDecimal tax, BigDecimal net) throws SQLException {
        requireOpenSeason(connection, seasonId);
        PortfolioPosition current = position(connection, uuid, stockId, seasonId).orElseThrow(() -> new IllegalStateException("NOT_ENOUGH_SHARES"));
        if (shares <= 0 || shares > current.shares()) throw new IllegalStateException("NOT_ENOUGH_SHARES");
        long resulting = current.shares() - shares;
        BigDecimal basis = current.averageCost().multiply(BigDecimal.valueOf(shares));
        BigDecimal realized = Money.normalize(net.subtract(basis));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO transactions(transaction_id,uuid,stock_id,type,shares,price,gross,fee,tax,net,occurred_at,season_id,status,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindTransaction(statement, txId, uuid, stockId, TransactionType.SELL, shares, price, gross, fee, tax, net,
                    seasonId, TransactionStatus.COMPLETED, "realized=" + realized.toPlainString());
            statement.executeUpdate();
        }
        upsertPosition(connection, uuid, stockId, seasonId, resulting, resulting == 0 ? Money.ZERO : current.averageCost(), Money.ZERO, realized);
        String paymentId = "PAY-" + txId;
        insertPayment(connection, paymentId, uuid, net, "TRADE", txId, seasonId);
        updatePlayerStats(connection, uuid, TransactionType.SELL, gross, realized);
        return new SellCommit(resulting, realized, paymentId);
    }

    public List<String> incompleteEconomyAppliedBuys(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT transaction_id FROM transactions WHERE type=? AND status=?")) {
            statement.setString(1, TransactionType.BUY.name());
            statement.setString(2, TransactionStatus.ECONOMY_APPLIED.name());
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
        }
        return ids;
    }

    public int recoverBuyJournal(Connection connection) throws SQLException {
        List<PreparedBuy> applied = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT transaction_id,uuid,stock_id,season_id,shares,price,gross,fee,net FROM transactions WHERE type=? AND status=?")) {
            statement.setString(1, TransactionType.BUY.name()); statement.setString(2, TransactionStatus.ECONOMY_APPLIED.name());
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) applied.add(new PreparedBuy(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getBigDecimal(9))); }
        }
        int recovered=0;
        for(PreparedBuy buy:applied){
            try { completeBuy(connection,buy); recovered++; }
            catch(IllegalStateException closed){ failBuyWithRefund(connection,buy,"recovered-after-market-close"); }
        }
        try (PreparedStatement statement=connection.prepareStatement("UPDATE transactions SET status=?,metadata=? WHERE type=? AND status=?")) {
            statement.setString(1,TransactionStatus.REVIEW_REQUIRED.name());statement.setString(2,"ambiguous-prepared-buy-after-restart");statement.setString(3,TransactionType.BUY.name());statement.setString(4,TransactionStatus.PREPARED.name());statement.executeUpdate();
        }
        return recovered;
    }

    private void upsertPosition(Connection connection, UUID uuid, String stockId, long seasonId, long shares, BigDecimal average,
                                BigDecimal investedDelta, BigDecimal realizedDelta) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE portfolios SET shares=?,average_cost=?,invested=invested+?,realized_profit=realized_profit+?,version=version+1,updated_at=? WHERE uuid=? AND stock_id=? AND season_id=?")) {
            update.setLong(1, shares);
            update.setBigDecimal(2, average);
            update.setBigDecimal(3, investedDelta);
            update.setBigDecimal(4, realizedDelta);
            update.setLong(5, Instant.now().toEpochMilli());
            update.setString(6, uuid.toString());
            update.setString(7, stockId);
            update.setLong(8, seasonId);
            if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO portfolios(uuid,stock_id,season_id,shares,average_cost,invested,realized_profit,version,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, uuid.toString()); insert.setString(2, stockId); insert.setLong(3, seasonId);
            insert.setLong(4, shares); insert.setBigDecimal(5, average); insert.setBigDecimal(6, investedDelta);
            insert.setBigDecimal(7, realizedDelta); insert.setLong(8, 1); insert.setLong(9, Instant.now().toEpochMilli());
            insert.executeUpdate();
        }
    }

    private TransactionStatus transactionStatus(Connection connection, String txId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM transactions WHERE transaction_id=?")) {
            statement.setString(1, txId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("TRANSACTION_NOT_FOUND");
                return TransactionStatus.valueOf(rs.getString(1));
            }
        }
    }

    public boolean isCompleted(Connection connection, String txId) throws SQLException {
        return transactionStatus(connection, txId) == TransactionStatus.COMPLETED;
    }

    private void requireOpenSeason(Connection connection, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM seasons WHERE season_id=?")) {
            statement.setLong(1, seasonId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !"OPEN".equals(rs.getString(1))) throw new IllegalStateException("MARKET_CLOSED");
            }
        }
    }

    public static void insertPayment(Connection connection, String paymentId, UUID uuid, BigDecimal amount, String reason, String reference, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO pending_payments(payment_id,uuid,amount,reason,reference_id,season_id,state,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, paymentId); statement.setString(2, uuid.toString()); statement.setBigDecimal(3, amount);
            statement.setString(4, reason); statement.setString(5, reference); statement.setLong(6, seasonId);
            statement.setString(7, "PENDING"); statement.setLong(8, Instant.now().toEpochMilli()); statement.executeUpdate();
        }
    }

    static void bindTransaction(PreparedStatement statement, String txId, UUID uuid, String stockId, TransactionType type,
                                long shares, BigDecimal price, BigDecimal gross, BigDecimal fee, BigDecimal tax, BigDecimal net,
                                long seasonId, TransactionStatus status, String metadata) throws SQLException {
        statement.setString(1, txId); statement.setString(2, uuid.toString()); statement.setString(3, stockId);
        statement.setString(4, type.name()); statement.setLong(5, shares); statement.setBigDecimal(6, price);
        statement.setBigDecimal(7, gross); statement.setBigDecimal(8, fee); statement.setBigDecimal(9, tax);
        statement.setBigDecimal(10, net); statement.setLong(11, Instant.now().toEpochMilli()); statement.setLong(12, seasonId);
        statement.setString(13, status.name()); statement.setString(14, metadata);
    }

    private void updatePlayerStats(Connection connection, UUID uuid, TransactionType type, BigDecimal gross, BigDecimal realized) throws SQLException {
        String sql = type == TransactionType.BUY
                ? "UPDATE players SET total_bought=total_bought+?,trades=trades+1,buys=buys+1 WHERE uuid=?"
                : "UPDATE players SET total_sold=total_sold+?,realized_profit=realized_profit+?,total_profit=total_profit+?,total_loss=total_loss+?,max_profit=CASE WHEN ?>max_profit THEN ? ELSE max_profit END,max_loss=CASE WHEN ?<max_loss THEN ? ELSE max_loss END,trades=trades+1,sells=sells+1 WHERE uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (type == TransactionType.BUY) {
                statement.setBigDecimal(1, gross); statement.setString(2, uuid.toString());
            } else {
                BigDecimal profit = realized.max(Money.ZERO);
                BigDecimal loss = realized.min(Money.ZERO);
                statement.setBigDecimal(1, gross); statement.setBigDecimal(2, realized); statement.setBigDecimal(3, profit);
                statement.setBigDecimal(4, loss.abs()); statement.setBigDecimal(5, profit); statement.setBigDecimal(6, profit);
                statement.setBigDecimal(7, loss); statement.setBigDecimal(8, loss); statement.setString(9, uuid.toString());
            }
            statement.executeUpdate();
        }
    }
}
