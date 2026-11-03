-- M00 only. No users, credentials, chart records, or business data are seeded.
-- Flyway owns schema history; never edit this migration after it has been applied.
CREATE TABLE sys_platform_setting (
    setting_key VARCHAR(100) NOT NULL,
    setting_value VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_platform_setting (setting_key, setting_value)
VALUES ('product.name', 'DataLens');
