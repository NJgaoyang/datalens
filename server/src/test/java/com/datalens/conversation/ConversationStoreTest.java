package com.datalens.conversation;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import com.datalens.common.web.DomainException;
import static com.datalens.conversation.ConversationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit contracts supplement, but do not replace, the real database tests in ConversationIT. */
class ConversationStoreTest {
    JdbcTemplate db;
    ConversationStore store;
    final ConversationStore.Row row = new ConversationStore.Row("conversation", 7, "subject", "Sales", 0);

    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        store = spy(new ConversationStore(db, manager));
        doReturn(row).when(store).get("conversation");
    }

    ConversationStore.Stored stored(String status) {
        return new ConversationStore.Stored("turn", "conversation", 1, "Sales?", status,
            null, null, null, null, null, null, null, Instant.EPOCH);
    }

    void existingTurn(String status) {
        when(db.queryForList("SELECT id FROM conversation_turn WHERE conversation_id=? AND idempotency_key=?",
            String.class, "conversation", "turn-key")).thenReturn(List.of("turn"));
        doReturn(stored(status)).when(store).turn("conversation", "turn");
    }

    @Test void createRetryReturnsPersistedConversationAndNormalizesTitle() {
        when(db.queryForList("SELECT id FROM conversation WHERE owner_id=? AND idempotency_key=?",
            String.class, 7L, "create-key")).thenReturn(List.of("conversation"));
        assertThat(store.create(7, new Create("subject", "  Sales  ", "create-key"))).isEqualTo(row);
        verify(db).queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='conversation.lock' FOR UPDATE", String.class);
        verify(db, never()).update(startsWith("INSERT"), any(Object[].class));
    }

    @Test void createKeyCannotBeReusedWithAnotherTitleOrSubject() {
        when(db.queryForList("SELECT id FROM conversation WHERE owner_id=? AND idempotency_key=?",
            String.class, 7L, "create-key")).thenReturn(List.of("conversation"));
        assertConflict(() -> store.create(7, new Create("subject", "Changed", "create-key")));
        assertConflict(() -> store.create(7, new Create("another-subject", "Sales", "create-key")));
    }

    @Test void duplicateTurnIsResolvedBeforeStaleVersionAndPendingWorkChecks() {
        existingTurn("PLANNING");
        doReturn(new ConversationStore.Row("conversation", 7, "subject", "Sales", 25))
            .when(store).get("conversation");
        var admitted = store.admit(7, "conversation", new Send(" Sales? ", 0, "turn-key"));
        assertThat(admitted.fresh()).isFalse();
        assertThat(admitted.turn()).isEqualTo(stored("PLANNING"));
        verify(db, never()).queryForObject(contains("COUNT(*)"), eq(Long.class), eq("conversation"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void completedTurnRetryAlsoReturnsItsOriginalIdentity() {
        existingTurn("READY");
        var admitted = store.admit(7, "conversation", new Send("Sales?", 999, "turn-key"));
        assertThat(admitted.fresh()).isFalse();
        assertThat(admitted.turn().status()).isEqualTo("READY");
    }

    @Test void changedQuestionUnderAnExistingKeyConflicts() {
        existingTurn("CLARIFICATION_REQUIRED");
        assertConflict(() -> store.admit(7, "conversation", new Send("Different question", 0, "turn-key")));
    }

    @Test void ownerIsCheckedBeforeDuplicateLookup() {
        assertThatThrownBy(() -> store.admit(8, "conversation", new Send("Sales?", 0, "turn-key")))
            .isInstanceOf(AccessDeniedException.class);
        verify(db, never()).queryForList(contains("conversation_turn"), eq(String.class), any(), any());
    }

    @Test void newTurnRejectsStaleVersionWithoutWriting() {
        assertConflict(() -> store.admit(7, "conversation", new Send("Sales?", 1, "new-turn-key")));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void planningOrQueryingTurnBlocksAnotherNewTurn() {
        when(db.queryForObject("SELECT COUNT(*) FROM conversation_turn WHERE conversation_id=? AND status IN ('PLANNING','QUERYING')",
            Long.class, "conversation")).thenReturn(1L);
        assertConflict(() -> store.admit(7, "conversation", new Send("Sales?", 0, "new-turn-key")));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void turnLimitRejectsFreshAdmissionButAllowsExistingRetry() {
        doReturn(new ConversationStore.Row("conversation", 7, "subject", "Sales", 100))
            .when(store).get("conversation");
        assertConflict(() -> store.admit(7, "conversation", new Send("Sales?", 100, "new-turn-key")));
        existingTurn("READY");
        assertThat(store.admit(7, "conversation", new Send("Sales?", 0, "turn-key")).fresh()).isFalse();
    }

    @Test void freshAdmissionUsesLockedVersionAndWritesExactlyOneTurn() {
        when(db.queryForObject("SELECT COUNT(*) FROM conversation_turn WHERE conversation_id=? AND status IN ('PLANNING','QUERYING')",
            Long.class, "conversation")).thenReturn(0L);
        doReturn(stored("PLANNING")).when(store).turn(eq("conversation"), anyString());
        assertThat(store.admit(7, "conversation", new Send(" Sales? ", 0, "new-turn-key")).fresh()).isTrue();
        verify(db).queryForObject("SELECT id FROM conversation WHERE id=? FOR UPDATE", String.class, "conversation");
        verify(db).update(eq("INSERT INTO conversation_turn(id,conversation_id,sequence_no,idempotency_key,question,status) VALUES(?,?,?,?,?,'PLANNING')"),
            anyString(), eq("conversation"), eq(1L), eq("new-turn-key"), eq("Sales?"));
        verify(db).update("UPDATE conversation SET version=version+1 WHERE id=?", "conversation");
    }

    @Test void recoveryOnlyInterruptsUnfinishedPlanningAndNeverReplaysWork() {
        store.recover();
        verify(db).update("UPDATE conversation_turn SET status='INTERRUPTED',error_code='CONVERSATION_INTERRUPTED' WHERE status='PLANNING'");
        verifyNoMoreInteractions(db);
    }

    @Test void terminalWritesCannotOverwriteAnAlreadyTerminalTurn() {
        store.finish("turn", "FAILED", "MODEL_UNAVAILABLE");
        verify(db).update("UPDATE conversation_turn SET status=?,error_code=? WHERE id=? AND status IN ('PLANNING','QUERYING')",
            "FAILED", "MODEL_UNAVAILABLE", "turn");
    }

    @Test void latePlanningCallbacksCannotResurrectAnInterruptedTurn() {
        assertConflict(() -> store.plan("turn", "{}", "call"));
        store.clarify("turn", "Which metric?", "call");
        assertConflict(() -> store.preview("turn", "preview", "task"));
        verify(db).update("UPDATE conversation_turn SET definition_json=?,model_call_id=? WHERE id=? AND status='PLANNING'", "{}", "call", "turn");
        verify(db).update("UPDATE conversation_turn SET status='CLARIFICATION_REQUIRED',clarification=?,model_call_id=? WHERE id=? AND status='PLANNING'", "Which metric?", "call", "turn");
        verify(db).update("UPDATE conversation_turn SET status='QUERYING',preview_id=?,task_id=? WHERE id=? AND status='PLANNING'", "preview", "task", "turn");
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(DomainException.class,
            failure -> assertThat(failure.code()).isEqualTo("CONVERSATION_CONFLICT"));
    }
}
