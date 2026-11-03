package com.datalens.model;

import com.datalens.model.ModelModels.*;
/** Provider-neutral boundary; unavailable models fail truthfully and do not affect chart queries. */
public interface ModelGateway {
    Proposal generate(Generate input,String requestId);
}
