package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.api.*;
import com.amiraharcs.loyalty.competition.service.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Isolated prize catalogue/history fixtures; ordinary tests roll back all their writes. */
@Transactional
abstract class AdminPrizeTestSupport extends StaffQrTestSupport {
    static final java.time.Instant ADMIN_NOW=java.time.Instant.now().plus(java.time.Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @org.junit.jupiter.api.BeforeEach void adminTime() {org.mockito.Mockito.when(clock.instant()).thenReturn(ADMIN_NOW);}
    @Autowired com.amiraharcs.loyalty.competition.service.PrizeAdminService catalogue;
    @MockitoSpyBean com.amiraharcs.loyalty.competition.service.PrizeAssignmentAdminService assignmentAdmin;
    static final String ADMIN="/api/v1/admin/prizes";
    static final String ASSIGNMENTS="/api/v1/admin/prize-assignments";
    static final String PRIZES="/api/v1/customers/me/prizes";
    static final UUID STREAK_PRIZE=UUID.fromString("f0330000-0000-4000-8000-000000000010");
    static final UUID COFFEE=UUID.fromString("e83bb76e-b4bf-470b-a3cc-5fc67495f162");
    @Autowired com.amiraharcs.loyalty.visit.service.VisitService visitFlow;
    @Autowired com.amiraharcs.loyalty.competition.repository.CustomerPrizeRepository entitlements;
    @Autowired com.amiraharcs.loyalty.competition.repository.PrizeRepository prizeDefinitions;
    @Autowired WeeklyPrizeRules weeklyRules;
    @Autowired jakarta.persistence.EntityManager entities;
    @MockitoSpyBean PrizeAssignmentService prizeAssignments;
    private static final String URL=createDatabase();
    private static String createDatabase() {
        var database=PostgresTestDatabase.DATABASE;String name="admin_prizes_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+name);
            return "jdbc:postgresql://"+database.getHost()+":"+database.getMappedPort(5432)+"/"+name;
        } catch(SQLException error) {throw new IllegalStateException(error);}
    }
    @DynamicPropertySource static void prizeDatabase(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("spring.datasource.url",()->URL);registry.add("spring.flyway.url",()->URL);
        registry.add("spring.datasource.username",PostgresTestDatabase.DATABASE::getUsername);
        registry.add("spring.datasource.password",PostgresTestDatabase.DATABASE::getPassword);
        registry.add("spring.flyway.user",PostgresTestDatabase.DATABASE::getUsername);
        registry.add("spring.flyway.password",PostgresTestDatabase.DATABASE::getPassword);
    }
    UUID confirmDay(Setup setup,int day) {
        when(clock.instant()).thenReturn(ADMIN_NOW.plus(Duration.ofDays(day)));
        assume(setup.staff(),RoleCode.STAFF);
        return new TransactionTemplate(manager).execute(tx->visitFlow.confirmResolvedCustomer(customer(setup.customer()),setup.store().getId()).visitId());
    }
    UUID days(Setup setup,int count) {UUID last=null;for(int day=0;day<count;day++) {last=confirmDay(setup,day);}return last;}
    long prizeCount(Browser browser) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.customer_prizes WHERE customer_id=?",Long.class,customer(browser));}
    long balance(Browser browser) {return jdbc.queryForObject("SELECT coalesce(sum(t.signed_amount),0) FROM loyalty.point_transactions t JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=?",Long.class,customer(browser));}
    void freshLogin(Browser browser) throws Exception {
        browser.csrf();String email=jdbc.queryForObject("SELECT canonical_email FROM loyalty.users WHERE id=?",String.class,browser.userId);
        browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN",browser.csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))),201);browser.csrf();
    }
    CustomerPrizePage history(Browser browser,String query) throws Exception {
        return json.readValue(browser.send(get(PRIZES+query),200).getResponse().getContentAsString(),CustomerPrizePage.class);
    }
    Browser duplicateBrowser(Browser original) {
        var browser=new Browser();browser.cookies.putAll(original.cookies);browser.csrf=original.csrf;browser.userId=original.userId;return browser;
    }
}
