package com.datalens.export;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.datalens.identity.CurrentActor;
import com.datalens.chart.ChartModels.Summary;
import static com.datalens.export.ExportModels.*;
import static org.assertj.core.api.Assertions.*;
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @SpringBootTest
class ExportStoreIT {
 @Autowired ExportStore store;@Autowired JdbcTemplate db;
 final CurrentActor actor=new CurrentActor(900000001,"export-test","USER",1);String chart;String request;
 @BeforeEach void setup(){chart=UUID.randomUUID().toString();request=UUID.randomUUID().toString();}
 @AfterEach void cleanup(){db.update("DELETE FROM chart_export_job WHERE actor_id=?",actor.id());db.update("DELETE FROM chart_export_grant WHERE chart_id=?",chart);db.update("DELETE FROM sys_audit_event WHERE actor_id=?",actor.id());}
 Summary summary(){return new Summary(chart,"Export",actor.id(),1,"revision",Instant.EPOCH);}
 @Test void durableIdempotencyRecoveryExpiryAndGrantGeneration(){var grant=new Grant(actor.id(),1,true);store.grant(actor,chart,grant,()->{},request);assertThat(store.grant(chart,actor.id(),1)).isEqualTo(1);store.grant(actor,chart,new Grant(actor.id(),1,false),()->{},request);assertThat(store.grant(chart,actor.id(),1)).isZero();store.grant(actor,chart,grant,()->{},request);assertThat(store.grant(chart,actor.id(),1)).isEqualTo(3);var input=new Create(chart,"task","idempotent-key");var first=store.create(actor,summary(),3,input,()->{},request);assertThat(store.create(actor,summary(),3,input,()->{},request).id()).isEqualTo(first.id());assertThatThrownBy(()->store.create(actor,summary(),3,new Create(chart,"other-task","idempotent-key"),()->{},request)).hasMessageContaining("幂等");assertThat(store.claim(first.id())).isTrue();assertThat(store.claim(first.id())).isFalse();store.recover();assertThat(store.get(first.id()).status()).isEqualTo("INTERRUPTED");db.update("UPDATE chart_export_job SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?",first.id());store.cleanup();assertThatThrownBy(()->store.get(first.id())).hasMessageContaining("不存在");}
 @Test void successfulBytesAndPerUserCapacityAreBounded(){var first=store.create(actor,summary(),1,new Create(chart,"task","capacity-0"),()->{},request);store.claim(first.id());store.finish(first.id(),new byte[]{1,2,3});assertThat(store.content(first.id())).containsExactly((byte)1,(byte)2,(byte)3);for(int i=1;i<8;i++)store.create(actor,summary(),1,new Create(chart,"task","capacity-"+i),()->{},request);assertThatThrownBy(()->store.create(actor,summary(),1,new Create(chart,"task","capacity-9"),()->{},request)).hasMessageContaining("繁忙");}
}
