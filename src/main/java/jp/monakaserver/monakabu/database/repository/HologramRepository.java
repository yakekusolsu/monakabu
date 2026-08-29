package jp.monakaserver.monakabu.database.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HologramRepository {
    public record HologramRecord(String id,UUID worldId,double x,double y,double z,float yaw,float pitch,String stockId,UUID createdBy,Instant createdAt){}

    public void insert(Connection connection,HologramRecord record)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("INSERT INTO holograms(hologram_id,world_uuid,x,y,z,yaw,pitch,stock_id,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")){
            statement.setString(1,record.id());statement.setString(2,record.worldId().toString());statement.setDouble(3,record.x());statement.setDouble(4,record.y());statement.setDouble(5,record.z());statement.setFloat(6,record.yaw());statement.setFloat(7,record.pitch());statement.setString(8,record.stockId());statement.setString(9,record.createdBy().toString());statement.setLong(10,record.createdAt().toEpochMilli());statement.executeUpdate();
        }
    }

    public List<HologramRecord> findAll(Connection connection)throws SQLException{
        List<HologramRecord> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM holograms ORDER BY created_at");ResultSet rs=statement.executeQuery()){
            while(rs.next())result.add(new HologramRecord(rs.getString("hologram_id"),UUID.fromString(rs.getString("world_uuid")),rs.getDouble("x"),rs.getDouble("y"),rs.getDouble("z"),rs.getFloat("yaw"),rs.getFloat("pitch"),rs.getString("stock_id"),UUID.fromString(rs.getString("created_by")),Instant.ofEpochMilli(rs.getLong("created_at"))));
        }return result;
    }

    public long count(Connection connection)throws SQLException{try(PreparedStatement statement=connection.prepareStatement("SELECT COUNT(*) FROM holograms");ResultSet rs=statement.executeQuery()){return rs.next()?rs.getLong(1):0;}}
    public boolean delete(Connection connection,String id)throws SQLException{try(PreparedStatement statement=connection.prepareStatement("DELETE FROM holograms WHERE hologram_id=?")){statement.setString(1,id);return statement.executeUpdate()==1;}}
    public int deleteAll(Connection connection)throws SQLException{try(PreparedStatement statement=connection.prepareStatement("DELETE FROM holograms")){return statement.executeUpdate();}}
}
