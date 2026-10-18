package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.reward.api.RedemptionResponse;
import com.amiraharcs.loyalty.reward.service.FulfilmentService;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

abstract class FulfilmentTestSupport extends RedemptionTestSupport {
    static final String STAFF_REDEMPTIONS="/api/v1/staff/redemptions/";
    @Autowired FulfilmentService fulfilments;
    record Pending(RedemptionSetup setup,UUID id,Browser staff) { }
    Pending pending() throws Exception {
        var setup=redemptionSetup(3000,3000);UUID id=created(setup);
        var staff=login(RoleCode.STAFF);assign(staff,setup.store());return new Pending(setup,id,staff);
    }
    UUID created(RedemptionSetup setup) throws Exception {
        return json.readValue(redeem(setup,UUID.randomUUID(),201).getResponse().getContentAsString(),RedemptionResponse.class).redemptionId();
    }
    String fulfilPath(UUID id) { return STAFF_REDEMPTIONS+id+"/fulfilments"; }
    MvcResult fulfil(Browser staff,UUID id,UUID key,int expected) throws Exception {
        return staff.send(post(fulfilPath(id)).header("X-CSRF-TOKEN",staff.csrf).header("Idempotency-Key",key)
                .header("X-Client-Time","2099-01-01T00:00:00Z"),expected);
    }
    Map<String,Object> state(UUID id) { return jdbc.queryForMap("SELECT * FROM loyalty.reward_redemptions WHERE id=?",id); }
    void stillPending(Pending pending) {
        assertThat(state(pending.id())).containsEntry("status","PENDING").containsEntry("fulfilled_by",null).containsEntry("fulfilled_at",null).containsEntry("version",0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='REDEMPTION_FULFILLED'",Long.class,pending.id())).isZero();
        assertThat(accounts.currentBalance(customer(pending.setup().browser()))).isZero();assertThat(debits(pending.setup().browser())).isEqualTo(1);
    }
}
