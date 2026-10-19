package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeaderboardApiTests extends LeaderboardTestSupport {
    @Test void keysetPagesAreDeterministicAndOwnRankIsGlobalOutsidePage() throws Exception {
        var browser=login(RoleCode.CUSTOMER);accepted(customer(browser),1,NOW.minusSeconds(10));
        for(int i=1;i<=5;i++) { accepted(fixtureCustomer("Player "+i),10+i,NOW.minusSeconds(10)); }
        var first=board(browser,ALL+"?size=2");assertThat(first.items()).extracting(e->e.rank()).containsExactly(1L,2L);
        assertThat(first.currentUser().rank()).isEqualTo(6);assertThat(first.hasNext()).isTrue();
        var second=board(browser,ALL+"?size=2&cursor="+first.nextCursor());
        var third=board(browser,ALL+"?size=2&cursor="+second.nextCursor());
        assertThat(second.items()).extracting(e->e.rank()).containsExactly(3L,4L);
        assertThat(third.items()).extracting(e->e.rank()).containsExactly(5L,6L);
        assertThat(third.nextCursor()).isNull();assertThat(third.hasNext()).isFalse();
        assertThat(third.currentUser()).isEqualTo(first.currentUser());
        assertThat(board(browser,ALL+"?size=2").items()).isEqualTo(first.items());
    }
    @Test void weeklyPaginationAndScopeBoundCursorsRejectTampering() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        for(int i=0;i<4;i++) { accepted(fixtureCustomer("Player "+i),100,NOW.minusSeconds(20-i)); }
        var first=board(browser,WEEKLY+"?size=2");var second=board(browser,WEEKLY+"?size=2&cursor="+first.nextCursor());
        assertThat(second.items()).extracting(e->e.rank()).containsExactly(3L,4L);assertThat(second.hasNext()).isFalse();
        browser.send(get(ALL).param("cursor",first.nextCursor()),400);
        browser.send(get(WEEKLY).param("periodStart","2026-10-05").param("cursor",first.nextCursor()),400);
        String token=first.nextCursor();char changed=token.charAt(10)=='A'?'B':'A';
        browser.send(get(WEEKLY).param("cursor",token.substring(0,10)+changed+token.substring(11)),400);
    }
    @ParameterizedTest @ValueSource(strings={"size=0","size=-1","size=101","size=no","cursor=bad","cursor=","periodStart=2026-10-13","periodStart=2026-10-19","periodStart=no","periodStart=","sort=score","customerId=forged","size=1&size=2"})
    void rejectsInvalidAndUnrecognisedQueryParameters(String query) throws Exception {
        var browser=login(RoleCode.CUSTOMER);browser.send(get(WEEKLY+"?"+query),400);
    }
    @Test void publicProjectionNeverExposesEmailNamesIdsRolesOrPrivateAliases() throws Exception {
        var browser=login(RoleCode.CUSTOMER);UUID privateMember=fixtureCustomer(UUID.randomUUID(),"PRIVATE ALIAS",true,RoleCode.CUSTOMER);
        UUID emailMember=fixtureCustomer("do-not-disclose@example.org"),publicMember=fixtureCustomer("Coffee fan");
        accepted(privateMember,300,NOW.minusSeconds(10));accepted(emailMember,200,NOW.minusSeconds(10));accepted(publicMember,100,NOW.minusSeconds(10));
        var result=browser.send(get(ALL),200);String body=result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("@","PRIVATE ALIAS","PRIVATE DISPLAY NAME",privateMember.toString(),"canonicalEmail","userId","customerId","roles","acceptedAt");
        var page=json.readValue(body,com.amiraharcs.loyalty.leaderboard.api.LeaderboardPage.class);
        assertThat(page.items()).extracting(e->e.alias()).containsExactly("Member","Member","Coffee fan");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(page.period()).isNull();assertThat(page.serverTime()).isEqualTo(NOW);
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void explicitCustomerRoleRequired(String role) throws Exception {
        var wrong=login(RoleCode.valueOf(role));wrong.send(get(WEEKLY),403);wrong.send(get(ALL),403);
        var combined=login(RoleCode.valueOf(role),RoleCode.CUSTOMER);board(combined,WEEKLY);board(combined,ALL);
    }
    @Test void anonymousDisabledAndRevokedIdentitiesCannotRead() throws Exception {
        mvc.perform(get(WEEKLY)).andExpect(status().isUnauthorized());mvc.perform(get(ALL)).andExpect(status().isUnauthorized());
        var disabled=login(RoleCode.CUSTOMER);jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);
        disabled.send(get(WEEKLY),401);disabled.send(get(ALL),401);
        var revoked=login(RoleCode.CUSTOMER);jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);
        revoked.send(get(WEEKLY),403);revoked.send(get(ALL),403);
    }
    @Test void allTimeRejectsPeriodFilterAndUnknownLeaderboardRoutesRemainDenied() throws Exception {
        var browser=login(RoleCode.CUSTOMER);browser.send(get(ALL).param("periodStart","2026-10-12"),400);
        browser.send(get("/api/v1/leaderboards/friends"),403);
    }
}
