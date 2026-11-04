package com.datalens.foundation;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Dedicated disposable schema, never clean/drop or reuse the application integration schema. */
class MigrationUpgradeIT {
    @Test void upgradesVersionNineThroughJdbcSessionMigrationAndPreservesChecksums() throws Exception {
        String url = System.getenv("DATALENS_UPGRADE_DB_URL");
        assertThat(url).as("Create an empty dedicated datalens_upgrade database; see deploy/README.md")
                .startsWith("jdbc:mysql://127.0.0.1:").contains("/datalens_upgrade?");
        String user = System.getenv("DATALENS_DB_USERNAME"), password = System.getenv("DATALENS_DB_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).as("Upgrade acceptance requires a fresh empty schema").isZero();
            }
            var baseline = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                    .cleanDisabled(true).target("9").load();
            assertThat(baseline.migrate().migrationsExecuted).isEqualTo(9);
            statement.executeUpdate("INSERT INTO sys_user(username,password_hash,role,enabled) VALUES('upgrade_probe','disabled-ci-fixture','USER',FALSE)");
            statement.executeUpdate("INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('ci.upgrade.probe','9007199254740993|1234567890.0123456789')");
            statement.executeUpdate("INSERT INTO model_daily_budget(budget_date,reserved_tokens) VALUES('2020-01-01',12345)");
            var upgrade = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                    .cleanDisabled(true).validateOnMigrate(true).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
            upgrade.validate();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            try (var rows = statement.executeQuery("SELECT setting_value FROM sys_platform_setting WHERE setting_key='ci.upgrade.probe'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("9007199254740993|1234567890.0123456789");
            }
            try (var rows = statement.executeQuery("SELECT enabled FROM sys_user WHERE username='upgrade_probe'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isFalse();
            }
            try (var rows = statement.executeQuery("SELECT reserved_tokens FROM model_daily_budget WHERE budget_date='2020-01-01'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(12345);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM chart_export_grant")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            assertThat(upgrade.info().applied()).hasSize(11);
            assertThat(upgrade.info().pending()).isEmpty();
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('SPRING_SESSION','SPRING_SESSION_ATTRIBUTES')")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }
}
