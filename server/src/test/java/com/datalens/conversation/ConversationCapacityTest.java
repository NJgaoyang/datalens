package com.datalens.conversation;

import com.datalens.common.web.DomainException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationCapacityTest {
    JdbcTemplate db=mock(JdbcTemplate.class);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);ConversationStore store;
    ConversationModels.Create input=new ConversationModels.Create("subject","Title","key");
    @BeforeEach void setup(){when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));store=spy(new ConversationStore(db,manager,new ConversationCapacity(3,2)));}
    @Test void invalidConfigurationIsNotUnlimited(){assertThatThrownBy(()->new ConversationCapacity(-1,2)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new ConversationCapacity(1,0)).isInstanceOf(IllegalArgumentException.class);}
    @Test void totalAndOwnerAdmissionBoundariesDoNotWrite(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(3L);assertCapacity();when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(0L,2L);assertCapacity();verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void replayAndConflictPrecedeCapacity(){var row=new ConversationStore.Row("old",7,"subject","Title",100);doReturn(row).when(store).get("old");when(db.queryForList("SELECT id FROM conversation WHERE owner_id=? AND idempotency_key=?",String.class,7L,"key")).thenReturn(List.of("old"));assertThat(store.create(7,input)).isEqualTo(row);assertThatThrownBy(()->store.create(7,new ConversationModels.Create("subject","Changed","key"))).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("CONVERSATION_CONFLICT"));verify(db,never()).queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class));}
    @Test void unknownCountFailsClosed(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(null);assertThatThrownBy(()->store.create(7,input)).isInstanceOf(NullPointerException.class);verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void completionAndRecoveryAreNotAdmission(){store.finish("turn","FAILED","code");store.recover();store.saved("turn","chart");verify(db,never()).queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class));}
    @Test void readCommittedIsRequested(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(3L);assertCapacity();verify(manager).getTransaction(argThat(d->d.getIsolationLevel()==TransactionDefinition.ISOLATION_READ_COMMITTED));}
    void assertCapacity(){assertThatThrownBy(()->store.create(7,input)).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("CONVERSATION_HISTORY_CAPACITY"));}
}
