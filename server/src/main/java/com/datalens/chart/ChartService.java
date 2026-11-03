package com.datalens.chart;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import com.datalens.permission.PermissionService;
import com.datalens.common.api.PageResult;
import com.datalens.query.QueryService;
import com.datalens.query.QueryService.QueryContext;
import com.datalens.query.QueryModels.*;
import static com.datalens.chart.ChartModels.*;

@Service
public class ChartService {
    @org.springframework.beans.factory.annotation.Autowired private ChartSemanticAuthorization semantic;
    private final ChartStore store;private final QueryService queries;private final PermissionService permissions;private final ObjectMapper json;
    private final Clock clock;private final ChartPermissionService charts;
    @org.springframework.beans.factory.annotation.Autowired public ChartService(ChartStore store,QueryService queries,PermissionService permissions,ObjectMapper json,ChartPermissionService charts){this(store,queries,permissions,json,Clock.systemUTC(),charts);}
    ChartService(ChartStore store,QueryService queries,PermissionService permissions,ObjectMapper json,Clock clock,ChartPermissionService charts){this.store=store;this.queries=queries;this.permissions=permissions;this.json=json;this.clock=clock;this.charts=charts;}
    private Summary owned(String id){return charts.manage(permissions.current(),id,false);}
    private Summary viewed(String id){return charts.view(permissions.current(),id);}
    private ChartStore.PreviewRow ownPreview(String id){var actor=permissions.current();var p=store.preview(id);if(p.actorId()!=actor.id()||p.authVersion()!=actor.authVersion())throw new AccessDeniedException("Preview access denied");if(semantic!=null)semantic.validate(actor,definition(p.definition()));return p;}
    private Definition definition(String encoded){return json.readValue(encoded,Definition.class);}
    private String encodeParameters(List<Parameter> params){params.forEach(com.datalens.query.QueryParameters::value);String encoded=json.writeValueAsString(params);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)throw ChartRules.invalid("查询参数总大小超出限制");return encoded;}
    private List<Parameter> parameters(String encoded){return Arrays.asList(json.readValue(encoded,Parameter[].class));}
    public Preview preview(PreviewInput input,String requestId) {
        var actor=permissions.current();if(semantic!=null)semantic.validate(actor,input.definition());var resolved=ChartRules.resolve(input.definition(),clock);
        var p=store.createPreview(actor,input.idempotencyKey(),json.writeValueAsString(input.definition()),encodeParameters(resolved));
        ownPreview(p.id());
        Definition d=definition(p.definition());
        var task=queries.submitForPurpose(new Submit(d.subjectId(),d.sql(),parameters(p.params()),"cp_"+p.id()),new QueryContext("CHART_PREVIEW",p.id(),null),requestId);
        store.bindPreview(p.id(),task.id());return new Preview(p.id(),d,task.id());
    }
    public Preview preview(String id){var p=ownPreview(id);if(p.taskId()!=null)queries.get(p.taskId());return new Preview(id,definition(p.definition()),p.taskId());}
    public Result previewResult(String id){var p=ownPreview(id);if(p.taskId()==null)throw ChartRules.invalid("预览尚未完成");var result=queries.result(p.taskId());ChartRules.validate(definition(p.definition()).spec(),result);ownPreview(id);return result;}
    public Summary save(Save input,String requestId){var p=ownPreview(input.previewId());previewResult(p.id());return store.save(permissions.current(),p,input.name().trim(),requestId,()->{ownPreview(p.id());previewResult(p.id());});}
    public Summary publish(String id,Publish input,String requestId){owned(id);var p=ownPreview(input.previewId());previewResult(p.id());return store.publish(permissions.current(),id,p,input.name().trim(),input.expectedVersion(),requestId,()->{ownPreview(p.id());previewResult(p.id());charts.publication(permissions.current(),id,definition(p.definition()));});}
    public PageResult<Summary> list(String scope,int page,int size){return list(scope,page,size,"");}
    public PageResult<Summary> list(String scope,int page,int size,String search){
        if(search==null)search="";if(search.length()>100)throw ChartRules.invalid("搜索词最长100字符");final String needle=search.strip().toLowerCase(java.util.Locale.ROOT);
        var actor=permissions.current();if("trash".equals(scope)&&needle.isEmpty())return store.list(actor,scope,page,size);
        var visible=new ArrayList<Summary>();int batch=1;PageResult<Summary> rows;
        do{rows=store.list(actor,scope,batch++,200);for(var c:rows.items()){
            if(!c.name().toLowerCase(java.util.Locale.ROOT).contains(needle))continue;
            try{if("trash".equals(scope)){charts.manage(actor,c.id(),true);visible.add(c);continue;}charts.executionOwner(actor,c.id(),c.revisionId());visible.add(c);}
            catch(AccessDeniedException|com.datalens.common.web.DomainException denied){/* Exclude revoked semantic assets before counts and pagination. */}
        }}while((long)(batch-1)*200<rows.total());
        // Final delivery recheck, including metadata loaded in earlier pages.
        visible.removeIf(c->{try{var current="trash".equals(scope)?charts.manage(actor,c.id(),true):charts.view(actor,c.id());if(!current.revisionId().equals(c.revisionId())||current.ownerId()!=c.ownerId()||current.version()!=c.version())return true;if(!"trash".equals(scope))charts.executionOwner(actor,c.id(),c.revisionId());return false;}catch(AccessDeniedException|com.datalens.common.web.DomainException denied){return true;}});
        permissions.current();int from=(int)Math.min((long)(page-1)*size,visible.size()),to=Math.min(from+size,visible.size());return new PageResult<>(visible.subList(from,to),visible.size(),page,size);
    }
    public Detail detail(String id){var c=viewed(id);var actor=permissions.current();var d=definition(store.definition(id,c.revisionId()));boolean manage=charts.canManage(actor,c);if(!manage||d.semanticBinding()!=null)charts.executionOwner(actor,id,c.revisionId());
        if(!manage)d=new Definition("","",List.of(),d.timeRule(),d.metricDescription(),d.spec());
        var latest=viewed(id);if(latest.version()!=c.version()||latest.ownerId()!=c.ownerId()||!latest.revisionId().equals(c.revisionId()))throw ChartStore.conflict();if(!manage||d.semanticBinding()!=null)charts.executionOwner(actor,id,c.revisionId());return new Detail(c.id(),c.name(),c.ownerId(),c.version(),c.revisionId(),d,store.latestTask(id,c.revisionId(),actor.id()),store.lastSuccess(id,c.revisionId(),actor.id()),c.visibility(),manage?store.shares(id):List.of(),c.deleted(),manage);}
    public TaskView refresh(String id,Refresh input,String requestId){var c=viewed(id);var d=definition(store.definition(id,c.revisionId()));
        var run=store.createRun(permissions.current(),c,input.idempotencyKey(),encodeParameters(ChartRules.resolve(d,clock)));
        // A retry resolves the original immutable revision and dates, even after midnight or a new publication.
        d=definition(store.definition(id,run.revisionId()));
        var task=queries.submitForPurpose(new Submit(d.subjectId(),d.sql(),parameters(run.params()),"cr_"+run.id()),new QueryContext("CHART",id,run.revisionId()),requestId);
        store.bindRun(run.id(),task.id());return task;
    }
    /** Reauthorize a viewer-owned refresh without loading its payload. */
    public void authorizeResultTask(String id,String taskId){viewed(id);store.taskRun(id,taskId,permissions.current().id());var task=queries.get(taskId);if(!"SUCCEEDED".equals(task.status()))throw ChartRules.invalid("查询尚未成功完成");viewed(id);}
    public Result result(String id,String taskId){viewed(id);var run=store.taskRun(id,taskId,permissions.current().id());var result=queries.result(taskId);
        ChartRules.validate(definition(store.definition(id,run.revisionId())).spec(),result);viewed(id);var task=queries.get(taskId);store.success(run.id(),task.finishedAt());return result;}
    public Summary sharing(String id,Sharing input,String requestId){var actor=permissions.current();charts.sharing(actor,id,input);return store.manage(actor,id,input.expectedVersion(),"SHARE",input.visibility(),input.userIds(),null,()->charts.sharing(permissions.current(),id,input),requestId);}
    public Summary delete(String id,long version,String requestId){var actor=permissions.current();owned(id);return store.manage(actor,id,version,"DELETE",null,List.of(),null,()->charts.manage(permissions.current(),id,false),requestId);}
    public Summary restore(String id,Version input,String requestId){var actor=permissions.current();charts.restore(actor,id);return store.manage(actor,id,input.expectedVersion(),"RESTORE",null,List.of(),null,()->charts.restore(permissions.current(),id),requestId);}
    public Summary transfer(String id,Transfer input,String requestId){var actor=permissions.requireSuperAdmin();charts.transfer(actor,id,input.ownerId());return store.manage(actor,id,input.expectedVersion(),"TRANSFER",null,List.of(),input.ownerId(),()->charts.transfer(permissions.requireSuperAdmin(),id,input.ownerId()),requestId);}
    public List<com.datalens.identity.AccountDirectory.Recipient> recipients(){return charts.recipients();}
}

