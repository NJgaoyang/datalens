package com.datalens.datasource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

@Repository
public class SourceRepository {
    final JdbcTemplate db;
    public SourceRepository(JdbcTemplate db) {this.db=db;}
    SourceView sourceRow(ResultSet r,int index) throws SQLException {
        var last=r.getTimestamp("last_harvest_at");String id=r.getString("id");
        return new SourceView(id,r.getString("name"),r.getString("host"),r.getInt("port"),r.getString("catalog_name"),
            r.getString("database_name"),r.getString("username"),r.getBoolean("enabled"),tables(id),r.getLong("version"),
            r.getString("harvest_status"),last==null?null:last.toInstant(),r.getString("last_error_code"));
    }
    void lockAdministration(com.datalens.identity.CurrentActor actor) {
        db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='identity.lock' FOR UPDATE",String.class);
        var current=db.queryForList("SELECT enabled,auth_version,role FROM sys_user WHERE id=? FOR UPDATE",actor.id());
        if(current.isEmpty()) throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Session invalid");
        var row=current.getFirst();
        boolean enabled=Boolean.TRUE.equals(row.get("enabled")) || row.get("enabled") instanceof Number n && n.intValue()!=0;
        if(!enabled || ((Number)row.get("auth_version")).longValue()!=actor.authVersion())
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Session invalid");
        if(!"SUPER_ADMIN".equals(row.get("role"))) throw new org.springframework.security.access.AccessDeniedException("Administrator required");
    }
    List<String> tables(String id) {return db.queryForList("SELECT table_name FROM ds_scope_table WHERE source_id=? ORDER BY table_name",String.class,id);}
    List<SourceView> sources() {return db.query("SELECT * FROM ds_source ORDER BY name,id LIMIT 100",this::sourceRow);}
    StoredSource source(String id,boolean lock) {
        var found=db.query("SELECT * FROM ds_source WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new StoredSource(sourceRow(r,n),r.getString("credential")),id);
        if(found.isEmpty()) throw new DomainException(404,"SOURCE_NOT_FOUND","数据源不存在");
        return found.getFirst();
    }
    void create(String id,SourceInput in,String secret) {
        db.update("INSERT INTO ds_source(id,name,host,port,catalog_name,database_name,username,credential,enabled) VALUES(?,?,?,?,?,?,?,?,?)",
            id,in.name(),in.host(),in.port(),in.catalog(),in.database(),in.username(),secret,in.enabled());
        replaceTables(id,in.tables());
    }
    void update(String id,SourceInput in,String secret) {
        var previous=source(id,true).view();
        boolean scopeChanged=!previous.host().equals(in.host()) || previous.port()!=in.port() ||
            !previous.catalog().equals(in.catalog()) || !previous.database().equals(in.database()) ||
            !previous.username().equals(in.username()) || !new HashSet<>(previous.tables()).equals(new HashSet<>(in.tables()));
        if(scopeChanged) {
            // Same table/field names at a new endpoint are not the previously authorized resource.
            db.update("DELETE f FROM ds_subject_field f JOIN ds_subject s ON s.id=f.subject_id WHERE s.source_id=?",id);
            db.update("DELETE u FROM ds_subject_user u JOIN ds_subject s ON s.id=u.subject_id WHERE s.source_id=?",id);
        }
        db.update("UPDATE ds_source SET name=?,host=?,port=?,catalog_name=?,database_name=?,username=?,credential=?,enabled=?,version=version+1,harvest_status='NEVER',last_harvest_at=NULL,last_error_code=NULL WHERE id=?",
            in.name(),in.host(),in.port(),in.catalog(),in.database(),in.username(),secret,in.enabled(),id);
        replaceTables(id,in.tables());
        // Configuration changes invalidate metadata rather than retaining newly out-of-scope fields.
        db.update("DELETE FROM ds_column WHERE source_id=?",id);
    }
    void replaceTables(String id,List<String> tables) {
        db.update("DELETE FROM ds_scope_table WHERE source_id=?",id);
        new TreeSet<>(tables).forEach(t->db.update("INSERT INTO ds_scope_table(source_id,table_name) VALUES(?,?)",id,t));
    }
    List<ColumnView> columns(String id) {return db.query("SELECT * FROM ds_column WHERE source_id=? ORDER BY table_name,ordinal_position",
        (r,n)->new ColumnView(id,r.getString("table_name"),r.getString("column_name"),r.getString("type_name"),r.getInt("ordinal_position"),r.getBoolean("nullable")),id);}
    void snapshot(String id,List<ColumnView> columns) {
        db.update("DELETE FROM ds_column WHERE source_id=?",id);
        for(var c:columns) db.update("INSERT INTO ds_column(source_id,table_name,column_name,type_name,ordinal_position,nullable) VALUES(?,?,?,?,?,?)",
            id,c.tableName(),c.columnName(),c.typeName(),c.ordinalPosition(),c.nullable());
        db.update("UPDATE ds_source SET harvest_status='SUCCEEDED',last_harvest_at=CURRENT_TIMESTAMP(6),last_error_code=NULL WHERE id=?",id);
    }
    SubjectView subject(String id) {
        var list=db.query("SELECT * FROM ds_subject WHERE id=?",(r,n)->subjectRow(r),id);
        if(list.isEmpty()) throw new DomainException(404,"SUBJECT_NOT_FOUND","业务主题不存在");
        return list.getFirst();
    }
    private SubjectView subjectRow(ResultSet r) throws SQLException {
        String id=r.getString("id");
        return new SubjectView(id,r.getString("name"),r.getString("source_id"),fields(id,false),fields(id,true),
            db.queryForList("SELECT user_id FROM ds_subject_user WHERE subject_id=? ORDER BY user_id",Long.class,id),r.getString("share_scope"));
    }
    List<Field> fields(String id,boolean denied) {return db.query("SELECT table_name,column_name FROM ds_subject_field WHERE subject_id=? AND denied=? ORDER BY table_name,column_name",
        (r,n)->new Field(r.getString(1),r.getString(2)),id,denied);}
    List<SubjectView> subjects() {return db.query("SELECT * FROM ds_subject ORDER BY name,id LIMIT 100",(r,n)->subjectRow(r));}
    void saveSubject(String id,SubjectInput in,boolean create) {
        if(create) db.update("INSERT INTO ds_subject(id,name,source_id,share_scope) VALUES(?,?,?,?)",id,in.name(),in.sourceId(),in.shareScope());
        else db.update("UPDATE ds_subject SET name=?,source_id=?,share_scope=? WHERE id=?",in.name(),in.sourceId(),in.shareScope(),id);
        db.update("DELETE FROM ds_subject_field WHERE subject_id=?",id);
        db.update("DELETE FROM ds_subject_user WHERE subject_id=?",id);
        for(boolean denied:List.of(false,true)) for(var f:new HashSet<>(denied?in.deniedFields():in.fields()))
            db.update("INSERT INTO ds_subject_field(subject_id,table_name,column_name,denied) VALUES(?,?,?,?)",id,f.tableName(),f.columnName(),denied);
        new HashSet<>(in.userIds()).forEach(user->db.update("INSERT INTO ds_subject_user(subject_id,user_id) VALUES(?,?)",id,user));
    }
}
