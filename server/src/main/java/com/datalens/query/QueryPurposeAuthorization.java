package com.datalens.query;

import com.datalens.identity.CurrentActor;
import com.datalens.query.QueryService.QueryContext;

/** Owning modules supply fail-closed authorization for server-bound query purposes. */
public interface QueryPurposeAuthorization {
    boolean supports(String purpose);
    void authorize(CurrentActor actor,QueryContext context);
    /** An owning module may lend a narrow published-resource capability, never task ownership. */
    default CurrentActor executionActor(CurrentActor actor,QueryContext context) { return actor; }
    /** Validate the exact server-loaded SQL and values before allowing purpose-bound submission. */
    default void validateSubmission(CurrentActor actor,QueryContext context,QueryModels.Submit input) {}
}
