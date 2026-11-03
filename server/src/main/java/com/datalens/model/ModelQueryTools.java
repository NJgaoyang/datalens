package com.datalens.model;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.semantic.SemanticService;
import com.datalens.semantic.SemanticModels.Metric;
import com.datalens.query.*;
import com.datalens.query.QueryService.QueryContext;

/** Fixed application-owned tool. No SDK auto-execution; M08 chooses when to invoke it. */
@Service
public class ModelQueryTools implements QueryPurposeAuthorization {
    private final SemanticService semantics;private final PermissionService permissions;private final QueryService queries;
    public ModelQueryTools(SemanticService semantics,PermissionService permissions,QueryService queries){this.semantics=semantics;this.permissions=permissions;this.queries=queries;}
    public QueryModels.TaskView executeReviewedMetric(String subjectId,long version,String digest,String metricId,String requestId){
        var actor=permissions.current();var context=new QueryContext("MODEL_METRIC",subjectId,version+":"+digest+":"+metricId);
        var metric=metric(actor,context);
        return queries.submitForPurpose(new QueryModels.Submit(subjectId,metric.sql(),metric.parameters(),"model_"+java.util.UUID.randomUUID()),context,requestId);
    }
    private Metric metric(CurrentActor actor,QueryContext context){
        permissions.requireActive(actor);if(!supports(context.purpose())||context.revisionId()==null)throw denied();
        String[] parts=context.revisionId().split(":",-1);if(parts.length!=3)throw denied();
        var p=semantics.retrievePublished(actor,context.resourceId());
        if(!Long.toString(p.version()).equals(parts[0])||!p.digest().equals(parts[1]))throw denied();
        return ModelOutputValidator.eligible(p).stream().filter(m->m.id().equals(parts[2])).findFirst().orElseThrow(ModelQueryTools::denied);
    }
    @Override public boolean supports(String purpose){return "MODEL_METRIC".equals(purpose);}
    @Override public void authorize(CurrentActor actor,QueryContext context){metric(actor,context);}
    @Override public void validateSubmission(CurrentActor actor,QueryContext context,QueryModels.Submit input){var m=metric(actor,context);if(!Objects.equals(input.subjectId(),context.resourceId())||!input.sql().equals(m.sql())||!input.parameters().equals(m.parameters()))throw denied();}
    private static AccessDeniedException denied(){return new AccessDeniedException("Reviewed metric binding invalid");}
}
