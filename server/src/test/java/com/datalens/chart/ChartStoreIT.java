package com.datalens.chart;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.datalens.identity.CurrentActor;
import com.datalens.common.web.DomainException;
import static org.assertj.core.api.Assertions.*;
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class ChartStoreIT {
    @Autowired ChartStore store;@Autowired JdbcTemplate db;
    CurrentActor actor;final List<String> charts=new ArrayList<>();
    @BeforeEach void seed(){String name="chart_"+UUID.randomUUID();db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'not-a-login-hash','USER')",name);long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);actor=new CurrentActor(id,name,"USER",1);}
    @AfterEach void cleanup(){db.update("DELETE FROM chart_run WHERE actor_id=?",actor.id());db.update("DELETE FROM chart_preview WHERE actor_id=?",actor.id());for(String id:charts){db.update("DELETE FROM chart_revision WHERE chart_id=?",id);db.update("DELETE FROM chart WHERE id=?",id);}db.update("DELETE FROM sys_audit_event WHERE actor_id=?",actor.id());db.update("DELETE FROM sys_user WHERE id=?",actor.id());}
    @Test void previewRetriesKeepResolvedDatesAcrossMidnight(){var first=store.createPreview(actor,"preview-key","definition","[\"2026-11-03\"]");var retry=store.createPreview(actor,"preview-key","definition","[\"2026-11-04\"]");assertThat(retry.id()).isEqualTo(first.id());assertThat(retry.params()).isEqualTo(first.params());assertThatThrownBy(()->store.createPreview(actor,"preview-key","changed","[]")).isInstanceOf(DomainException.class);}
    @Test void explicitSaveIsPersistentIdempotentAndListedOnlyForOwner(){var preview=store.createPreview(actor,"preview-key","definition-v1","[]");var chart=store.save(actor,preview,"Sales",UUID.randomUUID().toString());charts.add(chart.id());assertThat(store.save(actor,preview,"Sales",UUID.randomUUID().toString()).id()).isEqualTo(chart.id());assertThat(store.list(actor.id(),1,20).items()).hasSize(1);assertThat(store.list(actor.id()+1,1,20).items()).isEmpty();assertThat(store.definition(chart.id(),chart.revisionId())).isEqualTo("definition-v1");assertThat(db.queryForObject("SELECT visibility FROM chart WHERE id=?",String.class,chart.id())).isEqualTo("PRIVATE");}
    @Test void publicationPreservesOldRevisionAndRejectsStaleVersion(){var p1=store.createPreview(actor,"preview-key1","definition-v1","[]");var chart=store.save(actor,p1,"Sales",UUID.randomUUID().toString());charts.add(chart.id());var p2=store.createPreview(actor,"preview-key2","definition-v2","[]");var updated=store.publish(actor,chart.id(),p2,"Sales2",1,UUID.randomUUID().toString());assertThat(updated.version()).isEqualTo(2);assertThat(store.definition(chart.id(),chart.revisionId())).isEqualTo("definition-v1");assertThat(store.definition(chart.id(),updated.revisionId())).isEqualTo("definition-v2");assertThatThrownBy(()->store.publish(actor,chart.id(),p2,"Sales3",1,UUID.randomUUID().toString())).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("CHART_VERSION_CONFLICT"));}
    @Test void refreshRetryKeepsOriginalRevisionAndNewIntentCreatesIndependentRun(){var p=store.createPreview(actor,"preview-key","v1","[]");var chart=store.save(actor,p,"Sales",UUID.randomUUID().toString());charts.add(chart.id());var first=store.createRun(actor,chart,"refresh-key","dates-day1");var retry=store.createRun(actor,chart,"refresh-key","dates-day2");assertThat(retry).isEqualTo(first);assertThat(store.createRun(actor,chart,"refresh-next","dates-day2").id()).isNotEqualTo(first.id());var newer=store.publish(actor,chart.id(),p,"Sales",1,UUID.randomUUID().toString());assertThat(store.createRun(actor,newer,"refresh-key","dates-day3").revisionId()).isEqualTo(chart.revisionId());}
    @Test void concurrentSaveCreatesOneIndependentAsset() throws Exception {var p=store.createPreview(actor,"preview-key","v1","[]");try(var executor=Executors.newFixedThreadPool(4)){var work=new ArrayList<Callable<String>>();for(int i=0;i<4;i++)work.add(()->store.save(actor,p,"Sales",UUID.randomUUID().toString()).id());var results=executor.invokeAll(work);Set<String> ids=new HashSet<>();for(var result:results)ids.add(result.get());charts.addAll(ids);assertThat(ids).hasSize(1);assertThat(store.list(actor.id(),1,20).total()).isEqualTo(1);}}
    @Test void originalSaveRetrySurvivesLaterRenameButChangedSaveIntentConflicts(){
        var preview=store.createPreview(actor,"preview-key","definition-v1","[]");
        var original=store.save(actor,preview,"Original name",UUID.randomUUID().toString());charts.add(original.id());
        var edited=store.createPreview(actor,"edit-preview-key","definition-v2","[]");
        var renamed=store.publish(actor,original.id(),edited,"Renamed chart",1,UUID.randomUUID().toString());
        assertThat(store.save(actor,preview,"Original name",UUID.randomUUID().toString())).isEqualTo(renamed);
        assertThatThrownBy(()->store.save(actor,preview,"Renamed chart",UUID.randomUUID().toString())).isInstanceOf(DomainException.class);
        assertThat(store.list(actor.id(),1,20).total()).isEqualTo(1);
    }
    @Test void staleSummaryCanRetryExistingRunButCannotCreateANewRunAfterPublication(){
        var preview=store.createPreview(actor,"preview-key","definition-v1","[]");
        var original=store.save(actor,preview,"Sales",UUID.randomUUID().toString());charts.add(original.id());
        var run=store.createRun(actor,original,"refresh-key","original-dates");
        store.publish(actor,original.id(),preview,"Sales",1,UUID.randomUUID().toString());
        assertThat(store.createRun(actor,original,"refresh-key","new-dates")).isEqualTo(run);
        assertThatThrownBy(()->store.createRun(actor,original,"new-refresh-key","new-dates")).isInstanceOf(DomainException.class);
    }


    @Test void realDatabaseEnumerationDoesNotRefuseRevokedOrUnicodeSearchCandidates(){
        for(int i=0;i<1002;i++){String id=UUID.randomUUID().toString();charts.add(id);db.update("INSERT INTO chart(id,owner_id,name,revision_id) VALUES(?,?,?,?)",id,actor.id(),i==1001?"唯一目标 %_!":"中文报表 "+i,UUID.randomUUID().toString());}
        var permissions=org.mockito.Mockito.mock(com.datalens.permission.PermissionService.class);
        var policy=org.mockito.Mockito.mock(ChartPermissionService.class);
        var queries=org.mockito.Mockito.mock(com.datalens.query.QueryService.class);
        org.mockito.Mockito.when(permissions.current()).thenReturn(actor);
        org.mockito.Mockito.when(policy.executionOwner(org.mockito.ArgumentMatchers.eq(actor),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        var service=new ChartService(store,queries,permissions,tools.jackson.databind.json.JsonMapper.builder().build(),policy);
        assertThat(service.list("mine",1,20).total()).isZero();
        var target=store.chart(charts.getLast());
        org.mockito.Mockito.doReturn(actor).when(policy).executionOwner(actor,target.id(),target.revisionId());
        org.mockito.Mockito.when(policy.view(actor,target.id())).thenReturn(target);
        var result=service.list("mine",1,20,"唯一目标 %_!");
        assertThat(result.total()).isEqualTo(1);assertThat(result.items()).containsExactly(target);
        org.mockito.Mockito.verifyNoInteractions(queries);
    }
}
