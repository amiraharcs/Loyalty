package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.reward.api.RewardPatchRequest;
import com.amiraharcs.loyalty.reward.model.Reward;
import com.amiraharcs.loyalty.reward.repository.RewardRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RewardConcurrencyTests extends RewardTestSupport {
    @MockitoSpyBean RewardRepository repository;
    @Test void competingAdminServiceEditsProduceOneSuccessOneConflictAndOneAudit() throws Exception {
        var admin = login(RoleCode.ADMIN); var reward = fixture("Original",1,true,null,null); var loaded = new CountDownLatch(2);
        // Coordinate after each transaction's real PostgreSQL load, before either optimistic UPDATE.
        doAnswer(call->{
            var result = Optional.ofNullable(entities.find(Reward.class,reward.getId()));
            loaded.countDown(); if(!loaded.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("Synchronization timeout"); }
            return result;
        }).when(repository).findById(eq(reward.getId()));
        try(var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(()->edit(admin,reward.getId(),"First"));
            var second = executor.submit(()->edit(admin,reward.getId(),"Second"));
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("UPDATED","CONFLICT");
        } finally { reset(repository); }
        assertThat(jdbc.queryForObject("SELECT version FROM loyalty.rewards WHERE id=?",Long.class,reward.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='REWARD_UPDATED'",Long.class,reward.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM loyalty.rewards WHERE id=?",String.class,reward.getId())).isIn("First","Second");
    }
    private String edit(Browser admin,UUID id,String name) {
        assume(admin,RoleCode.ADMIN);
        var patch = new RewardPatchRequest(); patch.setName(name);
        try { rewards.update(id,patch,UUID.randomUUID()); return "UPDATED"; }
        catch(ConflictException conflict) { return "CONFLICT"; }
        finally { SecurityContextHolder.clearContext(); }
    }
}
