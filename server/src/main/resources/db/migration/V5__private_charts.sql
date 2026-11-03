CREATE TABLE chart (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 name VARCHAR(160) NOT NULL,
 visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
 revision_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_chart_owner(owner_id,updated_at),
 FOREIGN KEY(owner_id) REFERENCES sys_user(id),
 CHECK (visibility='PRIVATE')
);
CREATE TABLE chart_revision (
 id VARCHAR(36) PRIMARY KEY,
 chart_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL,
 definition_json MEDIUMTEXT NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_chart_revision(chart_id,version),
 FOREIGN KEY(chart_id) REFERENCES chart(id),
 FOREIGN KEY(creator_id) REFERENCES sys_user(id)
);
CREATE TABLE chart_preview (
 id VARCHAR(36) PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 auth_version BIGINT NOT NULL,
 idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 definition_json MEDIUMTEXT NOT NULL,
 resolved_parameters_json TEXT NOT NULL,
 task_id VARCHAR(36) NULL,
 saved_chart_id VARCHAR(36) NULL,
 saved_name VARCHAR(160) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_chart_preview(actor_id,idempotency_key),
 FOREIGN KEY(actor_id) REFERENCES sys_user(id)
);
CREATE TABLE chart_run (
 id VARCHAR(36) PRIMARY KEY,
 chart_id VARCHAR(36) NOT NULL,
 revision_id VARCHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 resolved_parameters_json TEXT NOT NULL,
 task_id VARCHAR(36) NULL,
 success_at TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_chart_refresh(actor_id,chart_id,idempotency_key),
 INDEX ix_chart_runs(chart_id,revision_id,actor_id,created_at),
 FOREIGN KEY(chart_id) REFERENCES chart(id),
 FOREIGN KEY(revision_id) REFERENCES chart_revision(id),
 FOREIGN KEY(actor_id) REFERENCES sys_user(id)
);
ALTER TABLE query_task ADD COLUMN purpose VARCHAR(32) NULL, ADD COLUMN resource_id VARCHAR(36) NULL, ADD COLUMN revision_id VARCHAR(36) NULL;
INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('chart.lock','1');
