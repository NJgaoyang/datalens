package com.datalens.conversation;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import com.datalens.permission.PermissionService;
import com.datalens.semantic.*;
import com.datalens.semantic.SemanticModels.*;
import com.datalens.model.*;
import com.datalens.chart.*;
import com.datalens.query.*;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.conversation.ConversationModels.*;
@Service
public class ConversationService {
 private final ConversationStore store;private final PermissionService permissions;private final SemanticService semantics;
 private final ModelGateway model;private final ChartService charts;private final ChartSemanticAuthorization provenance;private final QueryService queries;
 private final ObjectMapper json;private final Validator validator;private final AuditService audit;
 public ConversationService(ConversationStore store,PermissionService permissions,SemanticService semantics,ModelGateway model,ChartService charts,ChartSemanticAuthorization provenance,QueryService queries,ObjectMapper json,Validator validator,AuditService audit){this.store=store;this.permissions=permissions;this.semantics=semantics;this.model=model;this.charts=charts;this.provenance=provenance;this.queries=queries;this.json=json;this.validator=validator;this.audit=audit;}
 @org.springframework.core.annotation.Order(100)
 @EventListener(ApplicationReadyEvent.class) public void recover(){queries.requireAvailable();store.recover();}
 private void check(Object input){if(input==null||!validator.validate(input).isEmpty())throw new DomainException(400,"CONVERSATION_INVALID","会话输入无效");}
 private ConversationStore.Row own(String id){var actor=permissions.current();var row=store.get(id);if(row.ownerId()!=actor.id())throw new AccessDeniedException("Conversation owner required");semantics.retrievePublished(actor,row.subjectId());return row;}
 private ChartModels.Definition definition(ConversationStore.Stored t){return t.definition()==null?null:json.readValue(t.definition(),ChartModels.Definition.class);}
 private void binding(ConversationStore.Stored t){var d=definition(t);if(d!=null)provenance.validate(permissions.current(),d);}
 public Summary create(Create input,String requestId){check(input);var actor=permissions.current();semantics.retrievePublished(actor,input.subjectId());var row=store.create(actor.id(),input);own(row.id());audit.append(actor.id(),"CONVERSATION_CREATE","CONVERSATION",row.id(),"SUCCEEDED",requestId);own(row.id());return row.summary();}
 public List<Summary> list(){var actor=permissions.current();var output=new ArrayList<Summary>();for(var row:store.list(actor.id())){try{own(row.id());output.add(row.summary());}catch(AccessDeniedException|DomainException unavailable){/* unavailable subjects do not leak titles */}}permissions.current();return List.copyOf(output);}
 public Detail detail(String id){var row=own(id);var turns=new ArrayList<Turn>();for(var t:store.turns(id))turns.add(view(t));own(id);return new Detail(id,row.subjectId(),row.title(),row.version(),List.copyOf(turns));}
 public Turn turn(String id,String turn){own(id);var result=view(store.turn(id,turn));own(id);return result;}
 private Turn view(ConversationStore.Stored input){binding(input);var t=input;java.time.Instant time=null;Boolean truncated=null;
  if(t.taskId()!=null){var task=queries.get(t.taskId());time=task.finishedAt();truncated=task.truncated();
   if("QUERYING".equals(t.status())){
    if("SUCCEEDED".equals(task.status())){try{var result=charts.previewResult(t.previewId());store.finish(t.id(),result.rows().isEmpty()?"NO_DATA":"READY",null);truncated=result.truncated();}catch(DomainException invalid){if(!"CHART_INVALID".equals(invalid.code()))throw invalid;store.finish(t.id(),"FAILED",invalid.code());}}
    else if(!Set.of("QUEUED","RUNNING","CANCELLING").contains(task.status()))store.finish(t.id(),"FAILED",task.errorCode());
    t=store.turn(t.conversationId(),t.id());
   }
  }
  var d=definition(t);binding(t);return new Turn(t.id(),t.sequence(),t.question(),t.status(),t.clarification(),d==null?null:d.metricDescription(),t.previewId(),t.taskId(),t.chartId(),t.errorCode(),t.createdAt(),d==null?null:d.spec(),d==null?null:d.timeRule(),time,truncated);
 }
 public Turn send(String id,Send input,String requestId){check(input);var row=own(id);
  // Resolve earlier async terminal state before admitting a new optimistic turn.
  for(var previous:store.turns(id))if("QUERYING".equals(previous.status()))view(previous);
  var admitted=store.admit(permissions.current().id(),id,input);if(!admitted.fresh())return turn(id,admitted.turn().id());var t=admitted.turn();
  try{
   var publication=semantics.retrievePublished(permissions.current(),row.subjectId());
   var history=store.turns(id).stream().filter(old->old.sequence()<t.sequence()).toList();
   ChartModels.Definition d;String call=null;
   if("改成上个月".equals(t.question())||"上个月".equals(t.question())){
    var previous=history.stream().filter(old->old.definition()!=null&&Set.of("READY","NO_DATA").contains(old.status())).reduce((a,b)->b).orElse(null);
    if(previous==null){store.clarify(t.id(),"请先选择并确认一个已审核指标，再调整为上个月。",null);return turn(id,t.id());}
    binding(previous);var old=definition(previous);var b=old.semanticBinding();var metric=metric(publication,b.metricId());
    if(metric.timeParameters()==null){store.clarify(t.id(),"该指标尚未审核可调整的时间参数，请管理员配置后重新提问。",null);return turn(id,t.id());}
    d=definition(publication,metric,old.spec(),SemanticRules.lastMonthRule(metric));
   }else{
    String question=t.question();
    if(!history.isEmpty()){
     var previous=history.getLast();
     // Explicit, bounded context from this owner only. No rows, SQL or raw model output.
     if("CLARIFICATION_REQUIRED".equals(previous.status()))question="Previous question: "+previous.question().substring(0,Math.min(1400,previous.question().length()))+"\nClarification answer: "+question;
     else if(previous.definition()!=null){var prior=definition(previous);binding(previous);question="Confirmed metric ID: "+prior.semanticBinding().metricId()+". Current request: "+question;}
    }
    if(question.length()>4000){store.clarify(t.id(),"上下文过长，请用更简短的问题明确指标。",null);return turn(id,t.id());}
    var proposal=model.generate(new ModelModels.Generate(row.subjectId(),question),requestId);own(id);
    if(proposal.publicationVersion()!=publication.version()||!proposal.publicationDigest().equals(publication.digest())||!proposal.subjectId().equals(row.subjectId()))throw new AccessDeniedException("Semantic publication changed");call=proposal.callId();
    if(proposal.output().intent().needsClarification()){store.clarify(t.id(),proposal.output().intent().clarification(),call);return turn(id,t.id());}
    var m=metric(publication,proposal.output().plan().metricId());d=definition(publication,m,proposal.output().chartSpec(),new ChartModels.TimeRule("NONE",null,null,null,null));
   }
   own(id);provenance.validate(permissions.current(),d);store.plan(t.id(),json.writeValueAsString(d),call);
   var preview=charts.preview(new ChartModels.PreviewInput(d,"conversation_"+t.id()),requestId);store.preview(t.id(),preview.id(),preview.taskId());
   audit.append(permissions.current().id(),"CONVERSATION_PREVIEW","CONVERSATION",id,"SUCCEEDED",requestId);return turn(id,t.id());
  }catch(Exception failure){store.finish(t.id(),"FAILED",failure instanceof DomainException e?e.code():failure instanceof AccessDeniedException?"CONVERSATION_ACCESS_REVOKED":"CONVERSATION_FAILED");throw failure;}
 }
 private Metric metric(Publication p,String id){return ModelOutputValidator.eligible(p).stream().filter(m->m.id().equals(id)).findFirst().orElseThrow(()->new AccessDeniedException("Metric unavailable"));}
 private ChartModels.Definition definition(Publication p,Metric m,ChartModels.Spec spec,ChartModels.TimeRule time){String description=ChartSemanticAuthorization.description(m);return new ChartModels.Definition(p.subjectId(),m.sql(),m.parameters(),time,description,spec,new ChartModels.SemanticBinding(p.version(),p.digest(),p.scopeFingerprint(),m.id()));}
 public QueryModels.Result result(String id,String turn){own(id);var t=store.turn(id,turn);binding(t);if(t.previewId()==null)throw new DomainException(409,"CONVERSATION_RESULT_UNAVAILABLE","尚无查询结果");var result=charts.previewResult(t.previewId());own(id);binding(t);return result;}
 public ChartModels.Summary save(String id,String turn,ConversationModels.Save input,String requestId){check(input);if(!input.confirmation())throw new DomainException(400,"CONVERSATION_CONFIRMATION_REQUIRED","请明确确认保存");own(id);var t=store.turn(id,turn);binding(t);var state=view(t);if(!Set.of("READY","NO_DATA").contains(state.status()))throw new DomainException(409,"CONVERSATION_RESULT_UNAVAILABLE","请先完成图表预览");var saved=charts.save(new ChartModels.Save(input.name(),t.previewId()),requestId);store.saved(t.id(),saved.id());own(id);binding(t);return saved;}
}
