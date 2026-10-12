package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.store.model.*;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.visit.service.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real commits validate the deferred aggregate constraint, not only in-transaction INSERTs. */
class VisitAwardTests extends VisitTestSupport {
    private Setup setup() {
        var setup = new TransactionTemplate(manager).execute(status -> fixture()); authenticate(setup.staff(), RoleCode.STAFF); return setup;
    }
    private VisitConfirmationResult confirm(Setup setup) {
        return new TransactionTemplate(manager).execute(status -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()));
    }
    private void state(Setup setup, long count, long balance) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?", Long.class, setup.customer().getId())).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id=? AND type='EARN'", Long.class, setup.account().getId())).isEqualTo(count);
        assertThat(loyalty.currentBalance(setup.customer().getId())).isEqualTo(balance);
    }
    private void previousAward(Setup setup, long amount) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var visit = visits.append(new Visit(setup.customer(), setup.account(), setup.store(), setup.staff(), NOW.minusSeconds(86400), amount));
            ledger.append(PointTransaction.forVisit(visit));
        });
    }

    @Test
    void confirmationCommitsOneVisitAndMatchingEarnAndReturnsOnlyServerDerivedValues() {
        var setup = setup(); var result = confirm(setup); state(setup, 1, 100);
        assertThat(result.earnedPoints()).isEqualTo(100); assertThat(result.balance()).isEqualTo(100);
        assertThat(result.confirmedAt()).isEqualTo(NOW); assertThat(result.businessDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        var row = jdbc.queryForMap("SELECT visit_id,account_id,signed_amount,type,actor_id,created_at FROM loyalty.point_transactions WHERE account_id=?", setup.account().getId());
        assertThat(row.get("visit_id")).isEqualTo(result.visitId()); assertThat(row.get("signed_amount")).isEqualTo(100L);
        assertThat(row.get("type")).isEqualTo("EARN"); assertThat(row.get("actor_id")).isEqualTo(setup.staff().getId());
        assertThat(jdbc.queryForObject("SELECT earned_points FROM loyalty.visits WHERE id=?", Long.class, result.visitId())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.loyalty_accounts WHERE id=?", Long.class, setup.account().getId())).isEqualTo(1);
    }

    @Test
    void awardAddsToExistingLedgerAndBothHistoriesRemainOrdered() {
        var setup = setup(); previousAward(setup, 100); var result = confirm(setup); state(setup, 2, 200);
        assertThat(result.balance()).isEqualTo(200);
        assertThat(service.history(setup.customer().getId(), 0, 10).getContent().getFirst().id()).isEqualTo(result.visitId());
        assertThat(loyalty.history(setup.customer().getId(), 0, 10).getContent()).extracting(item -> item.signedAmount()).containsExactly(100L, 100L);
        assertThat(loyalty.history(setup.customer().getId(), 0, 10).getContent().getFirst().createdAt()).isEqualTo(NOW);
    }

    @Test
    void successfulRetryCannotAwardAgain() {
        var setup = setup(); confirm(setup);
        assertThatThrownBy(() -> confirm(setup)).isInstanceOf(VisitAlreadyRecordedException.class); state(setup, 1, 100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAME_STAFF", "OTHER_STAFF", "OTHER_STORE"})
    void simultaneousConfirmationsAwardExactlyOnce(String scenario) throws Exception {
        var firstSetup = setup();
        var secondSetup = new TransactionTemplate(manager).execute(status -> {
            if (scenario.equals("OTHER_STAFF")) {
                var staff = user(RoleCode.STAFF); assignments.save(new StaffStore(staff, firstSetup.store(), staff, CREATED));
                return new Setup(firstSetup.customer(), firstSetup.account(), firstSetup.store(), staff);
            }
            if (scenario.equals("OTHER_STORE")) {
                return new Setup(firstSetup.customer(), firstSetup.account(), assignedStore(firstSetup.staff(), StoreStatus.ACTIVE), firstSetup.staff());
            }
            return firstSetup;
        });
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> attempt(firstSetup, start)); var second = workers.submit(() -> attempt(secondSetup, start)); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        state(firstSetup, 1, 100);
    }

    @Test
    void failureBetweenFlushedVisitAndEarnRollsBackBoth() {
        var setup = setup();
        doAnswer(invocation -> {
            PointTransaction entry = invocation.getArgument(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE id=?", Long.class, entry.getVisit().getId())).isEqualTo(1);
            throw new IllegalStateException("Synthetic EARN persistence failure");
        }).when(ledger).append(any(PointTransaction.class));
        assertThatThrownBy(() -> confirm(setup)).isInstanceOf(IllegalStateException.class); state(setup, 0, 0);
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.loyalty_accounts WHERE id=?", Long.class, setup.account().getId())).isZero();
    }

    @Test
    void failureAfterEarnInsertAndVersionUpdateRollsBackAllChanges() {
        var setup = setup();
        doAnswer(invocation -> {
            PointTransaction entry = invocation.getArgument(0); entities.persist(entry); entities.flush();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE visit_id=?", Long.class, entry.getVisit().getId())).isEqualTo(1);
            throw new IllegalStateException("Synthetic failure after EARN INSERT");
        }).when(ledger).append(any(PointTransaction.class));
        assertThatThrownBy(() -> confirm(setup)).isInstanceOf(IllegalStateException.class); state(setup, 0, 0);
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.loyalty_accounts WHERE id=?", Long.class, setup.account().getId())).isZero();
    }

    @Test
    void failureAfterSuccessfulServiceReturnBeforeCallerCommitRollsBackBoth() {
        var setup = setup();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()).balance()).isEqualTo(100);
            throw new IllegalStateException("Synthetic caller failure");
        })).isInstanceOf(IllegalStateException.class); state(setup, 0, 0);
    }

    @Test
    void databaseRejectsCommitOfVisitWithoutEarnEvenIfCallerBypassesService() {
        var setup = setup();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                visits.append(new Visit(setup.customer(), setup.account(), setup.store(), setup.staff(), NOW, 100))))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("A qualifying visit requires its EARN transaction");
        state(setup, 0, 0);
    }

    @Test
    void exactSafeIntegerBoundarySucceedsAndOverflowLeavesNoPartialVisit() {
        var setup = setup(); previousAward(setup, PointValues.MAX_SAFE_INTEGER - 100);
        assertThat(confirm(setup).balance()).isEqualTo(PointValues.MAX_SAFE_INTEGER); state(setup, 2, PointValues.MAX_SAFE_INTEGER);
        at(NOW.plusSeconds(86400));
        assertThatThrownBy(() -> confirm(setup)).isInstanceOf(ConflictException.class); state(setup, 2, PointValues.MAX_SAFE_INTEGER);
    }

    private boolean attempt(Setup setup, CountDownLatch start) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test start timed out"); }
            authenticate(setup.staff(), RoleCode.STAFF);
            var result = confirm(setup); assertThat(result.earnedPoints()).isEqualTo(100); assertThat(result.balance()).isEqualTo(100); return true;
        } catch (VisitAlreadyRecordedException duplicate) { return false; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
