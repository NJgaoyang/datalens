package com.datalens.model;

import com.datalens.common.web.DomainException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCapacityTest {
    JdbcTemplate db=mock(JdbcTemplate.class);ModelStore store=new ModelStore(db,new ModelCapacity(3,2));
    void reserve(){store.reserve("call",7,"subject",1,1,"digest",100,1000);}
    @Test void invalidConfigurationIsNotUnlimited(){assertThatThrownBy(()->new ModelCapacity(0,2)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new ModelCapacity(1,-1)).isInstanceOf(IllegalArgumentException.class);}
    @Test void totalAndActorBoundariesDoNotChargeBudget(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(3L);assertCapacity();when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(0L,2L);assertCapacity();verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void moduleLockPrecedesQuotaAndBudget(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(0L);when(db.update(startsWith("UPDATE model_daily_budget"),any(Object[].class))).thenReturn(1);reserve();var order=inOrder(db);order.verify(db).queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='model.lock' FOR UPDATE",String.class);order.verify(db,times(2)).queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class));order.verify(db).update(startsWith("INSERT INTO model_daily_budget"),any(Object[].class));order.verify(db).update(startsWith("UPDATE model_daily_budget"),any(Object[].class));order.verify(db).update(startsWith("INSERT INTO model_call"),any(Object[].class));}
    @Test void unavailableCountFailsClosed(){when(db.queryForObject(contains("capacity_rows"),eq(Long.class),any(Object[].class))).thenReturn(null);assertThatThrownBy(this::reserve).isInstanceOf(NullPointerException.class);verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void finishNeverChecksQuotaOrModuleLock(){store.finish("old","FAILED","code");verify(db).update("UPDATE model_call SET status=?,error_code=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?","FAILED","code","old");verifyNoMoreInteractions(db);}
    @Test void reservationIsolationAndPropagationAreExplicit() throws Exception {var a=ModelStore.class.getMethod("reserve",String.class,long.class,String.class,long.class,long.class,String.class,long.class,long.class).getAnnotation(Transactional.class);assertThat(a.isolation()).isEqualTo(Isolation.READ_COMMITTED);assertThat(a.propagation()).isEqualTo(Propagation.REQUIRES_NEW);}
    void assertCapacity(){assertThatThrownBy(this::reserve).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("MODEL_HISTORY_CAPACITY"));}
}
