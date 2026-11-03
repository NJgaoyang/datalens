package com.datalens.conversation;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.common.web.DomainException;
import static com.datalens.conversation.ConversationModels.*;
@Repository
public class ConversationStore {
 public record Row(String id,long ownerId,String subjectId,String title,long version) {Summary summary(){return new Summary(id,subjectId,title,version);}}
 public record Stored(String id,String conversationId,long sequence,String question,String status,String clarification,String definition,String modelCallId,String previewId,String taskId,String chartId,String errorCode,Instant createdAt){}
 public record Admission(Stored turn,boolean fresh){}
 private final JdbcTemplate db;private final TransactionTemplate tx;
 public ConversationStore(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;this.tx=new TransactionTemplate(manager);}
 static DomainException conflict(){return new DomainException(409,"CONVERSATION_CONFLICT","会话已更新或请求正在处理中，请刷新后重试");}
 static DomainException missing(){return new DomainException(404,"CONVERSATION_NOT_FOUND","会话或消息不存在");}
 public Row get(String id){var rows=db.query("SELECT * FROM conversation WHERE id=?",(r,n)->new Row(r.getString("id"),r.getLong("owner_id"),r.getString("subject_id"),r.getString("title"),r.getLong("version")),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
 public List<Row> list(long owner){return db.query("SELECT * FROM conversation WHERE owner_id=? ORDER BY created_at DESC,id LIMIT 100",(r,n)->new Row(r.getString("id"),r.getLong("owner_id"),r.getString("subject_id"),r.getString("title"),r.getLong("version")),owner);}
 public Row create(long owner,Create input){return tx.execute(s->{
  // Conversation-owned lock serializes durable creation keys without identity-table access.
  db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='conversation.lock' FOR UPDATE",String.class);
  var old=db.queryForList("SELECT id FROM conversation WHERE owner_id=? AND idempotency_key=?",String.class,owner,input.idempotencyKey());
  if(!old.isEmpty()){var row=get(old.getFirst());if(!row.subjectId().equals(input.subjectId())||!row.title().equals(input.title().trim()))throw conflict();return row;}
  String id=UUID.randomUUID().toString();db.update("INSERT INTO conversation(id,owner_id,subject_id,title,idempotency_key) VALUES(?,?,?,?,?)",id,owner,input.subjectId(),input.title().trim(),input.idempotencyKey());return get(id);
 });}
 private org.springframework.jdbc.core.RowMapper<Stored> mapper(){return (r,n)->new Stored(r.getString("id"),r.getString("conversation_id"),r.getLong("sequence_no"),r.getString("question"),r.getString("status"),r.getString("clarification"),r.getString("definition_json"),r.getString("model_call_id"),r.getString("preview_id"),r.getString("task_id"),r.getString("chart_id"),r.getString("error_code"),r.getTimestamp("created_at").toInstant());}
 public Stored turn(String conversation,String id){var rows=db.query("SELECT * FROM conversation_turn WHERE conversation_id=? AND id=?",mapper(),conversation,id);if(rows.isEmpty())throw missing();return rows.getFirst();}
 public List<Stored> turns(String conversation){return db.query("SELECT * FROM conversation_turn WHERE conversation_id=? ORDER BY sequence_no",mapper(),conversation);}
 public Admission admit(long owner,String id,Send input){return tx.execute(s->{db.queryForObject("SELECT id FROM conversation WHERE id=? FOR UPDATE",String.class,id);var row=get(id);if(row.ownerId()!=owner)throw new AccessDeniedException("Conversation owner required");
  var old=db.queryForList("SELECT id FROM conversation_turn WHERE conversation_id=? AND idempotency_key=?",String.class,id,input.idempotencyKey());if(!old.isEmpty()){var t=turn(id,old.getFirst());if(!t.question().equals(input.question().trim()))throw conflict();return new Admission(t,false);}
  if(row.version()!=input.expectedVersion()||row.version()>=100)throw conflict();
  if(db.queryForObject("SELECT COUNT(*) FROM conversation_turn WHERE conversation_id=? AND status IN ('PLANNING','QUERYING')",Long.class,id)>0)throw conflict();
  String turn=UUID.randomUUID().toString();db.update("INSERT INTO conversation_turn(id,conversation_id,sequence_no,idempotency_key,question,status) VALUES(?,?,?,?,?,'PLANNING')",turn,id,row.version()+1,input.idempotencyKey(),input.question().trim());db.update("UPDATE conversation SET version=version+1 WHERE id=?",id);return new Admission(turn(id,turn),true);
 });}
 public void plan(String id,String definition,String call){if(db.update("UPDATE conversation_turn SET definition_json=?,model_call_id=? WHERE id=? AND status='PLANNING'",definition,call,id)!=1)throw conflict();}
 public void clarify(String id,String message,String call){db.update("UPDATE conversation_turn SET status='CLARIFICATION_REQUIRED',clarification=?,model_call_id=? WHERE id=? AND status='PLANNING'",message,call,id);}
 public void preview(String id,String preview,String task){if(db.update("UPDATE conversation_turn SET status='QUERYING',preview_id=?,task_id=? WHERE id=? AND status='PLANNING'",preview,task,id)!=1)throw conflict();}
 public void finish(String id,String status,String error){db.update("UPDATE conversation_turn SET status=?,error_code=? WHERE id=? AND status IN ('PLANNING','QUERYING')",status,error,id);}
 public void saved(String id,String chart){db.update("UPDATE conversation_turn SET chart_id=? WHERE id=? AND (chart_id IS NULL OR chart_id=?)",chart,id,chart);}
 public void recover(){db.update("UPDATE conversation_turn SET status='INTERRUPTED',error_code='CONVERSATION_INTERRUPTED' WHERE status='PLANNING'");}
}
