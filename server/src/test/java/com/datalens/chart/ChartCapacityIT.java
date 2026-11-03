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

/** Disposable real MySQL admission bounds. No history is deleted by application quota handling. */
@SpringBootTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ChartCapacityIT {
    @Autowired ChartStore store;
    @Autowired JdbcTemplate db;
    CurrentActor actor;
    ChartCapacity savedCapacity;
    final List<String> charts = new ArrayList<>();
    @BeforeEach void seed() {
        savedCapacity=store.capacity;
        String name="cap_"+UUID.randomUUID().toString();
        db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'disabled-fixture','SUPER_ADMIN')",name);
        actor=new CurrentActor(db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name),name,"SUPER_ADMIN",1);
    }
    @AfterEach void cleanup() {
        store.capacity=savedCapacity;
        db.update("DELETE FROM chart_run WHERE actor_id=?",actor.id());
        db.update("DELETE FROM chart_preview WHERE actor_id=?",actor.id());
        for(String id:charts){db.update("DELETE FROM chart_management_event WHERE chart_id=?",id);db.update("DELETE FROM chart_share WHERE chart_id=?",id);db.update("DELETE FROM chart_revision WHERE chart_id=?",id);db.update("DELETE FROM chart WHERE id=?",id);}
        db.update("DELETE FROM sys_audit_event WHERE actor_id=?",actor.id());
        db.update("DELETE FROM sys_user WHERE id=?",actor.id());
    }
    void limit(int assets,int previews,int runs,int revisions) {
        // Tests own fresh rows; global limits deliberately retain room for unrelated integration fixtures.
        store.capacity=new ChartCapacity(10000,assets,10000,previews,10000,runs,20000,revisions);
    }
    ChartStore.PreviewRow preview(String key){return store.createPreview(actor,key,"fixture-definition","[]");}
    ChartModels.Summary chart(String key){var p=preview(key);var c=store.save(actor,p,key,"fixture-request");charts.add(c.id());return c;}
    void capacityDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("CHART_STORAGE_CAPACITY"));
    }
    @Test void replayAndExistingDataSurvivePreviewAndRunLimits() {
        limit(10,1,1,10);
        var p=preview("one");assertThat(preview("one").id()).isEqualTo(p.id());
        capacityDenied(()->preview("two"));
        assertThat(store.preview(p.id()).definition()).isEqualTo("fixture-definition");
        var c=store.save(actor,p,"one","fixture");charts.add(c.id());
        var run=store.createRun(actor,c,"one","[]");
        assertThat(store.createRun(actor,c,"one","changed-retry").id()).isEqualTo(run.id());
        capacityDenied(()->store.createRun(actor,c,"two","[]"));
        store.bindRun(run.id(),"existing-task");
        assertThat(store.run(run.id()).taskId()).isEqualTo("existing-task");
        assertThat(store.save(actor,p,"one","retry").id()).isEqualTo(c.id());
    }
    @Test void assetLimitCountsTrashAndStillAllowsRevocationAndRecovery() {
        limit(1,10,10,1);var c=chart("one");var second=preview("two");
        capacityDenied(()->store.save(actor,second,"two","fixture"));
        var deleted=store.manage(actor,c.id(),c.version(),"DELETE",null,List.of(),null,()->{},"fixture");
        capacityDenied(()->store.save(actor,second,"two","fixture"));
        var restored=store.manage(actor,c.id(),deleted.version(),"RESTORE",null,List.of(),null,()->{},"fixture");
        assertThat(restored.deleted()).isFalse();
        assertThat(store.definition(c.id(),c.revisionId())).isEqualTo("fixture-definition");
        capacityDenied(()->store.publish(actor,c.id(),second,"edited",restored.version(),"fixture"));
        var privateChart=store.manage(actor,c.id(),restored.version(),"SHARE","PRIVATE",List.of(),null,()->{},"fixture");
        assertThat(privateChart.visibility()).isEqualTo("PRIVATE");
    }
    @Test void concurrentPreviewAdmissionCannotOverrunPersonalLimit() throws Exception {
        limit(10,1,10,10);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var work=new ArrayList<Future<Boolean>>();
            for(int i=0;i<4;i++){String key="race-"+i;work.add(pool.submit(()->{start.await();try{preview(key);return true;}catch(DomainException denied){assertThat(denied.code()).isEqualTo("CHART_STORAGE_CAPACITY");return false;}}));}
            start.countDown();int accepted=0;for(var future:work)if(future.get(15,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart_preview WHERE actor_id=?",Long.class,actor.id())).isEqualTo(1);
    }
    @Test void fullGlobalRevisionCapacityCannotPartiallySaveAnAsset() {
        limit(10,10,10,10);chart("first");var p=preview("second");
        int existing=db.queryForObject("SELECT COUNT(*) FROM chart_revision",Integer.class);
        store.capacity=new ChartCapacity(10000,10,10000,10,10000,10,existing,1);
        capacityDenied(()->store.save(actor,p,"second","fixture"));
        assertThat(store.preview(p.id()).savedChartId()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart WHERE owner_id=?",Long.class,actor.id())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart_revision",Integer.class)).isEqualTo(existing);
    }
}
