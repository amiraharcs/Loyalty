package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.game.model.GameSessionStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameScoreApiTests extends GameScoreTestSupport {
    @Test void acceptedScoreCompletesRunAtServerTimeAndOwnedLocationIsRecoverable() throws Exception {
        var browser=login(RoleCode.CUSTOMER);assertThat(best(browser)).isNull();var run=start(browser);
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));var result=submit(browser,run.sessionId(),3600,UUID.randomUUID(),201);var body=score(result);
        assertThat(body.score()).isEqualTo(3600);assertThat(body.sessionId()).isEqualTo(run.sessionId());
        assertThat(body.acceptedAt()).isEqualTo(NOW.plusSeconds(10));assertThat(body.sessionStatus()).isEqualTo(GameSessionStatus.COMPLETED);
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(5);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(scorePath(run.sessionId()));
        assertThat(score(browser.send(get(result.getResponse().getHeader("Location")),200))).isEqualTo(body);
        assertThat(state(run.sessionId())).isEqualTo("COMPLETED");assertThat(scoreRows(run.sessionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM loyalty.game_sessions WHERE id=?",java.sql.Timestamp.class,run.sessionId()).toInstant()).isEqualTo(body.acceptedAt());
        assertThat(best(browser)).isEqualTo(3600);untouchedLife(browser);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE action='GAME_SCORE_ACCEPTED' AND actor_user_id=?",Long.class,browser.userId)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"-1","9007199254740992","9223372036854775808","1.0","1.5","\"1\"","true","null","[]"})
    void scoreMustBeAnExactNonnegativeSafeInteger(String value) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        scoreBody(browser,run.sessionId(),"{\"score\":"+value+"}",UUID.randomUUID(),400);
        assertThat(scoreRows(run.sessionId())).isZero();assertThat(state(run.sessionId())).isEqualTo("ACTIVE");untouchedLife(browser);
    }
    @ParameterizedTest @ValueSource(strings={"customerId","status","engineVersion","elapsedTime","startedAt","completedAt","acceptedAt","scoreCeiling","weeklyPeriod","lives"})
    void rejectsUnknownClientTimeIdentityAndRules(String field) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        scoreBody(browser,run.sessionId(),"{\"score\":0,\""+field+"\":\"forged\"}",UUID.randomUUID(),400);
        assertThat(scoreRows(run.sessionId())).isZero();
    }
    @Test void csrfKeyAndScoreAreRequiredAndQueryOverridesAreRejected() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        browser.send(post(scorePath(run.sessionId())).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"score\":0}"),403);
        submit(browser,run.sessionId(),0,null,400);scoreBody(browser,run.sessionId(),"{}",UUID.randomUUID(),400);
        browser.send(post(scorePath(run.sessionId())).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key","bad").contentType("application/json").content("{\"score\":0}"),400);
        browser.send(post(scorePath(run.sessionId())).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",UUID.randomUUID()).param("now","2099")
                .contentType("application/json").content("{\"score\":0}"),400);
        assertThat(scoreRows(run.sessionId())).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void rolesAreExplicitForSubmissionAndSummary(String role) throws Exception {
        var customer=login(RoleCode.CUSTOMER);var run=start(customer);var wrong=login(RoleCode.valueOf(role));
        submit(wrong,run.sessionId(),0,UUID.randomUUID(),403);wrong.send(get("/api/v1/customers/me/game-summary"),403);
        var combined=login(RoleCode.valueOf(role),RoleCode.CUSTOMER);var own=start(combined);submit(combined,own.sessionId(),0,UUID.randomUUID(),201);
    }
    @Test void anonymousDisabledAndRevokedIdentitiesCannotSubmitOrReadSummary() throws Exception {
        mvc.perform(get("/api/v1/customers/me/game-summary")).andExpect(status().isUnauthorized());
        var guest=new Browser();guest.csrf();submit(guest,UUID.randomUUID(),0,UUID.randomUUID(),401);
        var disabled=login(RoleCode.CUSTOMER);var run=start(disabled);jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);
        submit(disabled,run.sessionId(),0,UUID.randomUUID(),401);disabled.send(get("/api/v1/customers/me/game-summary"),401);
        var revoked=login(RoleCode.CUSTOMER);var second=start(revoked);jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);
        submit(revoked,second.sessionId(),0,UUID.randomUUID(),403);revoked.send(get("/api/v1/customers/me/game-summary"),403);
    }
    @Test void foreignAndMissingSessionsAre404AndEventsDoNotRetainForeignReference() throws Exception {
        var owner=login(RoleCode.CUSTOMER);var run=start(owner);var foreign=login(RoleCode.CUSTOMER);
        assertThat(code(submit(foreign,run.sessionId(),0,UUID.randomUUID(),404))).isEqualTo("RESOURCE_NOT_FOUND");
        submit(foreign,UUID.randomUUID(),0,UUID.randomUUID(),404);
        foreign.send(get(scorePath(run.sessionId())),404);assertThat(best(foreign)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.game_submission_events WHERE actor_user_id=? AND session_id IS NULL",Long.class,foreign.userId)).isEqualTo(2);
        assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRows(run.sessionId())).isZero();
    }
}
