package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RedemptionHistoryTests extends FulfilmentTestSupport {
    @Test void privateHistoryHasDeterministicNewestFirstPagesSnapshotsAndCurrentStatus() throws Exception {
        var setup=redemptionSetup(5000,1000);var ids=new ArrayList<UUID>();
        for(int count=0;count<4;count++) { ids.add(created(setup)); }
        var expected=new ArrayList<>(ids.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));UUID newest=created(setup);expected.addFirst(newest);
        var stranger=redemptionSetup(1000,1000);UUID strangerId=created(stranger);
        var staff=login(RoleCode.STAFF);assign(staff,setup.store());fulfil(staff,newest,UUID.randomUUID(),200);
        var admin=login(RoleCode.ADMIN);patch(admin,setup.reward().getId(),"{\"name\":\"Edited\",\"description\":\"Edited\",\"pointCost\":9000}",200);
        var rows=all(setup.browser(),REDEMPTIONS,2);
        assertThat(rows.stream().map(row->UUID.fromString(row.get("redemptionId").asText()))).containsExactlyElementsOf(expected).doesNotContain(strangerId);
        assertThat(rows).allSatisfy(row->{
            assertThat(row.get("rewardName").asText()).isEqualTo("Coffee");assertThat(row.get("pointCost").asLong()).isEqualTo(1000);
            assertThat(row.toString()).doesNotContain("customerId","accountId","fulfilledBy","version","email","password");
        });
        assertThat(rows.getFirst().get("status").asText()).isEqualTo("FULFILLED");assertThat(rows.getFirst().get("fulfilledAt").isNull()).isFalse();
        assertThat(rows.getLast().get("status").asText()).isEqualTo("PENDING");assertThat(rows.getLast().get("fulfilledAt").isNull()).isTrue();
        stranger.browser().send(get(REDEMPTIONS+"/"+newest),404);
    }
    @Test void emptyHistoryAndCustomerScopedCursorAreSafe() throws Exception {
        var empty=login(RoleCode.CUSTOMER);var response=empty.send(get(REDEMPTIONS),200);var page=json.readTree(response.getResponse().getContentAsString());
        assertThat(page.get("items").size()).isZero();assertThat(page.get("nextCursor").isNull()).isTrue();assertThat(page.get("hasNext").asBoolean()).isFalse();
        assertThat(response.getResponse().getHeader("Cache-Control")).contains("no-store");
        var setup=redemptionSetup(2000,1000);created(setup);created(setup);
        var first=json.readTree(setup.browser().send(get(REDEMPTIONS).param("size","1"),200).getResponse().getContentAsString());
        empty.send(get(REDEMPTIONS).param("cursor",first.get("nextCursor").asText()),400);
    }
    @ParameterizedTest @ValueSource(strings={"STAFF","ADMIN"})
    void rolesDoNotInheritCustomerHistory(String role) throws Exception { login(RoleCode.valueOf(role)).send(get(REDEMPTIONS),403); }
    @Test void anonymousAndDisabledCustomersCannotRead() throws Exception {
        mvc.perform(get(REDEMPTIONS)).andExpect(status().isUnauthorized());var customer=login(RoleCode.CUSTOMER);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",customer.userId);customer.send(get(REDEMPTIONS),401);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","101","invalid"})
    void invalidPageSizesFail(String size) throws Exception { login(RoleCode.CUSTOMER).send(get(REDEMPTIONS).param("size",size),400); }
    @Test void unknownFiltersOwnerOverridesAndMalformedCursorsAreRejected() throws Exception {
        var customer=login(RoleCode.CUSTOMER);
        for(String field:List.of("customerId","sort","status","from","serverTime")) { customer.send(get(REDEMPTIONS).param(field,"untrusted"),400); }
        customer.send(get(REDEMPTIONS).param("size","1","2"),400);
        for(String cursor:List.of("","!invalid!","YQ","x".repeat(257))) { customer.send(get(REDEMPTIONS).param("cursor",cursor),400); }
    }
}
