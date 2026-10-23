package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.leaderboard.repository.LeaderboardQuery;
import com.amiraharcs.loyalty.audit.service.AuditService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class OperationalReportCompetitionTests extends OperationalReportTestSupport {
    @Autowired LeaderboardQuery ranking;
    @Test void gamesUseStartedCohortsAcceptedScoresAndVirtualExpiry() throws Exception {
        var admin=admin();var one=login(RoleCode.CUSTOMER);var two=login(RoleCode.CUSTOMER);var three=login(RoleCode.CUSTOMER);var four=login(RoleCode.CUSTOMER);
        run(one,NOW.minusSeconds(3600),100L);run(one,NOW.minusSeconds(1800),200L);
        run(two,NOW.minusSeconds(60),null);run(three,NOW.minusSeconds(3600),null);
        var expired=run(four,NOW.minusSeconds(3600),null);jdbc.update("UPDATE loyalty.game_sessions SET status='EXPIRED' WHERE id=?",expired);
        jdbc.update("INSERT INTO loyalty.game_submission_events(id,actor_user_id,submitted_score,reason_code,occurred_at,request_id) VALUES (?,?,999999,'SCORE_IMPLAUSIBLE',?,?)",UUID.randomUUID(),one.userId,java.sql.Timestamp.from(NOW),UUID.randomUUID());
        asAdmin(admin);var result=reports.game(NOW.minusSeconds(7200).toString(),NOW.plusSeconds(1).toString());
        assertThat(result.sessionsStarted()).isEqualTo(5);assertThat(result.completed()).isEqualTo(2);assertThat(result.expired()).isEqualTo(2);assertThat(result.activeInRange()).isEqualTo(1);assertThat(result.uniquePlayers()).isEqualTo(4);
        assertThat(result.acceptedScores()).isEqualTo(2);assertThat(result.averageAcceptedScore()).isEqualByComparingTo("150.00");assertThat(result.maxAcceptedScore()).isEqualTo(200);assertThat(result.activeSessionsNow()).isEqualTo(1);
        assertThat(reports.game(NOW.minusSeconds(3600).plusSeconds(10).toString(),NOW.minusSeconds(1800).plusSeconds(10).toString()).acceptedScores()).isEqualTo(1);
        var overview=reports.overview();assertThat(overview.activeGameSessions()).isEqualTo(1);assertThat(overview.acceptedScoresToday()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.game_sessions WHERE customer_id=?",String.class,customer(three))).isEqualTo("ACTIVE");
    }
    @Test void competitionReusesAuthoritativeWeeklyRankingAndSafeAliases() throws Exception {
        var admin=admin();var one=login(RoleCode.CUSTOMER);var two=login(RoleCode.CUSTOMER);var staff=login(RoleCode.CUSTOMER,RoleCode.STAFF);var disabled=login(RoleCode.CUSTOMER);
        jdbc.update("UPDATE loyalty.customer_profiles SET leaderboard_alias='Visible member',leaderboard_private=false WHERE user_id=?",one.userId);
        jdbc.update("UPDATE loyalty.customer_profiles SET leaderboard_alias='private@example.org',leaderboard_private=true WHERE user_id=?",two.userId);
        run(one,NOW.minusSeconds(3600),300L);run(one,NOW.minusSeconds(1800),100L);run(two,NOW.minusSeconds(3600),300L);run(staff,NOW.minusSeconds(3600),1000L);run(disabled,NOW.minusSeconds(3600),2000L);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);
        asAdmin(admin);var result=reports.competition(NOW.minusSeconds(86400).toString(),NOW.plusSeconds(1).toString());
        assertThat(result.currentWeek().eligibleParticipants()).isEqualTo(2);assertThat(result.currentWeek().acceptedScores()).isEqualTo(5);assertThat(result.currentWeek().top()).hasSize(2);
        UUID period=jdbc.queryForObject("SELECT id FROM loyalty.weekly_periods WHERE start_date='2026-10-12'",UUID.class);
        var expected=ranking.read(period,null,5,null);assertThat(result.currentWeek().top().stream().map(t->t.alias()).toList()).containsExactlyElementsOf(expected.stream().map(LeaderboardQuery.Row::alias).toList());
        assertThat(result.currentWeek().top().stream().map(t->t.alias()).toList()).containsExactlyInAnyOrder("Member","Visible member");
        assertThat(json.writeValueAsString(result)).doesNotContain("private@example.org",one.userId.toString(),customer(one).toString(),"PRIVATE DISPLAY NAME");
        assertThat(result.weeklyPrizeDefinitions()).isEqualTo(4);assertThat(result.settlementImplemented()).isFalse();assertThat(result.currentWeek().status()).isEqualTo("OPEN");
    }
    @Test void prizeCohortProjectsAvailableExpiredAndRevokedWithoutInventingWeeklyAssignments() throws Exception {
        var admin=admin();var staff=login(RoleCode.STAFF);var member=login(RoleCode.CUSTOMER);var shop=store(staff);
        UUID first=visit(member,staff,shop.getId(),NOW.minusSeconds(86400));UUID second=visit(member,staff,shop.getId(),NOW);
        UUID third=visit(member,staff,shop.getId(),NOW.minusSeconds(86400*2));
        prize(member,first,NOW.minusSeconds(3600),NOW.plusSeconds(86400));
        prize(member,second,NOW.minusSeconds(3600),NOW);
        UUID revoked=prize(member,third,NOW.minusSeconds(3600),NOW.plusSeconds(86400));UUID event=UUID.randomUUID();
        audit.appendWithId(event,admin.userId,"CUSTOMER_PRIZE_REVOKED","CUSTOMER_PRIZE",revoked,NOW,UUID.randomUUID(),"{\"reason\":\"Synthetic reporting correction\"}");
        jdbc.update("UPDATE loyalty.customer_prizes SET status='REVOKED',revocation_audit_id=? WHERE id=?",event,revoked);
        asAdmin(admin);var before=counts();var result=reports.competition(NOW.minusSeconds(7200).toString(),NOW.toString());
        assertThat(result.prizeAssignments()).isEqualTo(3);assertThat(result.states().stream().filter(s->Set.of("AVAILABLE","EXPIRED","REVOKED").contains(s.status())).map(s->s.count())).containsOnly(1L);
        assertThat(result.states().stream().filter(s->Set.of("RESERVED","REDEEMED").contains(s.status())).mapToLong(s->s.count()).sum()).isZero();
        assertThat(result.triggers().stream().filter(t->t.trigger().equals("STREAK_MILESTONE")).findFirst().orElseThrow().count()).isEqualTo(3);
        assertThat(result.triggers().stream().filter(t->t.trigger().equals("WEEKLY_RANK")).findFirst().orElseThrow().count()).isZero();
        assertThat(reports.overview().availablePrizes()).isEqualTo(1);assertThat(counts()).isEqualTo(before);
    }
}
