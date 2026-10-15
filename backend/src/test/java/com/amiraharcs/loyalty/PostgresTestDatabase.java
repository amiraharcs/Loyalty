package com.amiraharcs.loyalty;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable PostgreSQL instance per test JVM; Testcontainers owns cleanup. */
final class PostgresTestDatabase {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("loyalty_tests")
            .withUsername("loyalty_test")
            .withPassword("isolated_test_only");

    static {
        DATABASE.start();
    }

    private PostgresTestDatabase() {
    }

    static void register(DynamicPropertyRegistry registry) {
        QrTestSigningProperties.register(registry);
        registry.add("DB_URL", DATABASE::getJdbcUrl);
        registry.add("DB_USERNAME", DATABASE::getUsername);
        registry.add("DB_PASSWORD", DATABASE::getPassword);
        // Cached Spring contexts share this container; keep their pools below its connection cap.
        registry.add("DB_POOL_SIZE", () -> "3");
        // This disposable local fixture has no TLS; production defaults to verify-full.
        registry.add("DB_SSL_MODE", () -> "disable");
        registry.add("DB_MIGRATION_URL", DATABASE::getJdbcUrl);
        registry.add("DB_MIGRATION_USERNAME", DATABASE::getUsername);
        registry.add("DB_MIGRATION_PASSWORD", DATABASE::getPassword);
    }
}
