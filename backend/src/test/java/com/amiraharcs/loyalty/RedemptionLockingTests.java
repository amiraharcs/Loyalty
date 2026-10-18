package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedemptionLockingTests extends RedemptionTestSupport {
    @ParameterizedTest @ValueSource(strings={"expiredDuringWait","revokedDuringWait"})
    void eligibilityAndPermissionsAreRecheckedAfterActualAccountLockWait(String scenario) throws Exception {
        var setup = redemptionSetup(3000,3000); UUID customer = customer(setup.browser());
        jdbc.update("UPDATE loyalty.rewards SET available_until=? WHERE id=?",java.sql.Timestamp.from(NOW.plusSeconds(1)),setup.reward().getId());
        var attempted = new CountDownLatch(1);
        try(var executor = Executors.newSingleThreadExecutor()) {
            var future = new TransactionTemplate(manager).execute(tx->{
                accounts.lockForMutation(customer);
                doAnswer(call->{attempted.countDown();return call.callRealMethod();})
                        .when(AopTestUtils.<LoyaltyAccountService>getUltimateTargetObject(accounts)).lockForMutation(eq(customer));
                var blocked = executor.submit(()->{
                    assume(setup.browser(),RoleCode.CUSTOMER);
                    try { redemptionService.redeem(setup.request(),UUID.randomUUID(),UUID.randomUUID());return 201; }
                    catch(ResourceNotFoundException unavailable) { return 404; }
                    catch(AccessDeniedException denied) { return 403; }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try { if(!attempted.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Worker did not attempt lock"); } }
                catch(InterruptedException exception) { Thread.currentThread().interrupt();throw new IllegalStateException(exception); }
                if(scenario.equals("expiredDuringWait")) { when(clock.instant()).thenReturn(NOW.plusSeconds(1)); }
                else { jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",setup.browser().userId); }
                assertThat(blocked.isDone()).isFalse();
                return blocked; // Committing here releases the genuine PostgreSQL account lock.
            });
            assertThat(future.get(20,TimeUnit.SECONDS)).isEqualTo(scenario.equals("expiredDuringWait")?404:403);
        } finally { reset(accounts); }
        nothingRedeemed(setup.browser(),3000);
    }
}
