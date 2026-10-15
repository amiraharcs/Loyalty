package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.loyalty.service.StreakService;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StreakApiTests extends StaffQrTestSupport {
    private static final String STREAK = "/api/v1/customers/me/streak";
    @MockitoSpyBean StreakService streaks;

    @Test void registrationCreatesEmptyPrivateProjectionAndEndpointUsesServerDate() throws Exception {
        var customer = login(RoleCode.CUSTOMER);
        var result = customer.send(get(STREAK).header("X-Client-Date", "2099-01-01"),200);
        var response = json.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("currentStreak").asInt()).isZero();
        assertThat(response.get("longestStreak").asInt()).isZero();
        assertThat(response.get("lastQualifyingDate").isNull()).isTrue();
        assertThat(response.get("serverDate").asText()).isEqualTo("2026-10-14");
        assertThat(response.size()).isEqualTo(4);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.streaks WHERE customer_id=?",Long.class,customer(customer))).isEqualTo(1);
        customer.send(get(STREAK).param("customerId",UUID.randomUUID().toString()),400);
        customer.send(get(STREAK).param("serverDate","2099-01-01"),400);
        customer.send(post(STREAK).header("X-CSRF-TOKEN",customer.csrf).contentType("application/json").content("{}"),405);
    }
    @Test void scanThenConfirmUpdatesOnlyOwningCustomerAndRetryDoesNotIncrement() throws Exception {
        var setup = setup(); var other = login(RoleCode.CUSTOMER); var resolution = scan(setup); var key = UUID.randomUUID();
        assertThat(jdbc.queryForObject("SELECT current_count FROM loyalty.streaks WHERE customer_id=?",Integer.class,customer(setup.customer()))).isZero();
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);
        var result = json.readTree(setup.customer().send(get(STREAK),200).getResponse().getContentAsString());
        assertThat(result.get("currentStreak").asInt()).isEqualTo(1);
        assertThat(result.get("longestStreak").asInt()).isEqualTo(1);
        assertThat(result.get("lastQualifyingDate").asText()).isEqualTo("2026-10-14");
        assertThat(json.readTree(other.send(get(STREAK),200).getResponse().getContentAsString()).get("currentStreak").asInt()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void rolesDoNotImplyCustomerAccess(String role) throws Exception { login(RoleCode.valueOf(role)).send(get(STREAK),403); }
    @Test void explicitCustomerGrantAllowsMultiRoleAccess() throws Exception { login(RoleCode.ADMIN,RoleCode.CUSTOMER).send(get(STREAK),200); }
    @Test void anonymousDisabledAndRevokedCustomersCannotRead() throws Exception {
        mvc.perform(get(STREAK)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var disabled = login(RoleCode.CUSTOMER); jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);
        disabled.send(get(STREAK),401);
        var revoked = login(RoleCode.CUSTOMER); jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);
        revoked.send(get(STREAK),403);
    }
    @Test void serverClockAndLondonZoneAreAuthoritativeAtReadTime() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-01T22:59:59Z"));
        var customer = login(RoleCode.CUSTOMER);
        when(clock.getZone()).thenReturn(ZoneId.of("Pacific/Auckland"));
        assertThat(json.readTree(customer.send(get(STREAK),200).getResponse().getContentAsString()).get("serverDate").asText()).isEqualTo("2026-07-01");
        when(clock.instant()).thenReturn(Instant.parse("2026-07-01T23:00:00Z"));
        assertThat(json.readTree(customer.send(get(STREAK),200).getResponse().getContentAsString()).get("serverDate").asText()).isEqualTo("2026-07-02");
    }
    @Test void failureAfterProjectionFlushRollsBackCompleteConfirmationAndCanRetry() throws Exception {
        var setup = setup(); var resolution = scan(setup); var key = UUID.randomUUID(); var owner = customer(setup.customer());
        var before = jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?",owner);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Synthetic streak failure after flush"); })
                .when(AopTestUtils.<StreakService>getUltimateTargetObject(streaks)).recordVisit(any());
        try { assertThat(code(confirmHttp(setup.staff(),resolution.scanResolutionId(),key,500))).isEqualTo("INTERNAL_ERROR"); }
        finally { reset(streaks); }
        unchanged(owner);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.streaks WHERE customer_id=?",owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM loyalty.scan_resolutions WHERE id=?",java.sql.Timestamp.class,resolution.scanResolutionId())).isNull();
        assertThat(validator.validate(setup.token())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,setup.staff().userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND key=?",Long.class,setup.staff().userId,key)).isZero();
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201); awardedOnce(setup);
        assertThat(jdbc.queryForObject("SELECT current_count FROM loyalty.streaks WHERE customer_id=?",Integer.class,owner)).isEqualTo(1);
    }
    @Test void failureCreatingStreakRollsBackRegistrationAggregate() {
        var email = UUID.randomUUID()+"@example.org";
        var before = jdbc.queryForMap("SELECT (SELECT count(*) FROM loyalty.customer_profiles) AS profiles,(SELECT count(*) FROM loyalty.loyalty_accounts) AS accounts,(SELECT count(*) FROM loyalty.streaks) AS streaks");
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Synthetic registration failure"); })
                .when(AopTestUtils.<StreakService>getUltimateTargetObject(streaks)).createForRegistration(any());
        try { assertThatThrownBy(() -> registration.register(new RegistrationRequest(email,PASSWORD,"Member"))).isInstanceOf(IllegalStateException.class); }
        finally { reset(streaks); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.users WHERE canonical_email=?",Long.class,email)).isZero();
        assertThat(jdbc.queryForMap("SELECT (SELECT count(*) FROM loyalty.customer_profiles) AS profiles,(SELECT count(*) FROM loyalty.loyalty_accounts) AS accounts,(SELECT count(*) FROM loyalty.streaks) AS streaks")).isEqualTo(before);
    }
}
