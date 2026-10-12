package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.loyalty.model.PointTransaction;
import com.amiraharcs.loyalty.visit.model.Visit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Transactional
class VisitEarnConstraintTests extends VisitTestSupport {
    private static final String INSERT = "INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at,visit_id) VALUES (?,?,?,?,?,'Synthetic source check',?,?)";
    private Visit pending(Setup setup) {
        return visits.append(new Visit(setup.customer(), setup.account(), setup.store(), setup.staff(), NOW, 100));
    }
    private Object[] values(Setup setup, Visit visit) {
        return new Object[]{UUID.randomUUID(), setup.account().getId(), 100L, "EARN", setup.staff().getId(), java.sql.Timestamp.from(NOW), visit.getId()};
    }
    private void rejected(Object... values) {
        assertThatThrownBy(() -> jdbc.update(INSERT, values)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueVisitSourceRejectsSecondEarn() {
        var setup = fixture(); var visit = append(setup, NOW);
        assertThatThrownBy(() -> jdbc.update(INSERT, values(setup, visit))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_point_transactions_visit_earn");
    }

    @Test
    void duplicateSourceThroughModuleServiceUsesSafeStableConflict() {
        var setup = fixture(); var visit = append(setup, NOW); authenticate(setup.staff(), RoleCode.STAFF);
        assertThatThrownBy(() -> loyalty.recordVisitEarn(visit)).isInstanceOf(ConflictException.class);
    }

    @Test
    void earnRequiresVisitSourceEvenWithValidAccountActorAndAmount() {
        var setup = fixture(); entities.flush();
        rejected(UUID.randomUUID(), setup.account().getId(), 100L, "EARN", setup.staff().getId(), java.sql.Timestamp.from(NOW), null);
    }

    @Test
    void missingVisitForeignKeyIsRejected() {
        var setup = fixture(); entities.flush();
        rejected(UUID.randomUUID(), setup.account().getId(), 100L, "EARN", setup.staff().getId(), java.sql.Timestamp.from(NOW), UUID.randomUUID());
    }

    @Test
    void validSourceCannotBeCombinedWithAnotherCustomersAccount() {
        var setup = fixture(); var other = customer(); var visit = pending(setup); var values = values(setup, visit); values[1] = other.getId(); rejected(values);
    }

    @Test
    void earningAmountMustMatchImmutableVisitSnapshot() {
        var setup = fixture(); var visit = pending(setup); var values = values(setup, visit); values[2] = 101L; rejected(values);
    }

    @Test
    void earningActorMustMatchConfirmingStaff() {
        var setup = fixture(); var other = user(RoleCode.STAFF); var visit = pending(setup); var values = values(setup, visit); values[4] = other.getId(); rejected(values);
    }

    @Test
    void earningActorCannotBeNullToBypassCompositeSourceForeignKey() {
        var setup = fixture(); var visit = pending(setup); var values = values(setup, visit); values[4] = null; rejected(values);
    }

    @Test
    void earningSignMustBePositive() {
        var setup = fixture(); var visit = pending(setup); var values = values(setup, visit); values[2] = -100L; rejected(values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REDEMPTION", "REFUND", "ADJUSTMENT"})
    void otherTransactionTypesCannotClaimVisitSource(String type) {
        var setup = fixture(); var visit = pending(setup); var values = values(setup, visit); values[3] = type;
        values[2] = type.equals("REDEMPTION") ? -100L : 100L; rejected(values);
    }

    @Test
    void modelCannotCreateUnlinkedEarn() {
        var setup = fixture();
        assertThatThrownBy(() -> new PointTransaction(setup.account(), 100, com.amiraharcs.loyalty.loyalty.model.PointTransactionType.EARN,
                setup.staff(), null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void visitSourceForeignKeyIsRestrictiveAndHistoryCannotBeDeleted() {
        var setup = fixture(); var visit = append(setup, NOW);
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='fk_point_transactions_visit_source'", String.class))
                .contains("ON UPDATE RESTRICT", "ON DELETE RESTRICT");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM loyalty.visits WHERE id=?", visit.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 9007199254740992L})
    void databaseRejectsInvalidVisitAwardSnapshots(long points) {
        var setup = fixture(); entities.flush();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO loyalty.visits(id,customer_id,account_id,store_id,confirmed_by,business_date,confirmed_at,earned_points) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), setup.customer().getId(), setup.account().getId(), setup.store().getId(), setup.staff().getId(),
                java.time.LocalDate.of(2026, 10, 12), java.sql.Timestamp.from(NOW), points)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
