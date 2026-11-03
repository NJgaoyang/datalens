package com.datalens.semantic;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SemanticStore {
    public record Workspace(String subjectId,long version,String document,String digest,String scope,String reviewedDigest,Long reviewerId,Instant reviewedAt,Long publishedVersion,long nextRelease) {}
    public record Release(String subjectId,long version,String document,String digest,String scope,long reviewerId,long publisherId,Instant publishedAt,Instant reviewedAt) {}
    private final JdbcTemplate db;
    public SemanticStore(JdbcTemplate db){this.db=db;}
    void lock(){db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='semantic.lock' FOR UPDATE",String.class);}
    Workspace workspace(String subjectId){var rows=db.query("SELECT * FROM semantic_workspace WHERE subject_id=?",(r,n)->new Workspace(r.getString("subject_id"),r.getLong("version"),r.getString("document_json"),r.getString("digest"),r.getString("scope_fingerprint"),r.getString("reviewed_digest"),r.getObject("reviewer_id",Long.class),r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toInstant(),r.getObject("published_version",Long.class),r.getLong("next_release")),subjectId);return rows.isEmpty()?null:rows.getFirst();}
    void save(String id,long version,String document,String digest,String scope){
        if(version==0)db.update("INSERT INTO semantic_workspace(subject_id,version,document_json,digest,scope_fingerprint) VALUES(?,1,?,?,?)",id,document,digest,scope);
        else db.update("UPDATE semantic_workspace SET version=version+1,document_json=?,digest=?,scope_fingerprint=?,reviewed_digest=NULL,reviewer_id=NULL,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE subject_id=?",document,digest,scope,id);
    }
    void review(String id,long reviewer){db.update("UPDATE semantic_workspace SET version=version+1,reviewed_digest=digest,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE subject_id=?",reviewer,id);}
    long publish(Workspace w,long actor){
        db.update("INSERT INTO semantic_release(subject_id,version,document_json,digest,scope_fingerprint,reviewer_id,publisher_id,reviewed_at) VALUES(?,?,?,?,?,?,?,?)",w.subjectId(),w.nextRelease(),w.document(),w.digest(),w.scope(),w.reviewerId(),actor,java.sql.Timestamp.from(w.reviewedAt()));
        db.update("UPDATE semantic_workspace SET version=version+1,published_version=next_release,next_release=next_release+1,updated_at=CURRENT_TIMESTAMP(6) WHERE subject_id=?",w.subjectId());return w.nextRelease();
    }
    void withdraw(String id){db.update("UPDATE semantic_workspace SET version=version+1,published_version=NULL,reviewed_digest=NULL,reviewer_id=NULL,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE subject_id=?",id);}
    List<Release> releases(String id){return db.query("SELECT * FROM semantic_release WHERE subject_id=? ORDER BY version DESC LIMIT 20",(r,n)->new Release(r.getString("subject_id"),r.getLong("version"),r.getString("document_json"),r.getString("digest"),r.getString("scope_fingerprint"),r.getLong("reviewer_id"),r.getLong("publisher_id"),r.getTimestamp("published_at").toInstant(),r.getTimestamp("reviewed_at").toInstant()),id);}
    Release release(String id,long version){var rows=db.query("SELECT * FROM semantic_release WHERE subject_id=? AND version=?",(r,n)->new Release(r.getString("subject_id"),r.getLong("version"),r.getString("document_json"),r.getString("digest"),r.getString("scope_fingerprint"),r.getLong("reviewer_id"),r.getLong("publisher_id"),r.getTimestamp("published_at").toInstant(),r.getTimestamp("reviewed_at").toInstant()),id,version);if(rows.isEmpty())throw SemanticRules.invalid("发布版本不存在");return rows.getFirst();}
}
