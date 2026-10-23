package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class OperationalReportActivityTests extends OperationalReportTestSupport {
    @Test void visitsUseHalfOpenBusinessDatesUniqueCustomersAndHistoricalStores() throws Exception {
        var admin=admin();var staff=login(RoleCode.STAFF);var one=login(RoleCode.CUSTOMER);var two=login(RoleCode.CUSTOMER);var a=store(staff);var b=store(staff);
        visit(one,staff,a.getId(),NOW.minusSeconds(86400));visit(one,staff,b.getId(),NOW);visit(two,staff,a.getId(),NOW.plusSeconds(86400));
        asAdmin(admin);var result=reports.visits("2026-10-13","2026-10-15",null);
        assertThat(result.totalVisits()).isEqualTo(2);assertThat(result.uniqueCustomers()).isEqualTo(1);assertThat(result.byBusinessDate()).hasSize(2);assertThat(result.byStore()).hasSize(2);
        jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",a.getId());
        assertThat(reports.visits("2026-10-13","2026-10-15",a.getId()).totalVisits()).isEqualTo(1);
        assertThat(reports.visits("2026-10-01","2026-10-02",null).byBusinessDate()).isEmpty();
    }
    @ParameterizedTest @CsvSource({"2026-03-29T23:30:00Z,2026-03-30","2026-10-25T00:30:00Z,2026-10-25","2026-10-25T01:30:00Z,2026-10-25","2026-10-14T23:30:00Z,2026-10-15"})
    void overviewTodayUsesLondonMidnightAndDst(String instant,String date) throws Exception {
        var admin=admin();var staff=login(RoleCode.STAFF);var member=login(RoleCode.CUSTOMER);var shop=store(staff);Instant at=Instant.parse(instant);
        when(clock.instant()).thenReturn(at);visit(member,staff,shop.getId(),at.minusSeconds(1));asAdmin(admin);
        var result=reports.overview();assertThat(result.businessDate()).isEqualTo(LocalDate.parse(date));assertThat(result.visitsToday()).isEqualTo(1);assertThat(result.pointsEarnedToday()).isEqualTo(100);
    }
    @Test void overviewActiveRoleCountsAndCurrentRewardAvailability() throws Exception {
        var admin=admin();login(RoleCode.CUSTOMER);login(RoleCode.STAFF);login(RoleCode.STAFF,RoleCode.CUSTOMER);var disabled=login(RoleCode.CUSTOMER);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);var staff=login(RoleCode.STAFF);var shop=store(staff);
        UUID available=reward();UUID future=reward();UUID inactive=reward();UUID ended=reward();
        jdbc.update("UPDATE loyalty.rewards SET available_from=? WHERE id=?",java.sql.Timestamp.from(NOW.plusSeconds(1)),future);
        jdbc.update("UPDATE loyalty.rewards SET active=false WHERE id=?",inactive);
        jdbc.update("UPDATE loyalty.rewards SET available_until=? WHERE id=?",java.sql.Timestamp.from(NOW),ended);
        asAdmin(admin);var result=reports.overview();assertThat(result.activeCustomers()).isEqualTo(2);assertThat(result.activeStaff()).isEqualTo(3);assertThat(result.activeStores()).isEqualTo(1);
        assertThat(result.rewards().available()).isEqualTo(2);assertThat(result.rewards().future()).isEqualTo(1);assertThat(result.rewards().inactive()).isEqualTo(1);assertThat(result.rewards().ended()).isEqualTo(1);
        assertThat(result.currentWeek().materialized()).isFalse();assertThat(result.settlementImplemented()).isFalse();
    }
    @Test void pointsAreLedgerMagnitudesWithUnattributedTypesExcludedForStores() throws Exception {
        var admin=admin();var staff=login(RoleCode.STAFF);var member=login(RoleCode.CUSTOMER);var shop=store(staff);var other=store(staff);var reward=reward();
        adjustment(member,1000,NOW.minusSeconds(86400),"ADJUSTMENT");visit(member,staff,shop.getId(),NOW);
        redemption(member,staff,shop.getId(),reward,NOW,"PENDING");redemption(member,staff,other.getId(),reward,NOW,"PENDING");
        adjustment(member,7,NOW,"REFUND");adjustment(member,11,NOW,"ADJUSTMENT");adjustment(member,-3,NOW,"ADJUSTMENT");
        asAdmin(admin);var all=reports.points(NOW.toString(),NOW.plusSeconds(1).toString(),null);
        assertThat(all.earned().points()).isEqualTo(100);assertThat(all.redeemed().points()).isEqualTo(40);assertThat(all.redeemed().count()).isEqualTo(2);
        assertThat(all.refunded().points()).isEqualTo(7);assertThat(all.positiveAdjustments().points()).isEqualTo(11);assertThat(all.negativeAdjustments().points()).isEqualTo(3);
        var scoped=reports.points(NOW.toString(),NOW.plusSeconds(1).toString(),shop.getId());
        assertThat(scoped.redeemed().points()).isEqualTo(20);assertThat(scoped.earned().points()).isEqualTo(100);
        assertThat(scoped.refunded().count()+scoped.positiveAdjustments().count()+scoped.negativeAdjustments().count()).isZero();assertThat(scoped.unattributedTypesExcluded()).isTrue();
        assertThat(reports.points(NOW.minusSeconds(1).toString(),NOW.toString(),null).earned().count()).isZero();
    }
    @Test void redemptionCohortUsesSnapshotsAndProjectsExpiryWithoutMutation() throws Exception {
        var admin=admin();var staff=login(RoleCode.STAFF);var member=login(RoleCode.CUSTOMER);var shop=store(staff);var other=store(staff);var reward=reward();adjustment(member,1000,NOW.minusSeconds(86400*4),"ADJUSTMENT");
        redemption(member,staff,shop.getId(),reward,NOW.minusSeconds(3600),"PENDING");
        redemption(member,staff,shop.getId(),reward,NOW.minusSeconds(7200),"FULFILLED");
        redemption(member,staff,shop.getId(),reward,NOW.minusSeconds(86400*2),"PENDING");
        redemption(member,staff,shop.getId(),reward,NOW.minusSeconds(7200),"CANCELLED");
        redemption(member,staff,other.getId(),reward,NOW.minusSeconds(7200),"EXPIRED");
        redemption(member,staff,shop.getId(),reward,NOW,"PENDING");
        jdbc.update("UPDATE loyalty.rewards SET point_cost=999 WHERE id=?",reward);
        asAdmin(admin);var result=reports.redemptions(NOW.minusSeconds(86400*3).toString(),NOW.toString(),shop.getId());
        assertThat(result.states()).allSatisfy(s->assertThat(s.count()).isEqualTo(1));assertThat(result.states()).allSatisfy(s->assertThat(s.pointsCostSnapshot()).isEqualTo(20));
        assertThat(result.byStore()).hasSize(1);assertThat(result.byStore().getFirst().count()).isEqualTo(4);assertThat(result.byStore().getFirst().pointsCostSnapshot()).isEqualTo(80);
        var overview=reports.overview();assertThat(overview.pendingRedemptions()).isEqualTo(2);assertThat(overview.fulfilledRedemptionsToday()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.reward_redemptions WHERE status='PENDING'",Long.class)).isEqualTo(3);
    }
    @Test void nanosecondBoundsComparePreciselyAgainstMicrosecondLedgerFacts() throws Exception {
        var admin=admin();var member=login(RoleCode.CUSTOMER);adjustment(member,5,NOW,"ADJUSTMENT");asAdmin(admin);
        assertThat(reports.points(NOW.minusNanos(1).toString(),NOW.plusNanos(1).toString(),null).positiveAdjustments().points()).isEqualTo(5);
        assertThat(reports.points(NOW.plusNanos(1).toString(),NOW.plusSeconds(1).toString(),null).positiveAdjustments().points()).isZero();
        assertThat(reports.points(NOW.minusSeconds(1).toString(),NOW.toString(),null).positiveAdjustments().points()).isZero();
    }
    @Test void maximumRangesAreAcceptedAndEmptySummariesStayExplicit() throws Exception {
        var admin=admin();asAdmin(admin);
        assertThat(reports.visits("2026-01-01","2026-04-01",null).totalVisits()).isZero();
        assertThat(reports.points("2026-01-01T00:00:00Z","2026-04-01T00:00:00Z",null).redeemed().points()).isZero();
        var game=reports.game("2026-01-01T00:00:00Z","2026-04-01T00:00:00Z");assertThat(game.averageAcceptedScore()).isNull();assertThat(game.maxAcceptedScore()).isNull();
    }
}
