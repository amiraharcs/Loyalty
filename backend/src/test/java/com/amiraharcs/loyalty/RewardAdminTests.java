package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.reward.model.Reward;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RewardAdminTests extends RewardTestSupport {
    @Test void createReturnsRetrievableLocationAndAdminCanEditDeactivateAndReactivate() throws Exception {
        var admin = login(RoleCode.ADMIN);
        var created = admin.send(post(ADMIN).header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content(body("Coffee",3000)),201);
        var dto = json.readTree(created.getResponse().getContentAsString()); var id = UUID.fromString(dto.get("id").asText());
        assertThat(created.getResponse().getHeader("Location")).isEqualTo(ADMIN+"/"+id);
        admin.send(get(created.getResponse().getHeader("Location")),200);
        assertThat(dto.size()).isEqualTo(9); assertThat(dto.has("version")).isFalse();
        var updated = patch(admin,id,"{\"name\":\"Updated coffee\",\"description\":\"Updated description\",\"pointCost\":2500}",200);
        assertThat(updated.get("name").asText()).isEqualTo("Updated coffee");
        assertThat(updated.get("description").asText()).isEqualTo("Updated description");
        assertThat(updated.get("pointCost").asLong()).isEqualTo(2500);
        assertThat(updated.get("createdAt").asText()).isEqualTo(dto.get("createdAt").asText());
        assertThat(patch(admin,id,"{\"active\":false}",200).get("active").asBoolean()).isFalse();
        assertThat(all(admin,ADMIN,100).stream().map(row->row.get("id").asText())).contains(id.toString());
        assertThat(patch(admin,id,"{\"active\":true}",200).get("active").asBoolean()).isTrue();
        assertThat(jdbc.queryForList("SELECT action FROM loyalty.audit_logs WHERE target_id=? ORDER BY occurred_at,id",String.class,id))
                .containsExactlyInAnyOrder("REWARD_CREATED","REWARD_UPDATED","REWARD_DEACTIVATED","REWARD_UPDATED");
        var metadata = jdbc.queryForList("SELECT metadata::text FROM loyalty.audit_logs WHERE target_id=?",String.class,id);
        assertThat(metadata).allSatisfy(value->assertThat(value).doesNotContain("Updated coffee","Updated description","password"));
        admin.send(delete(ADMIN+"/"+id).header("X-CSRF-TOKEN",admin.csrf),405);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.rewards WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void patchOmissionPreservesBoundsAndExplicitNullClearsThem() throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("Window",5,true,NOW,NOW.plusSeconds(60));
        var updated = patch(admin,reward.getId(),"{\"description\":\"Changed\"}",200);
        assertThat(updated.get("availableFrom").asText()).isEqualTo(NOW.toString());
        assertThat(updated.get("availableUntil").asText()).isEqualTo(NOW.plusSeconds(60).toString());
        patch(admin,reward.getId(),"{\"availableFrom\":\"2026-10-14T12:02:00Z\"}",400);
        var cleared = patch(admin,reward.getId(),"{\"availableFrom\":null,\"availableUntil\":null}",200);
        assertThat(cleared.get("availableFrom").isNull()).isTrue(); assertThat(cleared.get("availableUntil").isNull()).isTrue();
        patch(admin,reward.getId(),"{\"availableFrom\":\"2026-10-14T12:00:00Z\",\"availableUntil\":\"2026-10-14T12:01:00Z\"}",200);
        patch(admin,reward.getId(),"{\"name\":null}",400);
        patch(admin,reward.getId(),"{\"description\":null}",400);
        patch(admin,reward.getId(),"{\"pointCost\":null}",400);
        patch(admin,reward.getId(),"{\"active\":null}",400);
    }
    @ParameterizedTest @ValueSource(strings={"-1","9007199254740992","9223372036854775808","1.5","1.0","\"3000\"","null","true"})
    void unsafeOrNonIntegerCostIsRejectedOnCreateAndPatch(String cost) throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("Valid",10,true,null,null);
        create(admin,"{\"name\":\"Rejected\",\"description\":\"\",\"pointCost\":"+cost+",\"active\":true}",400);
        patch(admin,reward.getId(),"{\"pointCost\":"+cost+"}",400);
        assertThat(jdbc.queryForObject("SELECT point_cost FROM loyalty.rewards WHERE id=?",Long.class,reward.getId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,admin.userId)).isZero();
    }
    @Test void architectureAllowsZeroAndMaximumSafeCost() throws Exception {
        var admin = login(RoleCode.ADMIN);
        assertThat(create(admin,body("Zero",0),201).get("pointCost").asLong()).isZero();
        assertThat(create(admin,body("Maximum",9007199254740991L),201).get("pointCost").asLong()).isEqualTo(9007199254740991L);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"name\":\" \"}","{\"version\":7}","{\"id\":\"00000000-0000-0000-0000-000000000000\"}","{\"createdAt\":\"2026-10-14T12:00:00Z\"}","{\"availableFrom\":\"not-a-date\"}","{\"availableFrom\":\"2026-10-14T12:00:00.000000001Z\"}","{\"availableFrom\":\"2026-10-15T12:00:00Z\",\"availableUntil\":\"2026-10-14T12:00:00Z\"}"})
    void invalidOrUnallowlistedPatchCannotChangeStateOrWriteSuccessAudit(String body) throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("Original",1,true,null,null);
        var before = jdbc.queryForMap("SELECT * FROM loyalty.rewards WHERE id=?",reward.getId());
        patch(admin,reward.getId(),body,400);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.rewards WHERE id=?",reward.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,admin.userId)).isZero();
    }
    @Test void creationValidationRequiresFieldsAndBoundedTextAndValidWindow() throws Exception {
        var admin = login(RoleCode.ADMIN);
        for(String body:List.of("{}",body("x".repeat(201),1),"{\"name\":\"A\",\"description\":\""+"x".repeat(2001)+"\",\"pointCost\":1,\"active\":true}",
                "{\"name\":\"A\",\"description\":\"\",\"pointCost\":1,\"active\":true,\"availableFrom\":\"2026-10-14T12:00:00Z\",\"availableUntil\":\"2026-10-14T12:00:00Z\"}",
                "{\"name\":\"A\",\"description\":\"\",\"pointCost\":1,\"active\":true,\"role\":\"ADMIN\"}")) { create(admin,body,400); }
    }
    @Test void nullCharactersAreRejectedBeforePostgresAndDoNotWriteAudit() throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("Valid",1,true,null,null);
        create(admin,body("Invalid"+Character.toString(0),1),400);
        patch(admin,reward.getId(),json.writeValueAsString(Map.of("description","Invalid"+Character.toString(0))),400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,admin.userId)).isZero();
    }
    @Test void mutationsRequireCsrfAndUnknownRewardHasSafeNotFound() throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("CSRF",1,true,null,null);
        admin.send(post(ADMIN).contentType("application/json").content(body("Rejected",1)),403);
        admin.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(ADMIN+"/"+reward.getId()).contentType("application/json").content("{\"active\":false}"),403);
        assertThat(patch(admin,UUID.randomUUID(),"{\"active\":false}",404).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        admin.send(get(ADMIN+"/"+UUID.randomUUID()),404);
        assertThat(jdbc.queryForObject("SELECT active FROM loyalty.rewards WHERE id=?",Boolean.class,reward.getId())).isTrue();
    }
    @Test void auditFailureRollsBackCreationAndUpdateWithoutTouchingPoints() throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("Original",1,true,null,null);
        var before = jdbc.queryForMap("SELECT * FROM loyalty.rewards WHERE id=?",reward.getId());
        long rewardRows = jdbc.queryForObject("SELECT count(*) FROM loyalty.rewards",Long.class);
        long ledgerRows = jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions",Long.class);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure after audit INSERT");})
                .when(AopTestUtils.<AuditService>getUltimateTargetObject(audit)).append(eq(admin.userId),anyString(),eq("REWARD"),any(),any(),any(),anyString());
        try {
            assertThat(create(admin,body("Rolled back",1),500).get("code").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(patch(admin,reward.getId(),"{\"active\":false}",500).get("code").asText()).isEqualTo("INTERNAL_ERROR");
        } finally { reset(audit); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.rewards",Long.class)).isEqualTo(rewardRows);
        assertThat(jdbc.queryForMap("SELECT * FROM loyalty.rewards WHERE id=?",reward.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,admin.userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions",Long.class)).isEqualTo(ledgerRows);
    }
    @Test void serviceEnforcesAdminAndFreshDatabaseGrant() throws Exception {
        var customer = login(RoleCode.CUSTOMER); assume(customer,RoleCode.CUSTOMER);
        assertThatThrownBy(()->rewards.adminCatalogue(20,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var admin = login(RoleCode.ADMIN); assume(admin,RoleCode.ADMIN);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",admin.userId);
        assertThatThrownBy(()->rewards.update(UUID.randomUUID(),new com.amiraharcs.loyalty.reward.api.RewardPatchRequest(),UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void optimisticVersionPreventsOverlappingEditorsFromSilentlyOverwriting() throws Exception {
        var reward = fixture("Original",1,true,null,null); var ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(()->edit(reward.getId(),"First",ready)); var second = executor.submit(()->edit(reward.getId(),"Second",ready));
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.rewards WHERE id=?",Long.class,reward.getId())).isEqualTo(1);
    }
    private boolean edit(UUID id,String name,CountDownLatch ready) {
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx->{
                var entity = entities.find(Reward.class,id); ready.countDown();
                try { if(!ready.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Synchronization timeout"); } }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                entity.revise(name,entity.getDescription(),entity.getPointCost(),entity.isActive(),null,null,NOW); entities.flush();
            });
            return true;
        } catch(jakarta.persistence.OptimisticLockException conflict) { return false; }
    }
}
