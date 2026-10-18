package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
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

class GameSessionLockingTests extends GameSessionTestSupport {
    @ParameterizedTest @ValueSource(strings={"expiry","role","disabled"})
    void sessionStartRechecksTimeAndEligibilityAfterBlockingAggregateLock(String change) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);var original=start(browser);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var result=new TransactionTemplate(manager).execute(tx->{
                jdbc.queryForObject("SELECT version FROM loyalty.game_life_state WHERE customer_id=? FOR UPDATE",Long.class,owner);
                var future=worker.submit(()->{
                    assume(browser,RoleCode.CUSTOMER);
                    try {sessions.start(UUID.randomUUID(),UUID.randomUUID());return 201;}
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
                assertThat(blocked).as("session command actually waits on game state in PostgreSQL").isTrue();
                switch(change) {
                    case "expiry" -> when(clock.instant()).thenReturn(original.expiresAt());
                    case "role" -> jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
                    case "disabled" -> jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",browser.userId);
                }
                assertThat(future.isDone()).isFalse();return future;
            });
            assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(change.equals("expiry")?201:change.equals("role")?403:401);
        }
        assertThat(consumed(owner)).isEqualTo(change.equals("expiry")?2:1);
        assertThat(sessionRows(owner)).isEqualTo(change.equals("expiry")?2:1);
        assertThat(version(owner)).isEqualTo(change.equals("expiry")?2:1);
        if(change.equals("expiry")) {
            assertThat(jdbc.queryForObject("SELECT started_at FROM loyalty.game_sessions WHERE customer_id=? AND status='ACTIVE'",java.sql.Timestamp.class,owner).toInstant()).isEqualTo(original.expiresAt());
        }
    }
}
