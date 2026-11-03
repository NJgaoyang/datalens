package com.datalens.chart;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import com.datalens.common.api.PageResult;
import com.datalens.identity.CurrentActor;
import static com.datalens.chart.ChartModels.*;

@Repository
public class ChartStore {
    public record PreviewRow(String id,long actorId,long authVersion,String definition,String params,String taskId,String savedChartId) {}
    public record Run(String id,String chartId,String revisionId,long actorId,String params,String taskId) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final AuditService audit;
    public ChartStore(JdbcTemplate db,PlatformTransactionManager manager,AuditService audit){this.db=db;tx=new TransactionTemplate(manager);this.audit=audit;}
    void lock(){db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='chart.lock' FOR UPDATE",String.class);}
    static DomainException missing(){return new DomainException(404,"CHART_NOT_FOUND","图表或预览不存在");}
    public Summary chart(String id) {
        var rows=db.query("SELECT * FROM chart WHERE id=?",(r,n)->new Summary(r.getString("id"),r.getString("name"),r.getLong("owner_id"),r.getLong("version"),r.getString("revision_id"),r.getTimestamp("updated_at").toInstant()),id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public PageResult<Summary> list(long actor,int page,int size) {
        var items=db.query("SELECT * FROM chart WHERE owner_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Summary(r.getString("id"),r.getString("name"),r.getLong("owner_id"),r.getLong("version"),r.getString("revision_id"),r.getTimestamp("updated_at").toInstant()),actor,size,(page-1)*size);
        return new PageResult<>(items,db.queryForObject("SELECT COUNT(*) FROM chart WHERE owner_id=?",Long.class,actor),page,size);
    }
    public String definition(String chartId,String revisionId){var rows=db.queryForList("SELECT definition_json FROM chart_revision WHERE chart_id=? AND id=?",String.class,chartId,revisionId);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public PreviewRow preview(String id){var rows=db.query("SELECT * FROM chart_preview WHERE id=?",(r,n)->new PreviewRow(r.getString("id"),r.getLong("actor_id"),r.getLong("auth_version"),r.getString("definition_json"),r.getString("resolved_parameters_json"),r.getString("task_id"),r.getString("saved_chart_id")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public PreviewRow createPreview(CurrentActor actor,String key,String definition,String params) {
        return tx.execute(s->{lock();var previous=db.queryForList("SELECT id FROM chart_preview WHERE actor_id=? AND idempotency_key=?",String.class,actor.id(),key);
            if(!previous.isEmpty()){var row=preview(previous.getFirst());if(!row.definition().equals(definition))throw conflict();return row;}
            String id=UUID.randomUUID().toString();db.update("INSERT INTO chart_preview(id,actor_id,auth_version,idempotency_key,definition_json,resolved_parameters_json) VALUES(?,?,?,?,?,?)",id,actor.id(),actor.authVersion(),key,definition,params);return preview(id);
        });
    }
    public void bindPreview(String id,String taskId){db.update("UPDATE chart_preview SET task_id=? WHERE id=? AND (task_id IS NULL OR task_id=?)",taskId,id,taskId);}
    public Summary save(CurrentActor actor,PreviewRow preview,String name,String requestId) {
        return tx.execute(s->{lock();var current=preview(preview.id());if(current.savedChartId()!=null){var chart=chart(current.savedChartId());String savedName=db.queryForObject("SELECT saved_name FROM chart_preview WHERE id=?",String.class,current.id());if(!Objects.equals(savedName,name))throw conflict();return chart;}
            String id=UUID.randomUUID().toString(),revision=UUID.randomUUID().toString();
            db.update("INSERT INTO chart(id,owner_id,name,revision_id) VALUES(?,?,?,?)",id,actor.id(),name,revision);
            db.update("INSERT INTO chart_revision(id,chart_id,version,definition_json,creator_id) VALUES(?,?,1,?,?)",revision,id,preview.definition(),actor.id());
            db.update("UPDATE chart_preview SET saved_chart_id=?,saved_name=? WHERE id=?",id,name,preview.id());audit.append(actor.id(),"CHART_SAVE","CHART",id,"SUCCEEDED",requestId);return chart(id);
        });
    }
    public Summary publish(CurrentActor actor,String chartId,PreviewRow preview,String name,long expected,String requestId) {
        return tx.execute(s->{lock();var chart=chart(chartId);if(chart.ownerId()!=actor.id())throw new org.springframework.security.access.AccessDeniedException("Chart belongs to another user");
            if(chart.version()!=expected)throw new DomainException(409,"CHART_VERSION_CONFLICT","图表已更新，请重新预览");
            String revision=UUID.randomUUID().toString();db.update("INSERT INTO chart_revision(id,chart_id,version,definition_json,creator_id) VALUES(?,?,?,?,?)",revision,chartId,expected+1,preview.definition(),actor.id());
            db.update("UPDATE chart SET name=?,revision_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,revision,chartId);
            audit.append(actor.id(),"CHART_PUBLISH","CHART",chartId,"SUCCEEDED",requestId);return chart(chartId);
        });
    }
    public Run run(String id){var rows=db.query("SELECT * FROM chart_run WHERE id=?",(r,n)->new Run(r.getString("id"),r.getString("chart_id"),r.getString("revision_id"),r.getLong("actor_id"),r.getString("resolved_parameters_json"),r.getString("task_id")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public Run createRun(CurrentActor actor,Summary chart,String key,String params){return tx.execute(s->{lock();var previous=db.queryForList("SELECT id FROM chart_run WHERE actor_id=? AND chart_id=? AND idempotency_key=?",String.class,actor.id(),chart.id(),key);if(!previous.isEmpty())return run(previous.getFirst());var current=chart(chart.id());if(current.version()!=chart.version())throw conflict();String id=UUID.randomUUID().toString();db.update("INSERT INTO chart_run(id,chart_id,revision_id,actor_id,idempotency_key,resolved_parameters_json) VALUES(?,?,?,?,?,?)",id,chart.id(),chart.revisionId(),actor.id(),key,params);return run(id);});}
    public void bindRun(String id,String taskId){db.update("UPDATE chart_run SET task_id=? WHERE id=? AND (task_id IS NULL OR task_id=?)",taskId,id,taskId);}
    public Run taskRun(String chartId,String taskId,long actor){var ids=db.queryForList("SELECT id FROM chart_run WHERE chart_id=? AND task_id=? AND actor_id=?",String.class,chartId,taskId,actor);if(ids.isEmpty())throw missing();return run(ids.getFirst());}
    public void success(String runId,Instant finished){db.update("UPDATE chart_run SET success_at=? WHERE id=?",java.sql.Timestamp.from(finished),runId);}
    public String latestTask(String chartId,String revision,long actor){var rows=db.queryForList("SELECT task_id FROM chart_run WHERE chart_id=? AND revision_id=? AND actor_id=? ORDER BY created_at DESC,id DESC LIMIT 1",String.class,chartId,revision,actor);return rows.isEmpty()?null:rows.getFirst();}
    public Instant lastSuccess(String chartId,String revision,long actor){var result=db.queryForObject("SELECT MAX(success_at) FROM chart_run WHERE chart_id=? AND revision_id=? AND actor_id=?",java.sql.Timestamp.class,chartId,revision,actor);return result==null?null:result.toInstant();}
    static DomainException conflict(){return new DomainException(409,"CHART_REQUEST_CONFLICT","提交内容与已有操作不一致，请刷新后重试");}
}
