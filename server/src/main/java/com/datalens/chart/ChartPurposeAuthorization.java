package com.datalens.chart;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.query.*;
import com.datalens.query.QueryService.QueryContext;
/** Shared viewing is a persisted chart capability, never a free-form query grant. */
@Component
public class ChartPurposeAuthorization implements QueryPurposeAuthorization {
    @org.springframework.beans.factory.annotation.Autowired private ChartSemanticAuthorization semantic;
    private final ChartStore store;private final PermissionService permissions;private final ChartPermissionService charts;private final ObjectMapper json;
    public ChartPurposeAuthorization(ChartStore store,PermissionService permissions,ChartPermissionService charts,ObjectMapper json){this.store=store;this.permissions=permissions;this.charts=charts;this.json=json;}
    @Override public boolean supports(String purpose){return "CHART".equals(purpose)||"CHART_PREVIEW".equals(purpose);}
    @Override public void authorize(CurrentActor actor,QueryContext context){actor=permissions.requireActive(actor);
        if("CHART_PREVIEW".equals(context.purpose())){var p=store.preview(context.resourceId());if(p.actorId()!=actor.id()||p.authVersion()!=actor.authVersion()||context.revisionId()!=null)throw new AccessDeniedException("Preview access denied");}
        else if("CHART".equals(context.purpose()))charts.executionOwner(actor,context.resourceId(),context.revisionId());
        else throw new AccessDeniedException("Unsupported purpose");
        if(semantic!=null){var d=json.readValue("CHART_PREVIEW".equals(context.purpose())?store.preview(context.resourceId()).definition():store.definition(context.resourceId(),context.revisionId()),ChartModels.Definition.class);semantic.validate(executionActor(actor,context),d);}
    }
    @Override public CurrentActor executionActor(CurrentActor actor,QueryContext context){return "CHART".equals(context.purpose())?charts.executionOwner(actor,context.resourceId(),context.revisionId()):permissions.requireActive(actor);}
    @Override public void validateSubmission(CurrentActor actor,QueryContext context,QueryModels.Submit input){
        authorize(actor,context);String definition,params;
        if("CHART".equals(context.purpose())){
            if(!input.idempotencyKey().startsWith("cr_"))throw new AccessDeniedException("Chart run required");
            var run=store.run(input.idempotencyKey().substring(3));
            if(run.actorId()!=actor.id()||!run.chartId().equals(context.resourceId())||!run.revisionId().equals(context.revisionId()))throw new AccessDeniedException("Chart run binding mismatch");
            definition=store.definition(run.chartId(),run.revisionId());params=run.params();
        }else{var p=store.preview(context.resourceId());if(!input.idempotencyKey().equals("cp_"+p.id()))throw new AccessDeniedException("Preview binding mismatch");definition=p.definition();params=p.params();}
        var d=json.readValue(definition,ChartModels.Definition.class);
        if(!d.subjectId().equals(input.subjectId())||!d.sql().equals(input.sql())||!Arrays.asList(json.readValue(params,QueryModels.Parameter[].class)).equals(input.parameters()))throw new AccessDeniedException("Published query cannot be overridden");
    }
}
