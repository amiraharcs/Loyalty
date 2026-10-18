package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.game.model.GameSessionStatus;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameSessionLifecycleTests extends GameSessionTestSupport {
    @Test void recoveryExpiresExactlyAtBoundaryOnceAndNeverRefundsLife() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var session=start(browser);var owner=customer(browser);
        when(clock.instant()).thenReturn(session.expiresAt().minusNanos(1000));
        assertThat(recovery(browser,session.sessionId()).status()).isEqualTo(GameSessionStatus.ACTIVE);
        when(clock.instant()).thenReturn(session.expiresAt());
        var expired=recovery(browser,session.sessionId());assertThat(expired.status()).isEqualTo(GameSessionStatus.EXPIRED);
        assertThat(expired.remainingLives()).isEqualTo(2);assertThat(expired.nextRechargeAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(expired.serverTime()).isEqualTo(session.expiresAt());
        assertThat(recovery(browser,session.sessionId())).isEqualTo(expired);
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.game_sessions WHERE id=?",Long.class,session.sessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM loyalty.game_sessions WHERE id=?",java.sql.Timestamp.class,session.sessionId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='GAME_SESSION_EXPIRED'",Long.class,session.sessionId())).isEqualTo(1);
        assertThat(consumed(owner)).isEqualTo(1);assertThat(version(owner)).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(7200));
        assertThat(recovery(browser,session.sessionId()).remainingLives()).isEqualTo(3);
        assertThat(consumed(owner)).isEqualTo(1);
    }
    @Test void startExpiresStaleActiveSessionThenCreatesExactlyOneReplacement() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var previous=start(browser);
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));var next=start(browser);
        assertThat(next.sessionId()).isNotEqualTo(previous.sessionId());assertThat(next.startedAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(next.remainingLives()).isEqualTo(1);assertThat(next.nextRechargeAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.game_sessions WHERE id=?",String.class,previous.sessionId())).isEqualTo("EXPIRED");
        assertThat(sessionRows(customer(browser))).isEqualTo(2);assertThat(activeRows(customer(browser))).isEqualTo(1);
        assertThat(consumed(customer(browser))).isEqualTo(2);
    }
    @Test void exhaustedLivesFailWithoutLeavingSessionOrSuccessRetryRecord() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        consume(browser);consume(browser);consume(browser);var owner=customer(browser);
        var key=UUID.randomUUID();assertThat(code(startHttp(browser,key,null,409))).isEqualTo("NO_GAME_LIVES");
        assertThat(sessionRows(owner)).isEqualTo(3);assertThat(activeRows(owner)).isZero();assertThat(consumed(owner)).isEqualTo(3);
        assertThat(retries(browser.userId)).isZero();assertThat(starts(browser.userId)).isZero();
        when(clock.instant()).thenReturn(NOW.plusSeconds(7200));startHttp(browser,key,null,201);
        assertThat(consumed(owner)).isEqualTo(4);
    }
    @Test void abandonedRunRemainsSpentAndFailedReplacementRollsBackLazyExpiry() throws Exception {
        var browser=login(RoleCode.CUSTOMER);consume(browser);consume(browser);var last=start(browser);var owner=customer(browser);
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        assertThat(code(startHttp(browser,UUID.randomUUID(),null,409))).isEqualTo("NO_GAME_LIVES");
        // All changes of a rejected start roll back; owned recovery performs lazy expiry separately.
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.game_sessions WHERE id=?",String.class,last.sessionId())).isEqualTo("ACTIVE");
        assertThat(recovery(browser,last.sessionId()).status()).isEqualTo(GameSessionStatus.EXPIRED);
        assertThat(snapshot(browser,NOW.plusSeconds(900)).availableLives()).isZero();assertThat(consumed(owner)).isEqualTo(3);
    }
    @Test void serverPrecisionAndElapsedLifetimeRemainStableAcrossMidnightAndDst() throws Exception {
        var now=Instant.parse("2026-10-25T00:59:59.123456789Z");when(clock.instant()).thenReturn(now);
        var browser=login(RoleCode.CUSTOMER);var result=start(browser);
        assertThat(result.startedAt()).isEqualTo(Instant.parse("2026-10-25T00:59:59.123456Z"));
        assertThat(Duration.between(result.startedAt(),result.expiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(result.nextRechargeAt()).isEqualTo(result.startedAt().plusSeconds(7200));
    }
}
