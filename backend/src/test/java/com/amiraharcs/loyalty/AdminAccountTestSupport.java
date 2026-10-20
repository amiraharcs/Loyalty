package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.admin.service.*;
import com.amiraharcs.loyalty.auth.service.AdminAccountManagement;
import com.amiraharcs.loyalty.auth.model.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Dedicated database: final-admin tests must not count other suites' synthetic administrators. */
abstract class AdminAccountTestSupport extends StaffQrTestSupport {
    @Autowired AdminAccountManagement management;
    static final String ROOT="/api/v1/admin";
    private static final String URL=createDatabase();
    private static String createDatabase() {
        var db=PostgresTestDatabase.DATABASE;String name="admin_accounts_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+name);return "jdbc:postgresql://"+db.getHost()+":"+db.getMappedPort(5432)+"/"+name;
        } catch(SQLException error) {throw new IllegalStateException(error);}
    }
    @DynamicPropertySource static void managementDatabase(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("spring.datasource.url",()->URL);registry.add("spring.flyway.url",()->URL);
        registry.add("spring.datasource.username",PostgresTestDatabase.DATABASE::getUsername);
        registry.add("spring.datasource.password",PostgresTestDatabase.DATABASE::getPassword);
        registry.add("spring.flyway.user",PostgresTestDatabase.DATABASE::getUsername);
        registry.add("spring.flyway.password",PostgresTestDatabase.DATABASE::getPassword);
    }
    @BeforeEach void resetAdminFixtures() {jdbc.update("DELETE FROM loyalty.user_roles WHERE role_code='ADMIN'");}
    MvcResult mutate(Browser actor,MockHttpServletRequestBuilder builder,Object body,int status) throws Exception {
        builder.header("X-CSRF-TOKEN",actor.csrf).header("X-Request-ID","untrusted");
        if(body!=null) {builder.contentType("application/json").content(json.writeValueAsString(body));}
        var result=actor.send(builder,status);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        if(status>=400) {code(result);}
        return result;
    }
    MvcResult status(Browser actor,UUID target,String status,int expected) throws Exception {
        return mutate(actor,patch(ROOT+"/users/"+target),Map.of("status",status),expected);
    }
    MvcResult role(Browser actor,UUID target,String role,boolean grant,int expected) throws Exception {
        return mutate(actor,grant?post(ROOT+"/users/"+target+"/roles/"+role):delete(ROOT+"/users/"+target+"/roles/"+role),null,expected);
    }
    MvcResult assign(Browser admin,Browser staff,UUID store,int expected) throws Exception {
        return mutate(admin,post(ROOT+"/staff-store-assignments"),Map.of("staffUserId",staff.userId,"storeId",store),expected);
    }
    MvcResult assignment(Browser admin,Browser staff,UUID store,boolean active,int expected) throws Exception {
        return mutate(admin,patch(ROOT+"/staff-store-assignments/"+staff.userId+"/"+store),Map.of("active",active),expected);
    }
    void freshLogin(Browser browser,int expected) throws Exception {
        String email=jdbc.queryForObject("SELECT canonical_email FROM loyalty.users WHERE id=?",String.class,browser.userId);
        browser.csrf();browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN",browser.csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))),expected);
        if(expected==201) {browser.csrf();}
    }
    long sessions(Browser browser) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.spring_session WHERE principal_name=?",Long.class,browser.userId.toString());}
    long events(UUID user,String action) {return jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action=?",Long.class,user,action);}
    void staffLookup(Browser staff,Browser member,UUID store,int expected) throws Exception {
        staff.send(get("/api/v1/staff/customers").queryParam("identifier",customer(member).toString()).queryParam("storeId",store.toString()),expected);
    }
}
