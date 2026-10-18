package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.reward.api.*;
import com.amiraharcs.loyalty.reward.model.*;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RedemptionApiTests extends RedemptionTestSupport {
    @Test void exactBalanceCreatesPendingSnapshotAndOneMatchingImmutableDebitWithOwnedLocation() throws Exception {
        var setup = redemptionSetup(3000,3000); var result = redeem(setup,UUID.randomUUID(),201);
        var response = json.readValue(result.getResponse().getContentAsString(),RedemptionResponse.class);
        assertThat(response.newBalance()).isZero(); assertThat(response.pointCost()).isEqualTo(3000);
        assertThat(response.status()).isEqualTo(RedemptionStatus.PENDING); assertThat(response.requestedAt()).isEqualTo(NOW);
        assertThat(Duration.between(response.requestedAt(),response.expiresAt())).isEqualTo(Duration.ofHours(24));
        assertThat(response.rewardId()).isEqualTo(setup.reward().getId()); assertThat(response.storeId()).isEqualTo(setup.store().getId());
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(9);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(REDEMPTIONS+"/"+response.redemptionId());
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        var details = setup.browser().send(get(result.getResponse().getHeader("Location")),200);
        assertThat(details.getResponse().getContentAsString()).doesNotContain("customerId","accountId","version","fulfilledBy");
        var row = jdbc.queryForMap("SELECT * FROM loyalty.reward_redemptions WHERE id=?",response.redemptionId());
        assertThat(row).containsEntry("source","POINTS").containsEntry("status","PENDING").containsEntry("point_cost_snapshot",3000L)
                .containsEntry("reward_name_snapshot","Coffee").containsEntry("reward_description_snapshot","Synthetic catalogue description")
                .containsEntry("fulfilled_by",null).containsEntry("fulfilled_at",null);
        var debit = jdbc.queryForMap("SELECT type,signed_amount,actor_id,visit_id,redemption_id,created_at FROM loyalty.point_transactions WHERE redemption_id=?",response.redemptionId());
        assertThat(debit).containsEntry("type","REDEMPTION").containsEntry("signed_amount",-3000L).containsEntry("actor_id",setup.browser().userId)
                .containsEntry("visit_id",null).containsEntry("redemption_id",response.redemptionId());
        assertThat(debits(setup.browser())).isEqualTo(1); assertThat(pending(setup.browser())).isEqualTo(1);
        var event = jdbc.queryForMap("SELECT actor_user_id,request_id,metadata::text FROM loyalty.audit_logs WHERE target_id=?",response.redemptionId());
        assertThat(event.get("actor_user_id")).isEqualTo(setup.browser().userId);
        assertThat(event.get("request_id").toString()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
        assertThat(event.get("metadata").toString()).contains(setup.reward().getId().toString(),setup.store().getId().toString(),"3000").doesNotContain("email","password","session","Synthetic");
    }
    @ParameterizedTest @ValueSource(longs={0,2999})
    void insufficientPointsCannotCommitAnyPartOfRedemption(long balance) throws Exception {
        var setup = redemptionSetup(balance,3000);
        assertThat(code(redeem(setup,UUID.randomUUID(),409))).isEqualTo("INSUFFICIENT_POINTS");
        nothingRedeemed(setup.browser(),balance);
    }
    @ParameterizedTest @ValueSource(strings={"inactive","future","expired","missing","zeroCost"})
    void unavailableRewardsAreRejectedEvenIfCallerPreviouslySawTheCatalogue(String state) throws Exception {
        var setup = redemptionSetup(3000,3000);
        switch(state) {
            case "inactive" -> jdbc.update("UPDATE loyalty.rewards SET active=false WHERE id=?",setup.reward().getId());
            case "future" -> jdbc.update("UPDATE loyalty.rewards SET available_from=? WHERE id=?",java.sql.Timestamp.from(NOW.plusSeconds(1)),setup.reward().getId());
            case "expired" -> jdbc.update("UPDATE loyalty.rewards SET available_until=? WHERE id=?",java.sql.Timestamp.from(NOW),setup.reward().getId());
            case "missing" -> { var request = json.writeValueAsString(Map.of("rewardId",UUID.randomUUID(),"storeId",setup.store().getId()));
                redeem(setup.browser(),request,UUID.randomUUID(),404); nothingRedeemed(setup.browser(),3000); return; }
            case "zeroCost" -> jdbc.update("UPDATE loyalty.rewards SET point_cost=0 WHERE id=?",setup.reward().getId());
        }
        redeem(setup,UUID.randomUUID(),state.equals("zeroCost")?409:404); nothingRedeemed(setup.browser(),3000);
    }
    @Test void availabilityStartIsInclusiveAndEndExclusiveUsingServerClock() throws Exception {
        var setup = redemptionSetup(6000,3000);
        jdbc.update("UPDATE loyalty.rewards SET available_from=?,available_until=? WHERE id=?",java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW.plusSeconds(1)),setup.reward().getId());
        redeem(setup,UUID.randomUUID(),201);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        redeem(setup,UUID.randomUUID(),404);
        assertThat(pending(setup.browser())).isEqualTo(1); assertThat(accounts.currentBalance(customer(setup.browser()))).isEqualTo(3000);
    }
    @ParameterizedTest @ValueSource(strings={"inactive","missing"})
    void targetStoreMustExistAndBeActive(String state) throws Exception {
        var setup = redemptionSetup(3000,3000);
        if(state.equals("inactive")) { jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",setup.store().getId()); redeem(setup,UUID.randomUUID(),404); }
        else { redeem(setup.browser(),json.writeValueAsString(Map.of("rewardId",setup.reward().getId(),"storeId",UUID.randomUUID())),UUID.randomUUID(),404); }
        nothingRedeemed(setup.browser(),3000);
    }
    @Test void catalogueEditsDoNotRewriteRedemptionBenefitOrCost() throws Exception {
        var setup = redemptionSetup(3000,3000); var created = redeem(setup,UUID.randomUUID(),201);
        var response = json.readValue(created.getResponse().getContentAsString(),RedemptionResponse.class);
        var admin = login(RoleCode.ADMIN);
        patch(admin,setup.reward().getId(),"{\"name\":\"New benefit\",\"description\":\"New description\",\"pointCost\":5000,\"active\":false}",200);
        var details = json.readValue(setup.browser().send(get(REDEMPTIONS+"/"+response.redemptionId()),200).getResponse().getContentAsString(),RedemptionDetails.class);
        assertThat(details.rewardName()).isEqualTo("Coffee"); assertThat(details.rewardDescription()).isEqualTo("Synthetic catalogue description");
        assertThat(details.pointCost()).isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT signed_amount FROM loyalty.point_transactions WHERE redemption_id=?",Long.class,response.redemptionId())).isEqualTo(-3000);
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void explicitRolesDoNotInheritCustomerRedemptionPermission(String role) throws Exception {
        var browser = login(RoleCode.valueOf(role));
        redeem(browser,json.writeValueAsString(Map.of("rewardId",UUID.randomUUID(),"storeId",UUID.randomUUID())),UUID.randomUUID(),403);
        browser.send(get(REDEMPTIONS+"/"+UUID.randomUUID()),403);
        assume(browser,RoleCode.valueOf(role));
        assertThatThrownBy(()->redemptionService.redeem(new RedemptionRequest(UUID.randomUUID(),UUID.randomUUID()),UUID.randomUUID(),UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void explicitCustomerGrantAllowsMultiRoleAccountToRedeemOwnPoints() throws Exception {
        var browser = login(RoleCode.CUSTOMER,RoleCode.STAFF,RoleCode.ADMIN); fund(browser,3000);
        var reward = fixture("Coffee",3000,true,null,null); var store = shop(StoreStatus.ACTIVE);
        redeem(browser,json.writeValueAsString(new RedemptionRequest(reward.getId(),store.getId())),UUID.randomUUID(),201);
        assertThat(accounts.currentBalance(customer(browser))).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"account","profile"})
    void missingCustomerStateIsNotRecreatedByRedemption(String missing) throws Exception {
        var setup = redemptionSetup(0,3000); UUID owner = customer(setup.browser());
        jdbc.update("DELETE FROM loyalty.loyalty_accounts WHERE customer_id=?",owner);
        if(missing.equals("profile")) {
            jdbc.update("DELETE FROM loyalty.streaks WHERE customer_id=?",owner);
            jdbc.update("DELETE FROM loyalty.game_life_state WHERE customer_id=?",owner);
            jdbc.update("DELETE FROM loyalty.customer_profiles WHERE id=?",owner);
        }
        redeem(setup,UUID.randomUUID(),404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.reward_redemptions WHERE customer_id=?",Long.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=?",Long.class,setup.browser().userId)).isZero();
    }
    @Test void anonymousDisabledAndRevokedCustomersAreDeniedAndServiceAlsoChecksGrants() throws Exception {
        mvc.perform(post(REDEMPTIONS).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var setup = redemptionSetup(3000,3000);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",setup.browser().userId);
        redeem(setup,UUID.randomUUID(),401); nothingRedeemed(setup.browser(),3000);
        var revoked = redemptionSetup(3000,3000);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.browser().userId);
        redeem(revoked,UUID.randomUUID(),403);
        assume(revoked.browser(),RoleCode.CUSTOMER);
        assertThatThrownBy(()->redemptionService.redeem(revoked.request(),UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @ParameterizedTest @ValueSource(strings={"customerId","pointCost","balance","signedAmount","source","status","expiresAt","actor","rewardNameSnapshot","customerPrizeId","serverTime"})
    void requestCannotSupplyOwnershipAmountLifecycleOrClock(String field) throws Exception {
        var setup = redemptionSetup(3000,3000); var request = new HashMap<String,Object>();
        request.put("rewardId",setup.reward().getId()); request.put("storeId",setup.store().getId()); request.put(field,"untrusted");
        redeem(setup.browser(),json.writeValueAsString(request),UUID.randomUUID(),400); nothingRedeemed(setup.browser(),3000);
    }
    @Test void validationCsrfAndIdempotencyHeadersAreRequired() throws Exception {
        var setup = redemptionSetup(3000,3000);
        for(String body:List.of("{}","{\"rewardId\":null,\"storeId\":null}","{\"rewardId\":\"invalid\",\"storeId\":\"invalid\"}")) { redeem(setup.browser(),body,UUID.randomUUID(),400); }
        setup.browser().send(post(REDEMPTIONS).header("X-CSRF-TOKEN",setup.browser().csrf).contentType("application/json").content(payload(setup)),400);
        setup.browser().send(post(REDEMPTIONS).header("X-CSRF-TOKEN",setup.browser().csrf).header("Idempotency-Key","invalid").contentType("application/json").content(payload(setup)),400);
        setup.browser().send(post(REDEMPTIONS).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(payload(setup)),403);
        nothingRedeemed(setup.browser(),3000);
    }
    @Test void resourceRecoveryIsOwnedAndCancellationAndDeletionRemainUnavailable() throws Exception {
        var setup = redemptionSetup(3000,3000); var result = redeem(setup,UUID.randomUUID(),201); var location = result.getResponse().getHeader("Location");
        var other = login(RoleCode.CUSTOMER); other.send(get(location),404);
        setup.browser().send(get(REDEMPTIONS+"/"+UUID.randomUUID()),404);
        setup.browser().send(get(REDEMPTIONS),200);
        setup.browser().send(delete(location).header("X-CSRF-TOKEN",setup.browser().csrf),405);
        setup.browser().send(post(location+"/cancellations").header("X-CSRF-TOKEN",setup.browser().csrf),404);
        var staff = login(RoleCode.STAFF); staff.send(post("/api/v1/staff/redemptions/"+UUID.randomUUID()+"/fulfilments").header("X-CSRF-TOKEN",staff.csrf).header("Idempotency-Key",UUID.randomUUID()),404);
    }
    @Test void maximumSafeBalanceAndPriceRemainExact() throws Exception {
        var setup = redemptionSetup(9007199254740991L,9007199254740991L);
        var response = json.readValue(redeem(setup,UUID.randomUUID(),201).getResponse().getContentAsString(),RedemptionResponse.class);
        assertThat(response.newBalance()).isZero(); assertThat(response.pointCost()).isEqualTo(9007199254740991L);
    }
    @ParameterizedTest @ValueSource(strings={"2026-03-28T12:00:00Z","2026-10-24T12:00:00Z"})
    void expiryIs24UtcHoursAcrossLondonDstChanges(String instant) throws Exception {
        when(clock.instant()).thenReturn(Instant.parse(instant));
        var setup = redemptionSetup(3000,3000);
        var response = json.readValue(redeem(setup,UUID.randomUUID(),201).getResponse().getContentAsString(),RedemptionResponse.class);
        assertThat(response.requestedAt()).isEqualTo(Instant.parse(instant));
        assertThat(response.expiresAt()).isEqualTo(Instant.parse(instant).plus(Duration.ofHours(24)));
    }
}
