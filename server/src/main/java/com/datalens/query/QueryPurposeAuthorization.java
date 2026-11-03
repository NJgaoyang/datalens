package com.datalens.query;

import com.datalens.identity.CurrentActor;
import com.datalens.query.QueryService.QueryContext;

/** Owning modules supply fail-closed authorization for server-bound query purposes. */
public interface QueryPurposeAuthorization {
    boolean supports(String purpose);
    void authorize(CurrentActor actor,QueryContext context);
}
