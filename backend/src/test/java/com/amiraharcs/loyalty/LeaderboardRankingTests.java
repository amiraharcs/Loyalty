package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LeaderboardRankingTests extends LeaderboardTestSupport {
    @Test void oneBestPerCustomerIgnoresLowerLaterAndRetainsFirstEqualAcceptance() throws Exception {
        var browser=login(RoleCode.CUSTOMER);UUID a=fixtureCustomer("Alpha"),b=fixtureCustomer("Bravo");
        accepted(a,5000,NOW.minusSeconds(300));accepted(a,40,NOW.minusSeconds(200));
        accepted(b,5000,NOW.minusSeconds(250));accepted(a,5000,NOW.minusSeconds(100));
        var page=board(browser,ALL);
        assertThat(page.items()).extracting(e->e.alias()).containsExactly("Alpha","Bravo");
        assertThat(page.items()).extracting(e->e.rank()).containsExactly(1L,2L);
        assertThat(page.items()).extracting(e->e.score()).containsExactly(5000L,5000L);
        assertThat(page.currentUser().rank()).isNull();assertThat(page.currentUser().score()).isNull();
    }
    @Test void higherLaterBestReplacesBothScoreAndTieTimestamp() throws Exception {
        var browser=login(RoleCode.CUSTOMER);UUID a=fixtureCustomer("Alpha"),b=fixtureCustomer("Bravo");
        accepted(a,10,NOW.minusSeconds(300));accepted(b,6000,NOW.minusSeconds(200));accepted(a,6000,NOW.minusSeconds(100));
        assertThat(board(browser,ALL).items()).extracting(e->e.alias()).containsExactly("Bravo","Alpha");
    }
    @Test void exactScoreAndAcceptanceTieUsesPostgresUnsignedUuidOrder() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        UUID high=fixtureCustomer(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),"High",false,RoleCode.CUSTOMER);
        UUID low=fixtureCustomer(UUID.fromString("00000000-0000-0000-0000-000000000001"),"Low",false,RoleCode.CUSTOMER);
        accepted(high,50,NOW.minusSeconds(10));accepted(low,50,NOW.minusSeconds(10));
        assertThat(board(browser,ALL).items()).extracting(e->e.alias()).containsExactly("Low","High");
    }
    @Test void acceptedZeroCountsAndNeverSubmittedCustomerRemainsUnranked() throws Exception {
        var browser=login(RoleCode.CUSTOMER);accepted(customer(browser),0,NOW.minusSeconds(10));fixtureCustomer("No score");
        var page=board(browser,ALL);assertThat(page.items()).hasSize(1);
        assertThat(page.currentUser().rank()).isEqualTo(1);assertThat(page.currentUser().score()).isZero();
        assertThat(page.currentUser().isCurrentUser()).isTrue();assertThat(page.items().getFirst().isCurrentUser()).isTrue();
    }
    @Test void previousWeekBestPersistsOnlyInAllTimeAndMembershipUsesSessionStart() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        Instant boundary=Instant.parse("2026-10-11T23:00:00Z");
        run(owner,boundary.minusSeconds(1),boundary.plusSeconds(5),9000L);
        accepted(owner,200,NOW.minusSeconds(10));
        assertThat(board(browser,WEEKLY).currentUser().score()).isEqualTo(200);
        assertThat(board(browser,ALL).currentUser().score()).isEqualTo(9000);
        var previous=board(browser,WEEKLY+"?periodStart=2026-10-05");
        assertThat(previous.currentUser().score()).isEqualTo(9000);assertThat(previous.period().startDate()).isEqualTo(LocalDate.parse("2026-10-05"));
    }
    @Test void activeExpiredRejectedAndUnscoredRunsAreAbsent() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        UUID expired=run(owner,NOW.minusSeconds(2000),null,null);
        jdbc.update("UPDATE loyalty.game_sessions SET status='EXPIRED' WHERE id=?",expired);
        run(owner,NOW.minusSeconds(20),null,null);
        jdbc.update("INSERT INTO loyalty.game_submission_events(id,actor_user_id,submitted_score,reason_code,occurred_at,request_id) VALUES (?,?,999999,'SCORE_IMPLAUSIBLE',?,?)",
                UUID.randomUUID(),browser.userId,java.sql.Timestamp.from(NOW),UUID.randomUUID());
        assertThat(board(browser,WEEKLY).items()).isEmpty();assertThat(board(browser,ALL).items()).isEmpty();
    }
    @Test void disabledAndRevokedCustomersAreNotRankedAndCombinedRolesOnlyAppearInAllTime() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        UUID disabled=fixtureCustomer("Disabled"),revoked=fixtureCustomer("Revoked");
        accepted(disabled,999,NOW.minusSeconds(10));accepted(revoked,998,NOW.minusSeconds(10));
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=(SELECT user_id FROM loyalty.customer_profiles WHERE id=?)",disabled);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=(SELECT user_id FROM loyalty.customer_profiles WHERE id=?)",revoked);
        UUID staff=fixtureCustomer(UUID.randomUUID(),"Staff customer",false,RoleCode.CUSTOMER,RoleCode.STAFF);
        UUID admin=fixtureCustomer(UUID.randomUUID(),"Admin customer",false,RoleCode.CUSTOMER,RoleCode.ADMIN);
        accepted(staff,500,NOW.minusSeconds(10));accepted(admin,400,NOW.minusSeconds(10));
        assertThat(board(browser,WEEKLY).items()).isEmpty();
        assertThat(board(browser,ALL).items()).extracting(e->e.alias()).containsExactly("Staff customer","Admin customer");
    }
    @Test void safeIntegerMaximumIsPreservedWithoutFloatingPointConversion() throws Exception {
        var browser=login(RoleCode.CUSTOMER);accepted(customer(browser),9007199254740991L,NOW.minusSeconds(1));
        assertThat(board(browser,ALL).currentUser().score()).isEqualTo(9007199254740991L);
    }
}
