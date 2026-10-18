package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameScoreRetryTests extends GameScoreTestSupport {
    @Test void exactKeyReplayAndNaturalRetryRetainOriginalAcceptanceAfterExpiry() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var key=UUID.randomUUID();var first=submit(browser,run.sessionId(),100,key,201);
        when(clock.instant()).thenReturn(run.expiresAt().plusSeconds(1));var replay=submit(browser,run.sessionId(),100,key,201);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(replay.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        var naturalKey=UUID.randomUUID();var natural=submit(browser,run.sessionId(),100,naturalKey,200);
        assertThat(natural.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        submit(browser,run.sessionId(),100,naturalKey,200);
        assertThat(scoreRows(run.sessionId())).isEqualTo(1);assertThat(scoreRetries(browser.userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.game_sessions WHERE id=?",Long.class,run.sessionId())).isEqualTo(1);untouchedLife(browser);
    }
    @Test void changedKeyPayloadAndDifferentNaturalScoreHaveDistinctStableConflicts() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var key=UUID.randomUUID();var accepted=score(submit(browser,run.sessionId(),10,key,201));
        assertThat(code(submit(browser,run.sessionId(),11,key,409))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(code(submit(browser,run.sessionId(),11,UUID.randomUUID(),409))).isEqualTo("SCORE_ALREADY_SUBMITTED");
        assertThat(scoreRows(run.sessionId())).isEqualTo(1);assertThat(best(browser)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT accepted_at FROM loyalty.game_scores WHERE id=?",java.sql.Timestamp.class,accepted.scoreId()).toInstant()).isEqualTo(accepted.acceptedAt());
        assertThat(rejections(browser.userId,"IDEMPOTENCY_KEY_REUSED")).isEqualTo(1);assertThat(rejections(browser.userId,"SCORE_ALREADY_SUBMITTED")).isEqualTo(1);
    }
    @Test void personalBestUsesOnlyOwnedAcceptedScoresAndNeverFalls() throws Exception {
        var browser=login(RoleCode.CUSTOMER);assertThat(best(browser)).isNull();var a=start(browser);submit(browser,a.sessionId(),50,UUID.randomUUID(),201);
        var b=start(browser);submit(browser,b.sessionId(),100,UUID.randomUUID(),201);var c=start(browser);submit(browser,c.sessionId(),0,UUID.randomUUID(),201);
        assertThat(best(browser)).isEqualTo(100);var other=login(RoleCode.CUSTOMER);var d=start(other);submit(other,d.sessionId(),80,UUID.randomUUID(),201);
        assertThat(best(other)).isEqualTo(80);assertThat(best(browser)).isEqualTo(100);
    }
}
