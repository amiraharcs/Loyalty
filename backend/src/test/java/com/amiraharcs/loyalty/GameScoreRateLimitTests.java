package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameScoreRateLimitTests extends GameScoreTestSupport {
    @Test void customerBudgetReturnsSafe429AndRecoversAfterWindow() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        for(int i=0;i<60;i++) {scoreLimiter.reserve(browser.userId,browser.ip);}
        var limited=submit(browser,run.sessionId(),0,UUID.randomUUID(),429);assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L,60L);
        assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRows(run.sessionId())).isZero();untouchedLife(browser);
        assertThat(rejections(browser.userId,"RATE_LIMITED")).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));submit(browser,run.sessionId(),0,UUID.randomUUID(),201);
    }
    @Test void addressBudgetAppliesAcrossCustomers() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        for(int i=0;i<240;i++) {scoreLimiter.reserve(UUID.randomUUID(),browser.ip);}
        submit(browser,run.sessionId(),0,UUID.randomUUID(),429);assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRows(run.sessionId())).isZero();
    }
}
