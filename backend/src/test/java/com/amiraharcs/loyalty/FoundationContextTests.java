package com.amiraharcs.loyalty;

import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Run the same foundation contract under each supported configuration profile. */
abstract class FoundationContextTests {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Autowired
    MockMvc mvc;

    @Test
    void startsWithApplicationAndValidationInfrastructure() {
        assertThat(context.getBean(LoyaltyBackendApplication.class)).isNotNull();
        assertThat(context.getBean(Validator.class)).isNotNull();
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("loyalty-backend");
    }

    @Test
    void startsPostgresFlywayAndValidatesIdentityEntities() throws Exception {
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getSchema()).isEqualTo("loyalty");
        }
        var entityManagerFactory = context.getBean(EntityManagerFactory.class);
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).extracting(jakarta.persistence.metamodel.EntityType::getName)
                .containsExactlyInAnyOrder("User", "Role", "CustomerProfile");
        var flyway = context.getBean(Flyway.class);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("2");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
    }

    @Test
    void doesNotGenerateADevelopmentUser() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void rejectsRequestsWithoutAddingLoginOrBasicAuthentication() throws Exception {
        mvc.perform(get("/api/v1/not-implemented"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("code").value("UNAUTHENTICATED"));
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMutationsEvenWithAValidCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/not-implemented").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedDenialsUseForbiddenProblemJson() throws Exception {
        mvc.perform(get("/api/v1/not-implemented").with(user("fixture").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("code").value("FORBIDDEN"));
    }

    @Test
    void missingCsrfUsesForbiddenProblemJson() throws Exception {
        mvc.perform(post("/api/v1/not-implemented"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("code").value("FORBIDDEN"));
    }

    @Test
    void exposesSafeHealthWithoutOpeningOtherActuatorEndpoints() throws Exception {
        assertThat(environment.getProperty("management.endpoints.access.default")).isEqualTo("none");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(environment.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("status").value("UP"))
                    .andExpect(jsonPath("components").doesNotExist())
                    .andExpect(jsonPath("details").doesNotExist());
        }
        mvc.perform(head("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(user("fixture").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("components").doesNotExist())
                .andExpect(jsonPath("details").doesNotExist());
        mvc.perform(post("/actuator/health").with(csrf())).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/actuator", "/actuator/env", "/actuator/configprops", "/actuator/beans",
                "/actuator/metrics", "/actuator/heapdump", "/actuator/health/db"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("fixture").roles("ADMIN"))).andExpect(status().isForbidden());
        }
    }
}
