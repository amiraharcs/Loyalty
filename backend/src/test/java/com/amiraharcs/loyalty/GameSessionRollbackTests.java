package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.game.service.GameLifeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameSessionRollbackTests extends GameSessionTestSupport {
    @MockitoSpyBean GameLifeService lives;
    @Test void failureAfterSessionInsertBeforeLifeRollsBackEverythingAndKeyCanRetry() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();
        doAnswer(call->{assertThat(sessionRows(customer(browser))).isEqualTo(1);throw new IllegalStateException("Synthetic before life failure");})
                .when(AopTestUtils.<GameLifeService>getUltimateTargetObject(lives)).consumeForSession(any());
        try {assertThat(code(startHttp(browser,key,null,500))).isEqualTo("INTERNAL_ERROR");}
        finally {reset(lives);}
        noStart(browser);startHttp(browser,key,null,201);
    }
    @Test void failureAfterLifeBeforeCommitRollsBackSessionConsumptionVersionAndAudit() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();
        doAnswer(call->{call.callRealMethod();assertThat(consumed(customer(browser))).isEqualTo(1);throw new IllegalStateException("Synthetic after life failure");})
                .when(AopTestUtils.<GameLifeService>getUltimateTargetObject(lives)).consumeForSession(any());
        try {assertThat(code(startHttp(browser,key,null,500))).isEqualTo("INTERNAL_ERROR");}
        finally {reset(lives);}
        noStart(browser);startHttp(browser,key,null,201);
    }
    @Test void failureAfterRetryResultWasWrittenAlsoRollsBackSuccessfulAuditAndSourcePair() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic after retry write failure");})
                .when(AopTestUtils.<com.amiraharcs.loyalty.common.idempotency.IdempotencyService>getUltimateTargetObject(idempotency))
                .complete(eq(browser.userId),eq("CUSTOMER_GAME_START"),eq(key),anyString(),anyString(),any());
        try {assertThat(code(startHttp(browser,key,null,500))).isEqualTo("INTERNAL_ERROR");}
        finally {reset(idempotency);}
        noStart(browser);startHttp(browser,key,null,201);
    }
}
