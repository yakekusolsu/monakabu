package jp.monakaserver.monakabu.database.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public final class PlayerRepository {
    public void upsert(Connection connection, UUID uuid, String name) throws SQLException {
        long now = Instant.now().toEpochMilli();
        if (update(connection,uuid,name,now)>0)return;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO players(uuid,last_name,created_at,last_seen) VALUES(?,?,?,?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name);
            insert.setLong(3, now);
            insert.setLong(4, now);
            try { insert.executeUpdate(); } catch (SQLException race) { if(update(connection,uuid,name,now)==0)throw race; }
        }
    }

    private int update(Connection connection,UUID uuid,String name,long now)throws SQLException{try(PreparedStatement update=connection.prepareStatement("UPDATE players SET last_name=?,last_seen=? WHERE uuid=?")){update.setString(1,name);update.setLong(2,now);update.setString(3,uuid.toString());return update.executeUpdate();}}

    public String name(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_name FROM players WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getString(1) : uuid.toString(); }
        }
    }
}
