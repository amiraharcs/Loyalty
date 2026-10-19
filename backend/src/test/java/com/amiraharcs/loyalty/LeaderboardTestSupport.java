package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.leaderboard.api.LeaderboardPage;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Separate disposable DB isolates global ranking fixtures; each test rolls back its rows. */
@Transactional
abstract class LeaderboardTestSupport extends GameScoreTestSupport {
    static final String WEEKLY="/api/v1/leaderboards/weekly";
    static final String ALL="/api/v1/leaderboards/all-time";
    private static final String URL=createDatabase();
    private static String createDatabase() {
        var database=PostgresTestDatabase.DATABASE;
        String name="leaderboard_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+name);
            return "jdbc:postgresql://"+database.getHost()+":"+database.getMappedPort(5432)+"/"+name;
        } catch(SQLException exception) { throw new IllegalStateException(exception); }
    }
    @DynamicPropertySource static void leaderboardDatabase(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        // Explicit properties win over inherited dynamic DB_URL registration, independent of method order.
        registry.add("spring.datasource.url",()->URL);registry.add("spring.flyway.url",()->URL);
        registry.add("spring.datasource.username",PostgresTestDatabase.DATABASE::getUsername);
        registry.add("spring.datasource.password",PostgresTestDatabase.DATABASE::getPassword);
        registry.add("spring.flyway.user",PostgresTestDatabase.DATABASE::getUsername);
        registry.add("spring.flyway.password",PostgresTestDatabase.DATABASE::getPassword);
    }
    LeaderboardPage board(Browser browser,String path) throws Exception {
        return json.readValue(browser.send(get(path),200).getResponse().getContentAsString(),LeaderboardPage.class);
    }
    UUID fixtureCustomer(UUID id,String alias,boolean privateAlias,RoleCode... roles) {
        UUID user=UUID.randomUUID();
        jdbc.update("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,?)",
                user,user+"@example.org","synthetic-hash",Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES (?,?,'PRIVATE DISPLAY NAME',?,?)",id,user,alias,privateAlias);
        for(var role:roles) { jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,?)",user,role.name()); }
        jdbc.update("INSERT INTO loyalty.game_life_state(customer_id) VALUES (?)",id);
        return id;
    }
    UUID fixtureCustomer(String alias) { return fixtureCustomer(UUID.randomUUID(),alias,false,RoleCode.CUSTOMER); }
    UUID run(UUID customer,Instant started,Instant accepted,Long score) {
        return new TransactionTemplate(manager).execute(tx->{
            UUID session=UUID.randomUUID();
            jdbc.update("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version) VALUES (?,?,?,?,'ACTIVE','v1')",
                    session,customer,Timestamp.from(started),Timestamp.from(started.plusSeconds(900)));
            jdbc.update("INSERT INTO loyalty.game_life_consumptions(id,customer_id,session_id,consumed_at,recharge_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(),customer,session,Timestamp.from(started),Timestamp.from(started.plusSeconds(7200)));
            if(score!=null) {
                jdbc.update("UPDATE loyalty.game_sessions SET status='COMPLETED',completed_at=? WHERE id=?",Timestamp.from(accepted),session);
                jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,?,?)",UUID.randomUUID(),session,score,Timestamp.from(accepted));
            }
            return session;
        });
    }
    void accepted(UUID customer,long score,Instant accepted) { run(customer,accepted.minusSeconds(10),accepted,score); }
}
