package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.reward.model.Reward;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RewardCatalogueTests extends RewardTestSupport {
    @Test void exactServerClockBoundariesHideInactiveFutureAndExpiredRewards() throws Exception {
        var active = fixture("Active",2,true,null,null);
        var inactive = fixture("Inactive",3,false,null,null);
        var future = fixture("Future",4,true,NOW.plusSeconds(1),null);
        var expired = fixture("Expired",5,true,null,NOW);
        var bounded = fixture("Bounded",6,true,NOW,NOW.plusSeconds(1));
        var browser = login(RoleCode.CUSTOMER);
        var first = all(browser,CATALOGUE,100);
        assertThat(first.stream().map(row->UUID.fromString(row.get("id").asText()))).contains(active.getId(),bounded.getId()).doesNotContain(inactive.getId(),future.getId(),expired.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        var next = all(browser,CATALOGUE,100);
        assertThat(next.stream().map(row->UUID.fromString(row.get("id").asText()))).contains(active.getId(),future.getId()).doesNotContain(inactive.getId(),bounded.getId(),expired.getId());
        var dto = next.stream().filter(row->row.get("id").asText().equals(active.getId().toString())).findFirst().orElseThrow();
        assertThat(dto.size()).isEqualTo(6);
        assertThat(dto.has("version")).isFalse(); assertThat(dto.has("active")).isFalse(); assertThat(dto.has("createdAt")).isFalse();
    }
    @Test void keysetPagesUseCostNameAndUuidOrderWithoutDuplicateOrMissingRows() throws Exception {
        var one = fixture("Equal",1,true,null,null); var two = fixture("Equal",1,true,null,null);
        var cheap = fixture("Z",0,true,null,null); var alphabetical = fixture("A",1,true,null,null);
        var expensive = fixture("A",2,true,null,null);
        var own = List.of(one,two,cheap,alphabetical,expensive);
        var expected = own.stream().sorted(Comparator.comparingLong(Reward::getPointCost).thenComparing(Reward::getName).thenComparing(r->r.getId().toString())).map(Reward::getId).toList();
        var ids = all(login(RoleCode.STAFF),CATALOGUE,2).stream().map(row->UUID.fromString(row.get("id").asText())).toList();
        assertThat(ids).doesNotHaveDuplicates(); assertThat(ids.stream().filter(expected::contains)).containsExactlyElementsOf(expected);
    }
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","STAFF"})
    void explicitReadersCanReadButCannotManage(String role) throws Exception {
        var browser = login(RoleCode.valueOf(role)); browser.send(get(CATALOGUE),200);
        create(browser,body("Forbidden",1),403);
        patch(browser,UUID.randomUUID(),"{\"active\":false}",403);
        browser.send(get(ADMIN),403);
    }
    @Test void adminDoesNotInheritReaderRoleButExplicitCombinedGrantWorks() throws Exception {
        login(RoleCode.ADMIN).send(get(CATALOGUE),403);
        login(RoleCode.ADMIN,RoleCode.CUSTOMER).send(get(CATALOGUE),200);
        login(RoleCode.ADMIN,RoleCode.STAFF).send(get(CATALOGUE),200);
    }
    @Test void anonymousDisabledAndRevokedReadersAreDenied() throws Exception {
        mvc.perform(get(CATALOGUE)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        var disabled = login(RoleCode.CUSTOMER); jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",disabled.userId);
        disabled.send(get(CATALOGUE),401);
        var revoked = login(RoleCode.STAFF); jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",revoked.userId);
        revoked.send(get(CATALOGUE),403);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","101","oops","2147483648"})
    void invalidPageSizesFailInsteadOfTruncating(String size) throws Exception { login(RoleCode.CUSTOMER).send(get(CATALOGUE).param("size",size),400); }
    @Test void callerCannotChooseAvailabilityTimeFiltersOrAnotherListingScope() throws Exception {
        var browser = login(RoleCode.CUSTOMER);
        for(String key:List.of("now","active","sort","serverTime","from")) { browser.send(get(CATALOGUE).param(key,"2099-01-01T00:00:00Z"),400); }
        browser.send(get(CATALOGUE).param("size","1","2"),400);
        for(String cursor:List.of("!not-base64!","","YQ")) { browser.send(get(CATALOGUE).param("cursor",cursor),400); }
        var admin = login(RoleCode.ADMIN);
        var first = json.readTree(admin.send(get(ADMIN).param("size","1"),200).getResponse().getContentAsString());
        fixture("Ensure second page",7,true,null,null);
        first = json.readTree(admin.send(get(ADMIN).param("size","1"),200).getResponse().getContentAsString());
        browser.send(get(CATALOGUE).param("cursor",first.get("nextCursor").asText()),400);
        var response = browser.send(get(CATALOGUE).header("X-Client-Time","2099-01-01T00:00:00Z"),200);
        assertThat(response.getResponse().getHeader("Cache-Control")).contains("no-store");
    }
    @Test void serviceAlsoRechecksFreshRoleGrants() throws Exception {
        var browser = login(RoleCode.STAFF); assume(browser,RoleCode.STAFF);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
        assertThatThrownBy(()->rewards.catalogue(20,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
