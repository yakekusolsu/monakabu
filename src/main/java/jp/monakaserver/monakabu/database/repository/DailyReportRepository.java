package jp.monakaserver.monakabu.database.repository;

import jp.monakaserver.monakabu.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

public final class DailyReportRepository {
    public boolean claim(Connection connection, DatabaseManager.Dialect dialect,
                         LocalDate reportDate, Instant generatedAt) throws SQLException {
        String sql = dialect == DatabaseManager.Dialect.SQLITE
                ? "INSERT OR IGNORE INTO daily_reports(report_date,generated_at) VALUES(?,?)"
                : "INSERT IGNORE INTO daily_reports(report_date,generated_at) VALUES(?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reportDate.toString());
            statement.setLong(2, generatedAt.toEpochMilli());
            return statement.executeUpdate() == 1;
        }
    }
}
