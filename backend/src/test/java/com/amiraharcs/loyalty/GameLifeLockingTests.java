package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameLifeLockingTests extends GameLifeTestSupport {
    @ParameterizedTest @ValueSource(strings={"recharge", "role", "disabled"})
    void serverTimeAndIdentityAreRecheckedAfterRealAggregateLockWait(String change) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        consume(browser);consume(browser);consume(browser);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var result=new TransactionTemplate(manager).execute(tx->{
                jdbc.queryForObject("SELECT version FROM loyalty.game_life_state WHERE customer_id=? FOR UPDATE",Long.class,owner);
                var future=worker.submit(()->{
                    assume(browser,RoleCode.CUSTOMER);
                    try {new TransactionTemplate(manager).executeWithoutResult(inner->lives.consumeOwnLife());return 200;}
                    catch(AccessDeniedException denied) {return 403;}
                    catch(BadCredentialsException disabled) {return 401;}
                    finally {SecurityContextHolder.clearContext();}
                });
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean blocked=false;
                while(System.nanoTime()<deadline) {
                    // PostgreSQL caches activity snapshots within a transaction. Refresh before polling.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%game_life_state%')",Boolean.class));
                    if(blocked) {break;}
                    try {Thread.sleep(10);}
                    catch(InterruptedException exception) {Thread.currentThread().interrupt();throw new IllegalStateException(exception);}
                }
                assertThat(blocked).as("worker blocked on the real PostgreSQL game aggregate").isTrue();
                switch(change) {
                    case "recharge" -> when(clock.instant()).thenReturn(NOW.plusSeconds(7200));
                    case "role" -> jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
                    case "disabled" -> jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",browser.userId);
                }
                assertThat(future.isDone()).isFalse();return future;
            });
            assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(change.equals("recharge")?200:change.equals("role")?403:401);
        }
        assertThat(consumed(owner)).isEqualTo(change.equals("recharge")?4:3);
        assertThat(version(owner)).isEqualTo(change.equals("recharge")?4:3);
        if(change.equals("recharge")) {
            var state=snapshot(browser,NOW.plusSeconds(7200));assertThat(state.availableLives()).isEqualTo(2);
            assertThat(state.nextRechargeAt()).isEqualTo(NOW.plusSeconds(14400));
        }
    }
}
