package jp.monakaserver.monakabu.database;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<T, R> {
    R apply(T value) throws SQLException;
}
