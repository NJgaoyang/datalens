package com.datalens.chart;

import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import com.datalens.datasource.SourcePools;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP, CSRF, Redis sessions and MySQL policies; only analytics JDBC is mocked.
 * This suite does not establish real StarRocks compatibility or database cancellation. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.servlet.session.cookie.secure=false","server.address=127.0.0.1"})
class ChartSharingIT {
    private static final String PASSWORD="Sharing-integration-only-42";
    private static final String SQL="SELECT id FROM orders";
    private static final String EXACT="9007199254740993";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @MockitoBean SourcePools analytics;
    final List<Long> users=new ArrayList<>();
    final List<String> charts=new ArrayList<>();
    Browser owner,viewer,admin,other;
    String source,subject;
    volatile CountDownLatch entered,release;

    @BeforeEach void seed() throws Exception {
        owner=account("USER");viewer=account("USER");admin=account("SUPER_ADMIN");other=account("USER");
        source=UUID.randomUUID().toString();subject=UUID.randomUUID().toString();
        db.update("INSERT INTO ds_source(id,name,host,port,catalog_name,database_name,username,credential,enabled) VALUES(?,'Sharing test','10.20.30.40',9030,'default_catalog','sales','reader','not-used',TRUE)",source);
        db.update("INSERT INTO ds_column(source_id,table_name,column_name,type_name,ordinal_position,nullable) VALUES(?,'orders','id','BIGINT',1,FALSE)",source);
        db.update("INSERT INTO ds_subject(id,name,source_id,share_scope) VALUES(?,'Sharing test',?,'ALL_AUTHENTICATED')",subject,source);
        db.update("INSERT INTO ds_subject_field(subject_id,table_name,column_name,denied) VALUES(?,'orders','id',FALSE)",subject);
        grant(owner.id);
        // The pool API deliberately hides its StoredSource argument outside the datasource module.
        var open=Arrays.stream(SourcePools.class.getMethods()).filter(m->m.getName().equals("open")).findFirst().orElseThrow();
        when((Connection)open.invoke(analytics,new Object[]{any()})).thenAnswer(invocation->connection());
    }
    Browser account(String role) throws Exception {
        String name="share_"+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,?,?)",name,passwords.encode(PASSWORD),role);
        long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);users.add(id);
        Browser browser=new Browser(id);browser.token();
        browser.ok("POST","/api/v1/auth/login",Map.of("username",name,"password",PASSWORD));browser.token();return browser;
    }
    void grant(long user){db.update("INSERT INTO ds_subject_user(subject_id,user_id) VALUES(?,?)",subject,user);}
    Connection connection() throws Exception {
        Connection c=mock(Connection.class);Statement setup=mock(Statement.class);PreparedStatement query=mock(PreparedStatement.class);
        ResultSet rows=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(c.createStatement()).thenReturn(setup);when(c.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenAnswer(invocation->{
            CountDownLatch gate=release,started=entered;
            if(gate!=null){started.countDown();if(!gate.await(8,TimeUnit.SECONDS))throw new AssertionError("Analytics gate timed out");}
            return rows;
        });
        when(rows.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");when(metadata.getColumnTypeName(1)).thenReturn("BIGINT");
        when(rows.next()).thenReturn(true,false);when(rows.getString(1)).thenReturn(EXACT);return c;
    }
    @AfterEach void cleanup() throws Exception {
        if(release!=null)release.countDown();
        if(source!=null) {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status IN ('QUEUED','RUNNING','CANCELLING')",Long.class,source)>0&&System.nanoTime()<until)Thread.sleep(20);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status IN ('QUEUED','RUNNING','CANCELLING')",Long.class,source)).isZero();
        }
        for(String chart:charts){db.update("DELETE FROM chart_export_job WHERE chart_id=?",chart);db.update("DELETE FROM chart_export_grant WHERE chart_id=?",chart);db.update("DELETE FROM chart_management_event WHERE chart_id=?",chart);db.update("DELETE FROM chart_share WHERE chart_id=?",chart);db.update("DELETE FROM chart_run WHERE chart_id=?",chart);db.update("DELETE FROM chart_revision WHERE chart_id=?",chart);db.update("DELETE FROM chart WHERE id=?",chart);}
        for(long user:users){db.update("DELETE FROM chart_preview WHERE actor_id=?",user);db.update("DELETE FROM sys_audit_event WHERE actor_id=?",user);}
        db.update("DELETE FROM query_task WHERE source_id=?",source);
        db.update("DELETE FROM ds_subject_user WHERE subject_id=?",subject);db.update("DELETE FROM ds_subject_field WHERE subject_id=?",subject);db.update("DELETE FROM ds_subject WHERE id=?",subject);
        db.update("DELETE FROM ds_column WHERE source_id=?",source);db.update("DELETE FROM ds_source WHERE id=?",source);
        for(long user:users)db.update("DELETE FROM sys_user WHERE id=?",user);
    }
    class Browser {
        final long id;
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String csrf;
        Browser(long id){this.id=id;}
        HttpResponse<String> send(String method,String path,Object body,boolean token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
            if(token&&csrf!=null)request.header("X-CSRF-TOKEN",csrf);
            if(body!=null)request.header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method,String path,Object body) throws Exception {
            var response=send(method,path,body,true);assertThat(response.statusCode()).withFailMessage("%s %s: %s",method,path,response.body()).isEqualTo(200);
            return json.readTree(response.body()).path("data");
        }
        void denied(String method,String path,Object body) throws Exception {assertThat(send(method,path,body,true).statusCode()).as("%s %s",method,path).isEqualTo(403);}
        void token() throws Exception {csrf=ok("GET","/api/v1/auth/csrf",null).path("token").asString();}
    }
    Map<String,Object> definition(){return Map.of("subjectId",subject,"sql",SQL,"parameters",List.of(),"timeRule",Map.of("kind","NONE"),"metricDescription","Exact id","spec",Map.of("type","TABLE","measures",List.of()));}
    JsonNode preview(Browser who) throws Exception {
        var p=who.ok("POST","/api/v1/charts/previews",Map.of("definition",definition(),"idempotencyKey",UUID.randomUUID().toString()));
        terminal(p.path("taskId").asString(),"SUCCEEDED");return p;
    }
    JsonNode save() throws Exception {
        var p=preview(owner);var chart=owner.ok("POST","/api/v1/charts",Map.of("name","Sales","previewId",p.path("id").asString()));charts.add(chart.path("id").asString());return chart;
    }
    String path(JsonNode c){return "/api/v1/charts/"+c.path("id").asString();}
    JsonNode share(Browser who,JsonNode c,String visibility,List<Long> recipients) throws Exception {
        return who.ok("PUT",path(c)+"/sharing",Map.of("visibility",visibility,"userIds",recipients,"expectedVersion",c.path("version").asLong()));
    }
    String refresh(Browser who,JsonNode c) throws Exception {return who.ok("POST",path(c)+"/refreshes",Map.of("idempotencyKey",UUID.randomUUID().toString())).path("id").asString();}
    void terminal(String task,String expected) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);String state;
        do{state=db.queryForObject("SELECT status FROM query_task WHERE id=?",String.class,task);if(!Set.of("QUEUED","RUNNING","CANCELLING").contains(state))break;Thread.sleep(20);}while(System.nanoTime()<until);
        assertThat(state).as("Task %s",task).isEqualTo(expected);
    }
    void inaccessible(Browser who,JsonNode c,String task) throws Exception {
        who.denied("GET","/api/v1/query-tasks/"+task,null);
        who.denied("GET","/api/v1/query-tasks/"+task+"/result",null);
        who.denied("POST","/api/v1/query-tasks/"+task+"/cancel",Map.of());
        who.denied("GET",path(c)+"/refreshes/"+task+"/result",null);
    }
    @Test void specificSharingGrantsExactPublishedResultsButNeverFreeSqlAndRevocationClosesEveryTaskRoute() throws Exception {
        var chart=save();viewer.denied("GET",path(chart),null);
        chart=share(owner,chart,"SPECIFIC",List.of(viewer.id));
        assertThat(viewer.ok("GET","/api/v1/charts?scope=shared",null).path("items").toString()).contains(chart.path("id").asString());
        var detail=viewer.ok("GET",path(chart),null);assertThat(detail.path("canManage").asBoolean()).isFalse();
        assertThat(detail.path("definition").path("sql").asString()).isEmpty();
        assertThat(detail.path("definition").path("subjectId").asString()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ds_subject_user WHERE subject_id=? AND user_id=?",Long.class,subject,viewer.id)).isZero();
        viewer.denied("POST","/api/v1/query-tasks",Map.of("subjectId",subject,"sql",SQL,"parameters",List.of(),"idempotencyKey","free-sql-denied"));
        String task=refresh(viewer,chart);terminal(task,"SUCCEEDED");
        var result=viewer.ok("GET",path(chart)+"/refreshes/"+task+"/result",null);
        assertThat(result.path("rows").get(0).get(0).asString()).isEqualTo(EXACT);
        assertThat(result).isEqualTo(viewer.ok("GET","/api/v1/query-tasks/"+task+"/result",null));
        assertThat(db.queryForObject("SELECT actor_id FROM query_task WHERE id=?",Long.class,task)).isEqualTo(viewer.id);
        assertThat(db.queryForObject("SELECT purpose FROM query_task WHERE id=?",String.class,task)).isEqualTo("CHART");
        other.denied("GET",path(chart),null);other.denied("GET","/api/v1/query-tasks/"+task,null);
        viewer.denied("PUT",path(chart)+"/sharing",Map.of("visibility","ALL","userIds",List.of(),"expectedVersion",chart.path("version").asLong()));
        chart=share(owner,chart,"PRIVATE",List.of());inaccessible(viewer,chart,task);
        assertThat(viewer.ok("GET","/api/v1/charts?scope=shared",null).path("items").toString()).doesNotContain(chart.path("id").asString());
    }
    @Test void allSharingRequiresAuthenticationAndMutationsRequireCsrf() throws Exception {
        var chart=save();var body=Map.of("visibility","ALL","userIds",List.of(),"expectedVersion",chart.path("version").asLong());
        assertThat(owner.send("PUT",path(chart)+"/sharing",body,false).statusCode()).isEqualTo(403);
        chart=share(owner,chart,"ALL",List.of());viewer.ok("GET",path(chart),null);other.ok("GET",path(chart),null);
        assertThat(new Browser(0).send("GET",path(chart),null,false).statusCode()).isEqualTo(401);
        assertThat(other.ok("GET","/api/v1/charts?scope=all",null).path("items").toString()).contains(chart.path("id").asString());
        chart=share(owner,chart,"PRIVATE",List.of());other.denied("GET",path(chart),null);
    }
    @Test void administratorPublicationPreservesOwnerUsesActualAuditActorAndClearsSharing() throws Exception {
        var chart=share(owner,save(),"SPECIFIC",List.of(viewer.id));var p=preview(admin);
        var edited=admin.ok("POST",path(chart)+"/revisions",Map.of("name","Admin edited","previewId",p.path("id").asString(),"expectedVersion",chart.path("version").asLong()));
        assertThat(edited.path("ownerId").asLong()).isEqualTo(owner.id);assertThat(edited.path("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(db.queryForObject("SELECT actor_id FROM sys_audit_event WHERE resource_id=? AND action='CHART_PUBLISH'",Long.class,chart.path("id").asString())).isEqualTo(admin.id);
        assertThat(db.queryForObject("SELECT creator_id FROM chart_revision WHERE id=?",Long.class,edited.path("revisionId").asString())).isEqualTo(admin.id);
        viewer.denied("GET",path(edited),null);
    }
    @Test void softDeleteAndRestoreKeepHistoryButRestorePrivate() throws Exception {
        var chart=share(owner,save(),"ALL",List.of());String task=refresh(viewer,chart);terminal(task,"SUCCEEDED");
        viewer.denied("DELETE",path(chart)+"?expectedVersion="+chart.path("version").asLong(),null);
        var deleted=owner.ok("DELETE",path(chart)+"?expectedVersion="+chart.path("version").asLong(),null);
        assertThat(deleted.path("deleted").asBoolean()).isTrue();inaccessible(viewer,chart,task);
        assertThat(owner.ok("GET","/api/v1/charts?scope=trash",null).path("items").toString()).contains(chart.path("id").asString());
        var restored=admin.ok("POST",path(chart)+"/restore",Map.of("expectedVersion",deleted.path("version").asLong()));
        assertThat(restored.path("deleted").asBoolean()).isFalse();assertThat(restored.path("visibility").asString()).isEqualTo("PRIVATE");
        owner.ok("GET",path(chart),null);viewer.denied("GET",path(chart),null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart_revision WHERE chart_id=?",Long.class,chart.path("id").asString())).isEqualTo(2);
        assertThat(restored.path("revisionId").asString()).isNotEqualTo(chart.path("revisionId").asString());
        restored=share(owner,restored,"ALL",List.of());
        viewer.ok("GET",path(restored),null);inaccessible(viewer,restored,task);
        String newTask=refresh(viewer,restored);terminal(newTask,"SUCCEEDED");
        assertThat(viewer.ok("GET",path(restored)+"/refreshes/"+newTask+"/result",null).path("rows").get(0).get(0).asString()).isEqualTo(EXACT);
    }
    @Test void transferIsAdminOnlyRequiresTargetQueryPermissionAndResetsPrivate() throws Exception {
        var chart=share(owner,save(),"SPECIFIC",List.of(viewer.id));
        var transfer=Map.of("ownerId",viewer.id,"expectedVersion",chart.path("version").asLong());
        owner.denied("POST",path(chart)+"/owner",transfer);admin.denied("POST",path(chart)+"/owner",transfer);
        grant(viewer.id);var moved=admin.ok("POST",path(chart)+"/owner",transfer);
        assertThat(moved.path("ownerId").asLong()).isEqualTo(viewer.id);assertThat(moved.path("visibility").asString()).isEqualTo("PRIVATE");
        owner.denied("GET",path(chart),null);viewer.ok("GET",path(chart),null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart_share WHERE chart_id=?",Long.class,chart.path("id").asString())).isZero();
        assertThat(db.queryForObject("SELECT actor_id FROM sys_audit_event WHERE resource_id=? AND action='CHART_TRANSFER'",Long.class,chart.path("id").asString())).isEqualTo(admin.id);
    }
    @Test void revokingSharingWhileViewerQueryRunsPreventsPersistenceAndEveryRead() throws Exception {
        var chart=share(owner,save(),"SPECIFIC",List.of(viewer.id));entered=new CountDownLatch(1);release=new CountDownLatch(1);
        String task=refresh(viewer,chart);
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            chart=share(owner,chart,"PRIVATE",List.of());
            inaccessible(viewer,chart,task);
        }finally{release.countDown();}
        terminal(task,"FAILED");
        assertThat(db.queryForObject("SELECT error_code FROM query_task WHERE id=?",String.class,task)).isEqualTo("QUERY_ACCESS_REVOKED");
        assertThat(db.queryForObject("SELECT result_json FROM query_task WHERE id=?",String.class,task)).isNull();
        inaccessible(viewer,chart,task);
    }
    @Test void disablingViewerDuringExecutionInvalidatesSessionAndNeverPersistsResults() throws Exception {
        var chart=share(owner,save(),"ALL",List.of());entered=new CountDownLatch(1);release=new CountDownLatch(1);
        String task=refresh(viewer,chart);
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            admin.ok("PUT","/api/v1/admin/users/"+viewer.id+"/enabled",Map.of("enabled",false));
        }finally{release.countDown();}
        terminal(task,"FAILED");
        assertThat(db.queryForObject("SELECT result_json FROM query_task WHERE id=?",String.class,task)).isNull();
        for(String route:List.of(path(chart),"/api/v1/query-tasks/"+task,"/api/v1/query-tasks/"+task+"/result",path(chart)+"/refreshes/"+task+"/result"))
            assertThat(viewer.send("GET",route,null,true).statusCode()).as(route).isEqualTo(401);
        assertThat(viewer.send("POST","/api/v1/query-tasks/"+task+"/cancel",Map.of(),true).statusCode()).isIn(401,403);
        admin.ok("PUT","/api/v1/admin/users/"+viewer.id+"/enabled",Map.of("enabled",true));
        assertThat(viewer.send("GET",path(chart),null,true).statusCode()).isEqualTo(401);
    }
    @Test void staleManagementVersionsCannotOverwriteSharingDeleteOrTransfer() throws Exception {
        var original=save();var chart=share(owner,original,"SPECIFIC",List.of(viewer.id));
        var stale=owner.send("PUT",path(chart)+"/sharing",Map.of("visibility","ALL","userIds",List.of(),"expectedVersion",original.path("version").asLong()),true);
        assertThat(stale.statusCode()).isEqualTo(409);assertThat(stale.body()).contains("CHART_VERSION_CONFLICT");
        assertThat(owner.send("DELETE",path(chart)+"?expectedVersion="+original.path("version").asLong(),null,true).statusCode()).isEqualTo(409);
        grant(viewer.id);
        assertThat(admin.send("POST",path(chart)+"/owner",Map.of("ownerId",viewer.id,"expectedVersion",original.path("version").asLong()),true).statusCode()).isEqualTo(409);
        var current=owner.ok("GET",path(chart),null);
        assertThat(current.path("version").asLong()).isEqualTo(chart.path("version").asLong());
        assertThat(current.path("ownerId").asLong()).isEqualTo(owner.id);
        assertThat(current.path("visibility").asString()).isEqualTo("SPECIFIC");
        assertThat(db.queryForList("SELECT user_id FROM chart_share WHERE chart_id=?",Long.class,chart.path("id").asString())).containsExactly(viewer.id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart_management_event WHERE chart_id=?",Long.class,chart.path("id").asString())).isEqualTo(1);
    }
    @Test void disablingOwnerWhileSharedQueryRunsPreventsResultPersistence() throws Exception {revokedDuringExecution(true);}
    @Test void revokingSubjectSharePolicyWhileQueryRunsPreventsResultPersistence() throws Exception {revokedDuringExecution(false);}
    void revokedDuringExecution(boolean disableOwner) throws Exception {
        var chart=share(owner,save(),"ALL",List.of());entered=new CountDownLatch(1);release=new CountDownLatch(1);
        String task=refresh(viewer,chart);
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            if(disableOwner)admin.ok("PUT","/api/v1/admin/users/"+owner.id+"/enabled",Map.of("enabled",false));
            else db.update("UPDATE ds_subject SET share_scope='NONE' WHERE id=?",subject);
        }finally{release.countDown();}
        terminal(task,"FAILED");
        assertThat(db.queryForObject("SELECT error_code FROM query_task WHERE id=?",String.class,task)).isEqualTo("QUERY_ACCESS_REVOKED");
        assertThat(db.queryForObject("SELECT result_json FROM query_task WHERE id=?",String.class,task)).isNull();
        inaccessible(viewer,chart,task);
        viewer.denied("POST",path(chart)+"/refreshes",Map.of("idempotencyKey",UUID.randomUUID().toString()));
        // Administrators can still revoke a stale capability after its owner/source becomes invalid.
        var privateChart=share(admin,chart,"PRIVATE",List.of());assertThat(privateChart.path("visibility").asString()).isEqualTo("PRIVATE");
    }
    String exportGrantPath(JsonNode chart){return "/api/v1/exports/charts/"+chart.path("id").asString()+"/grant";}
    void exportGrant(JsonNode chart,boolean enabled) throws Exception {admin.ok("PUT",exportGrantPath(chart),Map.of("userId",viewer.id,"expectedChartVersion",chart.path("version").asLong(),"enabled",enabled));}
    String exportReady(JsonNode chart,String task) throws Exception {
        String id=viewer.ok("POST","/api/v1/exports",Map.of("chartId",chart.path("id").asString(),"taskId",task,"idempotencyKey",UUID.randomUUID().toString())).path("id").asString();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);String state;
        do{state=viewer.ok("GET","/api/v1/exports/"+id,null).path("status").asString();if(!Set.of("QUEUED","RUNNING").contains(state))break;Thread.sleep(20);}while(System.nanoTime()<until);
        assertThat(state).isEqualTo("SUCCEEDED");return id;
    }
    @Test void controlledExportRequiresIndependentGrantAndRevocationClosesOldDownload() throws Exception {
        var chart=share(owner,save(),"SPECIFIC",List.of(viewer.id));String task=refresh(viewer,chart);terminal(task,"SUCCEEDED");
        var input=Map.of("chartId",chart.path("id").asString(),"taskId",task,"idempotencyKey",UUID.randomUUID().toString());
        viewer.denied("POST","/api/v1/exports",input);owner.denied("PUT",exportGrantPath(chart),Map.of("userId",viewer.id,"expectedChartVersion",chart.path("version").asLong(),"enabled",true));
        exportGrant(chart,true);viewer.denied("GET","/api/v1/audit-events",null);assertThat(admin.ok("GET","/api/v1/audit-events?page=1&size=20",null).path("total").asLong()).isGreaterThan(0);String id=exportReady(chart,task),url="/api/v1/exports/"+id+"/download";
        var response=viewer.send("GET",url,null,true);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains(EXACT);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        other.denied("GET",url,null);assertThat(new Browser(0).send("GET",url,null,false).statusCode()).isEqualTo(401);
        exportGrant(chart,false);viewer.denied("GET",url,null);exportGrant(chart,true);viewer.denied("GET",url,null);
        String next=exportReady(chart,task);share(owner,chart,"PRIVATE",List.of());viewer.denied("GET","/api/v1/exports/"+next+"/download",null);
    }
    @Test void exportRejectsOldSourceVersionAndSearchHidesRevokedPolicy() throws Exception {
        var chart=share(owner,save(),"ALL",List.of());String task=refresh(viewer,chart);terminal(task,"SUCCEEDED");exportGrant(chart,true);String id=exportReady(chart,task);
        db.update("UPDATE ds_source SET version=version+1 WHERE id=?",source);
        viewer.denied("GET","/api/v1/exports/"+id,null);viewer.denied("GET","/api/v1/exports/"+id+"/download",null);
        db.update("UPDATE ds_subject SET share_scope='NONE' WHERE id=?",subject);
        assertThat(viewer.ok("GET","/api/v1/charts?scope=all&search=Sales",null).path("total").asLong()).isZero();viewer.denied("GET",path(chart),null);
    }

}
