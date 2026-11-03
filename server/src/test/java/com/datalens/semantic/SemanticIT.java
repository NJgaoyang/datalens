package com.datalens.semantic;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static com.datalens.semantic.SemanticModels.*;

/** Real MySQL, Redis, session/CSRF HTTP checks. No analytics query is executed. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.servlet.session.cookie.secure=false","server.address=127.0.0.1"})
class SemanticIT {
    static final String PASSWORD="Semantic-integration-only-42";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    final List<Long> users=new ArrayList<>();
    Browser admin,reader,outsider;
    String source,subject;
    @BeforeEach void seed() throws Exception {
        admin=account("SUPER_ADMIN");reader=account("USER");outsider=account("USER");
        source=UUID.randomUUID().toString();subject=UUID.randomUUID().toString();
        db.update("INSERT INTO ds_source(id,name,host,port,catalog_name,database_name,username,credential,enabled) VALUES(?,'Semantic synthetic test','10.20.30.40',9030,'default_catalog','sales','reader','not-used',TRUE)",source);
        db.update("INSERT INTO ds_subject(id,name,source_id,share_scope) VALUES(?,'Semantic synthetic test',?,'NONE')",subject,source);
        for(var c:SemanticFixtures.columns(source)) {
            db.update("INSERT INTO ds_column(source_id,table_name,column_name,type_name,ordinal_position,nullable) VALUES(?,?,?,?,?,?)",source,c.tableName(),c.columnName(),c.typeName(),c.ordinalPosition(),c.nullable());
            db.update("INSERT INTO ds_subject_field(subject_id,table_name,column_name,denied) VALUES(?,?,?,FALSE)",subject,c.tableName(),c.columnName());
        }
        db.update("INSERT INTO ds_subject_user(subject_id,user_id) VALUES(?,?)",subject,reader.id);
    }
    Browser account(String role) throws Exception {
        String name="sem_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,?,?)",name,passwords.encode(PASSWORD),role);
        long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);users.add(id);
        var browser=new Browser(id);browser.token();browser.ok("POST","/api/v1/auth/login",Map.of("username",name,"password",PASSWORD));browser.token();return browser;
    }
    String path(String suffix){return "/api/v1/semantics/"+subject+suffix;}
    JsonNode save(long version,Document document) throws Exception {return admin.ok("PUT",path("/draft"),new Save(version,document));}
    JsonNode review(long version) throws Exception {return admin.ok("POST",path("/review"),new Review(version,true));}
    JsonNode publish() throws Exception {
        var d=save(0,SemanticFixtures.document());var r=review(d.path("version").asLong());
        return admin.ok("POST",path("/publish"),new Version(r.path("version").asLong()));
    }
    @AfterEach void cleanup() {
        // Tables owned by this module only, followed by source and account fixtures.
        db.update("DELETE FROM semantic_release WHERE subject_id=?",subject);
        db.update("DELETE FROM semantic_workspace WHERE subject_id=?",subject);
        for(long user:users)db.update("DELETE FROM sys_audit_event WHERE actor_id=?",user);
        db.update("DELETE FROM ds_subject_user WHERE subject_id=?",subject);
        db.update("DELETE FROM ds_subject_field WHERE subject_id=?",subject);
        db.update("DELETE FROM ds_subject WHERE id=?",subject);
        db.update("DELETE FROM ds_column WHERE source_id=?",source);db.update("DELETE FROM ds_source WHERE id=?",source);
        for(long user:users)db.update("DELETE FROM sys_user WHERE id=?",user);
    }
    class Browser {
        final long id;String csrf;
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        Browser(long id){this.id=id;}
        HttpResponse<String> send(String method,String path,Object body,boolean withToken) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
            if(withToken&&csrf!=null)request.header("X-CSRF-TOKEN",csrf);
            if(body!=null)request.header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method,String path,Object body) throws Exception {
            var response=send(method,path,body,true);assertThat(response.statusCode()).withFailMessage("%s %s: %s",method,path,response.body()).isEqualTo(200);return json.readTree(response.body()).path("data");
        }
        void status(int expected,String method,String path,Object body) throws Exception {var r=send(method,path,body,true);assertThat(r.statusCode()).withFailMessage("%s %s: %s",method,path,r.body()).isEqualTo(expected);}
        void token() throws Exception {csrf=ok("GET","/api/v1/auth/csrf",null).path("token").asString();}
    }
    @Test void publishRequiresCurrentExplicitHumanReviewAndCsrf() throws Exception {
        var d=save(0,SemanticFixtures.document());long v=d.path("version").asLong();
        admin.status(409,"POST",path("/publish"),new Version(v));
        admin.status(400,"POST",path("/review"),new Review(v,false));
        assertThat(admin.send("POST",path("/review"),new Review(v,true),false).statusCode()).isEqualTo(403);
        var reviewed=review(v);assertThat(reviewed.path("reviewed").asBoolean()).isTrue();
        var changed=save(reviewed.path("version").asLong(),SemanticFixtures.document());
        assertThat(changed.path("reviewed").asBoolean()).isFalse();
        admin.status(409,"POST",path("/publish"),new Version(changed.path("version").asLong()));
    }
    @Test void ordinaryUsersCannotManageOrReadDraftAndAnonymousCannotRead() throws Exception {
        publish();
        reader.status(403,"GET",path("/draft"),null);
        reader.status(403,"PUT",path("/draft"),new Save(1,SemanticFixtures.document()));
        for(String action:List.of("review","publish","withdraw"))reader.status(403,"POST",path("/"+action),Map.of("expectedVersion",1,"confirmation",true));
        outsider.status(403,"GET",path("/published"),null);
        outsider.status(403,"GET",path("/search?q=sales"),null);
        new Browser(0).status(401,"GET",path("/published"),null);
        reader.status(403,"GET",path("/releases"),null);
    }
    @Test void publicationIsImmutableAndSearchOnlyReturnsPublishedAliases() throws Exception {
        var p=publish();String digest=p.path("digest").asString();
        var d=admin.ok("GET",path("/draft"),null);
        var original=SemanticFixtures.document();var changed=SemanticFixtures.withMetric(original,SemanticFixtures.metric("sales","草稿秘密","SELECT SUM(amount) AS sales FROM orders WHERE created_at IS NOT NULL"));
        save(d.path("version").asLong(),changed);
        var visible=reader.ok("GET",path("/published"),null);
        assertThat(visible.path("digest").asString()).isEqualTo(digest);
        assertThat(visible.toString()).doesNotContain("草稿秘密");
        String q=URLEncoder.encode("营收",java.nio.charset.StandardCharsets.UTF_8);
        assertThat(reader.ok("GET",path("/search?q="+q),null).path("matches").size()).isPositive();
        assertThat(reader.ok("GET",path("/search?q="+URLEncoder.encode("草稿秘密",java.nio.charset.StandardCharsets.UTF_8)),null).path("matches").size()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM semantic_release WHERE subject_id=?",Long.class,subject)).isEqualTo(1);
    }
    @Test void newPublicationKeepsHistoricalBytesAndMonotonicVersion() throws Exception {
        var first=publish();var draft=admin.ok("GET",path("/draft"),null);
        var d=SemanticFixtures.document();var edited=SemanticFixtures.withMetric(d,SemanticFixtures.metric("sales","新版口径",d.metrics().getFirst().sql()));
        var saved=save(draft.path("version").asLong(),edited);var reviewed=review(saved.path("version").asLong());
        var second=admin.ok("POST",path("/publish"),new Version(reviewed.path("version").asLong()));
        assertThat(second.path("version").asLong()).isEqualTo(first.path("version").asLong()+1);
        assertThat(reader.ok("GET",path("/published"),null).path("digest").asString()).isEqualTo(second.path("digest").asString());
        var history=admin.ok("GET",path("/releases"),null);assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(1).path("digest").asString()).isEqualTo(first.path("digest").asString());
        assertThat(history.get(1).path("document")).isEqualTo(first.path("document"));
        assertThat(history.get(1).path("reviewedAt")).isEqualTo(first.path("reviewedAt"));
        assertThat(first.path("reviewedAt").isNull()).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_audit_event WHERE actor_id=? AND action='SEMANTIC_PUBLISH'",Long.class,admin.id)).isEqualTo(2);
    }
    @Test void concurrentPublishHasOneWinnerAndOneImmutableRelease() throws Exception {
        var d=save(0,SemanticFixtures.document());var reviewed=review(d.path("version").asLong());var input=new Version(reviewed.path("version").asLong());
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{start.await();return admin.send("POST",path("/publish"),input,true).statusCode();});
            var b=executor.submit(()->{start.await();return admin.send("POST",path("/publish"),input,true).statusCode();});
            start.countDown();
            assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM semantic_release WHERE subject_id=?",Long.class,subject)).isEqualTo(1);
    }
    @Test void staleDraftAndPublishVersionsConflict() throws Exception {
        var d=save(0,SemanticFixtures.document());long v=d.path("version").asLong();
        admin.status(409,"PUT",path("/draft"),new Save(0,SemanticFixtures.document()));
        var r=review(v);admin.status(409,"POST",path("/publish"),new Version(v+100));
        admin.ok("POST",path("/publish"),new Version(r.path("version").asLong()));
    }
    @Test void withdrawImmediatelyRemovesCurrentPublicationAndDoesNotDeleteHistory() throws Exception {
        publish();var d=admin.ok("GET",path("/draft"),null);
        var withdrawn=admin.ok("POST",path("/withdraw"),new Version(d.path("version").asLong()));
        assertThat(withdrawn.path("reviewed").asBoolean()).isFalse();
        admin.status(409,"POST",path("/publish"),new Version(withdrawn.path("version").asLong()));
        reader.status(404,"GET",path("/published"),null);reader.status(404,"GET",path("/search?q=sales"),null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM semantic_release WHERE subject_id=?",Long.class,subject)).isEqualTo(1);
    }
    @Test void subjectRevocationPreventsPublishedAndSearchReads() throws Exception {
        publish();db.update("DELETE FROM ds_subject_user WHERE subject_id=? AND user_id=?",subject,reader.id);
        reader.status(403,"GET",path("/published"),null);reader.status(403,"GET",path("/search?q=sales"),null);
    }
    @Test void disabledAccountCannotReadEvenWithExistingSession() throws Exception {
        publish();db.update("UPDATE sys_user SET enabled=FALSE WHERE id=?",reader.id);
        reader.status(401,"GET",path("/published"),null);
    }
    @Test void disabledDatasourceBlocksPublicationReads() throws Exception {
        publish();db.update("UPDATE ds_source SET enabled=FALSE WHERE id=?",source);
        reader.status(409,"GET",path("/published"),null);
    }
    @Test void disabledSourceStillAllowsRedactedVersionDiscoveryAndWithdrawal() throws Exception {
        publish();db.update("UPDATE ds_source SET enabled=FALSE WHERE id=?",source);
        var recovery=admin.ok("GET",path("/draft"),null);
        assertThat(recovery.path("document").isNull()).isTrue();assertThat(recovery.path("version").asLong()).isPositive();
        assertThat(recovery.path("reviewed").asBoolean()).isFalse();
        var withdrawn=admin.ok("POST",path("/withdraw"),new Version(recovery.path("version").asLong()));
        assertThat(withdrawn.path("publishedVersion").isNull()).isTrue();
        db.update("UPDATE ds_source SET enabled=TRUE WHERE id=?",source);
        reader.status(404,"GET",path("/published"),null);
    }
    @Test void zeroAllowedFieldsStillAllowsRedactedVersionDiscoveryAndWithdrawal() throws Exception {
        publish();db.update("UPDATE ds_subject_field SET denied=TRUE WHERE subject_id=?",subject);
        var recovery=admin.ok("GET",path("/draft"),null);
        assertThat(recovery.path("document").isNull()).isTrue();assertThat(recovery.path("version").asLong()).isPositive();
        reader.status(403,"GET",path("/draft"),null);reader.status(403,"GET",path("/published"),null);
        admin.ok("POST",path("/withdraw"),new Version(recovery.path("version").asLong()));
        db.update("UPDATE ds_subject_field SET denied=FALSE WHERE subject_id=?",subject);
        reader.status(404,"GET",path("/published"),null);
    }
    @Test void forbiddenPhysicalFieldInvalidatesRelease() throws Exception {
        publish();db.update("UPDATE ds_subject_field SET denied=TRUE WHERE subject_id=? AND column_name='amount'",subject);
        reader.status(409,"GET",path("/published"),null);
    }
    @Test void changedPhysicalTypeInvalidatesRelease() throws Exception {
        publish();db.update("UPDATE ds_column SET type_name='VARCHAR' WHERE source_id=? AND column_name='amount'",source);
        reader.status(409,"GET",path("/published"),null);
    }
}
