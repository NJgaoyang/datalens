CREATE TABLE ds_source (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, host VARCHAR(15) NOT NULL,
 port INT NOT NULL, catalog_name VARCHAR(64) NOT NULL, database_name VARCHAR(64) NOT NULL,
 username VARCHAR(128) NOT NULL, credential TEXT NOT NULL, enabled BOOLEAN NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, harvest_status VARCHAR(16) NOT NULL DEFAULT 'NEVER',
 harvest_started_at TIMESTAMP(6) NULL, last_harvest_at TIMESTAMP(6) NULL, last_error_code VARCHAR(40) NULL
);
CREATE TABLE ds_scope_table (
 source_id VARCHAR(36) NOT NULL, table_name VARCHAR(64) NOT NULL,
 PRIMARY KEY(source_id,table_name), FOREIGN KEY(source_id) REFERENCES ds_source(id)
);
CREATE TABLE ds_column (
 source_id VARCHAR(36) NOT NULL, table_name VARCHAR(64) NOT NULL, column_name VARCHAR(64) NOT NULL,
 type_name VARCHAR(128) NOT NULL, ordinal_position INT NOT NULL, nullable BOOLEAN NOT NULL,
 PRIMARY KEY(source_id,table_name,column_name), FOREIGN KEY(source_id) REFERENCES ds_source(id)
);
CREATE TABLE ds_subject (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, source_id VARCHAR(36) NOT NULL,
 share_scope VARCHAR(24) NOT NULL, FOREIGN KEY(source_id) REFERENCES ds_source(id)
);
CREATE TABLE ds_subject_field (
 subject_id VARCHAR(36) NOT NULL, table_name VARCHAR(64) NOT NULL, column_name VARCHAR(64) NOT NULL,
 denied BOOLEAN NOT NULL, PRIMARY KEY(subject_id,table_name,column_name,denied),
 FOREIGN KEY(subject_id) REFERENCES ds_subject(id)
);
CREATE TABLE ds_subject_user (
 subject_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 PRIMARY KEY(subject_id,user_id), FOREIGN KEY(subject_id) REFERENCES ds_subject(id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
