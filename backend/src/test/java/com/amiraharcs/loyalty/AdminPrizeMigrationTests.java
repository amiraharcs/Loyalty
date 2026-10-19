package com.amiraharcs.loyalty;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdminPrizeMigrationTests {
    Flyway at(String url,String version) {
        var db=PostgresTestDatabase.DATABASE;
        return Flyway.configure().dataSource(url,db.getUsername(),db.getPassword()).schemas("loyalty").defaultSchema("loyalty")
                .locations("classpath:db/migration").target(version).load();
    }
    @Test void v21PreservesIssuanceHistoryAndRequiresBoundAuditForOnlyRevocationTransition() throws Exception {
        var db=PostgresTestDatabase.DATABASE;String name="admin_prize_migration_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var ddl=admin.createStatement()) {
            ddl.execute("CREATE DATABASE "+name);String url="jdbc:postgresql://"+db.getHost()+":"+db.getMappedPort(5432)+"/"+name;
            try {
                at(url,"20").migrate();UUID user=UUID.randomUUID(),actor=UUID.randomUUID(),customer=UUID.randomUUID(),account=UUID.randomUUID(),store=UUID.randomUUID(),visit=UUID.randomUUID(),entitlement=UUID.randomUUID();
                try(var c=DriverManager.getConnection(url,db.getUsername(),db.getPassword());var s=c.createStatement()) {
                    c.setAutoCommit(false);
                    for(UUID id:List.of(user,actor)) {s.executeUpdate("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) VALUES ('"+id+"','"+id+"@example.org','synthetic','ACTIVE',now(),now())");}
                    s.executeUpdate("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES ('"+actor+"','ADMIN')");
                    s.executeUpdate("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES ('"+customer+"','"+user+"','Member','Member',true)");
                    s.executeUpdate("INSERT INTO loyalty.loyalty_accounts(id,customer_id,created_at) VALUES ('"+account+"','"+customer+"',now())");
                    s.executeUpdate("INSERT INTO loyalty.stores(id,name,status,address,created_at,updated_at) VALUES ('"+store+"','Synthetic cafe','ACTIVE','Fixture',now(),now())");
                    s.executeUpdate("INSERT INTO loyalty.visits(id,customer_id,account_id,store_id,confirmed_by,business_date,confirmed_at,earned_points) VALUES ('"+visit+"','"+customer+"','"+account+"','"+store+"','"+actor+"','2026-10-19','2026-10-19T12:00:00Z',100)");
                    s.executeUpdate("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,created_at,visit_id) VALUES ('"+UUID.randomUUID()+"','"+account+"',100,'EARN','"+actor+"','2026-10-19T12:00:00Z','"+visit+"')");
                    s.executeUpdate("INSERT INTO loyalty.customer_prizes(id,customer_id,prize_id,trigger_type,trigger_visit_id,prize_name_snapshot,benefit_type,benefit_description_snapshot,reward_id,reward_name_snapshot,reward_description_snapshot,status,awarded_at,expires_at) SELECT '"+entitlement+"','"+customer+"',p.id,p.trigger_type,'"+visit+"',p.name,p.benefit_type,p.benefit_description,p.reward_id,r.name,r.description,'AVAILABLE','2026-10-19T12:00:00Z','2026-10-26T12:00:00Z' FROM loyalty.prizes p JOIN loyalty.rewards r ON r.id=p.reward_id WHERE p.trigger_type='STREAK_MILESTONE'");
                    c.commit();
                }
                var migration=at(url,"21");assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();assertThat(migration.migrate().migrationsExecuted).isZero();
                try(var c=DriverManager.getConnection(url,db.getUsername(),db.getPassword());var s=c.createStatement()) {
                    try(var rows=s.executeQuery("SELECT status,prize_name_snapshot,awarded_at,revocation_audit_id FROM loyalty.customer_prizes")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("AVAILABLE");assertThat(rows.getString(2)).isEqualTo("Ten-day streak");
                        assertThat(rows.getTimestamp(3).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-19T12:00:00Z"));assertThat(rows.getObject(4)).isNull();assertThat(rows.next()).isFalse();
                    }
                    for(String mutation:List.of("UPDATE loyalty.customer_prizes SET status='REVOKED'","UPDATE loyalty.customer_prizes SET prize_name_snapshot='Changed'","DELETE FROM loyalty.customer_prizes","UPDATE loyalty.prizes SET weekly_rank=1 WHERE weekly_rank=2")) {
                        assertThatThrownBy(()->s.executeUpdate(mutation)).isInstanceOf(SQLException.class);
                    }
                    UUID event=UUID.randomUUID();
                    s.executeUpdate("INSERT INTO loyalty.audit_logs(id,actor_user_id,actor_type,action,target_type,target_id,occurred_at,request_id,metadata) VALUES ('"+event+"','"+actor+"','USER','CUSTOMER_PRIZE_REVOKED','CUSTOMER_PRIZE','"+entitlement+"','2026-10-20T12:00:00Z','"+UUID.randomUUID()+"','{\"reason\":\"Verified correction\"}')");
                    s.executeUpdate("UPDATE loyalty.customer_prizes SET status='REVOKED',revocation_audit_id='"+event+"' WHERE id='"+entitlement+"'");
                    assertThatThrownBy(()->s.executeUpdate("UPDATE loyalty.customer_prizes SET status='AVAILABLE',revocation_audit_id=NULL")).isInstanceOf(SQLException.class);
                    try(var rows=s.executeQuery("SELECT count(*),sum(signed_amount) FROM loyalty.point_transactions")) {assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);assertThat(rows.getLong(2)).isEqualTo(100);}
                }
            } finally {ddl.execute("DROP DATABASE "+name+" WITH (FORCE)");}
        }
    }
}
