package com.datalens.query;
import java.sql.*;
import javax.sql.DataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
/** This first in-process executor deliberately supports one active application instance per metadata DB. */
@Component
public class QueryLease {
    private final DataSource db;private Connection connection;
    public QueryLease(DataSource db){this.db=db;}
    public synchronized void acquire() throws SQLException {
        if(connection!=null) return;
        Connection candidate=db.getConnection();
        try(var statement=candidate.prepareStatement("SELECT GET_LOCK(CONCAT(DATABASE(), ':query-executor'), 0)")) {
            statement.setQueryTimeout(3);
            try(var rows=statement.executeQuery()) {
                if(!rows.next() || rows.getInt(1)!=1) throw new SQLException("Another query executor owns the database lease");
            }
            connection=candidate;
        } catch(SQLException failure){candidate.close();throw failure;}
    }
    public synchronized boolean valid() {
        if(connection==null) return false;
        try(var statement=connection.prepareStatement("SELECT IS_USED_LOCK(CONCAT(DATABASE(), ':query-executor')) = CONNECTION_ID()")) {
            statement.setQueryTimeout(3);
            try(var rows=statement.executeQuery()){return rows.next()&&rows.getBoolean(1);}
        } catch(SQLException failure){return false;}
    }
    @PreDestroy public synchronized void close() throws SQLException {
        if(connection==null)return;
        try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(CONCAT(DATABASE(), ':query-executor'))")){statement.execute();}
        finally{connection.close();connection=null;}
    }
}
