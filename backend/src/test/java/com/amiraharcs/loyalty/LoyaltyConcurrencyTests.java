package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.repository.UserRepository;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.loyalty.repository.*;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class LoyaltyConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired CustomerProfileRepository profiles;
    @Autowired LoyaltyAccountRepository accounts;
    @Autowired PointTransactionRepository ledger;
    @Autowired LoyaltyAccountService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entities;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private UUID customer() {
        return new TransactionTemplate(manager).execute(status -> {
            var user = users.save(new User(UUID.randomUUID() + "@example.org", "synthetic-hash", AccountStatus.ACTIVE, Instant.now(), Set.of()));
            var profile = profiles.save(new CustomerProfile(user, "Member", "Member", true));
            accounts.save(new LoyaltyAccount(profile, Instant.now()));
            return profile.getId();
        });
    }

    @Test
    void lockRequiresCallerTransactionRatherThanReturningAnAlreadyReleasedLock() {
        assertThatThrownBy(() -> service.lockForMutation(UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void pessimisticLockBlocksAnotherTransactionUntilOwnerCommits() throws Exception {
        UUID customer = customer();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var attempting = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner = workers.submit(() -> new TransactionTemplate(manager).execute(status -> {
                service.lockForMutation(customer); locked.countDown();
                await(release); return true;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var waiter = workers.submit(() -> new TransactionTemplate(manager).execute(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '5s'");
                    attempting.countDown(); return service.lockForMutation(customer).getCustomer().getId();
                }));
                assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> waiter.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(owner.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo(customer);
            } finally { release.countDown(); }
        }
    }

    @Test
    void committedAccountSupportsVersionedForceIncrement() {
        UUID customer = customer();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var account = service.resolve(customer);
            assertThat(account.getVersion()).isZero();
            entities.lock(account, jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        });
        assertThat(service.resolve(customer).getVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {java.sql.Connection.TRANSACTION_READ_COMMITTED,
            java.sql.Connection.TRANSACTION_REPEATABLE_READ, java.sql.Connection.TRANSACTION_SERIALIZABLE})
    void concurrentAppendCannotCommitAnUnsafeTotal(int isolation) throws Exception {
        UUID customer = customer();
        new TransactionTemplate(manager).executeWithoutResult(status -> ledger.append(new PointTransaction(
                service.lockForMutation(customer), PointValues.MAX_SAFE_INTEGER - 1, PointTransactionType.REFUND, null, null, Instant.now())));
        var start = new CountDownLatch(1); var readers = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> append = () -> {
                await(start);
                try {
                    var transaction = new TransactionTemplate(manager);
                    transaction.setIsolationLevel(isolation);
                    transaction.executeWithoutResult(status -> {
                        var account = service.resolve(customer);
                        readers.countDown(); await(readers);
                        ledger.append(new PointTransaction(account, 1, PointTransactionType.REFUND, null, null, Instant.now()));
                    });
                    return true;
                } catch (org.springframework.dao.DataIntegrityViolationException
                        | org.springframework.dao.ConcurrencyFailureException expected) { return false; }
            };
            var first = workers.submit(append); var second = workers.submit(append); start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.currentBalance(customer)).isEqualTo(PointValues.MAX_SAFE_INTEGER);
        assertThat(service.history(customer, 0, 10).getTotalElements()).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test synchronization timed out"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }
}
