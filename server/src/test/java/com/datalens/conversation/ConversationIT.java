package com.datalens.conversation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.datalens.chart.ChartModels;
import com.datalens.datasource.SourceModels.Field;
import com.datalens.datasource.SourcePools;
import com.datalens.query.QueryModels;
import com.datalens.model.ModelGateway;
import com.datalens.model.ModelModels;
import com.datalens.semantic.SemanticModels;
import static com.datalens.conversation.ConversationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real MySQL persistence, Redis sessions, HTTP authorization and CSRF.
 * ModelGateway and the analytics JDBC pool are deterministic mocks; authorization, query
 * execution orchestration, chart services and persistence are real. This is not provider or
 * StarRocks compatibility evidence. Run the integration Maven profile with disposable services.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.servlet.session.cookie.secure=false", "server.address=127.0.0.1"})
class ConversationIT {
    static final String PASSWORD = "Conversation-integration-only-42";
    static final String ROOT = "/api/v1/conversations";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ConversationStore store;
    @Autowired ConversationService service;
    @MockitoBean(enforceOverride = true) ModelGateway model;
    @MockitoBean(enforceOverride = true) SourcePools analytics;
    final AtomicInteger analyticsExecutions = new AtomicInteger();
    volatile boolean analyticsHasRows = true;
    final List<Long> users = new ArrayList<>();
    Browser admin, owner, other;
    String source, subject;
    long publicationVersion;
    String publicationDigest;

    @BeforeEach void seed() throws Exception {
        // Test services only: preserve account-specific counters and all session keys.
        redis.delete("datalens:login:global");
        admin = account("SUPER_ADMIN");
        owner = account("USER");
        other = account("USER");
        source = UUID.randomUUID().toString();
        subject = UUID.randomUUID().toString();
        db.update("INSERT INTO ds_source(id,name,host,port,catalog_name,database_name,username,credential,enabled) VALUES(?,'Conversation synthetic test','10.20.30.40',9030,'default_catalog','sales','reader','not-used',TRUE)", source);
        db.update("INSERT INTO ds_subject(id,name,source_id,share_scope) VALUES(?,'Conversation synthetic test',?,'NONE')", subject, source);
        db.update("INSERT INTO ds_column(source_id,table_name,column_name,type_name,ordinal_position,nullable) VALUES(?,'orders','amount','DECIMAL',1,TRUE)", source);
        db.update("INSERT INTO ds_column(source_id,table_name,column_name,type_name,ordinal_position,nullable) VALUES(?,'orders','created_at','DATETIME',2,FALSE)", source);
        db.update("INSERT INTO ds_subject_field(subject_id,table_name,column_name,denied) VALUES(?,'orders','amount',FALSE)", subject);
        db.update("INSERT INTO ds_subject_field(subject_id,table_name,column_name,denied) VALUES(?,'orders','created_at',FALSE)", subject);
        db.update("INSERT INTO ds_subject_user(subject_id,user_id) VALUES(?,?),(?,?)", subject, owner.id, subject, other.id);
        var draft = admin.ok("PUT", semantic("/draft"), new SemanticModels.Save(0, document()));
        var reviewed = admin.ok("POST", semantic("/review"), new SemanticModels.Review(draft.path("version").asLong(), true));
        var published = admin.ok("POST", semantic("/publish"), new SemanticModels.Version(reviewed.path("version").asLong()));
        publicationVersion = published.path("version").asLong();
        publicationDigest = published.path("digest").asString();
        when(model.generate(any(), anyString())).thenAnswer(invocation -> clarification());
        // SourcePools deliberately hides its StoredSource argument outside its own module.
        var open = Arrays.stream(SourcePools.class.getMethods()).filter(method -> method.getName().equals("open")).findFirst().orElseThrow();
        when((Connection) open.invoke(analytics, new Object[]{any()})).thenAnswer(invocation -> connection());
    }

    private SemanticModels.Document document() {
        var fields = List.of(
            new SemanticModels.BusinessField("amount", "Paid amount", List.of(), new Field("orders", "amount"), "DECIMAL", "CNY", "PUBLIC"),
            new SemanticModels.BusinessField("time", "Created time", List.of(), new Field("orders", "created_at"), "DATETIME", "time", "PUBLIC"));
        String sql = "SELECT SUM(orders.amount) AS sales FROM orders WHERE orders.created_at >= ? AND orders.created_at < ?";
        var parameters = List.of(new QueryModels.Parameter("DATE", "2026-01-01"), new QueryModels.Parameter("DATE", "2026-02-01"));
        var metric = new SemanticModels.Metric("sales", "Sales", List.of(), "Sum paid amount", "All states; synthetic fixture",
            "time", "Synthetic test owner", "CNY", "One order per row", "NULL ignored", "UTC; [start,end)", List.of("amount", "time"), sql, parameters, new SemanticModels.TimeParameters(0, 1, "UTC", "DATE"));
        var example = new SemanticModels.Example("sales_example", "What are sales?", List.of("sales"), sql, parameters, "Synthetic fixture only; not a verified business metric.");
        return new SemanticModels.Document(fields, List.of(metric), List.of(), List.of(), List.of(example));
    }

    private ModelModels.Proposal clarification() {
        var intent = new ModelModels.QueryIntent("Clarify synthetic request", true, "Which time range?", List.of(), List.of());
        var output = new ModelModels.Structured(intent, new ModelModels.QueryPlan("", 100), new ChartModels.Spec("TABLE", null, List.of()));
        return new ModelModels.Proposal(UUID.randomUUID().toString(), subject, publicationVersion, publicationDigest, output);
    }

    private ModelModels.Proposal proposal() {
        var intent = new ModelModels.QueryIntent("Synthetic approved sales", false, "", List.of("sales"), List.of());
        var output = new ModelModels.Structured(intent, new ModelModels.QueryPlan("sales", 1000), new ChartModels.Spec("TABLE", null, List.of("sales")));
        return new ModelModels.Proposal(UUID.randomUUID().toString(), subject, publicationVersion, publicationDigest, output);
    }

    private Connection connection() throws Exception {
        var connection = mock(Connection.class);
        var setup = mock(Statement.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(connection.createStatement()).thenReturn(setup);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(invocation -> { analyticsExecutions.incrementAndGet(); return rows; });
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("sales");
        when(metadata.getColumnTypeName(1)).thenReturn("DECIMAL");when(metadata.getColumnType(1)).thenReturn(java.sql.Types.DECIMAL);when(metadata.getPrecision(1)).thenReturn(38);when(metadata.getScale(1)).thenReturn(4);
        when(rows.next()).thenReturn(analyticsHasRows, false);
        when(rows.getString(1)).thenReturn("9007199254740993.125");
        return connection;
    }

    private Browser account(String role) throws Exception {
        String name = "conv_" + UUID.randomUUID().toString().replace("-", "");
        db.update("INSERT INTO sys_user(username,password_hash,role) VALUES(?,?,?)", name, passwords.encode(PASSWORD), role);
        long id = db.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
        users.add(id);
        var browser = new Browser(id);
        browser.token();
        browser.ok("POST", "/api/v1/auth/login", Map.of("username", name, "password", PASSWORD));
        browser.token();
        return browser;
    }

    @AfterEach void cleanup() throws Exception {
        redis.delete("datalens:login:global");
        if (source != null) {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status IN ('QUEUED','RUNNING','CANCELLING')", Long.class, source) > 0 && System.nanoTime() < until) Thread.sleep(20);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM query_task WHERE source_id=? AND status IN ('QUEUED','RUNNING','CANCELLING')", Long.class, source)).isZero();
        }
        for (long user : users) {
            db.update("DELETE t FROM conversation_turn t INNER JOIN conversation c ON c.id=t.conversation_id WHERE c.owner_id=?", user);
            db.update("DELETE FROM conversation WHERE owner_id=?", user);
            for (String chart : db.queryForList("SELECT id FROM chart WHERE owner_id=?", String.class, user)) {
                db.update("DELETE FROM chart_management_event WHERE chart_id=?", chart);
                db.update("DELETE FROM chart_share WHERE chart_id=?", chart);
                db.update("DELETE FROM chart_run WHERE chart_id=?", chart);
                db.update("DELETE FROM chart_revision WHERE chart_id=?", chart);
                db.update("DELETE FROM chart WHERE id=?", chart);
            }
            db.update("DELETE FROM chart_preview WHERE actor_id=?", user);
            db.update("DELETE FROM sys_audit_event WHERE actor_id=?", user);
        }
        db.update("DELETE FROM query_task WHERE source_id=?", source);
        db.update("DELETE FROM semantic_release WHERE subject_id=?", subject);
        db.update("DELETE FROM semantic_workspace WHERE subject_id=?", subject);
        db.update("DELETE FROM ds_subject_user WHERE subject_id=?", subject);
        db.update("DELETE FROM ds_subject_field WHERE subject_id=?", subject);
        db.update("DELETE FROM ds_subject WHERE id=?", subject);
        db.update("DELETE FROM ds_column WHERE source_id=?", source);
        db.update("DELETE FROM ds_source WHERE id=?", source);
        for (long user : users) db.update("DELETE FROM sys_user WHERE id=?", user);
    }

    String semantic(String suffix) { return "/api/v1/semantics/" + subject + suffix; }
    String create() throws Exception { return owner.ok("POST", ROOT, new Create(subject, "Private sales", "create-key")).path("id").asString(); }
    String turns(String id) { return ROOT + "/" + id + "/turns"; }
    void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).withFailMessage("%s", response.body()).isEqualTo(status);
        assertThat(response.body()).contains(code);
    }

    @Test void createIsDurableIdempotentAndKeysAreScopedToOwner() throws Exception {
        String id = create();
        var retry = owner.ok("POST", ROOT, new Create(subject, " Private sales ", "create-key"));
        assertThat(retry.path("id").asString()).isEqualTo(id);
        var recreatedStore = new ConversationStore(db, transactions);
        assertThat(recreatedStore.create(owner.id, new Create(subject, "Private sales", "create-key")).id()).isEqualTo(id);
        assertThat(recreatedStore.list(owner.id)).hasSize(1);
        String others = other.ok("POST", ROOT, new Create(subject, "Other title", "create-key")).path("id").asString();
        assertThat(others).isNotEqualTo(id);
        assertCode(owner.send("POST", ROOT, new Create(subject, "Changed title", "create-key"), true), 409, "CONVERSATION_CONFLICT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM conversation WHERE owner_id=?", Long.class, owner.id)).isEqualTo(1);
        verifyNoInteractions(model);
    }

    @Test void anotherUserAndSuperAdminCannotReadOrMutatePrivateConversation() throws Exception {
        String id = create();
        String turn = owner.ok("POST", turns(id), new Send("Private question", 0, "turn-key")).path("id").asString();
        for (var stranger : List.of(other, admin)) {
            assertThat(stranger.ok("GET", ROOT, null).toString()).doesNotContain(id, "Private sales", "Private question");
            stranger.status(403, "GET", ROOT + "/" + id, null);
            stranger.status(403, "GET", turns(id) + "/" + turn, null);
            stranger.status(403, "GET", turns(id) + "/" + turn + "/result", null);
            stranger.status(403, "POST", turns(id), new Send("Private question", 0, "turn-key"));
            stranger.status(403, "POST", turns(id) + "/" + turn + "/save", new Save("Stolen chart", true));
        }
        verify(model, times(1)).generate(any(), anyString());
        assertThat(store.turns(id)).hasSize(1);
    }

    @Test void authenticationAndCsrfProtectEveryMutation() throws Exception {
        String id = create();
        new Browser(0).status(401, "GET", ROOT, null);
        new Browser(0).status(401, "GET", ROOT + "/" + id, null);
        assertThat(owner.send("POST", ROOT, new Create(subject, "Another", "other-create-key"), false).statusCode()).isEqualTo(403);
        assertThat(owner.send("POST", turns(id), new Send("Sales?", 0, "turn-key"), false).statusCode()).isEqualTo(403);
        assertThat(owner.send("POST", turns(id) + "/unknown/save", new Save("Sales", true), false).statusCode()).isEqualTo(403);
        assertThat(store.turns(id)).isEmpty();
        verifyNoInteractions(model);
    }

    @Test void duplicateSendSurvivesReloadAndDoesNotRepeatModelOperation() throws Exception {
        String id = create();
        var first = owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key"));
        var retry = owner.ok("POST", turns(id), new Send(" Sales? ", 0, "turn-key"));
        assertThat(retry).isEqualTo(first);
        assertThat(retry.path("status").asString()).isEqualTo("CLARIFICATION_REQUIRED");
        assertThat(owner.ok("GET", ROOT + "/" + id, null).path("version").asLong()).isEqualTo(1);
        var recreatedStore = new ConversationStore(db, transactions);
        var durableRetry = recreatedStore.admit(owner.id, id, new Send("Sales?", 0, "turn-key"));
        assertThat(durableRetry.fresh()).isFalse();
        assertThat(durableRetry.turn().id()).isEqualTo(first.path("id").asString());
        assertThat(durableRetry.turn().modelCallId()).isNotBlank();
        assertThat(recreatedStore.turns(id)).hasSize(1);
        verify(model, times(1)).generate(any(), anyString());
    }

    @Test void changedSendContentsAndStaleNewTurnConflictWithoutAdditionalModelCall() throws Exception {
        String id = create();
        owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key"));
        assertCode(owner.send("POST", turns(id), new Send("Changed question", 0, "turn-key"), true), 409, "CONVERSATION_CONFLICT");
        assertCode(owner.send("POST", turns(id), new Send("Last year", 0, "another-turn-key"), true), 409, "CONVERSATION_CONFLICT");
        assertThat(store.get(id).version()).isEqualTo(1);
        assertThat(store.turns(id)).hasSize(1);
        verify(model, times(1)).generate(any(), anyString());
        owner.ok("POST", turns(id), new Send("Last year", 1, "another-turn-key"));
        assertThat(store.get(id).version()).isEqualTo(2);
        assertThat(store.turns(id)).extracting(ConversationStore.Stored::sequence).containsExactly(1L, 2L);
        verify(model, times(2)).generate(any(), anyString());
    }

    @Test void concurrentDuplicateAdmissionsHaveExactlyOneFreshWinner() throws Exception {
        String id = create();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<ConversationStore.Admission>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return store.admit(owner.id, id, new Send("Sales?", 0, "turn-key"));
            });
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            var admissions = new ArrayList<ConversationStore.Admission>();
            for (var future : futures) admissions.add(future.get(15, TimeUnit.SECONDS));
            assertThat(admissions.stream().filter(ConversationStore.Admission::fresh)).hasSize(1);
            assertThat(admissions.stream().map(a -> a.turn().id()).distinct()).hasSize(1);
        }
        assertThat(store.turns(id)).hasSize(1);
        assertThat(store.get(id).version()).isEqualTo(1);
        verifyNoInteractions(model);
    }

    @Test void concurrentCreateRetriesPersistExactlyOneConversation() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 6; i++) tasks.add(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return store.create(owner.id, new Create(subject, "Private sales", "create-key")).id();
            });
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            var ids = new ArrayList<String>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
            assertThat(ids.stream().distinct()).hasSize(1);
        }
        assertThat(store.list(owner.id)).hasSize(1);
    }

    @Test void retryDuringPlanningReturnsOriginalTurnWithoutAnotherModelOperation() throws Exception {
        String id = create();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(model.generate(any(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release model gate");
            return clarification();
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key")));
            try {
                assertThat(entered.await(8, TimeUnit.SECONDS)).isTrue();
                var retry = owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key"));
                assertThat(retry.path("status").asString()).isEqualTo("PLANNING");
                assertCode(owner.send("POST", turns(id), new Send("Different request", 1, "another-turn-key"), true), 409, "CONVERSATION_CONFLICT");
                assertThat(store.turns(id)).hasSize(1);
                verify(model, times(1)).generate(any(), anyString());
                release.countDown();
                var completed = first.get(10, TimeUnit.SECONDS);
                assertThat(completed.path("id").asString()).isEqualTo(retry.path("id").asString());
                assertThat(completed.path("status").asString()).isEqualTo("CLARIFICATION_REQUIRED");
            } finally {
                release.countDown();
            }
        }
    }

    @Test void recoveryInterruptsPlanningAndRetryNeverReplaysItsModelOperation() throws Exception {
        String id = create();
        var admitted = store.admit(owner.id, id, new Send("Sales?", 0, "turn-key"));
        store.plan(admitted.turn().id(), null, "persisted-call-id");
        service.recover();
        service.recover();
        var retry = owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key"));
        assertThat(retry.path("id").asString()).isEqualTo(admitted.turn().id());
        assertThat(retry.path("status").asString()).isEqualTo("INTERRUPTED");
        assertThat(retry.path("errorCode").asString()).isEqualTo("CONVERSATION_INTERRUPTED");
        assertThat(store.turn(id, admitted.turn().id()).modelCallId()).isEqualTo("persisted-call-id");
        store.clarify(admitted.turn().id(), "Late completion", "late-call");
        assertThatThrownBy(() -> store.preview(admitted.turn().id(), "late-preview", "late-task"))
            .isInstanceOfSatisfying(com.datalens.common.web.DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo("CONVERSATION_CONFLICT"));
        assertThat(store.turn(id, admitted.turn().id()).status()).isEqualTo("INTERRUPTED");
        assertThat(store.turn(id, admitted.turn().id()).taskId()).isNull();
        verifyNoInteractions(model);
        owner.ok("POST", turns(id), new Send("A fresh request", 1, "fresh-turn-key"));
        verify(model, times(1)).generate(any(), anyString());
        assertThat(store.get(id).version()).isEqualTo(2);
    }

    @Test void recoveryLeavesQueryingAndTerminalTurnsIntact() throws Exception {
        String first = create();
        var query = store.admit(owner.id, first, new Send("Sales?", 0, "turn-key"));
        store.preview(query.turn().id(), "persisted-preview", "persisted-task");
        var second = store.create(owner.id, new Create(subject, "Terminal", "terminal-create-key"));
        var terminal = store.admit(owner.id, second.id(), new Send("Sales?", 0, "turn-key"));
        store.clarify(terminal.turn().id(), "Which time range?", "call");
        service.recover();
        assertThat(store.turn(first, query.turn().id()).status()).isEqualTo("QUERYING");
        assertThat(store.turn(first, query.turn().id()).taskId()).isEqualTo("persisted-task");
        assertThat(store.turn(second.id(), terminal.turn().id()).status()).isEqualTo("CLARIFICATION_REQUIRED");
        verifyNoInteractions(model);
    }

    @Test void subjectRevocationHidesExistingConversationAndPreventsRetries() throws Exception {
        String id = create();
        String turn = owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key")).path("id").asString();
        db.update("DELETE FROM ds_subject_user WHERE subject_id=? AND user_id=?", subject, owner.id);
        assertThat(owner.ok("GET", ROOT, null).toString()).doesNotContain(id, "Private sales");
        owner.status(403, "GET", ROOT + "/" + id, null);
        owner.status(403, "GET", turns(id) + "/" + turn, null);
        owner.status(403, "POST", turns(id), new Send("Sales?", 0, "turn-key"));
        owner.status(403, "POST", ROOT, new Create(subject, "Private sales", "create-key"));
        verify(model, times(1)).generate(any(), anyString());
    }

    @Test void disabledAccountCannotUseAnExistingSession() throws Exception {
        String id = create();
        db.update("UPDATE sys_user SET enabled=FALSE WHERE id=?", owner.id);
        owner.status(401, "GET", ROOT, null);
        owner.status(401, "GET", ROOT + "/" + id, null);
        // Invalidating the authenticated session also invalidates its CSRF token.
        owner.status(403, "POST", turns(id), new Send("Sales?", 0, "turn-key"));
        owner.token();
        owner.status(401, "POST", turns(id), new Send("Sales?", 0, "turn-key"));
        verifyNoInteractions(model);
    }

    private void terminal(String task) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        String state;
        do {
            state = db.queryForObject("SELECT status FROM query_task WHERE id=?", String.class, task);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(state)) break;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        assertThat(state).as("Query task %s", task).isEqualTo("SUCCEEDED");
    }

    @Test void approvedQueryLastMonthFollowupAndConfirmedSaveReuseCanonicalServices() throws Exception {
        when(model.generate(any(), anyString())).thenAnswer(invocation -> proposal());
        String id = create();
        var first = owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key"));
        terminal(first.path("taskId").asString());
        String turn = first.path("id").asString();
        var ready = owner.ok("GET", turns(id) + "/" + turn, null);
        assertThat(ready.path("status").asString()).isEqualTo("READY");
        assertThat(ready.path("metricDescription").asString()).contains("Sum paid amount", "CNY");
        assertThat(ready.toString()).doesNotContain("SELECT", "definition_json", "modelCallId");
        var result = owner.ok("GET", turns(id) + "/" + turn + "/result", null);
        assertThat(result.path("rows").get(0).get(0).asString()).isEqualTo("9007199254740993.125");
        owner.status(400, "POST", turns(id) + "/" + turn + "/save", new Save("Sales", false));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart WHERE owner_id=?", Long.class, owner.id)).isZero();

        var followup = owner.ok("POST", turns(id), new Send("改成上个月", 1, "last-month-key"));
        terminal(followup.path("taskId").asString());
        String followupTurn = followup.path("id").asString();
        var lastMonth = owner.ok("GET", turns(id) + "/" + followupTurn, null);
        assertThat(lastMonth.path("status").asString()).isEqualTo("READY");
        assertThat(lastMonth.path("timeRule").path("kind").asString()).isEqualTo("LAST_MONTH");
        assertThat(lastMonth.path("timeRule").path("timeZone").asString()).isEqualTo("UTC");
        assertThat(lastMonth.path("metricDescription")).isEqualTo(ready.path("metricDescription"));
        var duplicate = owner.ok("POST", turns(id), new Send("改成上个月", 1, "last-month-key"));
        assertThat(duplicate.path("id").asString()).isEqualTo(followupTurn);
        assertThat(analyticsExecutions).hasValue(2);
        verify(model, times(1)).generate(any(), anyString());

        String savePath = turns(id) + "/" + followupTurn + "/save";
        var saved = owner.ok("POST", savePath, new Save("Last month sales", true));
        String chart = saved.path("id").asString();
        assertThat(owner.ok("POST", savePath, new Save("Last month sales", true)).path("id").asString()).isEqualTo(chart);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart WHERE owner_id=?", Long.class, owner.id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT visibility FROM chart WHERE id=?", String.class, chart)).isEqualTo("PRIVATE");
        assertThat(store.turn(id, followupTurn).chartId()).isEqualTo(chart);
        var definition = owner.ok("GET", "/api/v1/charts/" + chart, null).path("definition");
        assertThat(definition.path("semanticBinding").path("publicationVersion").asLong()).isEqualTo(publicationVersion);
        assertThat(definition.path("semanticBinding").path("digest").asString()).isEqualTo(publicationDigest);
        assertThat(definition.path("semanticBinding").path("metricId").asString()).isEqualTo("sales");
        assertThat(definition.path("timeRule").path("kind").asString()).isEqualTo("LAST_MONTH");
        assertThat(definition.path("sql").asString()).isEqualTo(document().metrics().getFirst().sql());
        other.status(403, "GET", "/api/v1/charts/" + chart, null);
        other.status(403, "GET", turns(id) + "/" + followupTurn + "/result", null);
        var refreshed = owner.ok("POST", "/api/v1/charts/" + chart + "/refreshes", new ChartModels.Refresh("refresh-key"));
        terminal(refreshed.path("id").asString());
        assertThat(analyticsExecutions).hasValue(3);
        verify(model, times(1)).generate(any(), anyString());
        db.update("DELETE FROM ds_subject_user WHERE subject_id=? AND user_id=?", subject, owner.id);
        owner.status(403, "GET", turns(id) + "/" + followupTurn + "/result", null);
        owner.status(403, "POST", "/api/v1/charts/" + chart + "/refreshes", new ChartModels.Refresh("revoked-refresh-key"));
    }

    @Test void emptyQueryResultIsNoDataAndNeverInventsValuesOrAutoSaves() throws Exception {
        analyticsHasRows = false;
        when(model.generate(any(), anyString())).thenAnswer(invocation -> proposal());
        String id = create();
        var sent = owner.ok("POST", turns(id), new Send("Sales?", 0, "turn-key"));
        terminal(sent.path("taskId").asString());
        String path = turns(id) + "/" + sent.path("id").asString();
        var turn = owner.ok("GET", path, null);
        assertThat(turn.path("status").asString()).isEqualTo("NO_DATA");
        assertThat(owner.ok("GET", path + "/result", null).path("rows").size()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chart WHERE owner_id=?", Long.class, owner.id)).isZero();
        assertThat(analyticsExecutions).hasValue(1);
        verify(model, times(1)).generate(any(), anyString());
    }

    class Browser {
        final long id;
        String csrf;
        final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
        Browser(long id) { this.id = id; }
        HttpResponse<String> send(String method, String path, Object body, boolean withToken) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
            if (withToken && csrf != null) request.header("X-CSRF-TOKEN", csrf);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method, String path, Object body) throws Exception {
            var response = send(method, path, body, true);
            assertThat(response.statusCode()).withFailMessage("%s %s: %s", method, path, response.body()).isEqualTo(200);
            return json.readTree(response.body()).path("data");
        }
        void status(int expected, String method, String path, Object body) throws Exception {
            var response = send(method, path, body, true);
            assertThat(response.statusCode()).withFailMessage("%s %s: %s", method, path, response.body()).isEqualTo(expected);
        }
        void token() throws Exception { csrf = ok("GET", "/api/v1/auth/csrf", null).path("token").asString(); }
    }
}
