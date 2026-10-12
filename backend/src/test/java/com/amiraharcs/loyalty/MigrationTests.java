package com.amiraharcs.loyalty;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement();
                 var tables = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                         + "WHERE table_schema = 'loyalty' ORDER BY table_name")) {
                var names = new java.util.ArrayList<String>();
                while (tables.next()) { names.add(tables.getString(1)); }
                assertThat(names).containsExactly("customer_profiles", "flyway_schema_history", "loyalty_accounts", "point_transactions", "roles", "spring_session", "spring_session_attributes", "staff_store", "stores", "user_roles", "users", "visits");
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
            assertThat(flyway(url).migrate().migrationsExecuted).isEqualTo(4);
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
            assertThat(migrated.migrate().migrationsExecuted).isEqualTo(3);
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
    void upgradesVersionFiveWithoutSeedingVisitsOrChangingPointHistory() throws Exception {
        withEmptyDatabase(url -> {
            Flyway.configure().dataSource(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()).schemas("loyalty").defaultSchema("loyalty")
                    .locations("classpath:db/migration").target("5").load().migrate();
            UUID user = UUID.randomUUID(); UUID customer = UUID.randomUUID(); UUID account = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) "
                        + "VALUES ('" + user + "','preserved-ledger@example.org','synthetic-hash','ACTIVE',now(),now())");
                statement.executeUpdate("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) "
                        + "VALUES ('" + customer + "','" + user + "','Member','Member',true)");
                statement.executeUpdate("INSERT INTO loyalty.loyalty_accounts(id,customer_id,created_at) VALUES ('" + account + "','" + customer + "',now())");
                statement.executeUpdate("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) "
                        + "VALUES ('" + UUID.randomUUID() + "','" + account + "',10,'EARN',now())");
            }
            // Keep this historical V5-to-V6 regression pinned; its synthetic EARN predates required sources.
            var upgraded = Flyway.configure().dataSource(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()).schemas("loyalty").defaultSchema("loyalty")
                    .locations("classpath:db/migration").target("6").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            upgraded.validate();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url,
                    PostgresTestDatabase.DATABASE.getUsername(), PostgresTestDatabase.DATABASE.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT (SELECT count(*) FROM loyalty.visits) AS visits, "
                         + "(SELECT SUM(signed_amount) FROM loyalty.point_transactions) AS points, "
                         + "(SELECT version FROM loyalty.loyalty_accounts) AS version")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("visits")).isZero();
                assertThat(result.getLong("points")).isEqualTo(10);
                assertThat(result.getLong("version")).isEqualTo(1);
            }
        });
    }

    @Test
    void upgradesVersionSixWithoutRewritingNonEarningLedgerHistory() throws Exception {
        withEmptyDatabase(url -> {
            var old = atVersion(url, "6"); old.migrate();
            var ids = seedLegacyIdentity(url);
            try (var connection = DriverManager.getConnection(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at) VALUES ('"
                        + UUID.randomUUID() + "','" + ids[2] + "',10,'ADJUSTMENT','" + ids[0] + "','Synthetic baseline',now())");
            }
            var upgraded = flyway(url);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1); upgraded.validate();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT (SELECT count(*) FROM loyalty.visits) AS visits, "
                         + "(SELECT SUM(signed_amount) FROM loyalty.point_transactions) AS points, "
                         + "(SELECT version FROM loyalty.loyalty_accounts) AS version")) {
                assertThat(result.next()).isTrue(); assertThat(result.getLong("visits")).isZero();
                assertThat(result.getLong("points")).isEqualTo(10); assertThat(result.getLong("version")).isEqualTo(1);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISIT", "EARN"})
    void refusesToInventAwardsOrRewriteLegacyHistory(String history) throws Exception {
        withEmptyDatabase(url -> {
            atVersion(url, "6").migrate(); var ids = seedLegacyIdentity(url);
            try (var connection = DriverManager.getConnection(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()); var statement = connection.createStatement()) {
                if (history.equals("EARN")) {
                    statement.executeUpdate("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,created_at) VALUES ('"
                            + UUID.randomUUID() + "','" + ids[2] + "',10,'EARN',now())");
                } else {
                    UUID store = UUID.randomUUID();
                    statement.executeUpdate("INSERT INTO loyalty.stores(id,name,address,status,created_at,updated_at) VALUES ('"
                            + store + "','Synthetic migration store','Synthetic address','ACTIVE',now(),now())");
                    statement.executeUpdate("INSERT INTO loyalty.visits(id,customer_id,account_id,store_id,confirmed_by,business_date,confirmed_at) VALUES ('"
                            + UUID.randomUUID() + "','" + ids[1] + "','" + ids[2] + "','" + store + "','" + ids[0]
                            + "',DATE '2026-10-12',TIMESTAMPTZ '2026-10-12 12:00:00+00')");
                }
            }
            assertThatThrownBy(() -> flyway(url).migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V7 requires no legacy unawarded visits or unlinked EARN records");
            assertThat(atVersion(url, "6").info().current().getVersion().toString()).isEqualTo("6");
            try (var connection = DriverManager.getConnection(url, PostgresTestDatabase.DATABASE.getUsername(),
                    PostgresTestDatabase.DATABASE.getPassword()); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT (SELECT count(*) FROM loyalty.visits) + (SELECT count(*) FROM loyalty.point_transactions) AS history")) {
                assertThat(result.next()).isTrue(); assertThat(result.getInt("history")).isEqualTo(1);
            }
        });
    }

    private static Flyway atVersion(String url, String version) {
        return Flyway.configure().dataSource(url, PostgresTestDatabase.DATABASE.getUsername(),
                PostgresTestDatabase.DATABASE.getPassword()).schemas("loyalty").defaultSchema("loyalty")
                .locations("classpath:db/migration").target(version).load();
    }

    private static UUID[] seedLegacyIdentity(String url) throws Exception {
        UUID user = UUID.randomUUID(); UUID customer = UUID.randomUUID(); UUID account = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, PostgresTestDatabase.DATABASE.getUsername(),
                PostgresTestDatabase.DATABASE.getPassword()); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) VALUES ('"
                    + user + "','migration-baseline@example.org','synthetic-hash','ACTIVE',now(),now())");
            statement.executeUpdate("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES ('"
                    + customer + "','" + user + "','Member','Member',true)");
            statement.executeUpdate("INSERT INTO loyalty.loyalty_accounts(id,customer_id,created_at) VALUES ('" + account + "','" + customer + "',now())");
        }
        return new UUID[]{user, customer, account};
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
