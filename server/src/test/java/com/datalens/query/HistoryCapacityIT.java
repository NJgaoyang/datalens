package com.datalens.query;

import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import com.datalens.conversation.*;
import com.datalens.identity.CurrentActor;
import com.datalens.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Disposable MySQL only. Does not execute analytics SQL or contact a model provider. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"datalens.capacity.query.total=2","datalens.capacity.query.per-actor=1",
    "datalens.capacity.conversation.total=2","datalens.capacity.conversation.per-actor=1",
    "datalens.capacity.model.total=2","datalens.capacity.model.per-actor=1"})
class HistoryCapacityIT {
    @Autowired QueryStore queries;@Autowired ConversationStore conversations;@Autowired ModelStore models;
    @Autowired JdbcTemplate db;@Autowired PlatformTransactionManager manager;@Autowired AuditService audit;
    List<CurrentActor> actors=new ArrayList<>();String source,subject;
    @BeforeEach void seed(){
        for(int i=0;i<9;i++){String name="capacity_"+UUID.randomUUID().toString().replace("-","");db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,'not-a-login-hash','USER')",name);long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);actors.add(new CurrentActor(id,name,"USER",1));}
        source=UUID.randomUUID().toString();subject=UUID.randomUUID().toString();
        db.update("INSERT INTO ds_source(id,name,host,port,catalog_name,database_name,username,credential,enabled) VALUES(?,'Capacity test','10.20.30.40',9030,'default_catalog','sales','reader','not-used',TRUE)",source);
        db.update("INSERT INTO ds_subject(id,name,source_id,share_scope) VALUES(?,'Capacity test',?,'NONE')",subject,source);
    }
    @AfterEach void clean(){
        for(var actor:actors){for(String id:db.queryForList("SELECT id FROM conversation WHERE owner_id=?",String.class,actor.id()))db.update("DELETE FROM conversation_turn WHERE conversation_id=?",id);db.update("DELETE FROM conversation WHERE owner_id=?",actor.id());db.update("DELETE FROM query_task WHERE actor_id=?",actor.id());db.update("DELETE FROM model_call WHERE actor_id=?",actor.id());db.update("DELETE FROM sys_audit_event WHERE actor_id=?",actor.id());}
        db.update("DELETE FROM ds_subject WHERE id=?",subject);db.update("DELETE FROM ds_source WHERE id=?",source);for(var actor:actors)db.update("DELETE FROM sys_user WHERE id=?",actor.id());
    }
    QueryStore.Stored query(int actor,String key){return queries.create(actors.get(actor),new QueryModels.Submit(subject,"SELECT id FROM orders",List.of(),key),source,1,"a".repeat(64),"b".repeat(64),"[]","capacity-test");}
    ConversationStore.Row conversation(int actor,String key){return conversations.create(actors.get(actor).id(),new ConversationModels.Create(subject,"Capacity",key));}
    void model(int actor,String id){models.reserve(id,actors.get(actor).id(),subject,1,1,"a".repeat(64),10,10_000_000);}
    @Test void queryOwnerLimitPreservesReplayConflictsAndTerminalWork(){
        var first=query(0,"first");assertThat(queries.start(first.view().id())).isTrue();queries.finish(first.view().id(),"SUCCEEDED",null,1,"{\"rows\":[]}",false);
        capacity(()->query(0,"fresh"),"QUERY_HISTORY_CAPACITY");assertThat(query(0,"first").view().id()).isEqualTo(first.view().id());
        assertThatThrownBy(()->queries.create(actors.getFirst(),new QueryModels.Submit(subject,"SELECT id FROM orders",List.of(),"first"),source,1,"changed","b".repeat(64),"[]","request")).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        var second=query(1,"second");assertThat(queries.cancelQueued(second.view().id())).isTrue();assertThat(queries.get(second.view().id()).view().status()).isEqualTo("CANCELLED");
        var reopened=new QueryStore(db,manager,audit,new QueryCapacity(1,1));assertThat(reopened.get(first.view().id()).view().status()).isEqualTo("SUCCEEDED");assertThat(reopened.get(first.view().id()).resultJson()).isEqualTo("{\"rows\":[]}");assertThat(reopened.create(actors.getFirst(),new QueryModels.Submit(subject,"SELECT id FROM orders",List.of(),"first"),source,1,"a".repeat(64),"b".repeat(64),"[]","request").view().id()).isEqualTo(first.view().id());
        capacity(()->reopened.create(actors.get(2),new QueryModels.Submit(subject,"SELECT id FROM orders",List.of(),"new"),source,1,"a".repeat(64),"b".repeat(64),"[]","request"),"QUERY_HISTORY_CAPACITY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=?",Long.class,source)).isEqualTo(2);
    }
    @Test void queryGlobalBoundaryIsAtomicAcrossActors() throws Exception {
        var first=query(0,"first");queries.finish(first.view().id(),"FAILED","TEST",0,null,false);
        assertRace(race(i->query(i+1,"race-"+i)),"QUERY_HISTORY_CAPACITY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=?",Long.class,source)).isEqualTo(2);
        queries.recover();assertThat(db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status='FAILED'",Long.class,source)).isEqualTo(2);
    }
    @Test void conversationOwnerLimitReplayAndExistingTurnsSurviveFullCapacity(){
        var first=conversation(0,"first");capacity(()->conversation(0,"fresh"),"CONVERSATION_HISTORY_CAPACITY");conversation(1,"second");
        assertThat(conversation(0,"first").id()).isEqualTo(first.id());assertThatThrownBy(()->conversations.create(actors.getFirst().id(),new ConversationModels.Create(subject,"Different","first"))).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("CONVERSATION_CONFLICT"));
        var turn=conversations.admit(actors.getFirst().id(),first.id(),new ConversationModels.Send("question",0,"turn"));conversations.finish(turn.turn().id(),"FAILED","TEST");
        assertThat(conversations.turn(first.id(),turn.turn().id()).status()).isEqualTo("FAILED");assertThat(conversations.admit(actors.getFirst().id(),first.id(),new ConversationModels.Send("question",0,"turn")).fresh()).isFalse();
        var reopened=new ConversationStore(db,manager,new ConversationCapacity(1,1));assertThat(reopened.create(actors.getFirst().id(),new ConversationModels.Create(subject,"Capacity","first")).id()).isEqualTo(first.id());capacity(()->reopened.create(actors.get(2).id(),new ConversationModels.Create(subject,"Capacity","new")),"CONVERSATION_HISTORY_CAPACITY");
    }
    @Test void conversationGlobalBoundaryIsAtomicAcrossOwners() throws Exception {conversation(0,"first");assertRace(race(i->conversation(i+1,"race-"+i)),"CONVERSATION_HISTORY_CAPACITY");assertThat(db.queryForObject("SELECT COUNT(*) FROM conversation WHERE subject_id=?",Long.class,subject)).isEqualTo(2);}
    @Test void modelActorQuotaDoesNotChargeBudgetAndFinishStillWorks(){
        String first=UUID.randomUUID().toString();model(0,first);long before=budget();capacity(()->model(0,UUID.randomUUID().toString()),"MODEL_HISTORY_CAPACITY");assertThat(budget()).isEqualTo(before);
        model(1,UUID.randomUUID().toString());models.finish(first,"FAILED","TEST");assertThat(db.queryForObject("SELECT status FROM model_call WHERE id=?",String.class,first)).isEqualTo("FAILED");capacity(()->model(2,UUID.randomUUID().toString()),"MODEL_HISTORY_CAPACITY");assertThat(budget()).isEqualTo(before+10);
    }
    @Test void modelGlobalBoundaryIsAtomicAndRejectedCallsCostNothing() throws Exception {model(0,UUID.randomUUID().toString());long before=budget();assertRace(race(i->{model(i+1,UUID.randomUUID().toString());return true;}),"MODEL_HISTORY_CAPACITY");assertThat(budget()).isEqualTo(before+10);assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call WHERE subject_id=?",Long.class,subject)).isEqualTo(2);}
    @Test void modelInsertFailureRollsBackBudgetReservation(){String first=UUID.randomUUID().toString();model(0,first);long before=budget();assertThatThrownBy(()->model(1,first)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);assertThat(budget()).isEqualTo(before);assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call WHERE subject_id=?",Long.class,subject)).isEqualTo(1);}
    long budget(){return db.queryForObject("SELECT COALESCE(SUM(reserved_tokens),0) FROM model_daily_budget",Long.class);}
    void capacity(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo(code));}
    List<String> race(IntFunction<?> work) throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)){CountDownLatch start=new CountDownLatch(1);List<Future<String>> futures=new ArrayList<>();for(int i=0;i<8;i++){final int n=i;futures.add(pool.submit(()->{if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("start timeout");try{work.apply(n);return "accepted";}catch(DomainException e){return e.code();}}));}start.countDown();List<String> results=new ArrayList<>();for(var f:futures)results.add(f.get(30,TimeUnit.SECONDS));return results;}
    }
    void assertRace(List<String> results,String rejection){assertThat(results).filteredOn("accepted"::equals).hasSize(1);assertThat(results).filteredOn(rejection::equals).hasSize(7);}
}
