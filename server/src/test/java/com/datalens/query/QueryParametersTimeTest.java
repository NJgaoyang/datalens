package com.datalens.query;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static com.datalens.query.QueryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryParametersTimeTest {
    @Test void instantTimestampDoesNotDependOnJvmDefaultZone() {
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            var value=(Timestamp)QueryParameters.value(new Parameter("TIMESTAMP","2026-03-01T05:00:00Z"));
            assertThat(value.toInstant()).isEqualTo(Instant.parse("2026-03-01T05:00:00Z"));
        } finally {TimeZone.setDefault(original);}
    }
    @Test void instantJdbcBindingExplicitlyUsesUtcCalendarAndRetainsLegacyLocalBinding() throws Exception {
        var statement=mock(PreparedStatement.class);
        QueryParameters.bind(statement,List.of(new Parameter("TIMESTAMP","2026-03-01T05:00:00Z"),new Parameter("TIMESTAMP","2026-03-01T05:00:00")));
        var calendar=ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(1),eq(Timestamp.from(Instant.parse("2026-03-01T05:00:00Z"))),calendar.capture());
        assertThat(calendar.getValue().getTimeZone().getID()).isEqualTo("UTC");
        verify(statement).setObject(eq(2),eq(Timestamp.valueOf("2026-03-01 05:00:00")));
        verifyNoMoreInteractions(statement);
    }
    @Test void malformedTimestampsAndMissingValuesFailClosed() {
        for(String value:List.of("2026-01-01Z","2026-13-01T00:00:00Z","not-a-date","2026-01-01T00:00:00+02:00"))
            assertThatThrownBy(()->QueryParameters.value(new Parameter("TIMESTAMP",value))).isInstanceOf(com.datalens.common.web.DomainException.class);
    }
}
