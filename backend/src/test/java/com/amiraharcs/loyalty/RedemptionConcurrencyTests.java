package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.InsufficientPointsException;
import com.amiraharcs.loyalty.reward.api.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class RedemptionConcurrencyTests extends RedemptionTestSupport {
    @ParameterizedTest @ValueSource(strings={"sameReward","differentRewards","sameKey"})
    void realConcurrentTransactionsCannotOverspendOrDuplicateDebits(String mode) throws Exception {
        var setup = redemptionSetup(3000,3000); var other = mode.equals("differentRewards") ? fixture("Alternative",2000,true,null,null) : setup.reward();
        var start = new CountDownLatch(1); var firstKey = UUID.randomUUID(); var secondKey = mode.equals("sameKey") ? firstKey : UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(()->attempt(setup.browser(),setup.request(),firstKey,start));
            var right = executor.submit(()->attempt(setup.browser(),new RedemptionRequest(other.getId(),setup.store().getId()),secondKey,start));
            start.countDown(); var results = List.of(left.get(20,TimeUnit.SECONDS),right.get(20,TimeUnit.SECONDS));
            if(mode.equals("sameKey")) {
                assertThat(results.get(0)).isEqualTo(results.get(1));
                assertThat(results.get(0)).isInstanceOf(RedemptionResponse.class);
            } else { assertThat(results.stream().filter("INSUFFICIENT_POINTS"::equals).count()).isEqualTo(1); }
        }
        assertThat(pending(setup.browser())).isEqualTo(1); assertThat(debits(setup.browser())).isEqualTo(1);
        long cost = jdbc.queryForObject("SELECT point_cost_snapshot FROM loyalty.reward_redemptions WHERE customer_id=?",Long.class,customer(setup.browser()));
        assertThat(accounts.currentBalance(customer(setup.browser()))).isEqualTo(3000-cost).isNotNegative();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='REWARD_REDEEMED'",Long.class,setup.browser().userId)).isEqualTo(1);
    }
    private Object attempt(Browser browser,RedemptionRequest request,UUID key,CountDownLatch start) throws Exception {
        if(!start.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Synchronization timeout"); }
        assume(browser,RoleCode.CUSTOMER);
        try { return redemptionService.redeem(request,key,UUID.randomUUID()); }
        catch(InsufficientPointsException insufficient) { return "INSUFFICIENT_POINTS"; }
        finally { SecurityContextHolder.clearContext(); }
    }
}
