package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameScoreRollbackTests extends GameScoreTestSupport {
    @Test void failureAfterScoreAndCompletionRollsBackAllAcceptedState() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var key=UUID.randomUUID();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic accepted audit failure");})
                .when(AopTestUtils.<com.amiraharcs.loyalty.audit.service.AuditService>getUltimateTargetObject(audit))
                .append(eq(browser.userId),eq("GAME_SCORE_ACCEPTED"),anyString(),any(),any(),any(),anyString());
        try {assertThat(code(submit(browser,run.sessionId(),10,key,500))).isEqualTo("INTERNAL_ERROR");}
        finally {reset(audit);}
        assertThat(scoreRows(run.sessionId())).isZero();assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRetries(browser.userId)).isZero();
        assertThat(rejections(browser.userId,"INTERNAL_ERROR")).isEqualTo(1);untouchedLife(browser);
        submit(browser,run.sessionId(),10,key,201);
    }
    @Test void failureAfterRetryWriteRollsBackScoreCompletionAuditAndRetry() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var key=UUID.randomUUID();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic score retry failure");})
                .when(AopTestUtils.<com.amiraharcs.loyalty.common.idempotency.IdempotencyService>getUltimateTargetObject(idempotency))
                .complete(eq(browser.userId),eq("CUSTOMER_GAME_SCORE"),eq(key),anyString(),anyString(),any());
        try {submit(browser,run.sessionId(),0,key,500);} finally {reset(idempotency);}
        assertThat(scoreRows(run.sessionId())).isZero();assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRetries(browser.userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE action='GAME_SCORE_ACCEPTED' AND actor_user_id=?",Long.class,browser.userId)).isZero();
        submit(browser,run.sessionId(),0,key,201);
    }
}
