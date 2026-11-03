package com.datalens.identity;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
/** Real HTTP cookies + Spring Security + Redis sessions + MySQL; disposable CI services only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"server.servlet.session.cookie.secure=false", "server.address=127.0.0.1"})
class IdentityIT {
    private static final String PASSWORD="Integration-only-Pass-42";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    String admin, user;
    long adminId,userId;
    @BeforeEach void seed() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        admin="admin_"+suffix;user="user_"+suffix;
        adminId=create(admin,"SUPER_ADMIN");userId=create(user,"USER");
    }
    long create(String name,String role) {
        jdbc.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,?,?)",name,encoder.encode(PASSWORD),role);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    class Browser {
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;
        HttpResponse<String> send(String method,String path,String body,boolean token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Accept","application/json");
            if(token && csrf!=null) request.header("X-CSRF-TOKEN",csrf);
            if(body!=null) request.header("Content-Type","application/json");
            request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        void token() throws Exception {
            var response=send("GET","/api/v1/auth/csrf",null,false);assertThat(response.statusCode()).isEqualTo(200);
            csrf=json.readTree(response.body()).path("data").path("token").asString();
        }
        HttpResponse<String> login(String username,String password) throws Exception {
            token();var response=send("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",username,"password",password)),true);
            if(response.statusCode()==200) token();return response;
        }
        HttpResponse<String> me() throws Exception {return send("GET","/api/v1/auth/me",null,false);}
    }
    @Test void loginRotatesSessionAndCsrfLogoutRevokesCookie() throws Exception {
        Browser b=new Browser();b.token();
        String initial=b.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("DATALENS_SESSION")).findFirst().orElseThrow().getValue();
        var login=b.send("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",user,"password",PASSWORD)),true);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).doesNotContain("password", "authVersion", "$2");
        String authenticated=b.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("DATALENS_SESSION")).findFirst().orElseThrow().getValue();
        assertThat(authenticated).isNotEqualTo(initial);
        assertThat(login.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(b.send("POST","/api/v1/auth/logout","{}",true).statusCode()).isEqualTo(403); // old CSRF
        b.token();assertThat(b.me().statusCode()).isEqualTo(200);
        assertThat(b.send("POST","/api/v1/auth/logout","{}",true).statusCode()).isEqualTo(200);
        assertThat(b.me().statusCode()).isEqualTo(401);
        var replay=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/me"))
            .header("Cookie","DATALENS_SESSION="+authenticated).GET().build();
        assertThat(HttpClient.newHttpClient().send(replay,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    @Test void loginAndMutationsRequireCsrfAndAnonymousCannotList() throws Exception {
        Browser b=new Browser();
        assertThat(b.send("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",user,"password",PASSWORD)),false).statusCode()).isEqualTo(403);
        assertThat(b.send("GET","/api/v1/admin/users",null,false).statusCode()).isEqualTo(401);
        assertThat(b.login(user,PASSWORD).statusCode()).isEqualTo(200);
        assertThat(b.send("POST","/api/v1/auth/password","{}",false).statusCode()).isEqualTo(403);
    }
    @Test void ordinaryUserCannotCreateAdminOrForgeActingIdentity() throws Exception {
        Browser b=new Browser();b.login(user,PASSWORD);
        assertThat(b.send("POST","/api/v1/admin/users",json.writeValueAsString(Map.of("username","forged_"+user,"password",PASSWORD,"role","SUPER_ADMIN","userId",adminId)),true).statusCode()).isEqualTo(403);
        assertThat(b.send("GET","/api/v1/admin/users?userId="+adminId,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST","/api/v1/auth/password",json.writeValueAsString(Map.of("currentPassword",PASSWORD,"newPassword","Replacement-Pass-42","userId",adminId)),true).statusCode()).isEqualTo(200);
        assertThat(b.me().statusCode()).isEqualTo(401);
        assertThat(new Browser().login(admin,PASSWORD).statusCode()).isEqualTo(200);
        assertThat(new Browser().login(user,"Replacement-Pass-42").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM sys_audit_event WHERE resource_id=? AND action='CHANGE_PASSWORD' ORDER BY id DESC LIMIT 1",Long.class,Long.toString(userId))).isEqualTo(userId);
    }
    @Test void disableAndReenableNeverRestoreOldSessions() throws Exception {
        Browser a=new Browser(),u=new Browser();a.login(admin,PASSWORD);u.login(user,PASSWORD);
        assertThat(a.send("PUT","/api/v1/admin/users/"+userId+"/enabled",json.writeValueAsString(Map.of("enabled",false)),true).statusCode()).isEqualTo(200);
        assertThat(u.me().statusCode()).isEqualTo(401);
        assertThat(new Browser().login(user,PASSWORD).statusCode()).isEqualTo(401);
        assertThat(a.send("PUT","/api/v1/admin/users/"+userId+"/enabled",json.writeValueAsString(Map.of("enabled",true)),true).statusCode()).isEqualTo(200);
        assertThat(u.me().statusCode()).isEqualTo(401);
        assertThat(new Browser().login(user,PASSWORD).statusCode()).isEqualTo(200);
    }
    @Test void passwordResetRevokesAllSessionsAndStoresOnlyHash() throws Exception {
        Browser a=new Browser(),u1=new Browser(),u2=new Browser();a.login(admin,PASSWORD);u1.login(user,PASSWORD);u2.login(user,PASSWORD);
        assertThat(a.send("POST","/api/v1/admin/users/"+userId+"/password",json.writeValueAsString(Map.of("newPassword","Replacement-Pass-42")),true).statusCode()).isEqualTo(200);
        assertThat(u1.me().statusCode()).isEqualTo(401);assertThat(u2.me().statusCode()).isEqualTo(401);
        assertThat(new Browser().login(user,PASSWORD).statusCode()).isEqualTo(401);
        assertThat(new Browser().login(user,"Replacement-Pass-42").statusCode()).isEqualTo(200);
        String hash=jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?",String.class,userId);
        assertThat(hash).startsWith("$2").doesNotContain("Replacement");assertThat(encoder.matches("Replacement-Pass-42",hash)).isTrue();
    }
    @Test void adminCreatesListsAndCannotDisableSelf() throws Exception {
        Browser a=new Browser();a.login(admin,PASSWORD);
        String name="created_"+user;
        var created=a.send("POST","/api/v1/admin/users",json.writeValueAsString(Map.of("username",name,"password",PASSWORD,"role","USER")),true);
        assertThat(created.statusCode()).isEqualTo(200);assertThat(created.body()).doesNotContain("passwordHash",PASSWORD);
        assertThat(a.send("POST","/api/v1/admin/users",json.writeValueAsString(Map.of("username",name.toUpperCase(Locale.ROOT),"password",PASSWORD,"role","USER")),true).statusCode()).isEqualTo(409);
        var list=a.send("GET","/api/v1/admin/users?page=1&size=2",null,false);
        assertThat(list.statusCode()).isEqualTo(200);assertThat(json.readTree(list.body()).path("data").path("items").size()).isEqualTo(2);
        assertThat(list.body()).doesNotContain("password_hash",PASSWORD,"authVersion");
        assertThat(a.send("PUT","/api/v1/admin/users/"+adminId+"/enabled",json.writeValueAsString(Map.of("enabled",false)),true).statusCode()).isEqualTo(409);
        assertThat(a.send("GET","/api/v1/admin/users?page=0",null,false).statusCode()).isEqualTo(400);
        assertThat(a.send("GET","/api/v1/not-implemented",null,false).statusCode()).isEqualTo(403);
    }
    @Test void repeatedFailuresArePersistentlyRateLimited() throws Exception {
        Browser b=new Browser();
        for(int i=0;i<5;i++) assertThat(b.login(user,"Wrong-password-42").statusCode()).isEqualTo(401);
        assertThat(b.login(user,PASSWORD).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM sys_user WHERE id=?",Integer.class,userId)).isEqualTo(5);
    }
}
