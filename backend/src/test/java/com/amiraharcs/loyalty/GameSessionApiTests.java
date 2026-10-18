package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.game.api.GameSessionResponse;
import com.amiraharcs.loyalty.game.model.GameSessionStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameSessionApiTests extends GameSessionTestSupport {
    @Test void startCreatesOneServerOwnedRunAndLinkedLifeAndOwnedLocation() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        var result=startHttp(browser,UUID.randomUUID(),null,201);var raw=json.readTree(result.getResponse().getContentAsString());
        var response=json.readValue(result.getResponse().getContentAsString(),GameSessionResponse.class);
        assertThat(raw.size()).isEqualTo(8);assertThat(response.status()).isEqualTo(GameSessionStatus.ACTIVE);
        assertThat(response.startedAt()).isEqualTo(NOW);assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(response.engineVersion()).isEqualTo("v1");assertThat(response.remainingLives()).isEqualTo(2);
        assertThat(response.nextRechargeAt()).isEqualTo(NOW.plusSeconds(7200));assertThat(response.serverTime()).isEqualTo(NOW);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(SESSIONS+"/"+response.sessionId());
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("customerId","completedAt","version",owner.toString(),"weeklyPeriod");
        assertThat(recovery(browser,response.sessionId())).isEqualTo(response);
        var source=jdbc.queryForMap("SELECT customer_id,consumed_at,recharge_at FROM loyalty.game_life_consumptions WHERE session_id=?",response.sessionId());
        assertThat(source.get("customer_id")).isEqualTo(owner);
        assertThat(((java.sql.Timestamp)source.get("consumed_at")).toInstant()).isEqualTo(response.startedAt());
        assertThat(((java.sql.Timestamp)source.get("recharge_at")).toInstant()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(sessionRows(owner)).isEqualTo(1);assertThat(consumed(owner)).isEqualTo(1);assertThat(version(owner)).isEqualTo(1);
        assertThat(starts(browser.userId)).isEqualTo(1);assertThat(retries(browser.userId)).isEqualTo(1);unchanged(owner);
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void otherRolesCannotStartOrReadUnlessCustomerIsExplicit(String role) throws Exception {
        var browser=login(RoleCode.valueOf(role));assertThat(code(startHttp(browser,UUID.randomUUID(),null,403))).isEqualTo("FORBIDDEN");
        browser.send(get(SESSIONS+"/"+UUID.randomUUID()),403);noStart(browser);
        var combined=login(RoleCode.valueOf(role),RoleCode.CUSTOMER);var session=start(combined);recovery(combined,session.sessionId());
    }
    @Test void anonymousDisabledAndRevokedCustomersAreDenied() throws Exception {
        mvc.perform(get(SESSIONS+"/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
        var anonymous=new Browser();anonymous.csrf();assertThat(code(startHttp(anonymous,UUID.randomUUID(),null,401))).isEqualTo("UNAUTHENTICATED");
        var disabled=login(RoleCode.CUSTOMER);jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);
        startHttp(disabled,UUID.randomUUID(),null,401);noStart(disabled);
        var revoked=login(RoleCode.CUSTOMER);jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);
        startHttp(revoked,UUID.randomUUID(),null,403);noStart(revoked);
    }
    @Test void csrfAndValidIdempotencyKeyAreRequired() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        browser.send(post(SESSIONS).header("Idempotency-Key",UUID.randomUUID()),403);
        startHttp(browser,null,null,400);
        browser.send(post(SESSIONS).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key","invalid"),400);
        noStart(browser);
    }
    @ParameterizedTest @ValueSource(strings={"customerId","engineVersion","startedAt","expiresAt","lives","score","sessionId","status","weeklyPeriodId"})
    void rejectsClientOwnedSessionFields(String field) throws Exception {
        var browser=login(RoleCode.CUSTOMER);startHttp(browser,UUID.randomUUID(),"{\""+field+"\":\"forged\"}",400);noStart(browser);
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","1","true","{","\"v1\""})
    void rejectsMalformedOrNonObjectBodies(String body) throws Exception {
        var browser=login(RoleCode.CUSTOMER);startHttp(browser,UUID.randomUUID(),body,400);noStart(browser);
    }
    @Test void queryOverridesAreRejectedOnBothEndpointsAndHeadersCannotChangeServerRules() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        browser.send(post(SESSIONS).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",UUID.randomUUID()).param("now","2099"),400);
        var result=browser.send(post(SESSIONS).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",UUID.randomUUID())
                .header("X-Client-Time","2099-01-01T00:00:00Z").header("X-Engine-Version","forged"),201);
        var created=json.readValue(result.getResponse().getContentAsString(),GameSessionResponse.class);
        assertThat(created.startedAt()).isEqualTo(NOW);assertThat(created.engineVersion()).isEqualTo("v1");
        browser.send(get(SESSIONS+"/"+created.sessionId()).param("customerId",UUID.randomUUID().toString()),400);
    }
    @Test void foreignAndMissingSessionsHaveSameSafeNotFoundAndCannotBeExpiredByForeignReader() throws Exception {
        var owner=login(RoleCode.CUSTOMER);var foreign=login(RoleCode.CUSTOMER);var session=start(owner);
        org.mockito.Mockito.when(clock.instant()).thenReturn(session.expiresAt());
        assertThat(code(foreign.send(get(SESSIONS+"/"+session.sessionId()),404))).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(code(foreign.send(get(SESSIONS+"/"+UUID.randomUUID()),404))).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.game_sessions WHERE id=?",String.class,session.sessionId())).isEqualTo("ACTIVE");
        noStart(foreign);
    }
    @Test void liveSessionConflictDoesNotConsumeASecondLifeAndNoCompletionRoutesExist() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var created=start(browser);
        assertThat(code(startHttp(browser,UUID.randomUUID(),null,409))).isEqualTo("GAME_SESSION_ACTIVE");
        for(String suffix:new String[]{"completions","cancellations"}) {
            browser.send(post(SESSIONS+"/"+created.sessionId()+"/"+suffix).header("X-CSRF-TOKEN",browser.csrf),404);
        }
        assertThat(sessionRows(customer(browser))).isEqualTo(1);assertThat(consumed(customer(browser))).isEqualTo(1);
        assertThat(starts(browser.userId)).isEqualTo(1);assertThat(retries(browser.userId)).isEqualTo(1);
    }
}
