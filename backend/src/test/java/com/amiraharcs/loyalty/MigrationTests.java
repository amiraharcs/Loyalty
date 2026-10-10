package com.amiraharcs.loyalty;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationTests {
    @TempDir
    Path changedMigrations;

    @Test
    void rejectsChangedMigrationChecksums() throws Exception {
        withEmptyDatabase(url -> {
            flyway(url).migrate();
            Files.writeString(changedMigrations.resolve("V1__initialize_persistence.sql"),
                    "COMMENT ON SCHEMA loyalty IS 'Changed after deployment';");
            var changed = Flyway.configure()
                    .dataSource(url, PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword())
                    .schemas("loyalty").defaultSchema("loyalty")
                    .locations("filesystem:" + changedMigrations).load();
            assertThatThrownBy(changed::validate).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum mismatch");
        });
    }

    @Test
    void migratesEmptyDatabaseWithOnlyIdentityAndSessionTablesAndCanRunAgain() throws Exception {
        withEmptyDatabase(url -> {
            var flyway = flyway(url);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement();
                 var tables = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                         + "WHERE table_schema = 'loyalty' ORDER BY table_name")) {
                var names = new java.util.ArrayList<String>();
                while (tables.next()) { names.add(tables.getString(1)); }
                assertThat(names).containsExactly("customer_profiles", "flyway_schema_history", "roles", "spring_session", "spring_session_attributes", "user_roles", "users");
            }
            assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class).hasMessageContaining("cleanDisabled");
        });
    }

    @Test
    void refusesToSilentlyBaselineAnUnmanagedSchema() throws Exception {
        withEmptyDatabase(url -> {
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA loyalty");
                statement.execute("CREATE TABLE loyalty.migration_probe (id integer)");
            }
            assertThatThrownBy(() -> flyway(url).migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("non-empty schema");
        });
    }

    private static Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, PostgresTestDatabase.DATABASE.getUsername(),
                PostgresTestDatabase.DATABASE.getPassword()).locations("classpath:db/migration")
                .schemas("loyalty").defaultSchema("loyalty").baselineOnMigrate(false)
                .cleanDisabled(true).validateOnMigrate(true).load();
    }

    private static void withEmptyDatabase(DatabaseCheck check) throws Exception {
        var database = PostgresTestDatabase.DATABASE;
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(),
                database.getUsername(), database.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
            String url = "jdbc:postgresql://" + database.getHost() + ":" + database.getMappedPort(5432) + "/" + name;
            try {
                check.run(url);
            } finally {
                statement.execute("DROP DATABASE " + name + " WITH (FORCE)");
            }
        }
    }

    @FunctionalInterface
    private interface DatabaseCheck {
        void run(String url) throws Exception;
    }
}
