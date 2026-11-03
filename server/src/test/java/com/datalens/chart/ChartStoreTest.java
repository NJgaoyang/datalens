package com.datalens.chart;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import com.datalens.audit.AuditService;
import com.datalens.identity.CurrentActor;
import com.datalens.common.web.DomainException;
import static com.datalens.chart.ChartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChartStoreTest {
    JdbcTemplate db;ChartStore store;
    final CurrentActor actor=new CurrentActor(7,"owner","USER",1);
    final Summary old=new Summary("chart","Original",7,1,"revision-1",Instant.EPOCH);
    @BeforeEach void setup(){
        db=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        store=spy(new ChartStore(db,manager,mock(AuditService.class)));doNothing().when(store).lock();
    }
    @Test void retryResolvesOriginalRunBeforeCheckingCurrentRevision(){
        var run=new ChartStore.Run("run","chart","revision-1",7,"original-dates","task");
        when(db.queryForList("SELECT id FROM chart_run WHERE actor_id=? AND chart_id=? AND idempotency_key=?",String.class,7L,"chart","refresh-key")).thenReturn(List.of("run"));
        doReturn(run).when(store).run("run");
        assertThat(store.createRun(actor,old,"refresh-key","new-dates")).isEqualTo(run);
        verify(store,never()).chart(anyString());
    }
    @Test void newRefreshIntentStillRejectsStaleRevision(){
        doReturn(new Summary("chart","New",7,2,"revision-2",Instant.EPOCH)).when(store).chart("chart");
        assertThatThrownBy(()->store.createRun(actor,old,"new-refresh","new-dates")).isInstanceOf(DomainException.class);
    }
    @Test void saveRetryComparesOriginalIntentNotPublishedName(){
        var preview=new ChartStore.PreviewRow("preview",7,1,"definition","[]","task","chart");
        var renamed=new Summary("chart","Renamed",7,2,"revision-2",Instant.EPOCH);
        doReturn(preview).when(store).preview("preview");doReturn(renamed).when(store).chart("chart");
        when(db.queryForObject("SELECT saved_name FROM chart_preview WHERE id=?",String.class,"preview")).thenReturn("Original");
        assertThat(store.save(actor,preview,"Original","request")).isEqualTo(renamed);
        assertThatThrownBy(()->store.save(actor,preview,"Renamed","request")).isInstanceOf(DomainException.class);
    }
}
