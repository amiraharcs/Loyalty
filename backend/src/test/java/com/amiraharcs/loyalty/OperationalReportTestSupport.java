package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.admin.service.OperationalReportService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Transactional
abstract class OperationalReportTestSupport extends StaffQrTestSupport {
    static final String REPORTS="/api/v1/admin/reports/";
    @Autowired OperationalReportService reports;
    private static final String URL=createDatabase();
    private static String createDatabase() {
        var db=PostgresTestDatabase.DATABASE;String name="reports_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) {
            s.execute("CREATE DATABASE "+name);return "jdbc:postgresql://"+db.getHost()+":"+db.getMappedPort(5432)+"/"+name;
        } catch(SQLException error) {throw new IllegalStateException(error);}
    }
    @DynamicPropertySource static void reportDatabase(DynamicPropertyRegistry r) {
        PostgresTestDatabase.register(r);r.add("spring.datasource.url",()->URL);r.add("spring.flyway.url",()->URL);
        r.add("spring.datasource.username",PostgresTestDatabase.DATABASE::getUsername);r.add("spring.datasource.password",PostgresTestDatabase.DATABASE::getPassword);
        r.add("spring.flyway.user",PostgresTestDatabase.DATABASE::getUsername);r.add("spring.flyway.password",PostgresTestDatabase.DATABASE::getPassword);
    }
    Browser admin() throws Exception {return login(RoleCode.ADMIN);}
    void asAdmin(Browser admin) {assume(admin,RoleCode.ADMIN);}
    MvcResult read(Browser admin,String route,int status) throws Exception {
        var result=admin.send(get(REPORTS+route).header("X-Request-ID","untrusted"),status);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        if(status>=400) {code(result);}return result;
    }
    @Override com.amiraharcs.loyalty.store.model.Store store(Browser staff) {
        var shop=super.store(staff);fixtureEntities.flush();return shop;
    }
    UUID account(Browser member) {return jdbc.queryForObject("SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?",UUID.class,customer(member));}
    void adjustment(Browser member,long value,Instant at,String type) {
        jdbc.update("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at) VALUES (?,?,?,?,?,'Synthetic reporting fixture',?)",
                UUID.randomUUID(),account(member),value,type,member.userId,Timestamp.from(at));
    }
    UUID visit(Browser member,Browser staff,UUID store,Instant at) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO loyalty.visits(id,customer_id,account_id,store_id,confirmed_by,business_date,confirmed_at,earned_points) VALUES (?,?,?,?,?,?,?,100)",
                id,customer(member),account(member),store,staff.userId,com.amiraharcs.loyalty.visit.model.Visit.businessDateAt(at),Timestamp.from(at));
        jdbc.update("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,created_at,visit_id) VALUES (?,?,100,'EARN',?,?,?)",UUID.randomUUID(),account(member),staff.userId,Timestamp.from(at),id);
        return id;
    }
    UUID reward() {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO loyalty.rewards(id,name,description,point_cost,active,created_at,updated_at) VALUES (?,'Reporting benefit','Safe catalogue fixture',20,true,?,?)",id,Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW.minusSeconds(86400)));return id;
    }
    UUID redemption(Browser member,Browser staff,UUID store,UUID reward,Instant at,String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO loyalty.reward_redemptions(id,customer_id,account_id,store_id,reward_id,source,reward_name_snapshot,reward_description_snapshot,point_cost_snapshot,status,requested_at,expires_at) VALUES (?,?,?,?,?,'POINTS','Frozen coffee','Frozen benefit',20,'PENDING',?,?)",
                id,customer(member),account(member),store,reward,Timestamp.from(at),Timestamp.from(at.plusSeconds(86400)));
        jdbc.update("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,created_at,redemption_id) VALUES (?,?,-20,'REDEMPTION',?,?,?)",UUID.randomUUID(),account(member),member.userId,Timestamp.from(at),id);
        if(status.equals("FULFILLED")) {jdbc.update("UPDATE loyalty.reward_redemptions SET status='FULFILLED',fulfilled_by=?,fulfilled_at=? WHERE id=?",staff.userId,Timestamp.from(at.plusSeconds(60)),id);}
        else if(!status.equals("PENDING")) {jdbc.update("UPDATE loyalty.reward_redemptions SET status=? WHERE id=?",status,id);}
        return id;
    }
    UUID run(Browser member,Instant start,Long score) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version) VALUES (?,?,?,?,'ACTIVE','v1')",id,customer(member),Timestamp.from(start),Timestamp.from(start.plusSeconds(900)));
        jdbc.update("INSERT INTO loyalty.game_life_consumptions(id,customer_id,session_id,consumed_at,recharge_at) VALUES (?,?,?,?,?)",UUID.randomUUID(),customer(member),id,Timestamp.from(start),Timestamp.from(start.plusSeconds(7200)));
        if(score!=null) {Instant accepted=start.plusSeconds(10);jdbc.update("UPDATE loyalty.game_sessions SET status='COMPLETED',completed_at=? WHERE id=?",Timestamp.from(accepted),id);
            jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,?,?)",UUID.randomUUID(),id,score,Timestamp.from(accepted));}
        return id;
    }
    UUID prize(Browser member,UUID visit,Instant awarded,Instant expires) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO loyalty.customer_prizes(id,customer_id,prize_id,trigger_type,trigger_visit_id,prize_name_snapshot,benefit_type,benefit_description_snapshot,reward_id,reward_name_snapshot,reward_description_snapshot,status,awarded_at,expires_at)
            VALUES (?,?,'f0330000-0000-4000-8000-000000000010','STREAK_MILESTONE',?,'Ten-day streak','REWARD','One free coffee.','e83bb76e-b4bf-470b-a3cc-5fc67495f162','Free coffee','One free coffee.','AVAILABLE',?,?)
            """,id,customer(member),visit,Timestamp.from(awarded),Timestamp.from(expires));return id;
    }
    Map<String,Long> counts() {
        var result=new HashMap<String,Long>();
        for(String table:List.of("visits","point_transactions","reward_redemptions","game_sessions","game_scores","weekly_periods","customer_prizes","audit_logs")) {
            result.put(table,jdbc.queryForObject("SELECT count(*) FROM loyalty."+table,Long.class));
        }return result;
    }
}
