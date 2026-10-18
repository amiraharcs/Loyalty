package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.common.api.*;
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

class FulfilmentLockingTests extends FulfilmentTestSupport {
    @ParameterizedTest @ValueSource(strings={"assignment","role","expiry"})
    void scopeAndTimeAreRecheckedAfterActualAccountLockWait(String changed) throws Exception {
        var pending=pending();UUID owner=customer(pending.setup().browser());var attempted=new CountDownLatch(1);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var result=new TransactionTemplate(manager).execute(tx->{
                accounts.lockForMutation(owner);
                doAnswer(call->{attempted.countDown();return call.callRealMethod();})
                        .when(AopTestUtils.<LoyaltyAccountService>getUltimateTargetObject(accounts)).lockForMutation(eq(owner));
                var future=worker.submit(()->{
                    assume(pending.staff(),RoleCode.STAFF);
                    try {fulfilments.fulfil(pending.id(),UUID.randomUUID(),UUID.randomUUID());return 200;}
                    catch(ResourceNotFoundException missing) {return 404;}
                    catch(AccessDeniedException denied) {return 403;}
                    catch(ConflictException expired) {return 409;}
                    finally {SecurityContextHolder.clearContext();}
                });
                try {if(!attempted.await(10,TimeUnit.SECONDS)) {throw new IllegalStateException("Worker did not attempt account lock");}}
                catch(InterruptedException exception) {Thread.currentThread().interrupt();throw new IllegalStateException(exception);}
                switch(changed) {
                    case "assignment" -> jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=?",pending.staff().userId);
                    case "role" -> jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",pending.staff().userId);
                    case "expiry" -> when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
                }
                assertThat(future.isDone()).isFalse();return future;
            });
            assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(changed.equals("assignment")?404:changed.equals("role")?403:409);
        } finally {reset(accounts);}
        stillPending(pending);
    }
}
