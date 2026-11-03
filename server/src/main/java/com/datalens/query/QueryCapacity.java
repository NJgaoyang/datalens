package com.datalens.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Admission thresholds only; no retention, deletion or disk-capacity guarantee. */
@Component
public record QueryCapacity(int total, int perActor) {
    public QueryCapacity(
            @Value("${datalens.capacity.query.total:10000}") int total,
            @Value("${datalens.capacity.query.per-actor:1000}") int perActor) {
        if (total <= 0 || perActor <= 0) throw new IllegalArgumentException("query capacity limits must be positive finite integers");
        this.total = total;
        this.perActor = perActor;
    }
    public static QueryCapacity defaults() { return new QueryCapacity(10000, 1000); }
}
