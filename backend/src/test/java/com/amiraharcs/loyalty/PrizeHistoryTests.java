package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.model.CustomerPrizeStatus;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrizeHistoryTests extends PrizeTestSupport {
    @Test void owningCustomerOnlyGetsSnapshotProjectionWithoutInternalSourceOrIdentity() throws Exception {
        var setup=setup();var foreign=login(RoleCode.CUSTOMER);days(setup,10);freshLogin(setup.customer());freshLogin(foreign);
        var result=setup.customer().send(get(PRIZES),200);String body=result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("customerId","userId","triggerVisitId","weeklyAssignmentId","version","actor","@","Private synthetic member");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(history(setup.customer(),"").items()).hasSize(1);assertThat(history(foreign,"").items()).isEmpty();
        setup.customer().send(get(PRIZES).param("customerId",customer(foreign).toString()),400);
    }
    @Test void deterministicDescendingAwardOrderAndCursorPagesAreOwned() throws Exception {
        var setup=setup();days(setup,20);freshLogin(setup.customer());var foreign=login(RoleCode.CUSTOMER);
        var first=history(setup.customer(),"?size=1");assertThat(first.hasNext()).isTrue();
        var second=history(setup.customer(),"?size=1&cursor="+first.nextCursor());assertThat(second.hasNext()).isFalse();assertThat(second.nextCursor()).isNull();
        assertThat(first.items().getFirst().awardedAt()).isAfter(second.items().getFirst().awardedAt());
        assertThat(first.items().getFirst().customerPrizeId()).isNotEqualTo(second.items().getFirst().customerPrizeId());
        assertThat(history(setup.customer(),"?size=1").items()).isEqualTo(first.items());
        foreign.send(get(PRIZES).param("cursor",first.nextCursor()),400);
    }
    @Test void expiryAtExactServerInstantIsProjectedWithoutWritingStatus() throws Exception {
        var setup=setup();days(setup,10);freshLogin(setup.customer());var expires=history(setup.customer(),"").items().getFirst().expiresAt();
        when(clock.instant()).thenReturn(expires.minusNanos(1));freshLogin(setup.customer());
        assertThat(history(setup.customer(),"").items().getFirst().status()).isEqualTo(CustomerPrizeStatus.AVAILABLE);
        when(clock.instant()).thenReturn(expires);
        assertThat(history(setup.customer(),"").items().getFirst().status()).isEqualTo(CustomerPrizeStatus.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.customer_prizes WHERE customer_id=?",String.class,customer(setup.customer()))).isEqualTo("AVAILABLE");
    }
    @Test void editsToPrizeAndRewardDoNotRewriteEntitlementMeaning() throws Exception {
        var setup=setup();days(setup,10);freshLogin(setup.customer());var before=history(setup.customer(),"").items().getFirst();
        jdbc.update("UPDATE loyalty.prizes SET name='Changed catalogue',benefit_description='Changed benefit',active=false,version=version+1 WHERE id=?",STREAK_PRIZE);
        jdbc.update("UPDATE loyalty.rewards SET name='Changed reward',description='New reward description',point_cost=9999,active=false WHERE id=?",COFFEE);
        entities.clear();assertThat(history(setup.customer(),"").items().getFirst()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void roleAloneCannotRead(String role) throws Exception {login(RoleCode.valueOf(role)).send(get(PRIZES),403);}
    @Test void anonymousDisabledAndRevokedCustomersAreDenied() throws Exception {
        mvc.perform(get(PRIZES)).andExpect(status().isUnauthorized());var disabled=login(RoleCode.CUSTOMER);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);disabled.send(get(PRIZES),401);
        var revoked=login(RoleCode.CUSTOMER);jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);revoked.send(get(PRIZES),403);
    }
    @ParameterizedTest @ValueSource(strings={"size=0","size=-1","size=101","size=bad","size=","cursor=bad","cursor=","customerId=foreign","status=AVAILABLE","size=1&size=2"})
    void rejectsMalformedAndUnsupportedQuery(String query) throws Exception {login(RoleCode.CUSTOMER).send(get(PRIZES+"?"+query),400);}
    @Test void customerAssignmentAndArbitraryAdminGrantRemainUnavailable() throws Exception {
        var browser=login(RoleCode.CUSTOMER);browser.send(post(PRIZES).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content("{}"),405);
        var admin=login(RoleCode.ADMIN);admin.send(post("/api/v1/admin/prize-assignments").header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content("{}"),405);
    }
}
