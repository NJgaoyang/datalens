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
    private final ChartCapacity capacity;
    private final JdbcTemplate db;private final TransactionTemplate tx;private final AuditService audit;
    public ChartStore(JdbcTemplate db,PlatformTransactionManager manager,AuditService audit){this(db,manager,audit,ChartCapacity.defaults());}
    @org.springframework.beans.factory.annotation.Autowired
    public ChartStore(JdbcTemplate db,PlatformTransactionManager manager,AuditService audit,ChartCapacity capacity){this.db=db;tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);this.audit=audit;this.capacity=capacity;}
    private boolean full(String table,String ownerColumn,Object owner,int limit) {
        // Every caller supplies a module-owned constant identifier, never a request value.
        String filter=ownerColumn==null?"":" WHERE "+ownerColumn+"=?";
        Object[] args=ownerColumn==null?new Object[]{limit}:new Object[]{owner,limit};
        return Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM "+table+filter+" LIMIT ?) capacity_rows",Long.class,args))>=limit;
    }
    private void requireCapacity(String table,String ownerColumn,Object owner,int globalLimit,int personalLimit) {
        if(full(table,null,null,globalLimit)||full(table,ownerColumn,owner,personalLimit))
            throw new DomainException(429,"CHART_STORAGE_CAPACITY","图表历史容量已达上限，请联系管理员；现有数据未删除");
    }
    void lock(){db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='chart.lock' FOR UPDATE",String.class);}
    static DomainException missing(){return new DomainException(404,"CHART_NOT_FOUND","图表或预览不存在");}
    public Summary chart(String id) {
        var rows=db.query("SELECT * FROM chart WHERE id=?",(r,n)->new Summary(r.getString("id"),r.getString("name"),r.getLong("owner_id"),r.getLong("version"),r.getString("revision_id"),r.getTimestamp("updated_at").toInstant(),r.getString("visibility"),r.getBoolean("deleted")),id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public PageResult<Summary> list(long actor,int page,int size) {
        var items=db.query("SELECT * FROM chart WHERE deleted=FALSE AND owner_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Summary(r.getString("id"),r.getString("name"),r.getLong("owner_id"),r.getLong("version"),r.getString("revision_id"),r.getTimestamp("updated_at").toInstant(),r.getString("visibility"),r.getBoolean("deleted")),actor,size,(page-1)*size);
        return new PageResult<>(items,db.queryForObject("SELECT COUNT(*) FROM chart WHERE deleted=FALSE AND owner_id=?",Long.class,actor),page,size);
    }
    public String definition(String chartId,String revisionId){var rows=db.queryForList("SELECT definition_json FROM chart_revision WHERE chart_id=? AND id=?",String.class,chartId,revisionId);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public PreviewRow preview(String id){var rows=db.query("SELECT * FROM chart_preview WHERE id=?",(r,n)->new PreviewRow(r.getString("id"),r.getLong("actor_id"),r.getLong("auth_version"),r.getString("definition_json"),r.getString("resolved_parameters_json"),r.getString("task_id"),r.getString("saved_chart_id")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public PreviewRow createPreview(CurrentActor actor,String key,String definition,String params) {
        return tx.execute(s->{lock();var previous=db.queryForList("SELECT id FROM chart_preview WHERE actor_id=? AND idempotency_key=?",String.class,actor.id(),key);
            if(!previous.isEmpty()){var row=preview(previous.getFirst());if(!row.definition().equals(definition))throw conflict();return row;}
            requireCapacity("chart_preview","actor_id",actor.id(),capacity.previews(),capacity.previewsPerUser());
            String id=UUID.randomUUID().toString();db.update("INSERT INTO chart_preview(id,actor_id,auth_version,idempotency_key,definition_json,resolved_parameters_json) VALUES(?,?,?,?,?,?)",id,actor.id(),actor.authVersion(),key,definition,params);return preview(id);
        });
    }
    public void bindPreview(String id,String taskId){db.update("UPDATE chart_preview SET task_id=? WHERE id=? AND (task_id IS NULL OR task_id=?)",taskId,id,taskId);}
    public Summary save(CurrentActor actor,PreviewRow preview,String name,String requestId) {return save(actor,preview,name,requestId,()->{});}
    public Summary save(CurrentActor actor,PreviewRow preview,String name,String requestId,Runnable validate) {
        return tx.execute(s->{lock();validate.run();var current=preview(preview.id());if(current.savedChartId()!=null){var chart=chart(current.savedChartId());if(chart.deleted()||(chart.ownerId()!=actor.id()&&!"SUPER_ADMIN".equals(actor.role())))throw new org.springframework.security.access.AccessDeniedException("Saved chart ownership changed");String savedName=db.queryForObject("SELECT saved_name FROM chart_preview WHERE id=?",String.class,current.id());if(!Objects.equals(savedName,name))throw conflict();return chart;}
            requireCapacity("chart","owner_id",actor.id(),capacity.assets(),capacity.assetsPerOwner());
            // Save creates both rows atomically; reserve ordinary revision capacity before either insert.
            if(full("chart_revision",null,null,capacity.revisions()))throw new DomainException(429,"CHART_STORAGE_CAPACITY","图表历史容量已达上限，请联系管理员；现有数据未删除");
            String id=UUID.randomUUID().toString(),revision=UUID.randomUUID().toString();
            db.update("INSERT INTO chart(id,owner_id,name,revision_id) VALUES(?,?,?,?)",id,actor.id(),name,revision);
            db.update("INSERT INTO chart_revision(id,chart_id,version,definition_json,creator_id) VALUES(?,?,1,?,?)",revision,id,preview.definition(),actor.id());
            db.update("UPDATE chart_preview SET saved_chart_id=?,saved_name=? WHERE id=?",id,name,preview.id());audit.append(actor.id(),"CHART_SAVE","CHART",id,"SUCCEEDED",requestId);return chart(id);
        });
    }
    public Summary publish(CurrentActor actor,String chartId,PreviewRow preview,String name,long expected,String requestId) {return publish(actor,chartId,preview,name,expected,requestId,()->{});}
    public Summary publish(CurrentActor actor,String chartId,PreviewRow preview,String name,long expected,String requestId,Runnable validate) {
        return tx.execute(s->{lock();var chart=chart(chartId);if(chart.deleted() || (chart.ownerId()!=actor.id()&&!"SUPER_ADMIN".equals(actor.role())))throw new org.springframework.security.access.AccessDeniedException("Chart belongs to another user");
            if(chart.version()!=expected)throw new DomainException(409,"CHART_VERSION_CONFLICT","图表已更新，请重新预览");
            validate.run();requireCapacity("chart_revision","chart_id",chartId,capacity.revisions(),capacity.revisionsPerChart());String revision=UUID.randomUUID().toString();db.update("INSERT INTO chart_revision(id,chart_id,version,definition_json,creator_id) VALUES(?,?,?,?,?)",revision,chartId,expected+1,preview.definition(),actor.id());
            db.update("UPDATE chart SET name=?,revision_id=?,visibility='PRIVATE',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",name,revision,chartId);
            db.update("DELETE FROM chart_share WHERE chart_id=?",chartId);
            audit.append(actor.id(),"CHART_PUBLISH","CHART",chartId,"SUCCEEDED",requestId);return chart(chartId);
        });
    }
    public Run run(String id){var rows=db.query("SELECT * FROM chart_run WHERE id=?",(r,n)->new Run(r.getString("id"),r.getString("chart_id"),r.getString("revision_id"),r.getLong("actor_id"),r.getString("resolved_parameters_json"),r.getString("task_id")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public Run createRun(CurrentActor actor,Summary chart,String key,String params){return tx.execute(s->{lock();var previous=db.queryForList("SELECT id FROM chart_run WHERE actor_id=? AND chart_id=? AND idempotency_key=?",String.class,actor.id(),chart.id(),key);if(!previous.isEmpty())return run(previous.getFirst());var current=chart(chart.id());if(current.version()!=chart.version())throw conflict();requireCapacity("chart_run","actor_id",actor.id(),capacity.runs(),capacity.runsPerUser());String id=UUID.randomUUID().toString();db.update("INSERT INTO chart_run(id,chart_id,revision_id,actor_id,idempotency_key,resolved_parameters_json) VALUES(?,?,?,?,?,?)",id,chart.id(),chart.revisionId(),actor.id(),key,params);return run(id);});}
    public void bindRun(String id,String taskId){db.update("UPDATE chart_run SET task_id=? WHERE id=? AND (task_id IS NULL OR task_id=?)",taskId,id,taskId);}
    public Run taskRun(String chartId,String taskId,long actor){var ids=db.queryForList("SELECT id FROM chart_run WHERE chart_id=? AND task_id=? AND actor_id=?",String.class,chartId,taskId,actor);if(ids.isEmpty())throw missing();return run(ids.getFirst());}
    public void success(String runId,Instant finished){db.update("UPDATE chart_run SET success_at=? WHERE id=?",java.sql.Timestamp.from(finished),runId);}
    public String latestTask(String chartId,String revision,long actor){var rows=db.queryForList("SELECT task_id FROM chart_run WHERE chart_id=? AND revision_id=? AND actor_id=? ORDER BY created_at DESC,id DESC LIMIT 1",String.class,chartId,revision,actor);return rows.isEmpty()?null:rows.getFirst();}
    public Instant lastSuccess(String chartId,String revision,long actor){var result=db.queryForObject("SELECT MAX(success_at) FROM chart_run WHERE chart_id=? AND revision_id=? AND actor_id=?",java.sql.Timestamp.class,chartId,revision,actor);return result==null?null:result.toInstant();}
    public List<Long> shares(String id){return db.queryForList("SELECT user_id FROM chart_share WHERE chart_id=? ORDER BY user_id",Long.class,id);}
    public PageResult<Summary> list(CurrentActor actor,String scope,int page,int size) {
        String where=listScope(actor,scope);
        var args=new ArrayList<Object>();for(int i=0;i<where.length();i++)if(where.charAt(i)=='?')args.add(actor.id());
        long total=db.queryForObject("SELECT COUNT(*) FROM chart WHERE "+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var items=db.query("SELECT * FROM chart WHERE "+where+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Summary(r.getString("id"),r.getString("name"),r.getLong("owner_id"),r.getLong("version"),r.getString("revision_id"),r.getTimestamp("updated_at").toInstant(),r.getString("visibility"),r.getBoolean("deleted")),args.toArray());
        return new PageResult<>(items,total,page,size);
    }
    void validateListScope(CurrentActor actor,String scope){listScope(actor,scope);}
    private String listScope(CurrentActor actor,String scope) {
        boolean admin="SUPER_ADMIN".equals(actor.role());
        return switch(scope){
            case "mine" -> "deleted=FALSE AND owner_id=?";
            case "shared" -> "deleted=FALSE AND owner_id<>? AND visibility='SPECIFIC' AND EXISTS(SELECT 1 FROM chart_share s WHERE s.chart_id=chart.id AND s.user_id=?)";
            case "all" -> "deleted=FALSE AND visibility='ALL'";
            case "admin" -> {if(!admin)throw new org.springframework.security.access.AccessDeniedException("Administrator required");yield "deleted=FALSE";}
            case "trash" -> admin?"deleted=TRUE":"deleted=TRUE AND owner_id=?";
            default -> throw new DomainException(400,"CHART_SCOPE_INVALID","图表列表范围无效");
        };
    }
    /** Serialize ACL/admin mutations with publication and repeat resource ownership under the lock. */
    public Summary manage(CurrentActor actor,String id,long expected,String action,String visibility,List<Long> recipients,Long owner,Runnable validate,String requestId){
        return tx.execute(s->{lock();var c=chart(id);
            boolean admin="SUPER_ADMIN".equals(actor.role());
            if(c.ownerId()!=actor.id()&&!admin)throw new org.springframework.security.access.AccessDeniedException("Chart management denied");
            if(c.version()!=expected)throw new DomainException(409,"CHART_VERSION_CONFLICT","图表已更新，请重新加载");
            if((action.equals("RESTORE")&&!c.deleted())||(!action.equals("RESTORE")&&c.deleted()))throw conflict();
            if(action.equals("TRANSFER")&&!admin)throw new org.springframework.security.access.AccessDeniedException("Administrator required");
            validate.run();var oldRecipients=shares(id);
            switch(action){
                case "SHARE" -> {db.update("DELETE FROM chart_share WHERE chart_id=?",id);for(long user:recipients)db.update("INSERT INTO chart_share(chart_id,user_id) VALUES(?,?)",id,user);db.update("UPDATE chart SET visibility=? WHERE id=?",visibility,id);}
                case "DELETE" -> {db.update("UPDATE chart SET deleted=TRUE,visibility='PRIVATE' WHERE id=?",id);db.update("DELETE FROM chart_share WHERE chart_id=?",id);}
                case "RESTORE" -> db.update("UPDATE chart SET deleted=FALSE,visibility='PRIVATE' WHERE id=?",id);
                case "TRANSFER" -> {db.update("UPDATE chart SET owner_id=?,visibility='PRIVATE' WHERE id=?",owner,id);db.update("DELETE FROM chart_share WHERE chart_id=?",id);}
                default -> throw new IllegalArgumentException("Unknown chart action");
            }
            db.update("UPDATE chart SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
            if(action.equals("RESTORE")||action.equals("TRANSFER")){
                String revision=UUID.randomUUID().toString();db.update("INSERT INTO chart_revision(id,chart_id,version,definition_json,creator_id) VALUES(?,?,?,?,?)",revision,id,expected+1,definition(id,c.revisionId()),actor.id());db.update("UPDATE chart SET revision_id=? WHERE id=?",revision,id);
            }
            var changed=chart(id);db.update("INSERT INTO chart_management_event(chart_id,actor_id,action,old_owner_id,new_owner_id,old_visibility,new_visibility,old_recipients,new_recipients,chart_version,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,actor.id(),action,c.ownerId(),changed.ownerId(),c.visibility(),changed.visibility(),oldRecipients.toString(),shares(id).toString(),changed.version(),requestId);
            audit.append(actor.id(),"CHART_"+action,"CHART",id,"SUCCEEDED",requestId);return chart(id);
        });
    }
    static DomainException conflict(){return new DomainException(409,"CHART_REQUEST_CONFLICT","提交内容与已有操作不一致，请刷新后重试");}
}
