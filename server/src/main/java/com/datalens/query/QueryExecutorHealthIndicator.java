package com.datalens.query;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Losing the exclusive executor lease requires controlled process recovery, not replay. */
@Component("queryExecutor")
public class QueryExecutorHealthIndicator implements HealthIndicator {
    private final QueryService queries;
    public QueryExecutorHealthIndicator(QueryService queries) { this.queries = queries; }
    @Override public Health health() {
        return queries.available() ? Health.up().build() : Health.down().build();
    }
}
