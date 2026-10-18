package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FulfilmentTransactionTests extends FulfilmentTestSupport {
    @Test void exactAndNaturalRetriesReturnOriginalEvidenceWithoutAnotherAudit() throws Exception {
        var pending=pending();var key=UUID.randomUUID();var original=fulfil(pending.staff(),pending.id(),key,200);
        var before=state(pending.id());
        for(UUID retry:List.of(key,UUID.randomUUID())) {
            assertThat(fulfil(pending.staff(),pending.id(),retry,200).getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        }
        assertThat(code(fulfil(pending.staff(),UUID.randomUUID(),key,409))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        var another=login(RoleCode.STAFF);assign(another,pending.setup().store());
        assertThat(fulfil(another,pending.id(),UUID.randomUUID(),200).getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        assertThat(state(pending.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='REDEMPTION_FULFILLED'",Long.class,pending.id())).isEqualTo(1);
        jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=?",pending.staff().userId);
        fulfil(pending.staff(),pending.id(),key,404);
    }
    @ParameterizedTest @ValueSource(strings={"audit","idempotency"})
    void rollbackRemovesTransitionActorTimeAuditAndRetryResult(String stage) throws Exception {
        var pending=pending();var key=UUID.randomUUID();
        if(stage.equals("audit")) {
            doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure after audit");})
                    .when(AopTestUtils.<AuditService>getUltimateTargetObject(audit)).append(eq(pending.staff().userId),eq("REDEMPTION_FULFILLED"),anyString(),any(),any(),any(),anyString());
        } else {
            doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure after retry result");})
                    .when(AopTestUtils.<IdempotencyService>getUltimateTargetObject(idempotency)).complete(eq(pending.staff().userId),eq("STAFF_REDEMPTION_FULFILMENT"),eq(key),anyString(),anyString(),any());
        }
        try { assertThat(code(fulfil(pending.staff(),pending.id(),key,500))).isEqualTo("INTERNAL_ERROR"); }
        finally { reset(audit,idempotency); }
        stillPending(pending);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND key=?",Long.class,pending.staff().userId,key)).isZero();
        fulfil(pending.staff(),pending.id(),key,200);
    }
    @ParameterizedTest @ValueSource(strings={"sameKey","differentKeys","differentStaff"})
    void competingTransactionsProduceOneTransitionActorTimeAndSuccessAudit(String mode) throws Exception {
        var pending=pending();var other=mode.equals("differentStaff")?login(RoleCode.STAFF):pending.staff();
        if(other!=pending.staff()) { assign(other,pending.setup().store()); }
        var start=new CountDownLatch(1);var key=UUID.randomUUID();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->attempt(pending.staff(),pending.id(),key,start));
            var second=workers.submit(()->attempt(other,pending.id(),mode.equals("sameKey")?key:UUID.randomUUID(),start));
            start.countDown();assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
        }
        assertThat(state(pending.id())).containsEntry("status","FULFILLED").containsEntry("version",1L);
        assertThat(state(pending.id()).get("fulfilled_by")).isIn(pending.staff().userId,other.userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='REDEMPTION_FULFILLED'",Long.class,pending.id())).isEqualTo(1);
        assertThat(debits(pending.setup().browser())).isEqualTo(1);assertThat(accounts.currentBalance(customer(pending.setup().browser()))).isZero();
    }
    private Object attempt(Browser staff,UUID id,UUID key,CountDownLatch start) throws Exception {
        if(!start.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Synchronization timeout"); }
        assume(staff,RoleCode.STAFF);
        try { return fulfilments.fulfil(id,key,UUID.randomUUID()); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
