CREATE TABLE sys_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 auth_version BIGINT NOT NULL DEFAULT 1,
 failed_attempts INT NOT NULL DEFAULT 0,
 locked_until TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_sys_user_role CHECK (role IN ('SUPER_ADMIN', 'USER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_audit_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 actor_id BIGINT NULL,
 action VARCHAR(80) NOT NULL,
 resource_type VARCHAR(40) NOT NULL,
 resource_id VARCHAR(80) NULL,
 outcome VARCHAR(20) NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_audit_actor_time(actor_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Single lock row serializes initial provisioning and admin membership changes.
INSERT INTO sys_platform_setting(setting_key, setting_value) VALUES ('identity.lock', '1');
