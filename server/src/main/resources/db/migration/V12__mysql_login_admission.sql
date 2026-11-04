-- Login flood admission counters, formerly in Redis. Do not alter earlier migrations.
-- Fixed 60-second windows; operational retention/cleanup policy is separate.
CREATE TABLE sys_login_rate_limit (
    bucket_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    window_start DATETIME(6) NOT NULL,
    attempts INT NOT NULL,
    INDEX ix_login_rate_window_start (window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
