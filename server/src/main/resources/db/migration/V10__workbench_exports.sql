INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('export.lock','1');
CREATE TABLE chart_export_grant (
 chart_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL, chart_version BIGINT NOT NULL,
 generation BIGINT NOT NULL DEFAULT 1, enabled BOOLEAN NOT NULL,
 PRIMARY KEY(chart_id,user_id)
);
CREATE TABLE chart_export_job (
 id VARCHAR(36) PRIMARY KEY, actor_id BIGINT NOT NULL, auth_version BIGINT NOT NULL,
 chart_id VARCHAR(36) NOT NULL, chart_version BIGINT NOT NULL, revision_id VARCHAR(36) NOT NULL,
 grant_generation BIGINT NOT NULL, task_id VARCHAR(36) NOT NULL, idempotency_key VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, error_code VARCHAR(48), content MEDIUMBLOB,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), expires_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_export_request(actor_id,idempotency_key), INDEX idx_export_expiry(expires_at), INDEX idx_export_actor(actor_id,status)
);
