package com.datalens.query;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.datalens.identity.CurrentActor;
import com.datalens.common.web.DomainException;
import static com.datalens.query.QueryModels.*;
import static org.assertj.core.api.Assertions.*;

/** Real disposable MySQL transactions and audit persistence. No analytics JDBC is simulated as acceptance. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class QueryStoreIT {
    @Autowired QueryStore store;
    @Autowired JdbcTemplate db;
    final List<Long> users=new ArrayList<>();
    final List<String> sources=new ArrayList<>(),subjects=new ArrayList<>();
    CurrentActor actor;String source,subject;
    @BeforeEach void seed() {actor=user();source=source();subject=subject(source);}
    CurrentActor user() {
        String name="qstore_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'not-a-login-hash','USER')",name);
        long id=Objects.requireNonNull(db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name));
        users.add(id);return new CurrentActor(id,name,"USER",1);
    }
    String source() {
        String id=UUID.randomUUID().toString();sources.add(id);
        db.update("INSERT INTO ds_source(id,name,host,port,catalog_name,database_name,username,credential,enabled) VALUES(?,'Query store test','10.20.30.40',9030,'default_catalog','sales','reader','not-used',TRUE)",id);
        return id;
    }
    String subject(String sourceId) {
        String id=UUID.randomUUID().toString();subjects.add(id);
        db.update("INSERT INTO ds_subject(id,name,source_id,share_scope) VALUES(?,'Query store test',?,'NONE')",id,sourceId);return id;
    }
    QueryStore.Stored create(CurrentActor who,String sourceId,String subjectId,String key,String hash) {
        return store.create(who,new Submit(subjectId,"SELECT id FROM orders",List.of(),key),sourceId,1,hash,"b".repeat(64),"[]",UUID.randomUUID().toString());
    }
    QueryStore.Stored create(String key) {return create(actor,source,subject,key,"a".repeat(64));}
    @AfterEach void cleanup() {
        for(long id:users)db.update("DELETE FROM sys_audit_event WHERE actor_id=?",id);
        for(String id:sources)db.update("DELETE FROM query_task WHERE source_id=?",id);
        for(String id:subjects)db.update("DELETE FROM ds_subject WHERE id=?",id);
        for(String id:sources)db.update("DELETE FROM ds_source WHERE id=?",id);
        for(long id:users)db.update("DELETE FROM sys_user WHERE id=?",id);
    }
    @Test void concurrentDuplicateCreatesExactlyOneTaskAndOneAudit() throws Exception {
        var results=race(8,i->create("same-key-123"));
        assertThat(results.stream().map(r->r.view().id()).distinct()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM query_task WHERE actor_id=?",actor.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sys_audit_event WHERE actor_id=? AND action='QUERY_SUBMIT'",actor.id())).isEqualTo(1);
        assertThatThrownBy(()->create(actor,source,subject,"same-key-123","c".repeat(64)))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @Test void concurrentDistinctSubmissionsCannotExceedActorLimit() throws Exception {
        var results=race(8,i->{try{return create("distinct-key-"+i).view().status();}catch(DomainException e){return e.code();}});
        assertThat(results).filteredOn("QUEUED"::equals).hasSize(2);
        assertThat(results).filteredOn("QUERY_CONCURRENCY_LIMIT"::equals).hasSize(6);
        assertThat(count("SELECT COUNT(*) FROM query_task WHERE actor_id=?",actor.id())).isEqualTo(2);
    }
    @Test void perSourceAdmissionCannotExceedThreeAcrossUsers() throws Exception {
        var actors=new ArrayList<CurrentActor>();for(int i=0;i<8;i++)actors.add(user());
        var results=race(8,i->{try{return create(actors.get(i),source,subject,"source-limit-"+i,"a".repeat(64)).view().status();}catch(DomainException e){return e.code();}});
        assertThat(results).filteredOn("QUEUED"::equals).hasSize(3);
        assertThat(results).filteredOn("QUERY_CONCURRENCY_LIMIT"::equals).hasSize(5);
    }
    @Test void terminalStateAndAuditCannotBeOverwritten() {
        String id=create("terminal-key").view().id();assertThat(store.start(id)).isTrue();
        store.finish(id,"SUCCEEDED",null,12,"{}",false);
        store.finish(id,"FAILED","LATE_ERROR",99,null,false);
        assertThat(store.get(id).view().status()).isEqualTo("SUCCEEDED");
        assertThat(store.get(id).resultJson()).isEqualTo("{}");
        assertThat(store.cancelling(id)).isFalse();
        assertThat(count("SELECT COUNT(*) FROM sys_audit_event WHERE resource_id=? AND action='QUERY_FINISH'",id)).isEqualTo(1);
    }
    @Test void uncertainTerminationBlocksNewSourceWorkButAllowsIdempotentRead() {
        String id=create("uncertain-key").view().id();store.start(id);
        store.finish(id,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",1,null,false);
        assertThat(create("uncertain-key").view().id()).isEqualTo(id);
        assertThatThrownBy(()->create("blocked-key"))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("SOURCE_QUERY_QUARANTINED"));
    }
    @Test void sourceQuarantineAlsoStopsWorkAlreadyQueued() {
        String running=create("quarantine-running").view().id(),queued=create("quarantine-queued").view().id();
        assertThat(store.start(running)).isTrue();
        store.finish(running,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",1,null,false);
        assertThat(store.start(queued)).isFalse();
        assertThat(store.get(queued).view().status()).isEqualTo("FAILED");
        assertThat(store.get(queued).view().errorCode()).isEqualTo("SOURCE_QUERY_QUARANTINED");
    }
    @Test void queuedCancellationIsConfirmedWithoutQuarantiningSource() {
        String id=create("cancel-before-dispatch").view().id();
        assertThat(store.cancelQueued(id)).isTrue();
        assertThat(store.get(id).view().status()).isEqualTo("CANCELLED");
        assertThat(store.start(id)).isFalse();
        assertThat(create("after-queued-cancel").view().status()).isEqualTo("QUEUED");
        assertThat(count("SELECT COUNT(*) FROM sys_audit_event WHERE resource_id=? AND action='QUERY_FINISH'",id)).isEqualTo(1);
    }
    @Test void cancelledTaskCannotRaceBackToSuccess() {
        String id=create("cancel-race-key").view().id();assertThat(store.start(id)).isTrue();
        assertThat(store.cancelling(id)).isTrue();
        store.finish(id,"SUCCEEDED",null,12,"{\"secret\":true}",false);
        assertThat(store.get(id).view().status()).isEqualTo("CANCEL_UNCONFIRMED");
        assertThat(store.get(id).resultJson()).isNull();
        store.finish(id,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",12,null,false);
        assertThat(store.get(id).view().status()).isEqualTo("CANCEL_UNCONFIRMED");
    }
    @Test void globalAdmissionCannotExceedThirtyTwoAcrossSourcesAndUsers() {
        for(int i=0;i<16;i++) {
            var who=user();String sourceId=source(),subjectId=subject(sourceId);
            create(who,sourceId,subjectId,"global-key-0","a".repeat(64));
            create(who,sourceId,subjectId,"global-key-1","a".repeat(64));
        }
        assertThatThrownBy(()->create("global-overflow"))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("QUERY_CONCURRENCY_LIMIT"));
    }
    @Test void recoveryNeverRerunsDispatchedQueriesAndQuarantinesSource() {
        String queued=create("recover-queued").view().id(),running=create("recover-running").view().id();
        store.start(running);store.recover();
        assertThat(store.get(queued).view().status()).isEqualTo("FAILED");
        assertThat(store.get(running).view().status()).isEqualTo("RECOVERY_UNCONFIRMED");
        store.recover();
        assertThat(count("SELECT COUNT(*) FROM sys_audit_event WHERE resource_id=? AND action='QUERY_FINISH'",running)).isEqualTo(1);
        assertThatThrownBy(()->create("after-recovery"))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("SOURCE_QUERY_QUARANTINED"));
    }
    long count(String sql,Object... args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    interface Job<T>{T run(int index)throws Exception;}
    <T> List<T> race(int count,Job<T> action)throws Exception {
        var ready=new CountDownLatch(count);var go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(count)) {
            var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++){final int index=i;futures.add(executor.submit(()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new AssertionError("Start gate timed out");return action.run(index);}));}
            try{assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();}finally{go.countDown();}
            var result=new ArrayList<T>();for(var future:futures)result.add(future.get(20,TimeUnit.SECONDS));return result;
        }
    }
}
