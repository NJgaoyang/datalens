package com.datalens.datasource;
import java.util.*;
import java.sql.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.datalens.datasource.SourceModels.*;
class SourcePoolsTest {
    SourceView view(long version,boolean enabled) {return new SourceView("id","name","10.20.30.40",9030,"default_catalog","sales","reader",enabled,List.of("orders"),version,"NEVER",null,null);}
    @Test void lazyIndependentPoolIsReusedAndClosedOnReconfigureDisableShutdown() throws Exception {
        var first=mock(HikariDataSource.class);var second=mock(HikariDataSource.class);
        var connection=mock(Connection.class);when(first.getConnection()).thenReturn(connection);when(second.getConnection()).thenReturn(connection);
        var queue=new ArrayDeque<>(List.of(first,second));
        var pools=new SourcePools(new EndpointPolicy("10.20.30.40:9030"),new SourceSecrets("")) {
            @Override protected HikariDataSource build(StoredSource source) {return queue.remove();}
        };
        assertThat(queue).hasSize(2);
        pools.open(new StoredSource(view(1,true),"cipher")).close();pools.open(new StoredSource(view(1,true),"cipher")).close();
        assertThat(queue).hasSize(1);verify(first,times(2)).getConnection();
        pools.invalidate("id",2);verify(first).close();
        assertThatThrownBy(()->pools.open(new StoredSource(view(1,true),"cipher"))).isInstanceOf(com.datalens.common.web.DomainException.class);
        assertThatThrownBy(()->pools.open(new StoredSource(view(2,false),"cipher"))).isInstanceOf(com.datalens.common.web.DomainException.class);
        pools.open(new StoredSource(view(3,true),"cipher")).close();
        assertThatThrownBy(()->pools.open(new StoredSource(view(2,true),"cipher"))).isInstanceOf(com.datalens.common.web.DomainException.class);
        pools.invalidate("id",2);pools.invalidate("id",3);verify(second,never()).close();
        pools.close();verify(second).close();
    }
}
