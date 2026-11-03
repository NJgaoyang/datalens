package com.datalens.query;

import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import com.datalens.identity.CurrentActor;
import static com.datalens.query.QueryModels.*;

@Repository
public class QueryStore {
    public record Stored(TaskView view,long actorId,long authVersion,String sourceId,long sourceVersion,String requestHash,String resultJson,String sql,String parametersJson) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final AuditService audit;
    public QueryStore(JdbcTemplate db,PlatformTransactionManager manager,AuditService audit) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.audit=audit;
    }
    public Stored get(String id) {
        var rows=db.query("SELECT * FROM query_task WHERE id=?",(r,n)->{
            var finished=r.getTimestamp("finished_at");var duration=r.getObject("duration_ms");
            return new Stored(new TaskView(r.getString("id"),r.getString("subject_id"),r.getString("status"),r.getString("error_code"),
                r.getTimestamp("created_at").toInstant(),finished==null?null:finished.toInstant(),duration==null?null:((Number)duration).longValue(),r.getBoolean("truncated")),
                r.getLong("actor_id"),r.getLong("auth_version"),r.getString("source_id"),r.getLong("source_version"),r.getString("request_hash"),r.getString("result_json"),r.getString("sql_text"),r.getString("parameters_json"));
        },id);
        if(rows.isEmpty()) throw new DomainException(404,"QUERY_NOT_FOUND","查询任务不存在");
        return rows.getFirst();
    }
    public Stored create(CurrentActor actor,Submit input,String sourceId,long sourceVersion,String hash,String version,String parametersJson,String requestId) {
        return tx.execute(status->{
            db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='query.lock' FOR UPDATE",String.class);
            var existing=db.queryForList("SELECT id FROM query_task WHERE actor_id=? AND idempotency_key=?",String.class,actor.id(),input.idempotencyKey());
            if(!existing.isEmpty()) {
                var previous=get(existing.getFirst());
                if(!hash.equals(previous.requestHash())) throw new DomainException(409,"IDEMPOTENCY_CONFLICT","重复提交键已用于不同查询");
                return previous;
            }
            // Unknown database termination quarantines this source until an operator verifies and resolves it.
            if(count("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status IN ('CANCEL_UNCONFIRMED','RECOVERY_UNCONFIRMED')",sourceId)>0)
                throw new DomainException(409,"SOURCE_QUERY_QUARANTINED","数据源存在未确认终止的查询，需要管理员检查");
            String active=" status IN ('QUEUED','RUNNING','CANCELLING')";
            if(count("SELECT COUNT(*) FROM query_task WHERE actor_id=? AND"+active,actor.id())>=2 ||
               count("SELECT COUNT(*) FROM query_task WHERE source_id=? AND"+active,sourceId)>=3 ||
               count("SELECT COUNT(*) FROM query_task WHERE"+active)>=32)
                throw new DomainException(429,"QUERY_CONCURRENCY_LIMIT","查询并发已达上限");
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO query_task(id,actor_id,auth_version,subject_id,source_id,source_version,idempotency_key,request_hash,query_version,sql_text,parameters_json,status,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?)",
                id,actor.id(),actor.authVersion(),input.subjectId(),sourceId,sourceVersion,input.idempotencyKey(),hash,version,input.sql(),parametersJson,requestId);
            audit.append(actor.id(),"QUERY_SUBMIT","QUERY",id,"QUEUED",requestId);
            return get(id);
        });
    }
    private long count(String sql,Object... args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    public boolean start(String id){return Boolean.TRUE.equals(tx.execute(status->{
        db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='query.lock' FOR UPDATE",String.class);
        var task=get(id);
        if(count("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status IN ('CANCEL_UNCONFIRMED','RECOVERY_UNCONFIRMED')",task.sourceId())>0) {
            finish(id,"FAILED","SOURCE_QUERY_QUARANTINED",0,null,false);return false;
        }
        return db.update("UPDATE query_task SET status='RUNNING' WHERE id=? AND status='QUEUED'",id)==1;
    }));}
    public boolean cancelling(String id){return db.update("UPDATE query_task SET status='CANCELLING' WHERE id=? AND status IN ('QUEUED','RUNNING')",id)==1;}
    public void finish(String id,String state,String error,long elapsed,String result,boolean truncated) {
        tx.executeWithoutResult(txStatus->{
            db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='query.lock' FOR UPDATE",String.class);
            String expected="SUCCEEDED".equals(state)?"status='RUNNING'":"status IN ('QUEUED','RUNNING','CANCELLING')";
            int updated=db.update("UPDATE query_task SET status=?,error_code=?,duration_ms=?,result_json=?,truncated=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND "+expected,
                state,error,elapsed,result,truncated,id);
            if(updated==0 && "SUCCEEDED".equals(state) && "CANCELLING".equals(get(id).view().status())) {
                finish(id,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",elapsed,null,false);return;
            }
            if(updated==1) {
                var row=db.queryForMap("SELECT actor_id,request_id FROM query_task WHERE id=?",id);
                audit.append(((Number)row.get("actor_id")).longValue(),"QUERY_FINISH","QUERY",id,state,(String)row.get("request_id"));
            }
        });
    }
    /** Only invoked after acquiring the exclusive single-executor lease. Never automatically reruns SQL. */
    public void recover() {
        for(String id:db.queryForList("SELECT id FROM query_task WHERE status IN ('QUEUED','RUNNING','CANCELLING')",String.class)) {
            String state=get(id).view().status();
            finish(id,state.equals("QUEUED")?"FAILED":"RECOVERY_UNCONFIRMED","PROCESS_RESTARTED",0,null,false);
        }
    }
}
