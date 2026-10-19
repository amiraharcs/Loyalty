package com.amiraharcs.loyalty;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.api.AdminAssignmentResponse;
import com.amiraharcs.loyalty.competition.model.CustomerPrizeStatus;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdminPrizeAssignmentTests extends AdminPrizeTestSupport {
    UUID entitlement(Setup setup) {return jdbc.queryForObject("SELECT id FROM loyalty.customer_prizes WHERE customer_id=? ORDER BY awarded_at DESC LIMIT 1",UUID.class,customer(setup.customer()));}
    AdminAssignmentResponse revoke(Browser admin,UUID id,String reason,int status) throws Exception {
        var result=admin.send(post(ASSIGNMENTS+"/"+id+"/revocations").header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content(json.writeValueAsString(Map.of("reason",reason))),status);
        return status==200?json.readValue(result.getResponse().getContentAsString(),AdminAssignmentResponse.class):null;
    }
    @Test void adminListsFiltersPagesAndInspectsOnlySupportSafeSnapshots() throws Exception {
        var setup=setup();days(setup,20);var admin=login(RoleCode.ADMIN);String scope="?customerId="+customer(setup.customer())+"&size=1";
        var first=json.readTree(admin.send(get(ASSIGNMENTS+scope),200).getResponse().getContentAsString());
        var second=json.readTree(admin.send(get(ASSIGNMENTS+scope+"&cursor="+first.get("nextCursor").asText()),200).getResponse().getContentAsString());
        assertThat(first.get("items").get(0).get("customerPrizeId").asText()).isNotEqualTo(second.get("items").get(0).get("customerPrizeId").asText());
        assertThat(second.get("hasNext").asBoolean()).isFalse();
        String id=first.get("items").get(0).get("customerPrizeId").asText();String body=admin.send(get(ASSIGNMENTS+"/"+id),200).getResponse().getContentAsString();
        assertThat(body).doesNotContain("@","email","password","session","version","revocationAuditId");
        admin.send(get(ASSIGNMENTS).param("cursor",first.get("nextCursor").asText()),400);
        var expired=json.readTree(admin.send(get(ASSIGNMENTS).param("customerId",customer(setup.customer()).toString()).param("status","EXPIRED").param("prizeId",STREAK_PRIZE.toString()).param("triggerType","STREAK_MILESTONE"),200).getResponse().getContentAsString());
        assertThat(expired.get("items").size()).isEqualTo(1);
        admin.send(get(ASSIGNMENTS+"/"+UUID.randomUUID()),404);
    }
    @Test void availableRevocationIsAuditedAtomicAndExactRetryDoesNotRewriteReasonOrSnapshot() throws Exception {
        var setup=setup();days(setup,10);var admin=login(RoleCode.ADMIN);var id=entitlement(setup);
        var before=jdbc.queryForMap("SELECT * FROM loyalty.customer_prizes WHERE id=?",id);
        var result=revoke(admin,id,"Incorrect award confirmed",200);assertThat(result.status()).isEqualTo(CustomerPrizeStatus.REVOKED);
        assertThat(revoke(admin,id,"Incorrect award confirmed",200)).isEqualTo(result);revoke(admin,id,"Different reason",409);
        var after=jdbc.queryForMap("SELECT * FROM loyalty.customer_prizes WHERE id=?",id);
        before.remove("status");before.remove("revocation_audit_id");after.remove("status");after.remove("revocation_audit_id");assertThat(after).isEqualTo(before);
        assertThat(balance(setup.customer())).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='CUSTOMER_PRIZE_REVOKED' AND actor_user_id=? AND metadata->>'reason'='Incorrect award confirmed'",Long.class,id,admin.userId)).isEqualTo(1);
        freshLogin(setup.customer());assertThat(history(setup.customer(),"").items().getFirst().status()).isEqualTo(CustomerPrizeStatus.REVOKED);
    }
    @Test void effectiveExpiryCannotBeRevokedOrRevivedAndAllOtherStatesAreRejected() throws Exception {
        var setup=setup();days(setup,10);var id=entitlement(setup);
        when(clock.instant()).thenReturn(ADMIN_NOW.plus(Duration.ofDays(16)));var admin=login(RoleCode.ADMIN);
        revoke(admin,id,"Expired cannot change",409);
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.customer_prizes WHERE id=?",String.class,id)).isEqualTo("AVAILABLE");
        assertThat(json.readTree(admin.send(get(ASSIGNMENTS+"/"+id),200).getResponse().getContentAsString()).get("status").asText()).isEqualTo("EXPIRED");
        // Reserved/redeemed cannot currently be manufactured in PostgreSQL; domain guard is separately exercised.
        var p=entities.find(com.amiraharcs.loyalty.competition.model.CustomerPrize.class,id);
        for(var state:List.of(CustomerPrizeStatus.RESERVED,CustomerPrizeStatus.REDEEMED,CustomerPrizeStatus.EXPIRED)) {
            org.springframework.test.util.ReflectionTestUtils.setField(p,"status",state);
            assertThatThrownBy(()->p.revoke(UUID.randomUUID(),ADMIN_NOW.plus(Duration.ofDays(10)))).isInstanceOf(com.amiraharcs.loyalty.common.api.ConflictException.class);
        }
        entities.detach(p);
    }
    @ParameterizedTest @ValueSource(strings={""," ","a"})
    void reasonValidationAndMissingAssignments(String reason) throws Exception {
        var admin=login(RoleCode.ADMIN);revoke(admin,UUID.randomUUID(),reason,reason.isBlank()?400:404);
    }
    @ParameterizedTest @ValueSource(strings={"size=0","size=101","status=BAD","triggerType=CASH","customerId=bad","prizeId=bad","awardedFrom=bad","awardedFrom=2026-10-20T00:00:00Z&awardedTo=2026-10-19T00:00:00Z","awardedFrom=2020-01-01T00:00:00Z&awardedTo=2026-01-01T00:00:00Z","cursor=bad","sort=score","size=1&size=2"})
    void boundedFiltersRejectUnsupportedInput(String query) throws Exception {login(RoleCode.ADMIN).send(get(ASSIGNMENTS+"?"+query),400);}
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","STAFF"})
    void nonAdminCannotInspectOrRevoke(String role) throws Exception {
        var browser=login(RoleCode.valueOf(role));browser.send(get(ASSIGNMENTS),403);browser.send(get(ASSIGNMENTS+"/"+UUID.randomUUID()),403);
        revoke(browser,UUID.randomUUID(),"Denied",403);
    }
    @Test void revokeRequiresCsrfAndNoArbitraryPatchDeleteOrGrantExists() throws Exception {
        var admin=login(RoleCode.ADMIN);var id=UUID.randomUUID();
        admin.send(post(ASSIGNMENTS+"/"+id+"/revocations").contentType("application/json").content("{\"reason\":\"No CSRF\"}"),403);
        admin.send(patch(ASSIGNMENTS+"/"+id).header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content("{\"status\":\"AVAILABLE\"}"),405);
        admin.send(delete(ASSIGNMENTS+"/"+id).header("X-CSRF-TOKEN",admin.csrf),405);
        admin.send(post(ASSIGNMENTS).header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content("{}"),405);
    }
}
