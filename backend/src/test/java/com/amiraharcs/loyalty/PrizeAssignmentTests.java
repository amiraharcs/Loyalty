package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class PrizeAssignmentTests extends PrizeTestSupport {
    @Test void nineTenElevenAndTwentyVisitsAwardExactlyCompletedTenDayBlocks() throws Exception {
        var setup=setup();days(setup,9);assertThat(prizeCount(setup.customer())).isZero();
        var tenth=confirmDay(setup,9);assertThat(prizeCount(setup.customer())).isEqualTo(1);
        confirmDay(setup,10);assertThat(prizeCount(setup.customer())).isEqualTo(1);
        for(int day=11;day<20;day++) {confirmDay(setup,day);}
        assertThat(prizeCount(setup.customer())).isEqualTo(2);assertThat(balance(setup.customer())).isEqualTo(2000);
        var rows=jdbc.queryForList("SELECT trigger_visit_id,awarded_at,expires_at,status FROM loyalty.customer_prizes WHERE customer_id=? ORDER BY awarded_at",customer(setup.customer()));
        assertThat(rows.getFirst().get("trigger_visit_id")).isEqualTo(tenth);
        assertThat(jdbc.queryForObject("SELECT current_count FROM loyalty.streaks WHERE customer_id=?",Integer.class,customer(setup.customer()))).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id IN (SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?) AND type<>'EARN'",Long.class,customer(setup.customer()))).isZero();
    }
    @Test void sameTriggerReturnsOriginalEvenAfterDeactivationWithoutPointsOrExpiryChanges() throws Exception {
        var setup=setup();UUID visit=days(setup,10);var before=jdbc.queryForMap("SELECT * FROM loyalty.customer_prizes WHERE customer_id=?",customer(setup.customer()));
        jdbc.update("UPDATE loyalty.prizes SET active=false WHERE id=?",STREAK_PRIZE);entities.clear();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(10)));assume(setup.staff(),RoleCode.STAFF);
        var result=new TransactionTemplate(manager).execute(tx->prizeAssignments.assignStreak(visit).orElseThrow());
        assertThat(result.getId()).isEqualTo(before.get("id"));assertThat(prizeCount(setup.customer())).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.customer_prizes WHERE customer_id=?",customer(setup.customer()))).isEqualTo(before);
        assertThat(balance(setup.customer())).isEqualTo(1000);
    }
    @Test void inactiveDefinitionSkipsNewAwardWithoutBlockingQualifyingVisitOrBackfillingOnRead() throws Exception {
        var setup=setup();jdbc.update("UPDATE loyalty.prizes SET active=false WHERE id=?",STREAK_PRIZE);
        days(setup,10);assertThat(prizeCount(setup.customer())).isZero();assertThat(balance(setup.customer())).isEqualTo(1000);
        jdbc.update("UPDATE loyalty.prizes SET active=true WHERE id=?",STREAK_PRIZE);freshLogin(setup.customer());
        assertThat(history(setup.customer(),"").items()).isEmpty();confirmDay(setup,10);assertThat(prizeCount(setup.customer())).isZero();
    }
    @Test void noAwardAtZeroOrNonMilestoneAndNoDuplicateSameDay() throws Exception {
        var setup=setup();UUID visit=confirmDay(setup,0);assume(setup.staff(),RoleCode.STAFF);
        var result=new TransactionTemplate(manager).execute(tx->prizeAssignments.assignStreak(visit));
        assertThat(result).isEmpty();
        assertThatThrownBy(()->confirmDay(setup,0)).isInstanceOf(com.amiraharcs.loyalty.visit.service.VisitAlreadyRecordedException.class);
        assertThat(prizeCount(setup.customer())).isZero();assertThat(balance(setup.customer())).isEqualTo(100);
    }
    @Test void exactlySevenDaysFromServerAssignmentAndExpiryIsExclusiveWithoutDatabaseMutation() throws Exception {
        var setup=setup();UUID visit=days(setup,10);
        var p=entitlements.findByCustomerIdAndTriggerVisitId(customer(setup.customer()),visit).orElseThrow();
        assertThat(p.getAwardedAt()).isEqualTo(NOW.plus(Duration.ofDays(9)));
        assertThat(p.getExpiresAt()).isEqualTo(p.getAwardedAt().plus(Duration.ofDays(7)));
        assertThat(p.isAvailableAt(p.getExpiresAt().minusNanos(1))).isTrue();assertThat(p.isAvailableAt(p.getExpiresAt())).isFalse();
        assertThat(p.effectiveStatus(p.getExpiresAt())).isEqualTo(CustomerPrizeStatus.EXPIRED);
        assertThat(entitlements.available(customer(setup.customer()),p.getExpiresAt(),org.springframework.data.domain.PageRequest.of(0,20))).isEmpty();
        assertThat(p.getStatus()).isEqualTo(CustomerPrizeStatus.AVAILABLE);
    }
    @Test void laterMissingDayResetsStreakWithoutRevokingUnexpiredEntitlement() throws Exception {
        var setup=setup();days(setup,10);confirmDay(setup,11);
        assertThat(prizeCount(setup.customer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_count FROM loyalty.streaks WHERE customer_id=?",Integer.class,customer(setup.customer()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT longest_count FROM loyalty.streaks WHERE customer_id=?",Integer.class,customer(setup.customer()))).isEqualTo(10);
        freshLogin(setup.customer());assertThat(history(setup.customer(),"").items().getFirst().status()).isEqualTo(CustomerPrizeStatus.AVAILABLE);
    }
    @Test void midnightDuringConfirmationKeepsVisitMilestoneAndUsesAssignmentClockForExpiry() throws Exception {
        var setup=setup();days(setup,9);assume(setup.staff(),RoleCode.STAFF);
        var before=Instant.parse("2026-10-23T22:59:59.999999Z");var after=before.plusNanos(2000);
        when(clock.instant()).thenReturn(before,after);
        var visit=new TransactionTemplate(manager).execute(tx->visitFlow.confirmResolvedCustomer(customer(setup.customer()),setup.store().getId()).visitId());
        var prize=entitlements.findByCustomerIdAndTriggerVisitId(customer(setup.customer()),visit).orElseThrow();
        assertThat(prize.getAwardedAt()).isEqualTo(after);
        assertThat(prize.getExpiresAt()).isEqualTo(after.plus(Duration.ofDays(7)));
        assertThat(prizeCount(setup.customer())).isEqualTo(1);
    }
    @Test void customerWithStaffOrAdminGrantStillReceivesLoyaltyStreakBenefit() throws Exception {
        var setup=setup();jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,'ADMIN'),(?,'STAFF')",setup.customer().userId,setup.customer().userId);
        days(setup,10);assertThat(prizeCount(setup.customer())).isEqualTo(1);
    }
    @Test void weeklyRulesReturnOnlyArchitectureBenefitsAndNeverMaterialiseEntitlements() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        assertThat(weeklyRules.forOutcome(owner,1).orElseThrow().benefitType()).isEqualTo(PrizeBenefitType.REWARD);
        assertThat(weeklyRules.forOutcome(owner,1).orElseThrow().rewardId()).isEqualTo(COFFEE);
        assertThat(weeklyRules.forOutcome(owner,2).orElseThrow().percentage()).isEqualTo(50);
        assertThat(weeklyRules.forOutcome(owner,3).orElseThrow().percentage()).isEqualTo(25);
        assertThat(weeklyRules.forOutcome(owner,4).orElseThrow().percentage()).isEqualTo(10);
        assertThat(weeklyRules.forOutcome(owner,0)).isEmpty();assertThat(weeklyRules.forOutcome(owner,5)).isEmpty();
        assertThat(prizeCount(browser)).isZero();assertThat(balance(browser)).isZero();
    }
    @Test void weeklyRulesExcludeCombinedRolesDisabledAndInactivePrizeDefinitions() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        jdbc.update("UPDATE loyalty.prizes SET active=false WHERE weekly_rank=1");assertThat(weeklyRules.forOutcome(owner,1)).isEmpty();
        jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,'STAFF')",browser.userId);assertThat(weeklyRules.forOutcome(owner,2)).isEmpty();
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=? AND role_code='STAFF'",browser.userId);
        jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,'ADMIN')",browser.userId);assertThat(weeklyRules.forOutcome(owner,2)).isEmpty();
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",browser.userId);assertThat(weeklyRules.forOutcome(owner,3)).isEmpty();
    }
}
