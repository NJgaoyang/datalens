package com.datalens.query;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryExecutorHealthIndicatorTest {
    @Test void readinessTracksExecutorWithoutExposingDetails() {
        QueryService queries = mock(QueryService.class);
        var probe = new QueryExecutorHealthIndicator(queries);
        when(queries.available()).thenReturn(false, true, false);
        assertThat(probe.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(probe.health().getStatus()).isEqualTo(Status.UP);
        var lostLease = probe.health();
        assertThat(lostLease.getStatus()).isEqualTo(Status.DOWN);
        assertThat(lostLease.getDetails()).isEmpty();
    }
}
