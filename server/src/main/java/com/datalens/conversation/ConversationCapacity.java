package com.datalens.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Admission thresholds only; no retention, deletion or disk-capacity guarantee. */
@Component
public record ConversationCapacity(int total, int perActor) {
    public ConversationCapacity(
            @Value("${datalens.capacity.conversation.total:1000}") int total,
            @Value("${datalens.capacity.conversation.per-actor:100}") int perActor) {
        if (total <= 0 || perActor <= 0) throw new IllegalArgumentException("conversation capacity limits must be positive finite integers");
        this.total = total;
        this.perActor = perActor;
    }
    public static ConversationCapacity defaults() { return new ConversationCapacity(1000, 100); }
}
