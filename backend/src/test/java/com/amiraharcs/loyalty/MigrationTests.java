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
    void migratesEmptyDatabaseWithOnlyImplementedDomainAndSessionTablesAndCanRunAgain() throws Exception {
        withEmptyDatabase(url -> {
            var flyway = flyway(url);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement();
                 var tables = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                         + "WHERE table_schema = 'loyalty' ORDER BY table_name")) {
                var names = new java.util.ArrayList<String>();
                while (tables.next()) { names.add(tables.getString(1)); }
                assertThat(names).containsExactly("customer_profiles", "flyway_schema_history", "loyalty_accounts", "point_transactions", "roles", "spring_session", "spring_session_attributes", "staff_store", "stores", "user_roles", "users");
            }
            assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class).hasMessageContaining("cleanDisabled");
        });
    }

    @Test
    void upgradesVersionThreeWithoutChangingIdentityDataOrSeedingStores() throws Exception {
        withEmptyDatabase(url -> {
            var old = Flyway.configure().dataSource(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()).schemas("loyalty").defaultSchema("loyalty")
                    .locations("classpath:db/migration").target("3").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(3);
            UUID identity = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var insert = connection.prepareStatement("INSERT INTO loyalty.users "
                         + "(id, canonical_email, password_hash, status, created_at, updated_at) "
                         + "VALUES (?, 'migration-preserved@example.org', 'synthetic-hash', 'ACTIVE', now(), now())")) {
                insert.setObject(1, identity);
                insert.executeUpdate();
            }
            assertThat(flyway(url).migrate().migrationsExecuted).isEqualTo(2);
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement();
                 var counts = statement.executeQuery("SELECT (SELECT count(*) FROM loyalty.users) AS users, "
                         + "(SELECT count(*) FROM loyalty.stores) AS stores, "
                         + "(SELECT count(*) FROM loyalty.staff_store) AS assignments")) {
                assertThat(counts.next()).isTrue();
                assertThat(counts.getInt("users")).isEqualTo(1);
                assertThat(counts.getInt("stores")).isZero();
                assertThat(counts.getInt("assignments")).isZero();
            }
        });
    }

    @Test
    void upgradesVersionFourByProvisioningExistingCustomerWithEmptyAccount() throws Exception {
        withEmptyDatabase(url -> {
            Flyway.configure().dataSource(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()).schemas("loyalty").defaultSchema("loyalty")
                    .locations("classpath:db/migration").target("4").load().migrate();
            UUID user = UUID.randomUUID(); UUID customer = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword())) {
                try (var insert = connection.prepareStatement("INSERT INTO loyalty.users "
                        + "(id, canonical_email, password_hash, status, created_at, updated_at) "
                        + "VALUES (?, 'existing-customer@example.org', 'synthetic-hash', 'ACTIVE', now(), now())")) {
                    insert.setObject(1, user); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO loyalty.customer_profiles "
                        + "(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES (?,?,'Member','Member',true)")) {
                    insert.setObject(1, customer); insert.setObject(2, user); insert.executeUpdate();
                }
            }
            var migrated = flyway(url);
            assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);
            migrated.validate();
            assertThat(migrated.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.prepareStatement("SELECT a.version, "
                         + "(SELECT count(*) FROM loyalty.point_transactions) AS entries, "
                         + "(SELECT count(*) FROM loyalty.users) AS identities "
                         + "FROM loyalty.loyalty_accounts a WHERE a.customer_id = ?")) {
                statement.setObject(1, customer);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong("version")).isZero();
                    assertThat(result.getLong("entries")).isZero();
                    assertThat(result.getLong("identities")).isEqualTo(1);
                    assertThat(result.next()).isFalse();
                }
            }
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
