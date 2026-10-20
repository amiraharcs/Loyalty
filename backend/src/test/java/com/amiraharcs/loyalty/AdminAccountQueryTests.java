package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdminAccountQueryTests extends AdminAccountTestSupport {
    @Test void customerAndStaffDetailsUseSafeAllowlistedProjections() throws Exception {
        var admin=login(RoleCode.ADMIN);var customer=login(RoleCode.CUSTOMER);var staff=login(RoleCode.STAFF);var shop=store(staff);
        var member=customer(customer);
        var detail=admin.send(get(ROOT+"/customers/"+member),200).getResponse().getContentAsString();
        var body=json.readTree(detail);assertThat(body.get("customerId").asText()).isEqualTo(member.toString());
        assertThat(body.get("balance").asLong()).isZero();assertThat(body.get("currentStreak").asInt()).isZero();
        var employee=admin.send(get(ROOT+"/staff/"+staff.userId),200).getResponse().getContentAsString();
        assertThat(json.readTree(employee).get("activeStoreIds").get(0).asText()).isEqualTo(shop.getId().toString());
        for(String text:List.of(detail,employee)) {assertThat(text).doesNotContain("email","password","hash","session","csrf","version","canonicalEmail");}
        var filtered=json.readTree(admin.send(get(ROOT+"/customers").param("customerId",member.toString()).param("status","ACTIVE"),200).getResponse().getContentAsString());
        assertThat(filtered.get("items").size()).isEqualTo(1);
        var employees=json.readTree(admin.send(get(ROOT+"/staff").param("userId",staff.userId.toString()),200).getResponse().getContentAsString());
        assertThat(employees.get("items").size()).isEqualTo(1);
    }
    @Test void rejectedPersonalSearchValuesAreNotLogged(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var admin=login(RoleCode.ADMIN);String raw="personal-admin-search@example.org";
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.web.servlet.DispatcherServlet");
        var previous=logger.getLevel();int offset=output.getAll().length();
        try {logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            var result=admin.send(get(ROOT+"/customers").queryParam("email",raw),400);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(raw);
            assertThat(output.getAll().substring(offset)).doesNotContain(raw);
        } finally {logger.setLevel(previous);}
    }
    @Test void supportReadAuditContainsOnlyTrustedActorTargetAndRequest() throws Exception {
        var admin=login(RoleCode.ADMIN);var member=login(RoleCode.CUSTOMER);
        var result=admin.send(get(ROOT+"/customers/"+customer(member)),200);
        var event=jdbc.queryForMap("SELECT actor_user_id,target_id,metadata::text AS metadata FROM loyalty.audit_logs WHERE action='ADMIN_CUSTOMER_VIEW' AND request_id=?",
                UUID.fromString(result.getResponse().getHeader("X-Request-ID")));
        assertThat(event).containsEntry("actor_user_id",admin.userId).containsEntry("target_id",member.userId).containsEntry("metadata","{}");
    }
    @Test void customerSummaryReflectsLedgerAndDisplayedStreak() throws Exception {
        var admin=login(RoleCode.ADMIN);var member=login(RoleCode.CUSTOMER);UUID id=customer(member);
        jdbc.update("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at) SELECT ?,id,275,'ADJUSTMENT',?,'Synthetic management fixture',? FROM loyalty.loyalty_accounts WHERE customer_id=?",
                UUID.randomUUID(),admin.userId,java.sql.Timestamp.from(NOW),id);
        jdbc.update("UPDATE loyalty.streaks SET current_count=4,longest_count=4,last_qualifying_date=? WHERE customer_id=?",com.amiraharcs.loyalty.visit.model.Visit.businessDateAt(NOW).minusDays(1),id);
        var body=json.readTree(admin.send(get(ROOT+"/customers/"+id),200).getResponse().getContentAsString());
        assertThat(body.get("balance").asLong()).isEqualTo(275);assertThat(body.get("currentStreak").asInt()).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(strings={"customers","staff"})
    void deterministicPaginationHasNoDuplicatesAndBindsFilterScope(String kind) throws Exception {
        var admin=login(RoleCode.ADMIN);for(int i=0;i<3;i++) {login(kind.equals("staff")?RoleCode.STAFF:RoleCode.CUSTOMER);}
        var expected=jdbc.queryForList(kind.equals("staff")?"SELECT u.id FROM loyalty.users u WHERE EXISTS(SELECT 1 FROM loyalty.user_roles r WHERE r.user_id=u.id AND role_code='STAFF') OR EXISTS(SELECT 1 FROM loyalty.staff_store s WHERE s.staff_user_id=u.id) ORDER BY created_at DESC,id DESC":"SELECT u.id FROM loyalty.users u JOIN loyalty.customer_profiles c ON c.user_id=u.id ORDER BY u.created_at DESC,u.id DESC",UUID.class);
        var actual=new ArrayList<UUID>();String cursor=null;String firstCursor=null;
        do {
            var request=get(ROOT+"/"+kind).param("size","2");if(cursor!=null) {request.param("cursor",cursor);}
            var page=json.readTree(admin.send(request,200).getResponse().getContentAsString());
            page.get("items").forEach(row->actual.add(UUID.fromString(row.get("userId").asText())));
            cursor=page.get("hasNext").asBoolean()?page.get("nextCursor").asText():null;if(firstCursor==null) {firstCursor=cursor;}
        } while(cursor!=null);
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        admin.send(get(ROOT+"/"+kind).param("cursor",firstCursor).param("status","DISABLED"),400);
        admin.send(get(ROOT+"/"+(kind.equals("staff")?"customers":"staff")).param("cursor",firstCursor),400);
    }
    @ParameterizedTest @ValueSource(strings={"size=0","size=101","size=-1","cursor=broken","status=DELETED","email=secret@example.org","sort=password","customerId=bad"})
    void invalidSearchFiltersAreSafe(String query) throws Exception {
        var admin=login(RoleCode.ADMIN);var pair=query.split("=",2);
        var result=admin.send(get(ROOT+"/customers").param(pair[0],pair[1]),400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");assertThat(result.getResponse().getContentAsString()).doesNotContain("secret@example.org");
    }
    @ParameterizedTest @EnumSource(value=RoleCode.class,names={"CUSTOMER","STAFF"})
    void nonAdminsCannotReadOrMutate(RoleCode role) throws Exception {
        var browser=login(role);browser.send(get(ROOT+"/customers"),403);browser.send(get(ROOT+"/staff"),403);
        status(browser,browser.userId,"DISABLED",403);role(browser,browser.userId,"ADMIN",true,403);
        assign(browser,browser,UUID.randomUUID(),403);
    }
    @Test void unauthenticatedAndCsrfLessMutationsDenied() throws Exception {
        new Browser().send(get(ROOT+"/customers"),401);
        var admin=login(RoleCode.ADMIN);admin.send(patch(ROOT+"/users/"+admin.userId).contentType("application/json").content("{\"status\":\"DISABLED\"}"),403);
    }
    @ParameterizedTest @ValueSource(strings={"customers","staff"})
    void absentDetailsAre404(String kind) throws Exception {var admin=login(RoleCode.ADMIN);admin.send(get(ROOT+"/"+kind+"/"+UUID.randomUUID()),404);}
}
