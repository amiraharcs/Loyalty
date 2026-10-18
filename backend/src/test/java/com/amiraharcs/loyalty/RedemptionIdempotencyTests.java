package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.reward.api.RedemptionResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RedemptionIdempotencyTests extends RedemptionTestSupport {
    @Test void exactRetryReturnsOriginalBodyStatusLocationAndBalanceAfterCatalogueOrStoreChanges() throws Exception {
        var setup = redemptionSetup(6000,3000); var key = UUID.randomUUID(); var original = redeem(setup,key,201);
        redeem(setup,UUID.randomUUID(),201); // Balance is now zero; replay still returns original creation balance 3000.
        jdbc.update("UPDATE loyalty.rewards SET active=false,point_cost=5000 WHERE id=?",setup.reward().getId());
        jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",setup.store().getId());
        var retry = redeem(setup,key,201);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo(original.getResponse().getHeader("Location"));
        assertThat(pending(setup.browser())).isEqualTo(2); assertThat(debits(setup.browser())).isEqualTo(2);
        assertThat(accounts.currentBalance(customer(setup.browser()))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='REWARD_REDEEMED'",Long.class,setup.browser().userId)).isEqualTo(2);
    }
    @Test void changedPayloadConflictsAndSameUuidKeyIsScopedToItsActor() throws Exception {
        var setup = redemptionSetup(3000,3000); var key = UUID.randomUUID(); var result = redeem(setup,key,201);
        var changed = json.writeValueAsString(Map.of("rewardId",UUID.randomUUID(),"storeId",setup.store().getId()));
        assertThat(code(redeem(setup.browser(),changed,key,409))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        var another = redemptionSetup(3000,3000);
        var other = redeem(another,key,201);
        assertThat(json.readValue(other.getResponse().getContentAsString(),RedemptionResponse.class).redemptionId())
                .isNotEqualTo(json.readValue(result.getResponse().getContentAsString(),RedemptionResponse.class).redemptionId());
        another.browser().send(get(result.getResponse().getHeader("Location")),404);
    }
    @Test void failedEligibilityDoesNotConsumeKeyAndTwoDistinctKeysWorkOnlyWhileBalanceAllows() throws Exception {
        var setup = redemptionSetup(0,3000); var key = UUID.randomUUID(); redeem(setup,key,409);
        nothingRedeemed(setup.browser(),0); fund(setup.browser(),6000);
        redeem(setup,key,201); redeem(setup,UUID.randomUUID(),201);
        assertThat(code(redeem(setup,UUID.randomUUID(),409))).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(pending(setup.browser())).isEqualTo(2); assertThat(debits(setup.browser())).isEqualTo(2);
    }
    @Test void replayRechecksCurrentAuthenticationAndCustomerGrant() throws Exception {
        var setup = redemptionSetup(3000,3000); var key = UUID.randomUUID(); redeem(setup,key,201);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",setup.browser().userId);
        redeem(setup,key,403);
        assume(setup.browser(),RoleCode.CUSTOMER);
        assertThatThrownBy(()->redemptionService.redeem(setup.request(),key,UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(pending(setup.browser())).isEqualTo(1);
    }
}
