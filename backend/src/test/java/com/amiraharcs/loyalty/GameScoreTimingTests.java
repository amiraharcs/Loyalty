package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameScoreTimingTests extends GameScoreTestSupport {
    @ParameterizedTest @ValueSource(longs={0,100})
    void immediateSubmissionAllowsZeroAndBaseAllowance(long value) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);submit(browser,run.sessionId(),value,UUID.randomUUID(),201);untouchedLife(browser);
    }
    @Test void tenSecondCeilingAccepts3600Rejects3601WithoutCompletingOrRecordingRetrySuccess() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);when(clock.instant()).thenReturn(NOW.plusSeconds(10));var key=UUID.randomUUID();
        assertThat(code(submit(browser,run.sessionId(),3601,key,409))).isEqualTo("SCORE_IMPLAUSIBLE");
        assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRows(run.sessionId())).isZero();assertThat(scoreRetries(browser.userId)).isZero();
        assertThat(rejections(browser.userId,"SCORE_IMPLAUSIBLE")).isEqualTo(1);
        submit(browser,run.sessionId(),3600,key,201);untouchedLife(browser);
    }
    @Test void exactExpiryCommitsExpiredStateWithoutCompletingOrRefunding() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);when(clock.instant()).thenReturn(run.expiresAt());
        assertThat(code(submit(browser,run.sessionId(),315100,UUID.randomUUID(),409))).isEqualTo("SESSION_EXPIRED");
        assertThat(state(run.sessionId())).isEqualTo("EXPIRED");assertThat(scoreRows(run.sessionId())).isZero();assertThat(best(browser)).isNull();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM loyalty.game_sessions WHERE id=?",java.sql.Timestamp.class,run.sessionId())).isNull();
        assertThat(rejections(browser.userId,"SESSION_EXPIRED")).isEqualTo(1);untouchedLife(browser);
    }
    @Test void justBeforeExpiryUsesWholeServerSecondsAndExpiredRunsRemainRejected() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);when(clock.instant()).thenReturn(run.expiresAt().minusNanos(1000));
        assertThat(code(submit(browser,run.sessionId(),314751,UUID.randomUUID(),409))).isEqualTo("SCORE_IMPLAUSIBLE");
        submit(browser,run.sessionId(),314750,UUID.randomUUID(),201);
        var other=login(RoleCode.CUSTOMER);var expired=start(other);when(clock.instant()).thenReturn(expired.expiresAt().plusSeconds(1));
        submit(other,expired.sessionId(),0,UUID.randomUUID(),409);assertThat(state(expired.sessionId())).isEqualTo("EXPIRED");
    }
    @Test void fractionalSecondsAreFlooredAndClientClockHeadersAreIgnored() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);when(clock.instant()).thenReturn(NOW.plusSeconds(10).plusNanos(999999000));
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(scorePath(run.sessionId()))
                .header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",UUID.randomUUID()).header("X-Elapsed-Time","900")
                .header("X-Client-Time","2099-01-01").contentType("application/json").content("{\"score\":3601}");
        assertThat(code(browser.send(request,409))).isEqualTo("SCORE_IMPLAUSIBLE");
    }
}
