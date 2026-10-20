package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdminAccountLifecycleTests extends AdminAccountTestSupport {
    @Test void disablingRevokesAllSessionsAndReactivationRequiresNewLogin() throws Exception {
        var admin=login(RoleCode.ADMIN);var member=login(RoleCode.CUSTOMER);assertThat(sessions(member)).isEqualTo(1);
        var originalCookies=new HashMap<>(member.cookies);status(admin,member.userId,"DISABLED",200);
        assertThat(sessions(member)).isZero();member.send(get("/api/v1/auth/me"),401);freshLogin(member,401);
        status(admin,member.userId,"ACTIVE",200);
        member.cookies.clear();member.cookies.putAll(originalCookies);member.send(get("/api/v1/auth/me"),401);
        freshLogin(member,201);member.send(get("/api/v1/customers/me/streak"),200);
        assertThat(events(member.userId,"USER_DISABLED")).isEqualTo(1);assertThat(events(member.userId,"USER_ACTIVATED")).isEqualTo(1);
    }
    @Test void roleGrantAndRemovalRevokeOldSessionsAndDoNotImplyOtherRoles() throws Exception {
        var admin=login(RoleCode.ADMIN);var user=login(RoleCode.CUSTOMER);
        role(admin,user.userId,"CUSTOMER",false,200);freshLogin(user,201);
        role(admin,user.userId,"STAFF",true,200);user.send(get(ROOT+"/staff"),401);
        freshLogin(user,201);user.send(get("/api/v1/customers/me/streak"),403);
        role(admin,user.userId,"STAFF",false,200);user.send(get("/api/v1/staff/stores"),401);
        freshLogin(user,201);user.send(get("/api/v1/staff/customers").param("identifier",UUID.randomUUID().toString()).param("storeId",UUID.randomUUID().toString()),403);
        var response=json.readTree(role(admin,user.userId,"STAFF",true,200).getResponse().getContentAsString());
        assertThat(response.get("roles").size()).isEqualTo(1);assertThat(response.get("roles").get(0).asText()).isEqualTo("STAFF");
    }
    @Test void adminRevocationCannotLeaveAnOldBrowserWithAdminAuthority() throws Exception {
        var actor=login(RoleCode.ADMIN);var target=login(RoleCode.ADMIN);
        role(actor,target.userId,"ADMIN",false,200);target.send(get(ROOT+"/customers"),401);
        freshLogin(target,201);target.send(get(ROOT+"/customers"),403);
    }
    @ParameterizedTest @ValueSource(strings={"STATUS","ROLE"})
    void selfDemotionWithAnotherAdminCannotRestoreItsCurrentHttpSession(String operation) throws Exception {
        var admin=login(RoleCode.ADMIN);var survivor=login(RoleCode.ADMIN);
        if(operation.equals("STATUS")) {status(admin,admin.userId,"DISABLED",200);}
        else {role(admin,admin.userId,"ADMIN",false,200);}
        assertThat(sessions(admin)).isZero();admin.send(get(ROOT+"/customers"),401);
        survivor.send(get(ROOT+"/customers"),200);
    }
    @Test void reactivationDoesNotRestoreRemovedStaffGrantsOrAssignments() throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);var shop=store(staff);
        role(admin,staff.userId,"STAFF",false,200);status(admin,staff.userId,"DISABLED",200);status(admin,staff.userId,"ACTIVE",200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.user_roles WHERE user_id=? AND role_code='STAFF'",Long.class,staff.userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT active FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",Boolean.class,staff.userId,shop.getId())).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"status","role"})
    void finalAdminCannotDisableOrRemoveItself(String operation) throws Exception {
        var admin=login(RoleCode.ADMIN);var result=operation.equals("status")?status(admin,admin.userId,"DISABLED",409):role(admin,admin.userId,"ADMIN",false,409);
        assertThat(code(result)).isEqualTo("LAST_ADMIN_REQUIRED");assertThat(events(admin.userId,"USER_DISABLED")+events(admin.userId,"ROLE_REVOKED")).isZero();
        admin.send(get(ROOT+"/customers"),200);
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","staff","SYSTEM"})
    void invalidRolesRejected(String role) throws Exception {var admin=login(RoleCode.ADMIN);role(admin,UUID.randomUUID(),role,true,400);}
    @ParameterizedTest @ValueSource(strings={"{\"status\":\"DELETED\"}","{\"status\":null}","{}","{\"status\":\"ACTIVE\",\"password\":\"private\"}"})
    void invalidStatusAndMassAssignmentRejected(String body) throws Exception {
        var admin=login(RoleCode.ADMIN);mutate(admin,patch(ROOT+"/users/"+admin.userId),json.readTree(body),400);
    }
    @Test void staffGrantDoesNotCreateCustomerAggregatesForAnOperatorProvisionedIdentity() throws Exception {
        var admin=login(RoleCode.ADMIN);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) SELECT ?,?,password_hash,'ACTIVE',created_at,updated_at FROM loyalty.users WHERE id=?",
                id,id+"@example.org",admin.userId);
        role(admin,id,"STAFF",true,200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.customer_profiles WHERE user_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForList("SELECT role_code FROM loyalty.user_roles WHERE user_id=?",String.class,id)).containsExactly("STAFF");
        admin.send(get(ROOT+"/staff/"+id),200);role(admin,id,"CUSTOMER",true,409);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void roleEndpointsRejectUnrecognisedBodyFields(boolean grant) throws Exception {
        var admin=login(RoleCode.ADMIN);var target=login(RoleCode.CUSTOMER);
        mutate(admin,grant?post(ROOT+"/users/"+target.userId+"/roles/STAFF"):delete(ROOT+"/users/"+target.userId+"/roles/STAFF"),Map.of("actorUserId",admin.userId),400);
    }
    @Test void missingUserIs404() throws Exception {var admin=login(RoleCode.ADMIN);status(admin,UUID.randomUUID(),"DISABLED",404);}
    @Test void exactRetriesDoNotDuplicateAudit() throws Exception {
        var admin=login(RoleCode.ADMIN);var member=login(RoleCode.CUSTOMER);
        status(admin,member.userId,"DISABLED",200);status(admin,member.userId,"DISABLED",200);
        role(admin,member.userId,"STAFF",true,200);role(admin,member.userId,"STAFF",true,200);
        assertThat(events(member.userId,"USER_DISABLED")).isEqualTo(1);assertThat(events(member.userId,"ROLE_GRANTED")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"STATUS","ROLE"})
    void auditFailureRollsBackStatusSessionsAndAssignments(String operation) throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);var shop=store(staff);long count=sessions(staff);
        var target=org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.audit.service.AuditService>getUltimateTargetObject(audit);
        doAnswer(call->{call.callRealMethod();if(call.getArgument(1).equals(operation.equals("STATUS")?"USER_DISABLED":"ROLE_REVOKED")) {throw new IllegalStateException("private internal failure");}return null;}).when(target).append(any(),anyString(),anyString(),any(),any(),any(),anyString());
        try {var response=operation.equals("STATUS")?status(admin,staff.userId,"DISABLED",500):role(admin,staff.userId,"STAFF",false,500);assertThat(response.getResponse().getContentAsString()).doesNotContain("private internal failure");}
        finally {reset(audit);}
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.users WHERE id=?",String.class,staff.userId)).isEqualTo("ACTIVE");
        assertThat(sessions(staff)).isEqualTo(count);assertThat(events(staff.userId,"USER_DISABLED")+events(staff.userId,"ROLE_REVOKED")+events(staff.userId,"STAFF_STORE_DEACTIVATED")).isZero();
        assertThat(jdbc.queryForObject("SELECT active FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",Boolean.class,staff.userId,shop.getId())).isTrue();
        staff.send(get("/api/v1/auth/me"),200);
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.session.SessionRepository sessionRepository;
    @Test @SuppressWarnings("unchecked") void inFlightSessionSaveCannotResurrectARevokedSession() throws Exception {
        var admin=login(RoleCode.ADMIN);var member=login(RoleCode.CUSTOMER);
        String sessionId=jdbc.queryForObject("SELECT session_id FROM loyalty.spring_session WHERE principal_name=?",String.class,member.userId.toString());
        org.springframework.session.Session stale=(org.springframework.session.Session)sessionRepository.findById(sessionId);
        assertThat(stale).isNotNull();status(admin,member.userId,"DISABLED",200);status(admin,member.userId,"ACTIVE",200);
        stale.setAttribute("syntheticInFlightTouch",true);
        try {sessionRepository.save(stale);} catch(org.springframework.dao.DataAccessException rejectedStaleSave) { /* FK may reject stale attribute inserts. */ }
        assertThat(sessions(member)).isZero();assertThat(sessionRepository.findById(sessionId)).isNull();
        member.send(get("/api/v1/auth/me"),401);
    }
    @Test void historySurvivesDisableAndRoleRemoval() throws Exception {
        var s=setup();var resolution=scan(s);confirmHttp(s.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);
        var admin=login(RoleCode.ADMIN);var id=customer(s.customer());
        var before=jdbc.queryForList("SELECT to_jsonb(v)::text AS data FROM loyalty.visits v WHERE customer_id=?",id);
        var points=jdbc.queryForList("SELECT to_jsonb(t)::text AS data FROM loyalty.point_transactions t JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=?",id);
        status(admin,s.customer().userId,"DISABLED",200);role(admin,s.customer().userId,"CUSTOMER",false,200);
        assertThat(jdbc.queryForList("SELECT to_jsonb(v)::text AS data FROM loyalty.visits v WHERE customer_id=?",id)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text AS data FROM loyalty.point_transactions t JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=?",id)).isEqualTo(points);
    }
}
