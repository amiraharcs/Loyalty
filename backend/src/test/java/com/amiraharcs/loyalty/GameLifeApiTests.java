package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameLifeApiTests extends GameLifeTestSupport {
    @Test void registrationStartsWithThreePrivateServerOwnedLivesAndPureReads() throws Exception {
        var customer = login(RoleCode.CUSTOMER); var owner = customer(customer);
        for (int i = 0; i < 2; i++) {
            var result = customer.send(get(LIVES).header("X-Client-Time", "2099-01-01T00:00:00Z"), 200);
            var response = json.readTree(result.getResponse().getContentAsString());
            assertThat(response.size()).isEqualTo(4);
            assertThat(response.get("availableLives").asInt()).isEqualTo(3);
            assertThat(response.get("maxLives").asInt()).isEqualTo(3);
            assertThat(response.get("nextRechargeAt").isNull()).isTrue();
            assertThat(response.get("serverTime").asText()).isEqualTo(NOW.toString());
            assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
            assertThat(result.getResponse().getContentAsString()).doesNotContain(owner.toString(), "version", "customerId", "consumption");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.game_life_state WHERE customer_id=?", Long.class, owner)).isEqualTo(1);
        assertThat(consumed(owner)).isZero(); assertThat(version(owner)).isZero(); unchanged(owner);
    }
    @ParameterizedTest @ValueSource(strings = {"STAFF", "ADMIN"})
    void otherRolesDoNotImplyCustomer(String role) throws Exception {
        assertThat(code(login(RoleCode.valueOf(role)).send(get(LIVES),403))).isEqualTo("FORBIDDEN");
    }
    @ParameterizedTest @ValueSource(strings = {"STAFF", "ADMIN"})
    void explicitCustomerGrantPermitsCombinedRole(String role) throws Exception {
        login(RoleCode.valueOf(role), RoleCode.CUSTOMER).send(get(LIVES),200);
    }
    @Test void anonymousDisabledAndRevokedCustomersAreDenied() throws Exception {
        mvc.perform(get(LIVES)).andExpect(status().isUnauthorized());
        var disabled=login(RoleCode.CUSTOMER);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?", disabled.userId);
        assertThat(code(disabled.send(get(LIVES),401))).isEqualTo("UNAUTHENTICATED");
        var revoked=login(RoleCode.CUSTOMER);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);
        assertThat(code(revoked.send(get(LIVES),403))).isEqualTo("FORBIDDEN");
    }
    @ParameterizedTest @ValueSource(strings={"customerId", "now", "serverTime", "maxLives", "rechargeInterval", "availableLives", "sessionId"})
    void rejectsCallerSelectedOwnerClockOrRules(String parameter) throws Exception {
        login(RoleCode.CUSTOMER).send(get(LIVES).param(parameter, UUID.randomUUID().toString()),400);
    }
    @Test void noPublicConsumptionOrCrossCustomerLookupExists() throws Exception {
        var customer=login(RoleCode.CUSTOMER); var other=login(RoleCode.CUSTOMER);
        consume(customer);
        var own=json.readTree(customer.send(get(LIVES),200).getResponse().getContentAsString());
        var separate=json.readTree(other.send(get(LIVES),200).getResponse().getContentAsString());
        assertThat(own.get("availableLives").asInt()).isEqualTo(2);
        assertThat(separate.get("availableLives").asInt()).isEqualTo(3);
        customer.send(post(LIVES).header("X-CSRF-TOKEN", customer.csrf).contentType("application/json").content("{}"),405);
        customer.send(post(LIVES+"/consumptions").header("X-CSRF-TOKEN", customer.csrf),404);
        customer.send(get("/api/v1/customers/"+customer(other)+"/game-lives"),404);
        assertThat(consumed(customer(customer))).isEqualTo(1); assertThat(consumed(customer(other))).isZero();
    }
    @Test void missingAggregateFailsSafelyRatherThanInventingStateOnRead() throws Exception {
        var customer=login(RoleCode.CUSTOMER); var owner=customer(customer);
        jdbc.update("DELETE FROM loyalty.game_life_state WHERE customer_id=?",owner);
        assertThat(code(customer.send(get(LIVES),500))).isEqualTo("INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.game_life_state WHERE customer_id=?",Long.class,owner)).isZero();
    }
    @Test void corruptCapacityFailsSafelyInsteadOfHidingItByClamping() throws Exception {
        var customer=login(RoleCode.CUSTOMER);var owner=customer(customer);
        for(int i=0;i<4;i++) {jdbc.update("INSERT INTO loyalty.game_life_consumptions VALUES (?,?,?,?)",UUID.randomUUID(),owner,
                java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW.plusSeconds(7200)));}
        var result=customer.send(get(LIVES),500);
        assertThat(code(result)).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("invariant", "GameLife", "loyalty.");
        assertThat(consumed(owner)).isEqualTo(4);
    }
}
