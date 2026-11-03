package com.datalens.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Admission thresholds only; no retention, deletion or disk-capacity guarantee. */
@Component
public record ModelCapacity(int total, int perActor) {
    public ModelCapacity(
            @Value("${datalens.capacity.model.total:10000}") int total,
            @Value("${datalens.capacity.model.per-actor:1000}") int perActor) {
        if (total <= 0 || perActor <= 0) throw new IllegalArgumentException("model capacity limits must be positive finite integers");
        this.total = total;
        this.perActor = perActor;
    }
    public static ModelCapacity defaults() { return new ModelCapacity(10000, 1000); }
}
