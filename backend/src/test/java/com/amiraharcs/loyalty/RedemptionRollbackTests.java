package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.AopTestUtils;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedemptionRollbackTests extends RedemptionTestSupport {
    @ParameterizedTest @ValueSource(strings={"beforeDebit","afterDebit","audit","idempotency"})
    void anyFailureRemovesRedemptionDebitAuditAndRetryResultAndPreservesKeyForRetry(String stage) throws Exception {
        var setup = redemptionSetup(3000,3000); var key = UUID.randomUUID();
        var before = jdbc.queryForMap("SELECT a.version FROM loyalty.loyalty_accounts a WHERE a.customer_id=?",customer(setup.browser()));
        switch(stage) {
            case "beforeDebit" -> doAnswer(call->{
                assertThat(pending(setup.browser())).isEqualTo(1); assertThat(debits(setup.browser())).isZero();
                throw new IllegalStateException("Synthetic failure between request and debit");
            }).when(AopTestUtils.<LoyaltyAccountService>getUltimateTargetObject(accounts)).recordRedemptionDebit(any());
            case "afterDebit" -> doAnswer(call->{
                call.callRealMethod(); assertThat(debits(setup.browser())).isEqualTo(1);
                throw new IllegalStateException("Synthetic failure after debit");
            }).when(AopTestUtils.<LoyaltyAccountService>getUltimateTargetObject(accounts)).recordRedemptionDebit(any());
            case "audit" -> doAnswer(call->{call.callRealMethod(); throw new IllegalStateException("Synthetic failure after audit");})
                    .when(AopTestUtils.<AuditService>getUltimateTargetObject(audit)).append(eq(setup.browser().userId),eq("REWARD_REDEEMED"),anyString(),any(),any(),any(),anyString());
            case "idempotency" -> doAnswer(call->{call.callRealMethod(); throw new IllegalStateException("Synthetic failure after retry INSERT");})
                    .when(AopTestUtils.<IdempotencyService>getUltimateTargetObject(idempotency)).complete(eq(setup.browser().userId),eq("CUSTOMER_REWARD_REDEMPTION"),eq(key),anyString(),anyString(),any());
        }
        try {
            var result = redeem(setup,key,500);
            assertThat(code(result)).isEqualTo("INTERNAL_ERROR");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Synthetic","Exception","trace","SQL");
        } finally { reset(accounts,audit,idempotency); }
        nothingRedeemed(setup.browser(),3000);
        assertThat(jdbc.queryForMap("SELECT a.version FROM loyalty.loyalty_accounts a WHERE a.customer_id=?",customer(setup.browser()))).isEqualTo(before);
        redeem(setup,key,201);
        assertThat(pending(setup.browser())).isEqualTo(1); assertThat(debits(setup.browser())).isEqualTo(1);
    }
}
