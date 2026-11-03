package com.datalens.model;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.*;
import static com.datalens.model.ModelModels.*;
import static org.assertj.core.api.Assertions.*;

/** Actual MySQL/Redis integration; never sends a provider request. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.servlet.session.cookie.secure=false","server.address=127.0.0.1",
    "DATALENS_MODEL_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "DATALENS_MODEL_ALLOWED_ENDPOINTS=https://8.8.8.8/v1/chat/completions"})
class ModelIT {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;@Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;@Autowired ModelStore store;
    final List<Long> users=new ArrayList<>();Browser admin,reader;
    @BeforeEach void seed() throws Exception {db.update("DELETE FROM model_configuration");db.update("DELETE FROM model_call");db.update("DELETE FROM model_daily_budget");admin=account("SUPER_ADMIN");reader=account("USER");}
    Browser account(String role) throws Exception {String name="model_"+UUID.randomUUID().toString().replace("-","");String password="Model-integration-only-42";db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,?,?)",name,passwords.encode(password),role);long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);users.add(id);var b=new Browser();b.token();b.ok("POST","/api/v1/auth/login",Map.of("username",name,"password",password));b.token();return b;}
    @AfterEach void clean(){db.update("DELETE FROM model_configuration");db.update("DELETE FROM model_call");db.update("DELETE FROM model_daily_budget");for(long id:users){db.update("DELETE FROM sys_audit_event WHERE actor_id=?",id);db.update("DELETE FROM sys_user WHERE id=?",id);}}
    Configure input(long v){return new Configure(v,true,"OPENAI_COMPATIBLE","https://8.8.8.8/v1/chat/completions","synthetic",10,12000,2000,1,100000,"SYNTHETIC-NOT-A-REAL-KEY");}
    @Test void startupWithoutConfigurationAndProviderIsHealthy() throws Exception {var config=admin.ok("GET","/api/v1/admin/model",null);assertThat(config.path("enabled").asBoolean()).isFalse();var response=reader.send("POST","/api/v1/model/proposals",new Generate("subject","Synthetic question"),true);assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("MODEL_NOT_CONFIGURED");}
    @Test void adminOnlyConfigAndCsrfAreEnforced() throws Exception {assertThat(reader.send("GET","/api/v1/admin/model",null,true).statusCode()).isEqualTo(403);assertThat(reader.send("PUT","/api/v1/admin/model",input(0),true).statusCode()).isEqualTo(403);assertThat(admin.send("PUT","/api/v1/admin/model",input(0),false).statusCode()).isEqualTo(403);assertThat(new Browser().send("GET","/api/v1/admin/model",null,false).statusCode()).isEqualTo(401);}
    @Test void secretsStayEncryptedAndOptimisticVersionIsEnforced() throws Exception {var saved=admin.ok("PUT","/api/v1/admin/model",input(0));assertThat(saved.path("credentialConfigured").asBoolean()).isTrue();assertThat(saved.toString()).doesNotContain("SYNTHETIC-NOT-A-REAL-KEY").doesNotContain("encryptedKey");assertThat(db.queryForObject("SELECT encrypted_key FROM model_configuration WHERE id=1",String.class)).startsWith("v1.").doesNotContain("SYNTHETIC-NOT-A-REAL-KEY");assertThat(admin.send("PUT","/api/v1/admin/model",input(0),true).statusCode()).isEqualTo(409);assertThat(admin.ok("GET","/api/v1/admin/model",null).toString()).doesNotContain("SYNTHETIC-NOT-A-REAL-KEY");}
    @Test void concurrentReservationsCannotOverspendAndAreNotRefunded() throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)){var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<8;i++)tasks.add(()->{try{store.reserve(UUID.randomUUID().toString(),users.getFirst(),"subject",1,1,"a".repeat(64),600,1000);return true;}catch(com.datalens.common.web.DomainException denied){assertThat(denied.code()).isEqualTo("MODEL_BUDGET_EXCEEDED");return false;}});long succeeded=0;for(var task:executor.invokeAll(tasks))if(task.get())succeeded++;assertThat(succeeded).isEqualTo(1);}
        assertThat(db.queryForObject("SELECT reserved_tokens FROM model_daily_budget",Long.class)).isEqualTo(600);assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call",Long.class)).isEqualTo(1);
    }
    class Browser {
        String csrf;final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> send(String method,String path,Object body,boolean token) throws Exception {var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(token&&csrf!=null)b.header("X-CSRF-TOKEN",csrf);if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return client.send(b.build(),HttpResponse.BodyHandlers.ofString());}
        JsonNode ok(String method,String path,Object body) throws Exception {var r=send(method,path,body,true);assertThat(r.statusCode()).withFailMessage("%s %s",r.statusCode(),r.body()).isEqualTo(200);return json.readTree(r.body()).path("data");}
        void token() throws Exception {csrf=ok("GET","/api/v1/auth/csrf",null).path("token").asString();}
    }
}
