package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameSessionIdempotencyTests extends GameSessionTestSupport {
    @Test void emptyBodyAndEmptyObjectRetryReturnOriginalBodyAndLocationEvenAfterExpiry() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();var original=startHttp(browser,key,null,201);
        var id=json.readTree(original.getResponse().getContentAsString()).get("sessionId").asText();
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));recovery(browser,UUID.fromString(id));
        var retried=startHttp(browser,key,"{}",201);
        assertThat(retried.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        assertThat(retried.getResponse().getHeader("Location")).isEqualTo(original.getResponse().getHeader("Location"));
        assertThat(consumed(customer(browser))).isEqualTo(1);assertThat(sessionRows(customer(browser))).isEqualTo(1);
        assertThat(version(customer(browser))).isEqualTo(1);assertThat(starts(browser.userId)).isEqualTo(1);assertThat(retries(browser.userId)).isEqualTo(1);
    }
    @Test void keyIsScopedToActorAndChangedStoredPayloadIsRejectedWithoutNewConsumption() throws Exception {
        var first=login(RoleCode.CUSTOMER);var second=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();
        startHttp(first,key,null,201);startHttp(second,key,null,201);
        jdbc.update("UPDATE loyalty.idempotency_records SET request_hash=? WHERE actor_id=? AND operation='CUSTOMER_GAME_START' AND key=?",
                com.amiraharcs.loyalty.common.idempotency.IdempotencyService.hash("changed-canonical-payload"),first.userId,key);
        assertThat(code(startHttp(first,key,null,409))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(consumed(customer(first))).isEqualTo(1);assertThat(consumed(customer(second))).isEqualTo(1);
    }
    @Test void replayStillRequiresCurrentCustomerGrantAndActiveAccount() throws Exception {
        var revoked=login(RoleCode.CUSTOMER);var key=UUID.randomUUID();startHttp(revoked,key,null,201);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);startHttp(revoked,key,null,403);
        var disabled=login(RoleCode.CUSTOMER);var another=UUID.randomUUID();startHttp(disabled,another,null,201);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);startHttp(disabled,another,null,401);
        assertThat(consumed(customer(revoked))).isEqualTo(1);assertThat(consumed(customer(disabled))).isEqualTo(1);
    }
}
