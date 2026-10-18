package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.game.api.ScoreRequest;
import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameScoreLockingTests extends GameScoreTestSupport {
    @ParameterizedTest @ValueSource(strings={"expiry","time","role","disabled"})
    void waitingSubmissionRechecksActualServerTimeAndEligibility(String change) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var owner=customer(browser);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var result=new TransactionTemplate(manager).execute(tx->{
                jdbc.queryForObject("SELECT version FROM loyalty.game_life_state WHERE customer_id=? FOR UPDATE",Long.class,owner);
                var future=worker.submit(()->{
                    assume(browser,RoleCode.CUSTOMER);
                    try {return scoring.submit(run.sessionId(),new ScoreRequest(BigInteger.ZERO),UUID.randomUUID(),UUID.randomUUID(),browser.ip).status();}
                    catch(ConflictException expired) {return expired.code().status().value();}
                    catch(AccessDeniedException denied) {return 403;}
                    catch(BadCredentialsException disabled) {return 401;}
                    finally {SecurityContextHolder.clearContext();}
                });
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean blocked=false;
                while(System.nanoTime()<deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%game_life_state%')",Boolean.class));
                    if(blocked) {break;}
                    try {Thread.sleep(10);} catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                assertThat(blocked).isTrue();
                switch(change) {
                    case "expiry" -> when(clock.instant()).thenReturn(run.expiresAt());
                    case "time" -> when(clock.instant()).thenReturn(NOW.plusSeconds(10));
                    case "role" -> jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
                    case "disabled" -> jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",browser.userId);
                }
                assertThat(future.isDone()).isFalse();return future;
            });
            assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(change.equals("time")?201:change.equals("expiry")?409:change.equals("role")?403:401);
        }
        assertThat(scoreRows(run.sessionId())).isEqualTo(change.equals("time")?1:0);untouchedLife(browser);
        if(change.equals("time")) {assertThat(jdbc.queryForObject("SELECT accepted_at FROM loyalty.game_scores WHERE session_id=?",java.sql.Timestamp.class,run.sessionId()).toInstant()).isEqualTo(NOW.plusSeconds(10));}
        if(change.equals("expiry")) {assertThat(state(run.sessionId())).isEqualTo("EXPIRED");}
    }
}
