INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('model.lock','1');
CREATE TABLE model_configuration (
 id INT PRIMARY KEY, settings_json TEXT NOT NULL, encrypted_key TEXT NULL
);
CREATE TABLE model_daily_budget (
 budget_date DATE PRIMARY KEY, reserved_tokens BIGINT NOT NULL
);
CREATE TABLE model_call (
 id VARCHAR(36) PRIMARY KEY, actor_id BIGINT NOT NULL, subject_id VARCHAR(36) NOT NULL,
 config_version BIGINT NOT NULL, semantic_version BIGINT NOT NULL, semantic_digest CHAR(64) NOT NULL,
 reserved_tokens BIGINT NOT NULL, status VARCHAR(20) NOT NULL, error_code VARCHAR(64) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at TIMESTAMP(6) NULL,
 INDEX idx_model_call_actor(actor_id,created_at)
);
