package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.customer.service.CustomerLookupRateLimiter;
import com.amiraharcs.loyalty.audit.service.CustomerLookupAudit;
import com.amiraharcs.loyalty.reward.model.Reward;
import com.amiraharcs.loyalty.store.model.Store;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

abstract class StaffCustomerLookupTestSupport extends StaffQrTestSupport {
    static final String LOOKUP = "/api/v1/staff/customers";
    @Autowired CustomerLookupRateLimiter lookupLimiter;
    @MockitoSpyBean CustomerLookupAudit lookupAudit;
    record LookupSetup(Browser customer, Browser staff, Store store, UUID identifier) { }
    LookupSetup lookupSetup() throws Exception {
        var member = login(RoleCode.CUSTOMER); var staff = login(RoleCode.STAFF);
        return new LookupSetup(member,staff,store(staff),customer(member));
    }
    MvcResult lookup(LookupSetup s, int expected) throws Exception {
        return lookup(s.staff(),s.identifier().toString(),s.store().getId().toString(),expected);
    }
    MvcResult lookup(Browser browser, String identifier, String store, int expected) throws Exception {
        var request = get(LOOKUP).header("X-Request-ID","untrusted");
        if (identifier != null) { request.queryParam("identifier",identifier); }
        if (store != null) { request.queryParam("storeId",store); }
        var result = browser.send(request,expected);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("X-Request-ID")).isNotBlank().isNotEqualTo("untrusted");
        if (expected >= 400) { code(result); }
        if (identifier != null && !identifier.isBlank()) { assertThat(result.getResponse().getContentAsString()).doesNotContain(identifier); }
        return result;
    }
    Map<String,Object> event(MvcResult result) {
        return jdbc.queryForMap("SELECT actor_user_id,target_type,target_id,store_id,metadata::text AS metadata FROM loyalty.audit_logs WHERE action='CUSTOMER_LOOKUP' AND request_id=?",
                UUID.fromString(result.getResponse().getHeader("X-Request-ID")));
    }
    void fund(Browser browser, long amount) {
        jdbc.update("INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at) "
                + "SELECT ?,id,?,'ADJUSTMENT',?,'Synthetic lookup fixture',? FROM loyalty.loyalty_accounts WHERE customer_id=?",
                UUID.randomUUID(),amount,browser.userId,java.sql.Timestamp.from(NOW),customer(browser));
    }
    UUID offer() {
        return new TransactionTemplate(manager).execute(tx -> {
            var reward = new Reward("Lookup coffee","Immutable service benefit",1,true,null,null,NOW);
            fixtureEntities.persist(reward); fixtureEntities.flush(); return reward.getId();
        });
    }
    UUID redeem(Browser member, UUID reward, UUID store) throws Exception {
        var result = member.send(post("/api/v1/customers/me/redemptions").header("X-CSRF-TOKEN",member.csrf)
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("rewardId",reward,"storeId",store))),201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("redemptionId").asText());
    }
    Map<String,Object> state(UUID id) {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM loyalty.visits WHERE customer_id=?) AS visits,
                    (SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY t.id),'[]') FROM loyalty.point_transactions t
                     JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=?) AS ledger,
                    (SELECT to_jsonb(s) FROM loyalty.streaks s WHERE customer_id=?) AS streak,
                    (SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY t.jti),'[]') FROM loyalty.qr_identity_tokens t WHERE customer_id=?) AS tokens,
                    (SELECT COALESCE(jsonb_agg(to_jsonb(s) ORDER BY s.id),'[]') FROM loyalty.scan_resolutions s WHERE customer_id=?) AS resolutions,
                    (SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]') FROM loyalty.reward_redemptions r WHERE customer_id=?) AS redemptions,
                    (SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY p.id),'[]') FROM loyalty.customer_prizes p WHERE customer_id=?) AS prizes,
                    (SELECT version FROM loyalty.loyalty_accounts WHERE customer_id=?) AS account_version
                """, id,id,id,id,id,id,id,id);
    }
}
