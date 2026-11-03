package com.datalens.query;

import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import com.datalens.identity.CurrentActor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import static com.datalens.query.QueryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryCapacityTest {
    JdbcTemplate db=mock(JdbcTemplate.class); PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    AuditService audit=mock(AuditService.class); QueryStore store;
    CurrentActor actor=new CurrentActor(7,"owner","USER",1);
    Submit input=new Submit("subject","SELECT id FROM orders",List.of(),"key");
    QueryStore.Stored previous=new QueryStore.Stored(new TaskView("old","subject","SUCCEEDED",null,Instant.EPOCH,Instant.EPOCH,1L,false),7,1,"source",1,"hash","payload","sql","[]");
    @BeforeEach void setup(){when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));store=spy(new QueryStore(db,manager,audit,new QueryCapacity(3,2)));when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(0L);doReturn(previous).when(store).get(anyString());}
    QueryStore.Stored create(){return store.create(actor,input,"source",1,"hash","version","[]","request");}
    @Test void rejectsInvalidConfiguration(){assertThatThrownBy(()->new QueryCapacity(0,1)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new QueryCapacity(1,-1)).isInstanceOf(IllegalArgumentException.class);}
    @Test void totalAndActorBoundariesRejectWithoutWriting(){
        when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(3L);
        assertCapacity();verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(audit);
        when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(0L,2L);assertCapacity();
    }
    @Test void existingReplayPrecedesCapacityAndConflictRemainsConflict(){
        when(db.queryForList("SELECT id FROM query_task WHERE actor_id=? AND idempotency_key=?",String.class,7L,"key")).thenReturn(List.of("old"));
        assertThat(create()).isEqualTo(previous);verify(db,never()).queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class));
        assertThatThrownBy(()->store.create(actor,input,"source",1,"changed","version","[]","request")).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @Test void unavailableCountFailsClosed(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(null);assertThatThrownBy(this::create).isInstanceOf(NullPointerException.class);verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void terminalWorkNeverChecksCapacity(){store.finish("old","FAILED","ERROR",1,null,false);store.cancelQueued("old");verify(db,never()).queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class));}
    @Test void admissionRequestsReadCommitted(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(3L);assertCapacity();verify(manager).getTransaction(argThat(d->d.getIsolationLevel()==TransactionDefinition.ISOLATION_READ_COMMITTED));}
    private void assertCapacity(){assertThatThrownBy(this::create).isInstanceOfSatisfying(DomainException.class,e->{assertThat(e.status()).isEqualTo(429);assertThat(e.code()).isEqualTo("QUERY_HISTORY_CAPACITY");});}
}
