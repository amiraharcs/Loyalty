package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.common.config.ProductionDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ProductionDatabaseSettingsTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ProductionDatabaseConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=");

    @Test
    void rejectsMissingProductionSettingsBeforeConnecting() {
        runner.run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("requires DB_URL"));
        runner.withPropertyValues("DB_URL=jdbc:postgresql://localhost/loyalty").run(context ->
                assertThat(context.getStartupFailure()).hasMessageContaining("requires DB_USERNAME"));
        runner.withPropertyValues("DB_URL=jdbc:postgresql://localhost/loyalty", "DB_USERNAME=app")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("requires DB_PASSWORD"));
    }

    @Test
    void rejectsOtherDatabaseTypesAndPartialMigrationCredentials() {
        runner.withPropertyValues("DB_URL=jdbc:h2:mem:test", "DB_USERNAME=app", "DB_PASSWORD=test")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("PostgreSQL JDBC URL"));
        runner.withPropertyValues("DB_URL=jdbc:postgresql://localhost/loyalty", "DB_USERNAME=app",
                        "DB_PASSWORD=test", "DB_MIGRATION_USERNAME=migrator")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("requires DB_MIGRATION_URL"));
    }

    @Test
    void productionDefaultsRequireVerifiedTlsAndNeverGenerateSchema() {
        runner.withPropertyValues("DB_URL=jdbc:postgresql://localhost/loyalty", "DB_USERNAME=app", "DB_PASSWORD=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var env = context.getEnvironment();
                    assertThat(env.getProperty("spring.datasource.hikari.data-source-properties.sslmode")).isEqualTo("verify-full");
                    assertThat(env.getProperty("spring.flyway.jdbc-properties.sslmode")).isEqualTo("verify-full");
                    assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
                    assertThat(env.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
                    assertThat(env.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
                });
    }
}
