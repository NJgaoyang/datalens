package com.datalens.foundation;

import com.datalens.common.web.RequestIdFilter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Requires disposable MySQL 8.4. Run with -Pintegration, never against production. */
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class FoundationIT {
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter traceFilter;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datalens.query.QueryLease queryLease;
    @Autowired org.springframework.boot.health.actuate.endpoint.HealthEndpoint health;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceFilter, springSecurityFilterChain).build();
    }

    @Test void realMySqlMigrationAndMapperWorkWithoutModelOrStarRocks() throws Exception {
        mvc.perform(get("/api/v1/system/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("DataLens"))
                .andExpect(jsonPath("$.data.stage").value("M10"))
                .andExpect(jsonPath("$.data.enabledModules.length()").value(10));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_platform_setting", Integer.class)).isEqualTo(8);
    }

    @Test void migrationIsRepeatableWithoutReseeding() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test void undisclosedRoutesAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/v1/charts")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test void unsafeRequestsHaveCsrfProtection() throws Exception {
        mvc.perform(post("/api/v1/system/info")).andExpect(status().isForbidden());
    }

    @Test void healthProbesAreRealAndDoNotExposeDependencyDetails() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test void lostLeaseAloneMakesReadinessFailEvenWithHealthyDatabase() throws Exception {
        // Disposable idle integration process only: deliberately release its advisory lock.
        queryLease.close();
        try {
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertThat(health.healthForPath("db").getStatus().getCode()).isEqualTo("UP");
            assertThat(health.healthForPath("queryExecutor").getStatus().getCode()).isEqualTo("DOWN");
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN")).andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally { queryLease.acquire(); }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test void mysqlSessionTablesExist() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('SPRING_SESSION','SPRING_SESSION_ATTRIBUTES')",Integer.class)).isEqualTo(2);
    }
}

