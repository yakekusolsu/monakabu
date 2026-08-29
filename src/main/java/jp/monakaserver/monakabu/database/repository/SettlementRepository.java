package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.model.RankingEntry;
import jp.monakaserver.monakabu.model.TransactionStatus;
import jp.monakaserver.monakabu.model.TransactionType;
import jp.monakaserver.monakabu.trading.TradeIds;
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
import java.util.UUID;

public final class SettlementRepository {
    public record SettlementOptions(double sellFeePercent, boolean chargeSellFee, double taxPercent, boolean chargeTax) {}
    public record Payout(UUID playerId,BigDecimal amount){}
    public record BatchResult(int positions, BigDecimal gross, BigDecimal fees, BigDecimal taxes,List<Payout> payouts) {}

    public BatchResult settleBatch(Connection connection, long seasonId, int seasonNumber, int limit, SettlementOptions options) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT p.uuid,p.stock_id,p.shares,p.average_cost,s.current_price FROM portfolios p JOIN stocks s ON s.stock_id=p.stock_id WHERE p.season_id=? AND p.shares>0 ORDER BY p.uuid,p.stock_id LIMIT ?")) {
            statement.setLong(1, seasonId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) rows.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4), rs.getBigDecimal(5)));
            }
        }
        BigDecimal grossTotal = Money.ZERO, feeTotal = Money.ZERO, taxTotal = Money.ZERO;List<Payout> payouts=new ArrayList<>();
        for (Row row : rows) {
            String txId = TradeIds.settlement(seasonNumber, row.uuid().toString(), row.stockId());
            if (transactionExists(connection, txId)) {
                zeroInconsistentPosition(connection, row, seasonId);
                continue;
            }
            BigDecimal gross = Money.normalize(row.price().multiply(BigDecimal.valueOf(row.shares())));
            BigDecimal fee = options.chargeSellFee() ? Money.percent(gross, options.sellFeePercent()) : Money.ZERO;
            BigDecimal basis = Money.normalize(row.averageCost().multiply(BigDecimal.valueOf(row.shares())));
            BigDecimal profitBeforeTax = gross.subtract(fee).subtract(basis).max(Money.ZERO);
            BigDecimal tax = options.chargeTax() ? Money.percent(profitBeforeTax, options.taxPercent()) : Money.ZERO;
            BigDecimal net = Money.normalize(gross.subtract(fee).subtract(tax).max(Money.ZERO));
            BigDecimal realized = Money.normalize(net.subtract(basis));
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO transactions(transaction_id,uuid,stock_id,type,shares,price,gross,fee,tax,net,occurred_at,season_id,status,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                TradingRepository.bindTransaction(statement, txId, row.uuid(), row.stockId(), TransactionType.SETTLEMENT, row.shares(), row.price(), gross,
                        fee, tax, net, seasonId, TransactionStatus.COMPLETED, "realized=" + realized.toPlainString());
                statement.executeUpdate();
            }
            String paymentId = "PAY-" + txId;
            TradingRepository.insertPayment(connection, paymentId, row.uuid(), net, "SETTLEMENT", txId, seasonId);
            payouts.add(new Payout(row.uuid(),net));
            try (PreparedStatement statement = connection.prepareStatement("UPDATE portfolios SET shares=0,average_cost=0,realized_profit=realized_profit+?,version=version+1,updated_at=? WHERE uuid=? AND stock_id=? AND season_id=? AND shares=?")) {
                statement.setBigDecimal(1, realized); statement.setLong(2, Instant.now().toEpochMilli()); statement.setString(3, row.uuid().toString());
                statement.setString(4, row.stockId()); statement.setLong(5, seasonId); statement.setLong(6, row.shares());
                if (statement.executeUpdate() != 1) throw new SQLException("Concurrent portfolio modification during settlement");
            }
            updatePlayer(connection, row.uuid(), gross, realized);
            grossTotal = grossTotal.add(gross); feeTotal = feeTotal.add(fee); taxTotal = taxTotal.add(tax);
        }
        return new BatchResult(rows.size(), Money.normalize(grossTotal), Money.normalize(feeTotal), Money.normalize(taxTotal),List.copyOf(payouts));
    }

    public void buildResults(Connection connection, long seasonId) throws SQLException {
        List<ResultRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT p.uuid,COALESCE(SUM(p.realized_profit),0),COALESCE(SUM(p.invested),0),(SELECT COUNT(*) FROM transactions t WHERE t.uuid=p.uuid AND t.season_id=p.season_id AND t.status=?) FROM portfolios p WHERE p.season_id=? GROUP BY p.uuid")) {
            statement.setString(1, TransactionStatus.COMPLETED.name());
            statement.setLong(2, seasonId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BigDecimal profit = rs.getBigDecimal(2);
                    BigDecimal invested = rs.getBigDecimal(3);
                    BigDecimal rate = invested.signum() == 0 ? BigDecimal.ZERO : profit.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    rows.add(new ResultRow(UUID.fromString(rs.getString(1)), profit, invested, rate, rs.getLong(4)));
                }
            }
        }
        for (ResultRow row : rows) upsertResult(connection, seasonId, row);
        int rank = 1;
        try (PreparedStatement select = connection.prepareStatement("SELECT uuid FROM season_results WHERE season_id=? ORDER BY realized_profit DESC, trades ASC, uuid ASC")) {
            select.setLong(1, seasonId);
            try (ResultSet rs = select.executeQuery(); PreparedStatement update = connection.prepareStatement("UPDATE season_results SET rank_profit=? WHERE season_id=? AND uuid=?")) {
                while (rs.next()) {
                    update.setInt(1, rank++); update.setLong(2, seasonId); update.setString(3, rs.getString(1)); update.addBatch();
                }
                update.executeBatch();
            }
        }
    }

    public List<RankingEntry> ranking(Connection connection, long seasonId, int limit) throws SQLException {
        List<RankingEntry> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT r.uuid,p.last_name,r.realized_profit,r.trades,r.rank_profit FROM season_results r JOIN players p ON p.uuid=r.uuid WHERE r.season_id=? ORDER BY r.rank_profit LIMIT ?")) {
            statement.setLong(1, seasonId); statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(new RankingEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4), rs.getInt(5)));
            }
        }
        return result;
    }

    public List<RankingEntry> liveRanking(Connection connection, long seasonId, int limit) throws SQLException {
        List<RankingEntry> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT p.uuid,p.last_name,COALESCE(SUM(po.realized_profit + po.shares*(s.current_price-po.average_cost)),0) AS profit,COALESCE(MAX((SELECT COUNT(*) FROM transactions t WHERE t.uuid=p.uuid AND t.season_id=? AND t.status=?)),0) AS trades FROM players p JOIN portfolios po ON po.uuid=p.uuid AND po.season_id=? JOIN stocks s ON s.stock_id=po.stock_id GROUP BY p.uuid,p.last_name ORDER BY profit DESC LIMIT ?")) {
            statement.setLong(1, seasonId); statement.setString(2, TransactionStatus.COMPLETED.name()); statement.setLong(3, seasonId); statement.setInt(4, limit);
            try (ResultSet rs = statement.executeQuery()) {
                int rank = 1;
                while (rs.next()) result.add(new RankingEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4), rank++));
            }
        }
        return result;
    }

    private boolean transactionExists(Connection connection, String txId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM transactions WHERE transaction_id=?")) {
            statement.setString(1, txId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next(); }
        }
    }

    private void zeroInconsistentPosition(Connection connection, Row row, long seasonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE portfolios SET shares=0,average_cost=0,version=version+1,updated_at=? WHERE uuid=? AND stock_id=? AND season_id=?")) {
            statement.setLong(1, Instant.now().toEpochMilli()); statement.setString(2, row.uuid().toString());
            statement.setString(3, row.stockId()); statement.setLong(4, seasonId); statement.executeUpdate();
        }
    }

    private void updatePlayer(Connection connection, UUID uuid, BigDecimal gross, BigDecimal realized) throws SQLException {
        BigDecimal profit = realized.max(Money.ZERO), loss = realized.min(Money.ZERO);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE players SET total_sold=total_sold+?,realized_profit=realized_profit+?,total_profit=total_profit+?,total_loss=total_loss+?,max_profit=CASE WHEN ?>max_profit THEN ? ELSE max_profit END,max_loss=CASE WHEN ?<max_loss THEN ? ELSE max_loss END,trades=trades+1,sells=sells+1 WHERE uuid=?")) {
            statement.setBigDecimal(1, gross); statement.setBigDecimal(2, realized); statement.setBigDecimal(3, profit); statement.setBigDecimal(4, loss.abs());
            statement.setBigDecimal(5, profit); statement.setBigDecimal(6, profit); statement.setBigDecimal(7, loss); statement.setBigDecimal(8, loss);
            statement.setString(9, uuid.toString()); statement.executeUpdate();
        }
    }

    private void upsertResult(Connection connection, long seasonId, ResultRow row) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE season_results SET realized_profit=?,asset_change=?,profit_rate=?,trades=?,max_single_profit=? WHERE season_id=? AND uuid=?")) {
            update.setBigDecimal(1, row.profit()); update.setBigDecimal(2, row.profit()); update.setBigDecimal(3, row.rate());
            update.setLong(4, row.trades()); update.setBigDecimal(5, maxSingleProfit(connection, seasonId, row.uuid()));
            update.setLong(6, seasonId); update.setString(7, row.uuid().toString()); if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO season_results(season_id,uuid,realized_profit,asset_change,profit_rate,trades,max_single_profit) VALUES(?,?,?,?,?,?,?)")) {
            insert.setLong(1, seasonId); insert.setString(2, row.uuid().toString()); insert.setBigDecimal(3, row.profit());
            insert.setBigDecimal(4, row.profit()); insert.setBigDecimal(5, row.rate()); insert.setLong(6, row.trades());
            insert.setBigDecimal(7, maxSingleProfit(connection, seasonId, row.uuid())); insert.executeUpdate();
        }
        try (PreparedStatement stats = connection.prepareStatement("UPDATE players SET seasons=seasons+1,best_season_profit=CASE WHEN ?>best_season_profit THEN ? ELSE best_season_profit END WHERE uuid=?")) {
            stats.setBigDecimal(1, row.profit()); stats.setBigDecimal(2, row.profit()); stats.setString(3, row.uuid().toString()); stats.executeUpdate();
        }
    }

    private BigDecimal maxSingleProfit(Connection connection, long seasonId, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT metadata FROM transactions WHERE season_id=? AND uuid=? AND status=? AND metadata LIKE 'realized=%'")) {
            statement.setLong(1, seasonId); statement.setString(2, uuid.toString()); statement.setString(3, TransactionStatus.COMPLETED.name());
            BigDecimal max = Money.ZERO;
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    try { max = max.max(new BigDecimal(rs.getString(1).substring("realized=".length()))); } catch (RuntimeException ignored) { }
                }
            }
            return max;
        }
    }

    private record Row(UUID uuid, String stockId, long shares, BigDecimal averageCost, BigDecimal price) {}
    private record ResultRow(UUID uuid, BigDecimal profit, BigDecimal invested, BigDecimal rate, long trades) {}
}
