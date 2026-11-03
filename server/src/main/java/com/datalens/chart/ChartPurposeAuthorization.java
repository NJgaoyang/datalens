package com.datalens.chart;

import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.query.QueryPurposeAuthorization;
import com.datalens.query.QueryService.QueryContext;

/** Resource permission is checked even when callers use generic query-task routes. */
@Component
public class ChartPurposeAuthorization implements QueryPurposeAuthorization {
    private final ChartStore store;private final PermissionService permissions;
    public ChartPurposeAuthorization(ChartStore store,PermissionService permissions){this.store=store;this.permissions=permissions;}
    @Override public boolean supports(String purpose){return "CHART".equals(purpose)||"CHART_PREVIEW".equals(purpose);}
    @Override public void authorize(CurrentActor actor,QueryContext context) {
        actor=permissions.requireActive(actor);
        if("CHART_PREVIEW".equals(context.purpose())) {
            var preview=store.preview(context.resourceId());
            if(preview.actorId()!=actor.id()||preview.authVersion()!=actor.authVersion()||context.revisionId()!=null)throw new AccessDeniedException("Preview access denied");
        } else if("CHART".equals(context.purpose())) {
            var chart=store.chart(context.resourceId());
            if(chart.ownerId()!=actor.id())throw new AccessDeniedException("Chart access denied");
            // Published historical revisions remain immutable; new refreshes always use current revision.
            store.definition(chart.id(),context.revisionId());
        } else throw new AccessDeniedException("Unsupported purpose");
    }
}
