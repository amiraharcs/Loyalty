package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ConflictException;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class StreakVisitTests extends VisitTestSupport {
    private void confirm(Setup setup, Instant instant) {
        at(instant); authenticate(setup.staff(), RoleCode.STAFF);
        new TransactionTemplate(manager).executeWithoutResult(tx -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()));
    }
    @Test void actualVisitsMaintainProjectionAndStaleReadsArePure() {
        var setup = new TransactionTemplate(manager).execute(tx -> fixture());
        for (int day = 0; day < 3; day++) { confirm(setup, NOW.plus(Duration.ofDays(day))); }
        var id = setup.customer().getId();
        var before = jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?", id);
        assertThat(streaks.snapshot(id, LocalDate.of(2026,10,14)).currentStreak()).isEqualTo(3);
        assertThat(streaks.snapshot(id, LocalDate.of(2026,10,15)).currentStreak()).isEqualTo(3);
        assertThat(streaks.snapshot(id, LocalDate.of(2026,10,16)).currentStreak()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?", id)).isEqualTo(before);
        confirm(setup, NOW.plus(Duration.ofDays(4)));
        var after = streaks.snapshot(id, LocalDate.of(2026,10,16));
        assertThat(after.currentStreak()).isEqualTo(1);
        assertThat(after.longestStreak()).isEqualTo(3);
        assertThat(after.lastQualifyingDate()).isEqualTo(LocalDate.of(2026,10,16));
        assertThat(loyalty.currentBalance(id)).isEqualTo(400);
    }
    @Test void outOfOrderRuntimeCommandRollsBackVisitEarnAndProjection() {
        var setup = new TransactionTemplate(manager).execute(tx -> fixture());
        confirm(setup, NOW);
        var before = jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?", setup.customer().getId());
        assertThatThrownBy(() -> confirm(setup, NOW.minus(Duration.ofDays(1)))).isInstanceOf(ConflictException.class);
        assertThat(service.history(setup.customer().getId(),0,10).getTotalElements()).isEqualTo(1);
        assertThat(loyalty.currentBalance(setup.customer().getId())).isEqualTo(100);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?",setup.customer().getId())).isEqualTo(before);
    }
}
