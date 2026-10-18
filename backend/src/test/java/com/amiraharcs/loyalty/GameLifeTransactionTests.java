package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.NoGameLivesException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class GameLifeTransactionTests extends GameLifeTestSupport {
    @Test void internalConsumeAndProvisioningRequireCallerOwnedTransaction() throws Exception {
        var browser=login(RoleCode.CUSTOMER);assume(browser,RoleCode.CUSTOMER);
        assertThatThrownBy(() -> lives.consumeForSession(null)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->lives.createForRegistration(customer(browser))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(consumed(customer(browser))).isZero();assertThat(version(customer(browser))).isZero();
    }
    @Test void callerRollbackRestoresConsumptionAndAggregateVersion() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);assume(browser,RoleCode.CUSTOMER);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            var consumed=consume(browser);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.game_life_consumptions WHERE id=?",Long.class,consumed.getId())).isEqualTo(1);
            assertThat(version(owner)).isEqualTo(1);
            throw new IllegalStateException("Synthetic future session failure");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Synthetic");
        assertThat(consumed(owner)).isZero();assertThat(version(owner)).isZero();
        assertThat(lives.current().availableLives()).isEqualTo(3);
    }
    @Test void twoSimultaneousAttemptsCanConsumeTheFinalLifeOnlyOnce() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);consume(browser);consume(browser);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> attempt=()->{
                assume(browser,RoleCode.CUSTOMER);ready.countDown();
                try {
                    if(!start.await(10,TimeUnit.SECONDS)) {throw new IllegalStateException("Race did not start");}
                    consume(browser);return "CONSUMED";
                } catch(NoGameLivesException exception) {return exception.code().name();}
                finally {SecurityContextHolder.clearContext();}
            };
            var first=pool.submit(attempt);var second=pool.submit(attempt);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(java.util.List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CONSUMED","NO_GAME_LIVES");
        } finally {start.countDown();}
        assertThat(consumed(owner)).isEqualTo(3);assertThat(version(owner)).isEqualTo(3);
        assertThat(snapshot(browser,NOW).availableLives()).isZero();unchanged(owner);
    }
    @Test void internalCommandsRecheckFreshAccountStatusAndRole() throws Exception {
        var browser=login(RoleCode.CUSTOMER);assume(browser,RoleCode.CUSTOMER);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
        assertThatThrownBy(()->consume(browser)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("INSERT INTO loyalty.user_roles VALUES (?, 'CUSTOMER')",browser.userId);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",browser.userId);
        assertThatThrownBy(()->consume(browser)).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThat(consumed(customer(browser))).isZero();assertThat(version(customer(browser))).isZero();
    }
}
