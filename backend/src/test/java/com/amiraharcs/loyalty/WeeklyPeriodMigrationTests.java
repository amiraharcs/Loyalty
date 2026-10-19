package com.amiraharcs.loyalty;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WeeklyPeriodMigrationTests {
    private Flyway migration(String url,String target) {
        var database=PostgresTestDatabase.DATABASE;
        var config=Flyway.configure().dataSource(url,database.getUsername(),database.getPassword())
                .schemas("loyalty").defaultSchema("loyalty").locations("classpath:db/migration");
        if(target!=null) {config.target(target);}
        return config.load();
    }
    @Test void v19BackfillsRealHistoricalStartsWithoutRewritingScoresOrLifeEvidence() throws Exception {
        var database=PostgresTestDatabase.DATABASE;
        String name="period_backfill_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());var ddl=admin.createStatement()) {
            ddl.execute("CREATE DATABASE "+name);
            String url="jdbc:postgresql://"+database.getHost()+":"+database.getMappedPort(5432)+"/"+name;
            try {
                assertThat(migration(url,"18").migrate().migrationsExecuted).isEqualTo(18);
                UUID user=UUID.randomUUID(),customer=UUID.randomUUID();
                List<Instant> starts=List.of(Instant.parse("2026-03-29T00:59:59Z"),Instant.parse("2026-03-29T01:00:00Z"),
                    Instant.parse("2026-03-29T23:00:00Z"),Instant.parse("2026-10-25T00:59:59Z"),Instant.parse("2026-10-25T01:00:00Z"),
                    Instant.parse("2026-10-26T00:00:00Z"));
                try(var connection=DriverManager.getConnection(url,database.getUsername(),database.getPassword());var statement=connection.createStatement()) {
                    connection.setAutoCommit(false);
                    statement.executeUpdate("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) VALUES ('"+user+"','backfill@example.org','synthetic','ACTIVE',now(),now())");
                    statement.executeUpdate("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES ('"+customer+"','"+user+"','Private','Alias',false)");
                    statement.executeUpdate("INSERT INTO loyalty.game_life_state(customer_id) VALUES ('"+customer+"')");
                    for(int i=0;i<starts.size();i++) {
                        UUID session=UUID.randomUUID();Instant start=starts.get(i);boolean completed=i==0;
                        try(var insert=connection.prepareStatement("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,completed_at,engine_version) VALUES (?,?,?,?,?,?,'v1')")) {
                            insert.setObject(1,session);insert.setObject(2,customer);insert.setTimestamp(3,Timestamp.from(start));insert.setTimestamp(4,Timestamp.from(start.plusSeconds(900)));
                            insert.setString(5,completed?"COMPLETED":"EXPIRED");insert.setTimestamp(6,completed?Timestamp.from(start.plusSeconds(10)):null);insert.executeUpdate();
                        }
                        statement.executeUpdate("INSERT INTO loyalty.game_life_consumptions(id,customer_id,session_id,consumed_at,recharge_at) VALUES ('"+UUID.randomUUID()+"','"+customer+"','"+session+"','"+start+"','"+start.plusSeconds(7200)+"')");
                        if(completed) {statement.executeUpdate("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES ('"+UUID.randomUUID()+"','"+session+"',123,'"+start.plusSeconds(10)+"')");}
                    }
                    connection.commit();
                }
                var latest=migration(url,null);assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
                assertThat(latest.migrate().migrationsExecuted).isZero();
                try(var connection=DriverManager.getConnection(url,database.getUsername(),database.getPassword());var statement=connection.createStatement()) {
                    try(var rows=statement.executeQuery("SELECT s.started_at,p.start_date,p.start_at,p.end_at,p.settlement_cutoff_at FROM loyalty.game_sessions s JOIN loyalty.weekly_periods p ON p.id=s.weekly_period_id ORDER BY s.started_at")) {
                        int count=0;
                        while(rows.next()) {
                            var start=rows.getTimestamp(1).toInstant();var monday=com.amiraharcs.loyalty.competition.service.WeeklyPeriodService.startDate(start);
                            assertThat(rows.getDate(2).toLocalDate()).isEqualTo(monday);
                            assertThat(rows.getTimestamp(3).toInstant()).isEqualTo(monday.atStartOfDay(ZoneId.of("Europe/London")).toInstant());
                            assertThat(rows.getTimestamp(4).toInstant()).isEqualTo(monday.plusDays(7).atStartOfDay(ZoneId.of("Europe/London")).toInstant());
                            assertThat(rows.getTimestamp(5).toInstant()).isEqualTo(rows.getTimestamp(4).toInstant().plusSeconds(900));count++;
                        }
                        assertThat(count).isEqualTo(starts.size());
                    }
                    try(var rows=statement.executeQuery("SELECT (SELECT count(*) FROM loyalty.weekly_periods) periods,(SELECT count(*) FROM loyalty.game_life_consumptions) lives,(SELECT count(*) FROM loyalty.game_scores) scores,(SELECT score FROM loyalty.game_scores) best")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getInt("periods")).isEqualTo(4);assertThat(rows.getInt("lives")).isEqualTo(6);
                        assertThat(rows.getInt("scores")).isEqualTo(1);assertThat(rows.getLong("best")).isEqualTo(123);
                    }
                    for(String sql:List.of("UPDATE loyalty.weekly_periods SET end_at=start_at","UPDATE loyalty.weekly_periods SET settlement_cutoff_at=end_at-interval '1 second'",
                        "UPDATE loyalty.weekly_periods SET status='INVALID'","UPDATE loyalty.weekly_periods SET start_date=start_date+1","UPDATE loyalty.game_sessions SET weekly_period_id='00000000-0000-0000-0000-000000000000'")) {
                        assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOf(SQLException.class);
                    }
                    statement.executeUpdate("UPDATE loyalty.weekly_periods SET status='SETTLED'");
                    assertThatThrownBy(()->statement.executeUpdate("UPDATE loyalty.weekly_periods SET status='OPEN'")).isInstanceOf(SQLException.class);
                }
            } finally {ddl.execute("DROP DATABASE "+name+" WITH (FORCE)");}
        }
    }
}
