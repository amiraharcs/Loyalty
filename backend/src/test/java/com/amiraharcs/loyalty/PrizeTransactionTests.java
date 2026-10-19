package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.service.PrizeAssignmentService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PrizeTransactionTests extends PrizeTestSupport {
    private Setup tenthDay(Setup setup) throws Exception {
        days(setup,9);when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(9)));
        freshLogin(setup.staff());freshLogin(setup.customer());
        return new Setup(setup.customer(),setup.staff(),setup.store(),issue(setup.customer()).token());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failureAfterEntitlementFlushRollsBackVisitEarnStreakSourceAuditAndRetryRecord() throws Exception {
        var setup=tenthDay(setup());var owner=customer(setup.customer());var resolution=scan(setup);var key=UUID.randomUUID();
        var before=jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?",owner);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure after prize persistence");})
                .when(AopTestUtils.<PrizeAssignmentService>getUltimateTargetObject(prizeAssignments)).assignStreak(any());
        try {assertThat(code(confirmHttp(setup.staff(),resolution.scanResolutionId(),key,500))).isEqualTo("INTERNAL_ERROR");}
        finally {reset(prizeAssignments);}
        assertThat(prizeCount(setup.customer())).isZero();assertThat(balance(setup.customer())).isEqualTo(900);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?",Long.class,owner)).isEqualTo(9);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?",owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM loyalty.scan_resolutions WHERE id=?",java.sql.Timestamp.class,resolution.scanResolutionId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND key=?",Long.class,setup.staff().userId,key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='STREAK_PRIZE_ASSIGNED'",Long.class,setup.staff().userId)).isZero();
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);assertThat(prizeCount(setup.customer())).isEqualTo(1);assertThat(balance(setup.customer())).isEqualTo(1000);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void competingTenthVisitRetriesCommitExactlyOneVisitEarnProjectionAndPrize() throws Exception {
        var setup=tenthDay(setup());var resolution=scan(setup);var key=UUID.randomUUID();var gate=new CountDownLatch(1);
        var first=duplicateBrowser(setup.staff());var second=duplicateBrowser(setup.staff());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return confirmHttp(first,resolution.scanResolutionId(),key,201);});
            var b=pool.submit(()->{gate.await();return confirmHttp(second,resolution.scanResolutionId(),key,201);});
            gate.countDown();assertThat(a.get(30,TimeUnit.SECONDS).getResponse().getContentAsString()).isEqualTo(b.get(30,TimeUnit.SECONDS).getResponse().getContentAsString());
        }
        assertThat(prizeCount(setup.customer())).isEqualTo(1);assertThat(balance(setup.customer())).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT current_count FROM loyalty.streaks WHERE customer_id=?",Integer.class,customer(setup.customer()))).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?",Long.class,customer(setup.customer()))).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=? AND action='STREAK_PRIZE_ASSIGNED'",Long.class,setup.staff().userId)).isEqualTo(1);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentDirectAssignmentOfSameTriggerReturnsSameEntitlement() throws Exception {
        var setup=setup();jdbc.update("UPDATE loyalty.prizes SET active=false WHERE id=?",STREAK_PRIZE);
        UUID visit;
        try {visit=days(setup,10);} finally {jdbc.update("UPDATE loyalty.prizes SET active=true WHERE id=?",STREAK_PRIZE);}
        assertThat(prizeCount(setup.customer())).isZero();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<UUID> call=()->{
                gate.await();assume(setup.staff(),RoleCode.STAFF);
                try {return new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx->prizeAssignments.assignStreak(visit).orElseThrow().getId());}
                finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            };
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(b.get(30,TimeUnit.SECONDS));
        }
        assertThat(prizeCount(setup.customer())).isEqualTo(1);assertThat(balance(setup.customer())).isEqualTo(1000);
    }
}
