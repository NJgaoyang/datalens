package com.datalens.export;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.datalens.common.web.DomainException;
import com.datalens.identity.CurrentActor;
import com.datalens.chart.ChartModels.Summary;
import com.datalens.audit.AuditService;
import static com.datalens.export.ExportModels.*;
@Repository
public class ExportStore {
 public record Row(String id,long actor,long authVersion,String chart,long chartVersion,String revision,long generation,String task,String status,String error,Instant expires){public Job view(){return new Job(id,chart,task,status,error,expires);}}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AuditService audit;
 public ExportStore(JdbcTemplate db,PlatformTransactionManager manager,AuditService audit){this.db=db;this.tx=new TransactionTemplate(manager);this.audit=audit;tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);}
 private void lock(){db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='export.lock' FOR UPDATE",String.class);}
 public java.util.List<GrantView> grants(String chart,long version){return db.query("SELECT user_id,enabled FROM chart_export_grant WHERE chart_id=? AND chart_version=? ORDER BY user_id",(r,n)->new GrantView(r.getLong(1),r.getBoolean(2)),chart,version);}
 public long grant(String chart,long actor,long version){var rows=db.queryForList("SELECT generation FROM chart_export_grant WHERE chart_id=? AND user_id=? AND chart_version=? AND enabled=TRUE",Long.class,chart,actor,version);return rows.isEmpty()?0:rows.getFirst();}
 public void grant(CurrentActor admin,String chart,Grant input,Runnable validate,String request){tx.executeWithoutResult(s->{lock();validate.run();db.update("INSERT INTO chart_export_grant(chart_id,user_id,chart_version,generation,enabled) VALUES(?,?,?,1,?) ON DUPLICATE KEY UPDATE chart_version=?,generation=generation+1,enabled=?",chart,input.userId(),input.expectedChartVersion(),input.enabled(),input.expectedChartVersion(),input.enabled());audit.append(admin.id(),input.enabled()?"EXPORT_GRANT":"EXPORT_REVOKE","CHART",chart,"SUCCEEDED",request);});}
 public Row get(String id){var rows=db.query("SELECT id,actor_id,auth_version,chart_id,chart_version,revision_id,grant_generation,task_id,status,error_code,expires_at FROM chart_export_job WHERE id=?",(r,n)->new Row(r.getString(1),r.getLong(2),r.getLong(3),r.getString(4),r.getLong(5),r.getString(6),r.getLong(7),r.getString(8),r.getString(9),r.getString(10),r.getTimestamp(11).toInstant()),id);if(rows.isEmpty())throw new DomainException(404,"EXPORT_NOT_FOUND","导出不存在");return rows.getFirst();}
 public Row create(CurrentActor actor,Summary chart,long generation,Create input,Runnable validate,String request){return tx.execute(s->{lock();validate.run();cleanup();var old=db.queryForList("SELECT id FROM chart_export_job WHERE actor_id=? AND idempotency_key=?",String.class,actor.id(),input.idempotencyKey());if(!old.isEmpty()){var row=get(old.getFirst());if(!row.chart().equals(input.chartId())||!row.task().equals(input.taskId()))throw new DomainException(409,"EXPORT_CONFLICT","幂等请求内容不同");return row;}
 long all=db.queryForObject("SELECT COUNT(*) FROM chart_export_job",Long.class),active=db.queryForObject("SELECT COUNT(*) FROM chart_export_job WHERE status IN ('QUEUED','RUNNING')",Long.class),own=db.queryForObject("SELECT COUNT(*) FROM chart_export_job WHERE actor_id=?",Long.class,actor.id());
 if(all>=128||active>=16||own>=8)throw new DomainException(429,"EXPORT_CAPACITY","导出任务繁忙，请稍后重试");
 String id=UUID.randomUUID().toString();db.update("INSERT INTO chart_export_job(id,actor_id,auth_version,chart_id,chart_version,revision_id,grant_generation,task_id,idempotency_key,status,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?)",id,actor.id(),actor.authVersion(),chart.id(),chart.version(),chart.revisionId(),generation,input.taskId(),input.idempotencyKey(),Timestamp.from(Instant.now().plusSeconds(900)));audit.append(actor.id(),"EXPORT_CREATE","EXPORT",id,"SUCCEEDED",request);return get(id);});}
 public boolean claim(String id){return db.update("UPDATE chart_export_job SET status='RUNNING' WHERE id=? AND status='QUEUED' AND expires_at>CURRENT_TIMESTAMP(6)",id)==1;}
 public void finish(String id,byte[] bytes){db.update("UPDATE chart_export_job SET status='SUCCEEDED',content=? WHERE id=? AND status='RUNNING' AND expires_at>CURRENT_TIMESTAMP(6)",bytes,id);}
 public void fail(String id,String code){db.update("UPDATE chart_export_job SET status='FAILED',error_code=?,content=NULL WHERE id=? AND status IN ('QUEUED','RUNNING')",code,id);}
 public byte[] content(String id){return db.queryForObject("SELECT content FROM chart_export_job WHERE id=? AND status='SUCCEEDED' AND expires_at>CURRENT_TIMESTAMP(6)",byte[].class,id);}
 public void recover(){db.update("UPDATE chart_export_job SET status='INTERRUPTED',error_code='EXPORT_INTERRUPTED',content=NULL WHERE status IN ('QUEUED','RUNNING')");cleanup();}
 public void cleanup(){db.update("DELETE FROM chart_export_job WHERE expires_at<=CURRENT_TIMESTAMP(6)");}
}
