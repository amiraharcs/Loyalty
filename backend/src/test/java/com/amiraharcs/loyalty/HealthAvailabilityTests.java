package com.amiraharcs.loyalty;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Dedicated disposable database: stopping it never affects other tests or local data. */
@SpringBootTest(properties = {"spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=1000",
        "spring.datasource.hikari.data-source-properties.connectTimeout=1",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthAvailabilityTests {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("health_tests").withUsername("health_test").withPassword("isolated_test_only");
    static { DATABASE.start(); }

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        QrTestSigningProperties.register(registry);
        registry.add("DB_URL", DATABASE::getJdbcUrl);
        registry.add("DB_USERNAME", DATABASE::getUsername);
        registry.add("DB_PASSWORD", DATABASE::getPassword);
    }

    @Test
    void databaseOutageMakesHealthAndReadinessDownButLeavesLivenessUp() throws Exception {
        assertThat(context.getBeansOfType(DataSourceHealthIndicator.class)).hasSize(1);
        try {
            for (String group : new String[]{"", "/readiness", "/liveness"}) {
                safeHealth(group, 200, "UP");
            }
            DATABASE.stop();
            safeHealth("", 503, "DOWN");
            safeHealth("/readiness", 503, "DOWN");
            safeHealth("/liveness", 200, "UP");
        } finally {
            DATABASE.stop();
        }
    }

    private void safeHealth(String suffix, int httpStatus, String state) throws Exception {
        var response = mvc.perform(get("/actuator/health" + suffix)).andExpect(status().is(httpStatus))
                .andExpect(jsonPath("status").value(state)).andExpect(jsonPath("components").doesNotExist())
                .andExpect(jsonPath("details").doesNotExist()).andExpect(header().exists("X-Request-ID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("jdbc:", "health_test", "isolated_test_only", "localhost", "exception", "trace");
    }
}
