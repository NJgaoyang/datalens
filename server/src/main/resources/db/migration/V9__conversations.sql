INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('conversation.lock','1');
CREATE TABLE conversation (
 id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, subject_id VARCHAR(36) NOT NULL,
 title VARCHAR(160) NOT NULL, version BIGINT NOT NULL DEFAULT 0, idempotency_key VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_conversation_key(owner_id,idempotency_key), INDEX idx_conversation_owner(owner_id,created_at)
);
CREATE TABLE conversation_turn (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL, sequence_no BIGINT NOT NULL,
 idempotency_key VARCHAR(64) NOT NULL, question TEXT NOT NULL, status VARCHAR(32) NOT NULL,
 clarification TEXT NULL, definition_json TEXT NULL, model_call_id VARCHAR(36) NULL,
 preview_id VARCHAR(36) NULL, task_id VARCHAR(36) NULL, chart_id VARCHAR(36) NULL,
 error_code VARCHAR(64) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_turn_key(conversation_id,idempotency_key), UNIQUE KEY uk_turn_sequence(conversation_id,sequence_no),
 CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);
