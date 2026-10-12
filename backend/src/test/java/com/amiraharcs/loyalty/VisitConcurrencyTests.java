package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.visit.service.VisitAlreadyRecordedException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class VisitConcurrencyTests extends VisitTestSupport {
    @Test
    void callerMustOwnConfirmationTransactionForFutureAtomicPointsIntegration() {
        var setup = new TransactionTemplate(manager).execute(status -> fixture()); authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void callerRollbackRemovesVisitAndDoesNotChangeLedger() {
        var setup = new TransactionTemplate(manager).execute(status -> fixture()); authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId());
            throw new IllegalStateException("Synthetic caller failure after visit INSERT");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.history(setup.customer().getId(), 0, 10)).isEmpty();
        assertThat(loyalty.currentBalance(setup.customer().getId())).isZero();
    }

    @Test
    void concurrentServiceConfirmationsAcrossStoresCommitAtMostOneVisit() throws Exception {
        var setup = new TransactionTemplate(manager).execute(status -> fixture());
        var other = new TransactionTemplate(manager).execute(status -> {
            var staff = user(RoleCode.STAFF); return new Setup(setup.customer(), setup.account(), assignedStore(staff, StoreStatus.ACTIVE), staff);
        });
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> confirm(setup, start)); var second = workers.submit(() -> confirm(other, start)); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.history(setup.customer().getId(), 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(loyalty.currentBalance(setup.customer().getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id = ?", Long.class, setup.account().getId())).isZero();
    }

    @Test
    void databaseUniquenessIsFinalAuthorityWithoutServiceAccountLock() throws Exception {
        var setup = new TransactionTemplate(manager).execute(status -> fixture());
        var ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> append = () -> {
                try {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        // Both transactions genuinely observe no visit before racing their INSERTs.
                        assertThat(visits.existsByCustomerIdAndBusinessDate(setup.customer().getId(), Visit.businessDateAt(NOW))).isFalse();
                        ready.countDown(); await(ready);
                        visits.append(new Visit(setup.customer(), setup.account(), setup.store(), setup.staff(), NOW));
                    });
                    return true;
                } catch (DataIntegrityViolationException duplicate) { return false; }
            };
            var first = workers.submit(append); var second = workers.submit(append);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.history(setup.customer().getId(), 0, 10).getTotalElements()).isEqualTo(1);
    }

    private boolean confirm(Setup setup, CountDownLatch start) {
        await(start); authenticate(setup.staff(), RoleCode.STAFF);
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()));
            return true;
        } catch (VisitAlreadyRecordedException duplicate) { return false; }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test synchronization timed out"); }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
