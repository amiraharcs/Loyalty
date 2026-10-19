package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StaffCustomerLookupSecurityTests extends StaffCustomerLookupTestSupport {
    @Test void assignedStaffFindsActiveMemberAndRecordsTrustedAudit() throws Exception {
        var s=lookupSetup(); var result=lookup(s,200); var body=json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("displayName").asText()).isEqualTo("Private synthetic member");
        assertThat(body.get("balance").asLong()).isZero(); assertThat(body.get("currentStreak").asInt()).isZero();
        assertThat(body.get("pendingRedemptions").size()).isZero();
        assertThat(body.get("hasMorePendingRedemptions").asBoolean()).isFalse();
        assertThat(event(result)).containsEntry("actor_user_id",s.staff().userId).containsEntry("store_id",s.store().getId())
                .containsEntry("target_type","CUSTOMER").containsEntry("target_id",s.identifier())
                .containsEntry("metadata","{\"outcome\": \"SUCCESS\"}");
    }
    @ParameterizedTest @EnumSource(value=RoleCode.class,names={"CUSTOMER","ADMIN"})
    void nonStaffRoleDenied(RoleCode role) throws Exception {
        var browser=login(role); lookup(browser,UUID.randomUUID().toString(),UUID.randomUUID().toString(),403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='CUSTOMER_LOOKUP'",Long.class,browser.userId)).isZero();
    }
    @Test void adminWithExplicitStaffGrantIsAllowed() throws Exception {
        var member=login(RoleCode.CUSTOMER);var staff=login(RoleCode.ADMIN,RoleCode.STAFF);var shop=store(staff);
        lookup(staff,customer(member).toString(),shop.getId().toString(),200);
    }
    @Test void unauthenticatedDenied() throws Exception { lookup(new Browser(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),401); }
    @Test void disabledStaffSessionIsRejected() throws Exception {
        var s=lookupSetup();jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",s.staff().userId);lookup(s,401);
    }
    @Test void removedStaffRoleTakesEffectOnNextRequest() throws Exception {
        var s=lookupSetup();jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",s.staff().userId);lookup(s,403);
    }
    @ParameterizedTest @ValueSource(strings={"OTHER_STORE","INACTIVE_STORE","INACTIVE_ASSIGNMENT","REMOVED_ASSIGNMENT"})
    void storeScopeCannotBeExpanded(String scenario) throws Exception {
        var s=lookupSetup();String target=s.store().getId().toString();
        switch(scenario) {
            case "OTHER_STORE" -> target=store(login(RoleCode.STAFF)).getId().toString();
            case "INACTIVE_STORE" -> jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",s.store().getId());
            case "INACTIVE_ASSIGNMENT" -> jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=? AND store_id=?",s.staff().userId,s.store().getId());
            default -> jdbc.update("DELETE FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",s.staff().userId,s.store().getId());
        }
        var result=lookup(s.staff(),s.identifier().toString(),target,404);
        assertThat(event(result)).containsEntry("store_id",null).containsEntry("target_type","CUSTOMER_LOOKUP_REQUEST")
                .containsEntry("target_id",UUID.fromString(result.getResponse().getHeader("X-Request-ID")))
                .containsEntry("metadata","{\"outcome\": \"STORE_DENIED\"}");
        assertThat(event(result).get("metadata").toString()).doesNotContain(s.identifier().toString(),target);
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","DISABLED","NO_CUSTOMER_ROLE"})
    void unserviceableCustomersHaveIdenticalSafeNotFound(String scenario) throws Exception {
        var s=lookupSetup();String id=s.identifier().toString();
        if(scenario.equals("UNKNOWN")) {id=UUID.randomUUID().toString();}
        if(scenario.equals("DISABLED")) {jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",s.customer().userId);}
        if(scenario.equals("NO_CUSTOMER_ROLE")) {jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",s.customer().userId);}
        var result=lookup(s.staff(),id,s.store().getId().toString(),404);
        assertThat(code(result)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(event(result)).containsEntry("target_type","CUSTOMER_LOOKUP_REQUEST").containsEntry("store_id",s.store().getId())
                .containsEntry("metadata","{\"outcome\": \"NOT_FOUND\"}");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","lookup-private@example.org","not-a-member","1-1-1-1-1","' OR true --"})
    void invalidIdentifiersAreSafeAndAudited(String identifier) throws Exception {
        var s=lookupSetup(); var result=lookup(s.staff(),identifier,s.store().getId().toString(),400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
        assertThat(event(result).get("metadata")).isEqualTo("{\"outcome\": \"INVALID_REQUEST\"}");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"invalid-store","1-1-1-1-1"})
    void missingAndMalformedStoreRejected(String store) throws Exception {
        var s=lookupSetup();lookup(s.staff(),s.identifier().toString(),store,400);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","extra"})
    void unsupportedOrRepeatedQueryParametersRejected(String kind) throws Exception {
        var s=lookupSetup();var request=get(LOOKUP).queryParam("identifier",s.identifier().toString()).queryParam("storeId",s.store().getId().toString());
        request.queryParam(kind.equals("duplicate")?"identifier":"email","private@example.org");
        var result=s.staff().send(request,400);assertThat(code(result)).isEqualTo("VALIDATION_ERROR");event(result);
    }
    @Test void arbitraryCustomerIdStillCannotConfirmAVisit() throws Exception {
        var s=lookupSetup();lookup(s,200);
        var result=s.staff().send(post("/api/v1/staff/visits").header("X-CSRF-TOKEN",s.staff().csrf)
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("customerId",s.identifier(),"storeId",s.store().getId()))),400);
        assertThat(code(result)).isEqualTo("MALFORMED_REQUEST");unchanged(s.identifier());
    }
}
