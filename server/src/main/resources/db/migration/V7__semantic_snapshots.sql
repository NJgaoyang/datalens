INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('semantic.lock','1');
CREATE TABLE semantic_workspace (
    subject_id VARCHAR(36) NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    document_json LONGTEXT NOT NULL,
    digest CHAR(64) NOT NULL,
    scope_fingerprint CHAR(64) NOT NULL,
    reviewed_digest CHAR(64) NULL,
    reviewer_id BIGINT NULL,
    reviewed_at TIMESTAMP(6) NULL,
    published_version BIGINT NULL,
    next_release BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE semantic_release (
    subject_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    document_json LONGTEXT NOT NULL,
    digest CHAR(64) NOT NULL,
    scope_fingerprint CHAR(64) NOT NULL,
    reviewer_id BIGINT NOT NULL,
    publisher_id BIGINT NOT NULL,
    reviewed_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(subject_id,version)
);
