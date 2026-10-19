package com.amiraharcs.loyalty;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PrizeMigrationTests {
    private Flyway at(String url,String target) {
        var database=PostgresTestDatabase.DATABASE;
        var config=Flyway.configure().dataSource(url,database.getUsername(),database.getPassword())
                .schemas("loyalty").defaultSchema("loyalty").locations("classpath:db/migration");
        if(target!=null) {config.target(target);}
        return config.load();
    }
    @Test void upgradesV19PreservingExistingProjectionAndNeverInventingHistoricalEntitlements() throws Exception {
        var database=PostgresTestDatabase.DATABASE;String name="prize_migration_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());var ddl=admin.createStatement()) {
            ddl.execute("CREATE DATABASE "+name);
            String url="jdbc:postgresql://"+database.getHost()+":"+database.getMappedPort(5432)+"/"+name;
            try {
                at(url,"19").migrate();UUID user=UUID.randomUUID(),customer=UUID.randomUUID();
                try(var connection=DriverManager.getConnection(url,database.getUsername(),database.getPassword());var statement=connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) VALUES ('"+user+"','legacy-prize@example.org','synthetic','ACTIVE',now(),now())");
                    statement.executeUpdate("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES ('"+customer+"','"+user+"','Member','Member',true)");
                    // A historical projection alone is insufficient evidence for retroactive issuance.
                    statement.executeUpdate("INSERT INTO loyalty.streaks(customer_id,current_count,longest_count,last_qualifying_date) VALUES ('"+customer+"',20,20,'2025-01-20')");
                }
                var migration=at(url,"20");assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
                migration.validate();assertThat(migration.migrate().migrationsExecuted).isZero();
                try(var connection=DriverManager.getConnection(url,database.getUsername(),database.getPassword());var statement=connection.createStatement();
                        var rows=statement.executeQuery("SELECT (SELECT count(*) FROM loyalty.prizes) definitions,(SELECT count(*) FROM loyalty.customer_prizes) entitlements,(SELECT count(*) FROM loyalty.point_transactions) ledger,(SELECT current_count FROM loyalty.streaks) streak")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getInt("definitions")).isEqualTo(5);
                    assertThat(rows.getInt("entitlements")).isZero();assertThat(rows.getInt("ledger")).isZero();assertThat(rows.getInt("streak")).isEqualTo(20);
                }
            } finally {ddl.execute("DROP DATABASE "+name+" WITH (FORCE)");}
        }
    }
}
