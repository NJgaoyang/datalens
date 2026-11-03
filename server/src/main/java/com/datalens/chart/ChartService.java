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
    private final ChartStore store;private final QueryService queries;private final PermissionService permissions;private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired public ChartService(ChartStore store,QueryService queries,PermissionService permissions,ObjectMapper json){this(store,queries,permissions,json,Clock.systemUTC());}
    ChartService(ChartStore store,QueryService queries,PermissionService permissions,ObjectMapper json,Clock clock){this.store=store;this.queries=queries;this.permissions=permissions;this.json=json;this.clock=clock;}
    private Summary owned(String id){var actor=permissions.current();var chart=store.chart(id);if(chart.ownerId()!=actor.id())throw new AccessDeniedException("Chart access denied");return chart;}
    private ChartStore.PreviewRow ownPreview(String id){var actor=permissions.current();var p=store.preview(id);if(p.actorId()!=actor.id()||p.authVersion()!=actor.authVersion())throw new AccessDeniedException("Preview access denied");return p;}
    private Definition definition(String encoded){return json.readValue(encoded,Definition.class);}
    private String encodeParameters(List<Parameter> params){params.forEach(com.datalens.query.QueryParameters::value);String encoded=json.writeValueAsString(params);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)throw ChartRules.invalid("查询参数总大小超出限制");return encoded;}
    private List<Parameter> parameters(String encoded){return Arrays.asList(json.readValue(encoded,Parameter[].class));}
    public Preview preview(PreviewInput input,String requestId) {
        var actor=permissions.current();var resolved=ChartRules.resolve(input.definition(),clock);
        var p=store.createPreview(actor,input.idempotencyKey(),json.writeValueAsString(input.definition()),encodeParameters(resolved));
        ownPreview(p.id());
        Definition d=definition(p.definition());
        var task=queries.submitForPurpose(new Submit(d.subjectId(),d.sql(),parameters(p.params()),"cp_"+p.id()),new QueryContext("CHART_PREVIEW",p.id(),null),requestId);
        store.bindPreview(p.id(),task.id());return new Preview(p.id(),d,task.id());
    }
    public Preview preview(String id){var p=ownPreview(id);if(p.taskId()!=null)queries.get(p.taskId());return new Preview(id,definition(p.definition()),p.taskId());}
    public Result previewResult(String id){var p=ownPreview(id);if(p.taskId()==null)throw ChartRules.invalid("预览尚未完成");var result=queries.result(p.taskId());ChartRules.validate(definition(p.definition()).spec(),result);ownPreview(id);return result;}
    public Summary save(Save input,String requestId){var p=ownPreview(input.previewId());previewResult(p.id());return store.save(permissions.current(),p,input.name().trim(),requestId);}
    public Summary publish(String id,Publish input,String requestId){owned(id);var p=ownPreview(input.previewId());previewResult(p.id());return store.publish(permissions.current(),id,p,input.name().trim(),input.expectedVersion(),requestId);}
    public PageResult<Summary> list(int page,int size){return store.list(permissions.current().id(),page,size);}
    public Detail detail(String id){var c=owned(id);var actor=permissions.current();return new Detail(c.id(),c.name(),c.ownerId(),c.version(),c.revisionId(),definition(store.definition(id,c.revisionId())),store.latestTask(id,c.revisionId(),actor.id()),store.lastSuccess(id,c.revisionId(),actor.id()));}
    public TaskView refresh(String id,Refresh input,String requestId){var c=owned(id);var d=definition(store.definition(id,c.revisionId()));
        var run=store.createRun(permissions.current(),c,input.idempotencyKey(),encodeParameters(ChartRules.resolve(d,clock)));
        // A retry resolves the original immutable revision and dates, even after midnight or a new publication.
        d=definition(store.definition(id,run.revisionId()));
        var task=queries.submitForPurpose(new Submit(d.subjectId(),d.sql(),parameters(run.params()),"cr_"+run.id()),new QueryContext("CHART",id,run.revisionId()),requestId);
        store.bindRun(run.id(),task.id());return task;
    }
    public Result result(String id,String taskId){owned(id);var run=store.taskRun(id,taskId,permissions.current().id());var result=queries.result(taskId);
        ChartRules.validate(definition(store.definition(id,run.revisionId())).spec(),result);owned(id);var task=queries.get(taskId);store.success(run.id(),task.finishedAt());return result;}
}
