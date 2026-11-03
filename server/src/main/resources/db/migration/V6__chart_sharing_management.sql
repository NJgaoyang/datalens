-- V5 created an unnamed MySQL check, assigned chart_chk_1 by MySQL 8.4.
ALTER TABLE chart DROP CHECK chart_chk_1;
ALTER TABLE chart ADD CONSTRAINT ck_chart_visibility CHECK (visibility IN ('PRIVATE','ALL','SPECIFIC')),
 ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE chart_share (
 chart_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 PRIMARY KEY(chart_id,user_id), FOREIGN KEY(chart_id) REFERENCES chart(id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX ix_chart_visibility ON chart(deleted,visibility,updated_at);
CREATE TABLE chart_management_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 chart_id VARCHAR(36) NOT NULL, actor_id BIGINT NOT NULL, action VARCHAR(16) NOT NULL,
 old_owner_id BIGINT NOT NULL, new_owner_id BIGINT NOT NULL,
 old_visibility VARCHAR(16) NOT NULL, new_visibility VARCHAR(16) NOT NULL,
 old_recipients VARCHAR(2200) NOT NULL, new_recipients VARCHAR(2200) NOT NULL,
 chart_version BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
