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

import javax.sql.DataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    void startsPostgresFlywayAndJpaWithoutDomainEntities() throws Exception {
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getSchema()).isEqualTo("loyalty");
        }
        var entityManagerFactory = context.getBean(EntityManagerFactory.class);
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isEmpty();
        var flyway = context.getBean(Flyway.class);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
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
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/login")).andExpect(status().isForbidden());
    }

    @Test
    void rejectsMutationsEvenWithAValidCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/not-implemented").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doesNotExposeActuatorYet() throws Exception {
        assertThat(environment.getProperty("management.endpoints.access.default")).isEqualTo("none");
        mvc.perform(get("/actuator/health")).andExpect(status().isForbidden());
    }
}
