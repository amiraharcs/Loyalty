package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.reward.api.FulfilmentResponse;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FulfilmentApiTests extends FulfilmentTestSupport {
    @Test void assignedStaffFulfilsOnceWithoutChangingLedgerOrSourceAndCustomerCanSeeIt() throws Exception {
        var pending=pending();var before=new HashMap<>(state(pending.id()));
        var ledger=jdbc.queryForList("SELECT * FROM loyalty.point_transactions WHERE account_id=? ORDER BY id",before.get("account_id"));
        var account=jdbc.queryForMap("SELECT * FROM loyalty.loyalty_accounts WHERE id=?",before.get("account_id"));
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        var result=fulfil(pending.staff(),pending.id(),UUID.randomUUID(),200);
        var response=json.readValue(result.getResponse().getContentAsString(),FulfilmentResponse.class);
        assertThat(response.fulfilledAt()).isEqualTo(NOW.plusSeconds(10));assertThat(response.status().name()).isEqualTo("FULFILLED");
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(4);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        var after=new HashMap<>(state(pending.id()));
        assertThat(after).containsEntry("fulfilled_by",pending.staff().userId).containsEntry("version",1L);
        for(String key:List.of("status","fulfilled_by","fulfilled_at","version")) { before.remove(key);after.remove(key); }
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM loyalty.point_transactions WHERE account_id=? ORDER BY id",before.get("account_id"))).isEqualTo(ledger);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.loyalty_accounts WHERE id=?",before.get("account_id"))).isEqualTo(account);
        var details=pending.setup().browser().send(get(REDEMPTIONS+"/"+pending.id()),200);
        assertThat(json.readTree(details.getResponse().getContentAsString()).get("fulfilledAt").asText()).isEqualTo(NOW.plusSeconds(10).toString());
        var auditRow=jdbc.queryForMap("SELECT actor_user_id,request_id,metadata::text FROM loyalty.audit_logs WHERE target_id=? AND action='REDEMPTION_FULFILLED'",pending.id());
        assertThat(auditRow.get("actor_user_id")).isEqualTo(pending.staff().userId);
        assertThat(auditRow.get("request_id").toString()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
        assertThat(auditRow.get("metadata").toString()).contains(pending.setup().store().getId().toString()).doesNotContain("email","password","Synthetic","session");
    }
    @ParameterizedTest @ValueSource(strings={"unassigned","otherStore","removedAssignment","inactiveStore","disabledStaff","revokedStaff","disabledCustomer","removedCustomerRole"})
    void currentStaffCustomerAndStoreScopeAreRequired(String scenario) throws Exception {
        var pending=pending();var staff=pending.staff();int status=404;
        switch(scenario) {
            case "unassigned" -> staff=login(RoleCode.STAFF);
            case "otherStore" -> { staff=login(RoleCode.STAFF);store(staff); }
            case "removedAssignment" -> jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=?",staff.userId);
            case "inactiveStore" -> jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",pending.setup().store().getId());
            case "disabledStaff" -> {jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",staff.userId);status=401;}
            case "revokedStaff" -> {jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",staff.userId);status=403;}
            case "disabledCustomer" -> jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",pending.setup().browser().userId);
            case "removedCustomerRole" -> jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",pending.setup().browser().userId);
        }
        fulfil(staff,pending.id(),UUID.randomUUID(),status);stillPending(pending);
    }
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","ADMIN"})
    void nonStaffCannotFulfilEvenWhenAssigned(String role) throws Exception {
        var pending=pending();var actor=login(RoleCode.valueOf(role));assign(actor,pending.setup().store());
        fulfil(actor,pending.id(),UUID.randomUUID(),403);assume(actor,RoleCode.valueOf(role));
        assertThatThrownBy(()->fulfilments.fulfil(pending.id(),UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        stillPending(pending);
    }
    @Test void explicitAdminAndStaffGrantWorksButSelfFulfilmentNeverDoes() throws Exception {
        var pending=pending();var actor=login(RoleCode.ADMIN,RoleCode.STAFF);assign(actor,pending.setup().store());
        fulfil(actor,pending.id(),UUID.randomUUID(),200);
        var self=redemptionSetup(3000,3000);var id=created(self);
        jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,'STAFF')",self.browser().userId);assign(self.browser(),self.store());
        fulfil(self.browser(),id,UUID.randomUUID(),403);
        assertThat(state(id)).containsEntry("status","PENDING").containsEntry("fulfilled_by",null);
    }
    @ParameterizedTest @ValueSource(strings={"exact","after","cancelled","expired"})
    void expiryIsExclusiveAndOtherTerminalStatesConflictWithoutRefund(String scenario) throws Exception {
        var pending=pending();
        if(scenario.equals("cancelled")||scenario.equals("expired")) { jdbc.update("UPDATE loyalty.reward_redemptions SET status=? WHERE id=?",scenario.toUpperCase(),pending.id()); }
        else { when(clock.instant()).thenReturn(NOW.plusSeconds(86400+(scenario.equals("after")?1:0))); }
        // Fresh authentication at the advanced Clock avoids confusing session expiry with business expiry.
        var actor=login(RoleCode.STAFF);assign(actor,pending.setup().store());
        assertThat(code(fulfil(actor,pending.id(),UUID.randomUUID(),409))).isEqualTo("CONFLICT");
        assertThat(state(pending.id()).get("status")).isEqualTo(scenario.equals("cancelled")?"CANCELLED":scenario.equals("expired")?"EXPIRED":"PENDING");
        assertThat(state(pending.id()).get("fulfilled_by")).isNull();assertThat(debits(pending.setup().browser())).isEqualTo(1);
        assertThat(accounts.currentBalance(customer(pending.setup().browser()))).isZero();
    }
    @Test void immediatelyBeforeExpiryStillSucceeds() throws Exception {
        var pending=pending();when(clock.instant()).thenReturn(NOW.plusSeconds(86400).minusNanos(1000));
        var actor=login(RoleCode.STAFF);assign(actor,pending.setup().store());fulfil(actor,pending.id(),UUID.randomUUID(),200);
    }
    @ParameterizedTest @ValueSource(strings={"customerId","staffUserId","storeId","pointCost","status","fulfilledAt","balance","refundAmount"})
    void emptyBodyContractRejectsEveryCallerSuppliedField(String field) throws Exception {
        var pending=pending();pending.staff().send(post(fulfilPath(pending.id())).header("X-CSRF-TOKEN",pending.staff().csrf)
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of(field,"untrusted"))),400);
        stillPending(pending);
    }
    @Test void headersCsrfAuthenticationAndResourceExistenceAreEnforced() throws Exception {
        var pending=pending();
        pending.staff().send(post(fulfilPath(pending.id())).header("Idempotency-Key",UUID.randomUUID()),403);
        pending.staff().send(post(fulfilPath(pending.id())).header("X-CSRF-TOKEN",pending.staff().csrf),400);
        pending.staff().send(post(fulfilPath(pending.id())).header("X-CSRF-TOKEN",pending.staff().csrf).header("Idempotency-Key","bad"),400);
        pending.staff().send(post(fulfilPath(pending.id())).header("X-CSRF-TOKEN",pending.staff().csrf).header("Idempotency-Key",UUID.randomUUID()).param("storeId",UUID.randomUUID().toString()),400);
        mvc.perform(post(fulfilPath(pending.id())).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isUnauthorized());
        fulfil(pending.staff(),UUID.randomUUID(),UUID.randomUUID(),404);stillPending(pending);
        pending.staff().send(post(fulfilPath(pending.id())).header("X-CSRF-TOKEN",pending.staff().csrf).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}"),200);
    }
}
