package com.datalens.datasource;

import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.datalens.datasource.SourceModels.*;

/** Real HTTP/security/MySQL/Redis; analysis connection/metadata are explicitly simulated, NOT StarRocks acceptance. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.servlet.session.cookie.secure=false","server.address=127.0.0.1",
    "DATALENS_SOURCE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "DATALENS_SOURCE_ALLOWED_ENDPOINTS=10.20.30.40:9030,10.20.30.41:9030"})
class SourceIT {
    static final String PASSWORD="Integration-only-Pass-42";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @MockitoBean SourcePools pools;
    @MockitoBean MetadataCollector collector;
    Browser admin,user;long userId;String username,adminName;
    @BeforeEach void seed() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);username="source_user_"+suffix;adminName="source_admin_"+suffix;
        for(String name:List.of(username,adminName)) db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,?,?)",name,encoder.encode(PASSWORD),name.equals(username)?"USER":"SUPER_ADMIN");
        userId=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
        admin=new Browser();admin.login(adminName);user=new Browser();user.login(username);
        when(pools.open(any())).thenReturn(mock(Connection.class));
    }
    class Browser {
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();String csrf;
        HttpResponse<String> send(String method,String path,Object body,boolean token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Accept","application/json");
            if(token && csrf!=null) request.header("X-CSRF-TOKEN",csrf);
            if(body!=null) request.header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        void token() throws Exception {csrf=json.readTree(send("GET","/api/v1/auth/csrf",null,false).body()).path("data").path("token").asString();}
        void login(String name) throws Exception {token();assertThat(send("POST","/api/v1/auth/login",Map.of("username",name,"password",PASSWORD),true).statusCode()).isEqualTo(200);token();}
    }
    SourceInput input(String host,String password,boolean enabled) {return new SourceInput("Sales",host,9030,"default_catalog","sales","reader",password,enabled,List.of("orders"));}
    String create() throws Exception {
        var response=admin.send("POST","/api/v1/admin/sources",input("10.20.30.40","source-password-only",true),true);
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).doesNotContain("source-password-only","credential","v1.");
        return json.readTree(response.body()).path("data").path("id").asString();
    }
    void harvest(String id) throws Exception {
        when(collector.collect(any(),any())).thenReturn(List.of(new ColumnView(id,"orders","id","BIGINT",1,false),new ColumnView(id,"orders","secret","VARCHAR",2,true)));
        assertThat(admin.send("POST","/api/v1/admin/sources/"+id+"/harvest",Map.of(),true).statusCode()).isEqualTo(200);
    }
    String subject(String source) throws Exception {
        var in=new SubjectInput("Sales",source,List.of(new Field("orders","id"),new Field("orders","secret")),List.of(new Field("orders","secret")),List.of(userId),"NONE");
        var response=admin.send("POST","/api/v1/admin/subjects",in,true);assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("id").asString();
    }
    @Test void administrationRequiresRoleAndCsrfAndEndpointAllowlist() throws Exception {
        var body=input("10.20.30.40","test-secret",true);
        assertThat(new Browser().send("GET","/api/v1/admin/sources",null,false).statusCode()).isEqualTo(401);
        assertThat(user.send("GET","/api/v1/admin/sources",null,false).statusCode()).isEqualTo(403);
        assertThat(user.send("POST","/api/v1/admin/sources",body,true).statusCode()).isEqualTo(403);
        assertThat(admin.send("POST","/api/v1/admin/sources",body,false).statusCode()).isEqualTo(403);
        assertThat(admin.send("POST","/api/v1/admin/sources",input("127.0.0.1","test-secret",true),true).statusCode()).isEqualTo(400);
        verify(pools,never()).open(any());
    }
    @Test void encryptedAtRestAndBlankPasswordPreservesSecretAndClosesPool() throws Exception {
        String id=create();String encrypted=db.queryForObject("SELECT credential FROM ds_source WHERE id=?",String.class,id);
        assertThat(encrypted).startsWith("v1.").doesNotContain("source-password-only");
        var changed=admin.send("PUT","/api/v1/admin/sources/"+id,input("10.20.30.40","",false),true);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT credential FROM ds_source WHERE id=?",String.class,id)).isEqualTo(encrypted);
        verify(pools).invalidate(id,2L);
        assertThat(user.send("GET","/api/v1/admin/sources/"+id+"/metadata",null,false).statusCode()).isEqualTo(403);
    }
    @Test void metadataScopeAndDeniedFieldsAndMembershipRevocation() throws Exception {
        String id=create();harvest(id);String subject=subject(id);
        var visible=user.send("GET","/api/v1/subjects/"+subject+"/metadata",null,false);
        assertThat(visible.statusCode()).isEqualTo(200);assertThat(visible.body()).contains("BIGINT").doesNotContain("secret","VARCHAR");
        var subjects=user.send("GET","/api/v1/subjects",null,false);assertThat(subjects.body()).doesNotContain("secret");
        db.update("DELETE FROM ds_subject_user WHERE subject_id=?",subject);
        assertThat(user.send("GET","/api/v1/subjects/"+subject+"/metadata",null,false).statusCode()).isEqualTo(403);
        assertThat(admin.send("POST","/api/v1/admin/subjects",new SubjectInput("Bad",id,List.of(new Field("unauthorized","id")),List.of(),List.of(userId),"NONE"),true).statusCode()).isEqualTo(400);
    }
    @Test void failedHarvestPreservesGoodSnapshotAndSanitizesDriverErrors() throws Exception {
        String id=create();harvest(id);
        when(collector.collect(any(),any())).thenThrow(new SQLException("credential=secret jdbc:untrusted"));
        var response=admin.send("POST","/api/v1/admin/sources/"+id+"/harvest",Map.of(),true);
        assertThat(response.statusCode()).isEqualTo(502);assertThat(response.body()).doesNotContain("jdbc:","credential=secret");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ds_column WHERE source_id=?",Long.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT harvest_status FROM ds_source WHERE id=?",String.class,id)).isEqualTo("FAILED");
    }
    @Test void changingConnectionRevokesOldSubjectAccessAndClearsSnapshot() throws Exception {
        String id=create();harvest(id);String subject=subject(id);
        assertThat(admin.send("PUT","/api/v1/admin/sources/"+id,input("10.20.30.41","",true),true).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ds_column WHERE source_id=?",Long.class,id)).isZero();
        harvest(id);
        assertThat(user.send("GET","/api/v1/subjects/"+subject+"/metadata",null,false).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ds_subject_field WHERE subject_id=?",Long.class,subject)).isZero();
    }
    @Test void crashedHarvestLeaseCanRecoverButActiveLeaseCannotOverlap() throws Exception {
        String id=create();
        db.update("UPDATE ds_source SET harvest_status='RUNNING',harvest_started_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        assertThat(admin.send("POST","/api/v1/admin/sources/"+id+"/harvest",Map.of(),true).statusCode()).isEqualTo(409);
        db.update("UPDATE ds_source SET harvest_started_at=CURRENT_TIMESTAMP - INTERVAL 3 MINUTE WHERE id=?",id);
        harvest(id);
    }
    @Test void revokedAdminCannotPublishHarvestResults() throws Exception {
        String id=create();harvest(id);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(collector.collect(any(),any())).thenAnswer(call->{
            entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new SQLException("timeout");
            return List.of(new ColumnView(id,"orders","new_column","BIGINT",1,false));
        });
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(()->admin.send("POST","/api/v1/admin/sources/"+id+"/harvest",Map.of(),true));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                db.update("UPDATE sys_user SET enabled=FALSE,auth_version=auth_version+1 WHERE username=?",adminName);
            } finally {release.countDown();}
            assertThat(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        }
        assertThat(db.queryForList("SELECT column_name FROM ds_column WHERE source_id=? ORDER BY column_name",String.class,id)).containsExactly("id","secret");
    }
    @Test void staleGenerationCannotPublishAfterConfigurationChange() throws Exception {
        String id=create();harvest(id);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(collector.collect(any(),any())).thenAnswer(call->{
            entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new SQLException("timeout");
            return List.of(new ColumnView(id,"orders","new_column","BIGINT",1,false));
        });
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(()->admin.send("POST","/api/v1/admin/sources/"+id+"/harvest",Map.of(),true));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                db.update("UPDATE ds_source SET version=version+1,harvest_status='NEVER' WHERE id=?",id);
            } finally {release.countDown();}
            assertThat(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(db.queryForObject("SELECT harvest_status FROM ds_source WHERE id=?",String.class,id)).isEqualTo("NEVER");
        assertThat(db.queryForList("SELECT column_name FROM ds_column WHERE source_id=? ORDER BY column_name",String.class,id)).containsExactly("id","secret");
    }

}
